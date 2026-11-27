@file:Suppress("DEPRECATION")

package ru.dayplanner.app.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StrikethroughSpan
import android.util.Log
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.dayplanner.app.MainActivity
import ru.dayplanner.app.R
import ru.dayplanner.app.container
import ru.dayplanner.app.domain.Occurrence
import ru.dayplanner.app.domain.TimeText
import ru.dayplanner.app.signals.SignalScheduler
import ru.dayplanner.app.ui.Fmt
import ru.dayplanner.app.ui.editor.EditorScreen
import ru.dayplanner.app.ui.effects.EffectsHost
import ru.dayplanner.app.ui.task.TaskScreen
import ru.dayplanner.app.ui.theme.PlannerTheme
import java.time.LocalDate
import java.time.ZoneId

private const val TAG = "Planner"

/** Обновление виджета «дела на сегодня». Вызывается после любого изменения дел и при смене суток. */
object WidgetUpdater {
    private const val ACTION_MIDNIGHT = "ru.dayplanner.app.widget.MIDNIGHT"

    fun isTick(intent: Intent): Boolean = intent.action == ACTION_MIDNIGHT

    fun requestUpdate(context: Context) {
        try {
            update(context.applicationContext)
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось обновить виджет", e)
        }
    }

    private fun update(c: Context) {
        val manager = AppWidgetManager.getInstance(c) ?: return
        val ids = manager.getAppWidgetIds(ComponentName(c, PlannerWidget::class.java)) ?: return
        val alarms = c.getSystemService(AlarmManager::class.java)
        val immutable = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val tick = PendingIntent.getBroadcast(
            c,
            0,
            Intent(c, PlannerWidget::class.java).setAction(ACTION_MIDNIGHT),
            immutable,
        )
        if (ids.isEmpty()) {
            alarms.cancel(tick)
            return
        }

        // Следующее обновление — ровно в полночь, чтобы виджет показал новый день.
        val today = LocalDate.now()
        val midnight = today.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        try {
            if (SignalScheduler.exact(c)) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, midnight, tick)
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, midnight, tick)
            }
        } catch (e: SecurityException) {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, midnight, tick)
        }

        val day = today.toEpochDay()
        val header = Fmt.weekday(day).replaceFirstChar { it.titlecase() } + ", " + Fmt.dayTitle(day, day)

        // Заголовок открывает экран дня (на сегодня).
        val openDayIntent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(ComponentName(c, MainActivity::class.java))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val openDay = PendingIntent.getActivity(c, 0, openDayIntent, immutable)
        val add = PendingIntent.getActivity(
            c,
            1,
            WidgetEntryActivity.intent(c, WidgetEntryActivity.ACTION_ADD),
            immutable,
        )
        // Шаблон для строк списка: номер дела и день подставляются из строки, поэтому он изменяемый.
        val mutable = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        val row = PendingIntent.getActivity(
            c,
            2,
            WidgetEntryActivity.intent(c, WidgetEntryActivity.ACTION_ROW),
            mutable,
        )

        for (id in ids) {
            val views = RemoteViews(c.packageName, R.layout.widget)
            views.setTextViewText(R.id.header, header)
            val adapter = Intent(c, RowsService::class.java)
                .setData(Uri.parse("planner-widget://local/list/$id"))
            views.setRemoteAdapter(R.id.rows, adapter)
            views.setEmptyView(R.id.rows, R.id.empty)
            views.setOnClickPendingIntent(R.id.header, openDay)
            views.setOnClickPendingIntent(R.id.add, add)
            views.setPendingIntentTemplate(R.id.rows, row)
            manager.updateAppWidget(id, views)
        }
        manager.notifyAppWidgetViewDataChanged(ids, R.id.rows)
    }
}

class PlannerWidget : AppWidgetProvider() {
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (WidgetUpdater.isTick(intent)) WidgetUpdater.requestUpdate(context)
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        WidgetUpdater.requestUpdate(context)
    }

    override fun onEnabled(context: Context) {
        WidgetUpdater.requestUpdate(context)
    }

    override fun onDisabled(context: Context) {
        WidgetUpdater.requestUpdate(context)
    }
}

class RowsService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = Rows(applicationContext)
}

/** Строки списка виджета: дела на сегодня в том же порядке, что и на экране дня. */
private class Rows(private val c: Context) : RemoteViewsService.RemoteViewsFactory {
    private var items: List<Occurrence> = emptyList()

    override fun onCreate() {}

    override fun onDestroy() {
        items = emptyList()
    }

    override fun onDataSetChanged() {
        val token = Binder.clearCallingIdentity()
        try {
            items = c.container.repository.snapshotBlocking().occurrencesOn(LocalDate.now().toEpochDay())
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось прочитать дела для виджета", e)
            items = emptyList()
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    override fun getCount(): Int = items.size

    override fun getViewAt(position: Int): RemoteViews? {
        val item = items.getOrNull(position) ?: return null
        val time = TimeText.label(item.task)
        val line = if (time.isEmpty()) item.task.title else time + "  " + item.task.title
        val text = SpannableString(line)
        if (item.done) {
            text.setSpan(StrikethroughSpan(), 0, line.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val views = RemoteViews(c.packageName, R.layout.widget_row)
        views.setTextViewText(R.id.row_text, text)
        val fillIn = Intent()
            .putExtra(WidgetEntryActivity.EXTRA_ID, item.task.id)
            .putExtra(WidgetEntryActivity.EXTRA_DAY, item.day)
        views.setOnClickFillInIntent(R.id.row_text, fillIn)
        return views
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 1

    override fun getItemId(position: Int): Long = items.getOrNull(position)?.task?.id ?: -1L

    override fun hasStableIds(): Boolean = true
}

/** Страница дела или добавление дела на сегодня, открытые из виджета или из уведомления. */
class WidgetEntryActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val adding = intent.action == ACTION_ADD
        val taskId = if (adding) 0L else intent.getLongExtra(EXTRA_ID, 0L)
        val day = intent.getLongExtra(EXTRA_DAY, LocalDate.now().toEpochDay())
        if (!adding && taskId <= 0L) {
            finish()
            return
        }
        enableEdgeToEdge()
        setContent {
            val style by container.settings.style.collectAsStateWithLifecycle()
            var editing by rememberSaveable { mutableStateOf(adding) }
            PlannerTheme(style = style) {
                EffectsHost(style = style) {
                    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        if (editing) {
                            EditorScreen(
                                taskId = taskId,
                                day = day,
                                onClose = { if (adding) finish() else editing = false },
                            )
                        } else {
                            TaskScreen(
                                taskId = taskId,
                                day = day,
                                onBack = { finish() },
                                onGone = { finish() },
                                onEdit = { editing = true },
                            )
                        }
                    }
                }
            }
            BackHandler(enabled = editing && !adding) { editing = false }
        }
    }

    companion object {
        const val ACTION_ADD = "ru.dayplanner.app.widget.ADD"
        const val ACTION_ROW = "ru.dayplanner.app.widget.ROW"
        const val EXTRA_ID = "id"
        const val EXTRA_DAY = "day"

        fun intent(c: Context, action: String): Intent =
            Intent(c, WidgetEntryActivity::class.java)
                .setAction(action)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }
}
