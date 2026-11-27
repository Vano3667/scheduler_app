@file:Suppress("DEPRECATION")

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
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
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
import ru.dayplanner.app.R
import ru.dayplanner.app.domain.Schedule
import ru.dayplanner.app.domain.SignalKind
import ru.dayplanner.app.domain.SignalType
import ru.dayplanner.app.domain.Task
import ru.dayplanner.app.domain.TimeText
import ru.dayplanner.app.ui.theme.AppStyle
import ru.dayplanner.app.ui.theme.PlannerTheme
import ru.dayplanner.app.widget.WidgetEntryActivity
import java.time.LocalDate

private const val TAG = "Planner"

/** Показ сигналов: обычное уведомление или будильник. */
object Signals {
    const val CHANNEL_NOTICE = "notice"
    const val CHANNEL_ALARM = "alarm"
    const val CHANNEL_RING = "ring"
    const val RING_ID = 7701
    const val ACTION_STOPPED = "ru.dayplanner.app.ALARM_STOPPED"

    /** Можно ли показать окно будильника на весь экран. */
    fun full(c: Context): Boolean =
        Build.VERSION.SDK_INT < 34 ||
            c.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

    fun alarmAttributes(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    fun channels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)

        val notice = NotificationChannel(
            CHANNEL_NOTICE,
            c.getString(R.string.notice_channel),
            NotificationManager.IMPORTANCE_HIGH,
        )
        notice.enableVibration(true)

        val alarm = NotificationChannel(
            CHANNEL_ALARM,
            c.getString(R.string.ring_channel),
            NotificationManager.IMPORTANCE_HIGH,
        )
        alarm.enableVibration(true)
        val alarmUri: Uri? = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        if (alarmUri != null) alarm.setSound(alarmUri, alarmAttributes())

        // Звук этого канала выключен: мелодию до нажатия «Выключить» играет сам сервис.
        val ring = NotificationChannel(
            CHANNEL_RING,
            c.getString(R.string.ring_service_channel),
            NotificationManager.IMPORTANCE_HIGH,
        )
        ring.setSound(null, null)
        ring.enableVibration(false)
        ring.lockscreenVisibility = Notification.VISIBILITY_PUBLIC

        nm.createNotificationChannels(listOf(notice, alarm, ring))
    }

    private fun text(c: Context, task: Task, kind: SignalKind): String {
        val minutes = Schedule.signalMinutes(task, kind) ?: task.startMinutes
        val res = if (kind == SignalKind.START) R.string.signal_now_start else R.string.signal_now_end
        return c.getString(res, TimeText.minutes(minutes))
    }

    /** Нажатие на уведомление открывает страницу дела на сегодня. */
    private fun openTask(c: Context, taskId: Long, kind: SignalKind): PendingIntent {
        val intent = WidgetEntryActivity.intent(c, WidgetEntryActivity.ACTION_ROW)
            .setData(Uri.parse("planner-task://local/$taskId"))
            .putExtra(WidgetEntryActivity.EXTRA_ID, taskId)
            .putExtra(WidgetEntryActivity.EXTRA_DAY, LocalDate.now().toEpochDay())
        return PendingIntent.getActivity(
            c,
            kind.ordinal,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun deliver(c: Context, task: Task, kind: SignalKind) {
        channels(c)
        val nm = c.getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) return
        val text = text(c, task, kind)
        val alarm = task.signalType == SignalType.ALARM
        if (alarm) {
            val ringChannel = nm.getNotificationChannel(CHANNEL_RING)
            val visible = ringChannel == null || ringChannel.importance != NotificationManager.IMPORTANCE_NONE
            if (visible && SignalScheduler.exact(c)) {
                try {
                    c.startForegroundService(RingService.intent(c, task.title, text))
                    return
                } catch (e: RuntimeException) {
                    Log.w(TAG, "Не удалось запустить будильник, показываю уведомление", e)
                }
            }
        }
        notifyPlain(c, task, kind, text, alarm)
    }

    private fun notifyPlain(c: Context, task: Task, kind: SignalKind, text: String, alarm: Boolean) {
        val nm = c.getSystemService(NotificationManager::class.java)
        val notification = Notification.Builder(c, if (alarm) CHANNEL_ALARM else CHANNEL_NOTICE)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(task.title)
            .setContentText(text)
            .setCategory(if (alarm) Notification.CATEGORY_ALARM else Notification.CATEGORY_REMINDER)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(openTask(c, task.id, kind))
            .build()
        nm.notify(task.id.toString() + ":" + kind.name, 1, notification)
    }
}

