package ru.dayplanner.app.ui.editor

import android.Manifest
import android.app.NotificationManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import ru.dayplanner.app.R
import ru.dayplanner.app.domain.SignalType
import ru.dayplanner.app.domain.TimeMode
import ru.dayplanner.app.ui.components.SectionCard
import ru.dayplanner.app.ui.settings.PermissionsSection
import ru.dayplanner.app.ui.templates.SignalSwitch

/**
 * Раздел «Сигнал» в редакторе дела. У дел без времени сигналов нет,
 * сигнал окончания доступен только для промежутка.
 *
 * Разрешение на уведомления запрашивается в момент, когда сигнал включили, а оно ещё не выдано.
 * Ниже остаётся блок с объяснениями и кнопками для остальных разрешений; при отказе он же
 * подсказывает, как включить разрешение позже.
 */
@Composable
fun SignalSection(state: EditorState, onChange: (EditorState) -> Unit) {
    val context = LocalContext.current
    val askNotifications = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ ->
        // Результат подхватит блок разрешений, когда экран снова станет активным.
    }
    if (state.timeMode == TimeMode.NONE) return

    val onSignalEnabled: () -> Unit = {
        if (Build.VERSION.SDK_INT >= 33) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (!nm.areNotificationsEnabled()) {
                try {
                    askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                } catch (e: Exception) {
                    // Системный запрос недоступен: остаются кнопки в блоке разрешений ниже.
                }
            }
        }
    }

    val isRange = state.timeMode == TimeMode.RANGE
    val anySignal = state.signalStart || (isRange && state.signalEnd)
    val alarm = state.signalType == SignalType.ALARM
    SectionCard(title = stringResource(R.string.template_signals)) {
        SignalSwitch(R.string.signal_when_start, state.signalStart) { on ->
            if (on) onSignalEnabled()
            onChange(state.copy(signalStart = on))
        }
        if (isRange) {
            SignalSwitch(R.string.signal_when_end, state.signalEnd) { on ->
                if (on) onSignalEnabled()
                onChange(state.copy(signalEnd = on))
            }
        }
        if (anySignal) {
            SignalSwitch(R.string.signal_use_alarm, alarm) { on ->
                onChange(state.copy(signalType = if (on) SignalType.ALARM else SignalType.NOTIFICATION))
            }
        }
    }
    if (anySignal) PermissionsSection(alarm)
}
