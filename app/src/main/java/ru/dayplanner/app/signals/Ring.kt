package ru.dayplanner.app.signals

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import ru.dayplanner.app.MainActivity
import ru.dayplanner.app.R
import ru.dayplanner.app.domain.*
import ru.dayplanner.app.ui.theme.*

private const val TAG = "Planner"

/** Показ сигналов: обычное уведомление либо будильник на весь экран. */
object Signals {
    const val ACTIVE = 7701
    const val CHANNEL_NOTICE = "notice"
    const val CHANNEL_ALARM = "alarm"
    const val CHANNEL_RING = "ring"
    const val ACTION_STOPPED = "ru.dayplanner.app.ALARM_STOPPED"
    const val ACTION_STOP = "stop"
    private const val REQUEST_OPEN = 4301

    /** Можно ли показать экран будильника поверх всего (на Android 14+ это отдельное разрешение). */
    fun full(c: Context): Boolean {
        if (Build.VERSION.SDK_INT < 34) return true
        return c.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
    }

    fun channels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        val alarmAudio = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
        val names = listOf(
            CHANNEL_NOTICE to R.string.notice_channel,
            CHANNEL_ALARM to R.string.ring_channel,
            CHANNEL_RING to R.string.ring_service_channel,
        )
        for (pair in names) {
            val key = pair.first
            val channel = NotificationChannel(key, c.getString(pair.second), NotificationManager.IMPORTANCE_HIGH)
            channel.enableVibration(true)
            if (key == CHANNEL_RING) {
                // Звук этого канала играет сам сервис, пока его не выключат.
                channel.setSound(null, null)
            } else if (key == CHANNEL_ALARM) {
                // Если мелодии будильника в системе нет, остаётся обычный звук уведомления.
                val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                if (alarmUri != null) channel.setSound(alarmUri, alarmAudio)
            }
            nm.createNotificationChannel(channel)
        }
    }

    /** Текст сигнала: «Пора начинать · 09:30» или «Время вышло · 11:00». */
    private fun text(c: Context, t: Task, k: SignalKind): String {
        val minutes = Schedule.signalMinutes(t, k) ?: t.startMinutes
        val res = if (k == SignalKind.START) R.string.signal_now_start else R.string.signal_now_end
        return c.getString(res, TimeText.minutes(minutes))
    }

    /** Открывает приложение так же, как значок на рабочем столе (без второго экземпляра экрана). */
    private fun openApp(c: Context): PendingIntent {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setClass(c, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return PendingIntent.getActivity(
            c, REQUEST_OPEN, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun deliver(c: Context, t: Task, k: SignalKind) {
        channels(c)
        val nm = c.getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) return
        val alarm = t.signalType == SignalType.ALARM
        val label = text(c, t, k)
        val title = t.title

        if (alarm && canRing(c, nm)) {
            try {
                val ring = Intent(c, RingService::class.java)
                    .putExtra("title", title)
                    .putExtra("label", label)
                c.startForegroundService(ring)
                return
            } catch (e: RuntimeException) {
                // Система не разрешила запуск сервиса: ниже покажем громкое уведомление.
                Log.w(TAG, "Не удалось запустить будильник, показываю уведомление", e)
            }
        }

        val notification = Notification.Builder(c, if (alarm) CHANNEL_ALARM else CHANNEL_NOTICE)
            .setSmallIcon(R.drawable.ic_notify)
            .setCategory(if (alarm) Notification.CATEGORY_ALARM else Notification.CATEGORY_REMINDER)
            .setContentTitle(title)
            .setContentText(label)
            .setAutoCancel(true)
            .setContentIntent(openApp(c))
            .build()
        if (alarm) {
            // Запасной вариант будильника: звук повторяется, пока уведомление не откроют или не смахнут.
            notification.flags = notification.flags or Notification.FLAG_INSISTENT
        }
        nm.notify("${t.id}:${k.name}", 1, notification)
    }

    private fun canRing(c: Context, nm: NotificationManager): Boolean {
        if (!full(c) || !SignalScheduler.exact(c)) return false
        val channel = nm.getNotificationChannel(CHANNEL_RING) ?: return false
        return channel.importance >= NotificationManager.IMPORTANCE_HIGH
    }
}

/** Играет звук будильника, пока пользователь не нажмёт «Выключить». */
class RingService : Service() {
    private var player: Ringtone? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == Signals.ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        Signals.channels(this)
        val title = intent.getStringExtra("title").orEmpty()
        val label = intent.getStringExtra("label").orEmpty()

        val screenIntent = Intent(this, AlarmActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra("title", title)
            .putExtra("label", label)
        val screen = PendingIntent.getActivity(
            this, 1, screenIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 2,
            Intent(this, RingService::class.java).setAction(Signals.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stopAction = Notification.Action.Builder(null, getString(R.string.ring_stop), stop).build()
        val builder = Notification.Builder(this, Signals.CHANNEL_RING)
            .setSmallIcon(R.drawable.ic_notify)
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setContentTitle(title)
            .setContentText(label)
            .setContentIntent(screen)
            .setFullScreenIntent(screen, true)
            .addAction(stopAction)
        if (Build.VERSION.SDK_INT >= 31) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        ringing = true
        try {
            startForeground(Signals.ACTIVE, builder.build())
        } catch (e: RuntimeException) {
            Log.w(TAG, "Не удалось показать будильник", e)
            stopSelf()
            return START_NOT_STICKY
        }

        stopPlayer()
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val ringtone: Ringtone? = if (uri != null) RingtoneManager.getRingtone(this, uri) else null
            if (ringtone != null) {
                ringtone.audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
                ringtone.isLooping = true
                ringtone.play()
            }
            player = ringtone
        } catch (e: RuntimeException) {
            Log.w(TAG, "Не удалось включить мелодию будильника", e)
        }
        return START_NOT_STICKY
    }

    private fun stopPlayer() {
        try {
            player?.stop()
        } catch (e: RuntimeException) {
            Log.w(TAG, "Не удалось остановить мелодию", e)
        }
        player = null
    }

    override fun onDestroy() {
        ringing = false
        stopPlayer()
        stopForeground(STOP_FOREGROUND_REMOVE)
        sendBroadcast(Intent(Signals.ACTION_STOPPED).setPackage(packageName))
        super.onDestroy()
    }

    companion object {
        /** true, пока будильник звонит. */
        @Volatile
        var ringing: Boolean = false
            private set
    }
}

/** Экран будильника. Эффектов здесь нет намеренно, всегда обычный стиль. */
class AlarmActivity : ComponentActivity() {
    private var registered = false

    private val closed = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            finishAndRemoveTask()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!RingService.ringing) {
            // Будильник уже выключен (например, окно восстановлено системой позже).
            finishAndRemoveTask()
            return
        }
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        ContextCompat.registerReceiver(
            this, closed, IntentFilter(Signals.ACTION_STOPPED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        registered = true
        val label = intent.getStringExtra("label").orEmpty()
        val title = intent.getStringExtra("title").orEmpty()
        setContent {
            PlannerTheme(AppStyle.NORMAL) {
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(28.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = title,
                            style = MaterialTheme.typography.headlineLarge,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(40.dp))
                        Button(
                            onClick = { stopRinging() },
                            modifier = Modifier.fillMaxWidth().height(64.dp),
                            shape = MaterialTheme.shapes.large,
                        ) {
                            Text(
                                text = stringResource(R.string.ring_stop),
                                style = MaterialTheme.typography.titleLarge,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun stopRinging() {
        stopService(Intent(this, RingService::class.java))
        finishAndRemoveTask()
    }

    override fun onDestroy() {
        if (registered) {
            registered = false
            unregisterReceiver(closed)
        }
        super.onDestroy()
    }
}