/** Играет мелодию будильника, пока пользователь не нажмёт «Выключить». */
class RingService : Service() {
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        Signals.channels(this)
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
        val flagsImmutable = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val screen = PendingIntent.getActivity(this, 1, AlarmActivity.intent(this, title, text), flagsImmutable)
        val stop = PendingIntent.getService(
            this,
            2,
            Intent(this, RingService::class.java).setAction(ACTION_STOP),
            flagsImmutable,
        )
        val stopAction = Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_notify),
            getString(R.string.ring_stop),
            stop,
        ).build()
        val builder = Notification.Builder(this, Signals.CHANNEL_RING)
            .setSmallIcon(R.drawable.ic_notify)
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(screen)
            .setFullScreenIntent(screen, true)
            .addAction(stopAction)
        if (Build.VERSION.SDK_INT >= 31) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        ringing = true
        try {
            startForeground(Signals.RING_ID, builder.build())
        } catch (e: RuntimeException) {
            Log.w(TAG, "Не удалось показать будильник", e)
            stopSelf()
            return START_NOT_STICKY
        }
        startRinging()
        return START_NOT_STICKY
    }

    private fun startRinging() {
        stopRinging()
        try {
            val uri: Uri? = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            if (uri != null) {
                val tone: Ringtone? = RingtoneManager.getRingtone(this, uri)
                if (tone != null) {
                    tone.audioAttributes = Signals.alarmAttributes()
                    tone.isLooping = true
                    tone.play()
                    ringtone = tone
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось включить мелодию будильника", e)
        }
        try {
            val device: Vibrator? = getSystemService(Vibrator::class.java)
            if (device != null && device.hasVibrator()) {
                device.vibrate(
                    VibrationEffect.createWaveform(longArrayOf(0L, 700L, 900L), 0),
                    Signals.alarmAttributes(),
                )
                vibrator = device
            }
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось включить вибрацию", e)
        }
    }

    private fun stopRinging() {
        try {
            ringtone?.stop()
        } catch (_: Exception) {
        }
        ringtone = null
        try {
            vibrator?.cancel()
        } catch (_: Exception) {
        }
        vibrator = null
    }

    override fun onDestroy() {
        ringing = false
        stopRinging()
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }
        sendBroadcast(Intent(Signals.ACTION_STOPPED).setPackage(packageName))
        super.onDestroy()
    }

    companion object {
        private const val ACTION_STOP = "ru.dayplanner.app.action.STOP_RING"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_TEXT = "text"

        /** true, пока будильник звонит. */
        @Volatile
        var ringing: Boolean = false
            private set

        fun intent(c: Context, title: String, text: String): Intent =
            Intent(c, RingService::class.java)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_TEXT, text)
    }
}

/** Полноэкранное окно будильника. Оформление всегда спокойное, без эффектов. */
class AlarmActivity : ComponentActivity() {

    private val stopped = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            finishAndRemoveTask()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ContextCompat.registerReceiver(
            this,
            stopped,
            IntentFilter(Signals.ACTION_STOPPED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        if (!RingService.ringing) {
            finishAndRemoveTask()
            return
        }
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
        setContent {
            PlannerTheme(style = AppStyle.NORMAL) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .safeDrawingPadding()
                            .padding(28.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = text,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = title,
                            style = MaterialTheme.typography.headlineMedium,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(40.dp))
                        Button(
                            onClick = {
                                stopService(Intent(this@AlarmActivity, RingService::class.java))
                                finishAndRemoveTask()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(64.dp),
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

    override fun onDestroy() {
        try {
            unregisterReceiver(stopped)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_TEXT = "text"

        fun intent(c: Context, title: String, text: String): Intent =
            Intent(c, AlarmActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_TEXT, text)
    }
}
