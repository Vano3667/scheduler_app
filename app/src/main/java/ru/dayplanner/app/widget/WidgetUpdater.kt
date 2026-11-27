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
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.dayplanner.app.MainActivity
import ru.dayplanner.app.R
import ru.dayplanner.app.container
import ru.dayplanner.app.domain.*
import ru.dayplanner.app.signals.SignalScheduler
import ru.dayplanner.app.ui.Fmt
import ru.dayplanner.app.ui.editor.EditorScreen
import ru.dayplanner.app.ui.effects.EffectsHost
import ru.dayplanner.app.ui.task.TaskScreen
import ru.dayplanner.app.ui.theme.PlannerTheme
import java.time.LocalDate
import java.time.ZoneId

/** Перерисовывает виджет и ставит будильник на ближайшую полночь. */
object WidgetUpdater {
    private const val MIDNIGHT = "ru.dayplanner.app.widget.MIDNIGHT"
    const val ACTION_TODAY = "today"
    const val ACTION_ADD = "add"
    const val ACTION_ROW = "row"

    fun requestUpdate(context: Context) {
        val c = context.applicationContext
        val manager = AppWidgetManager.getInstance(c)
        val ids = manager.getAppWidgetIds(ComponentName(c, PlannerWidget::class.java))
        val alarms = c.getSystemService(AlarmManager::class.java)
        val tick = PendingIntent.getBroadcast(
            c, 0, Intent(c, PlannerWidget::class.java).setAction(MIDNIGHT), PendingIntent.FLAG_IMMUTABLE,
        )
        if (ids.isEmpty()) {
            alarms.cancel(tick)
            return
        }

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

        for (id in ids) {
            val views = RemoteViews(c.packageName, R.layout.widget)
            views.setTextViewText(R.id.header, Fmt.fullDate(today.toEpochDay()))
            val adapter = Intent(c, RowsService::class.java)
                .setData(Uri.parse("planner-widget://local/list/$id"))
            views.setRemoteAdapter(R.id.rows, adapter)
            views.setEmptyView(R.id.rows, R.id.empty)
            views.setOnClickPendingIntent(R.id.header, entry(c, id, ACTION_TODAY, false))
            views.setOnClickPendingIntent(R.id.add, entry(c, id, ACTION_ADD, false))
            views.setPendingIntentTemplate(R.id.rows, entry(c, id, ACTION_ROW, true))
            manager.updateAppWidget(id, views)
        }
        manager.notifyAppWidgetViewDataChanged(ids, R.id.rows)
    }

    private fun entry(c: Context, widgetId: Int, action: String, mutable: Boolean): PendingIntent {
        val intent = Intent(c, WidgetEntryActivity::class.java).setAction(action)
        val mutability = when {
            !mutable -> PendingIntent.FLAG_IMMUTABLE
            Build.VERSION.SDK_INT >= 31 -> PendingIntent.FLAG_MUTABLE
            else -> 0
        }
        return PendingIntent.getActivity(c, widgetId, intent, PendingIntent.FLAG_UPDATE_CURRENT or mutability)
    }

    fun isTick(intent: Intent): Boolean =
        intent.action == MIDNIGHT || intent.action == Intent.ACTION_DATE_CHANGED
}

class PlannerWidget : AppWidgetProvider() {
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (WidgetUpdater.isTick(intent)) WidgetUpdater.requestUpdate(context)
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        WidgetUpdater.requestUpdate(context)
    }

    override fun onDisabled(context: Context) {
        WidgetUpdater.requestUpdate(context)
    }
}

class RowsService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = Rows(applicationContext)
}

/** Строки виджета: дела на сегодня в том же порядке, что и на экране дня. */
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
        } catch (e: RuntimeException) {
            // База недоступна (например, до первой разблокировки): оставляем прежний список.
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    override fun getCount(): Int = items.size

    override fun getViewAt(index: Int): RemoteViews? {
        val item = items.getOrNull(index) ?: return null
        val line = listOf(TimeText.label(item.task), item.task.title)
            .filter { it.isNotEmpty() }
            .joinToString("  ")
        val text = SpannableString(line)
        if (item.done) {
            text.setSpan(StrikethroughSpan(), 0, line.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val fill = Intent().putExtra("id", item.task.id).putExtra("day", item.day)
        val row = RemoteViews(c.packageName, R.layout.widget_row)
        row.setTextViewText(R.id.row_text, text)
        row.setOnClickFillInIntent(R.id.row_text, fill)
        return row
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 1

    override fun getItemId(index: Int): Long = items.getOrNull(index)?.task?.id ?: -1L

    override fun hasStableIds(): Boolean = true
}

/** Точка входа из виджета: страница дела, добавление дела на сегодня или экран дня. */
class WidgetEntryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val action = intent.action
        val id = intent.getLongExtra("id", 0)
        val day = intent.getLongExtra("day", LocalDate.now().toEpochDay())
        val openDay = action == WidgetUpdater.ACTION_TODAY || (action == WidgetUpdater.ACTION_ROW && id <= 0)
        if (openDay) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        enableEdgeToEdge()
        setContent {
            val style by container.settings.style.collectAsStateWithLifecycle()
            var editor by rememberSaveable { mutableStateOf(action == WidgetUpdater.ACTION_ADD) }
            val close: () -> Unit = {
                if (id == 0L || !editor) finish() else editor = false
            }
            PlannerTheme(style) {
                EffectsHost(style) {
                    if (editor) {
                        EditorScreen(id, day, close)
                    } else {
                        TaskScreen(id, day, { finish() }, { finish() }, { editor = true })
                    }
                }
            }
            BackHandler(onBack = close)
        }
    }
}
