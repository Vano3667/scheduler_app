package ru.dayplanner.app.signals

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.dayplanner.app.MainActivity
import ru.dayplanner.app.container
import ru.dayplanner.app.domain.DayKey
import ru.dayplanner.app.domain.PlannerData
import ru.dayplanner.app.domain.Schedule
import ru.dayplanner.app.domain.SignalKind
import ru.dayplanner.app.domain.Task
import ru.dayplanner.app.widget.WidgetUpdater
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

private const val TAG = "Planner"

/**
 * Планировщик сигналов. Для каждого дела и каждого вида сигнала (начало, окончание)
 * в системе стоит не больше одного будильника — на ближайшее срабатывание.
 * После любого изменения дел расписание пересчитывается целиком: лишние будильники
 * отменяются, для прошедшего времени будильник не ставится.
 */
object SignalScheduler {
    private const val PREFS = "armed"
    private const val KEY_ARMED = "keys"
    private const val REQUEST_SHOW = 4201
    const val EXTRA_DAY = "day"
    const val EXTRA_AT = "at"

    private val lock = Mutex()

    fun requestReschedule(context: Context) {
        val app = context.applicationContext
        app.container.launch {
            try {
                refresh(app)
            } catch (e: Exception) {
                Log.w(TAG, "Не удалось пересчитать сигналы", e)
            }
        }
    }

    /** Разрешены ли приложению точные будильники. */
    fun exact(c: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || c.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    private fun signalIntent(c: Context, key: String): Intent =
        Intent(c, SignalReceiver::class.java).setData(Uri.parse("planner-signal://local/$key"))

    /** Что открыть, если пользователь нажмёт на «следующий будильник» в системе. */
    private fun showIntent(c: Context): PendingIntent {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setClass(c, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return PendingIntent.getActivity(
            c,
            REQUEST_SHOW,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    suspend fun refresh(c: Context) {
        withContext(Dispatchers.IO) {
            lock.withLock {
                val alarms = c.getSystemService(AlarmManager::class.java)
                val prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val previous: Set<String> = prefs.getStringSet(KEY_ARMED, null)?.toSet() ?: emptySet()
                val armed = HashSet<String>()
                val data = c.container.repository.snapshot()
                val zone = ZoneId.systemDefault()
                val now = LocalDateTime.now(zone)
                val today = now.toLocalDate().toEpochDay()
                val exactAllowed = exact(c)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

                for (task in data.tasks) {
                    for (kind in SignalKind.entries) {
                        val horizon = maxOf(
                            400L,
                            task.startDay - today + data.skipped.size + data.completions.size + 8L,
                        ).coerceAtMost(100_000L).toInt()
                        val next = Schedule.nextSignal(task, kind, now, data.skipped, data.completions, horizon)
                            ?: continue
                        val time = next.atZone(zone).toInstant().toEpochMilli()
                        if (time <= System.currentTimeMillis()) continue

                        val key = task.id.toString() + "/" + kind.name
                        val intent = signalIntent(c, key)
                            .putExtra(EXTRA_DAY, next.toLocalDate().toEpochDay())
                            .putExtra(EXTRA_AT, time)
                        val operation = PendingIntent.getBroadcast(c, 0, intent, flags)
                        try {
                            if (exactAllowed) {
                                // Срабатывает точно по времени даже у спящего телефона.
                                alarms.setAlarmClock(AlarmManager.AlarmClockInfo(time, showIntent(c)), operation)
                            } else {
                                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, operation)
                            }
                        } catch (e: SecurityException) {
                            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, operation)
                        }
                        armed.add(key)
                    }
                }

                // Сигналы дел, которые выполнены, удалены или больше не имеют сигнала, отменяются.
                for (key in previous - armed) {
                    val stale = PendingIntent.getBroadcast(
                        c,
                        0,
                        signalIntent(c, key),
                        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
                    )
                    if (stale != null) {
                        alarms.cancel(stale)
                        stale.cancel()
                    }
                }
                prefs.edit().putStringSet(KEY_ARMED, armed).commit()
            }
        }
    }
}

/** Перезагрузка, обновление приложения, смена времени или пояса: расписание ставится заново. */
class ResetReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pending = goAsync()
        app.container.launch {
            try {
                SignalScheduler.refresh(app)
                WidgetUpdater.requestUpdate(app)
            } catch (e: Exception) {
                Log.w(TAG, "Не удалось восстановить сигналы", e)
            } finally {
                pending.finish()
            }
        }
    }
}

/** Срабатывание будильника одного сигнала. */
class SignalReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val segments = intent.data?.pathSegments ?: return
        val taskId = segments.getOrNull(0)?.toLongOrNull() ?: return
        val kindName = segments.getOrNull(1) ?: return
        val kind = SignalKind.entries.firstOrNull { it.name == kindName } ?: return
        val day = intent.getLongExtra(SignalScheduler.EXTRA_DAY, Long.MIN_VALUE)
        val at = intent.getLongExtra(SignalScheduler.EXTRA_AT, 0L)
        val app = context.applicationContext
        val pending = goAsync()
        app.container.launch {
            try {
                val data = app.container.repository.snapshot()
                val task = data.tasks.firstOrNull { it.id == taskId }
                if (task != null && isDue(task, kind, day, at, data)) {
                    Signals.deliver(app, task, kind)
                }
                SignalScheduler.refresh(app)
            } catch (e: Exception) {
                Log.w(TAG, "Не удалось обработать сигнал", e)
            } finally {
                pending.finish()
            }
        }
    }
}

/**
 * Сигнал показывается, только если дело всё ещё есть в этот день, не выполнено
 * и время сигнала не изменилось с момента установки будильника.
 */
private fun isDue(task: Task, kind: SignalKind, day: Long, at: Long, data: PlannerData): Boolean {
    if (day != LocalDate.now().toEpochDay()) return false
    if (!Schedule.occursOn(task, day, data.skipped)) return false
    if (DayKey(task.id, day) in data.completions) return false
    val minutes = Schedule.signalMinutes(task, kind) ?: return false
    val expected = LocalDate.ofEpochDay(day)
        .atTime(minutes / 60, minutes % 60)
        .atZone(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()
    return expected == at
}
