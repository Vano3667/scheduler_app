package ru.dayplanner.app.ui.editor

import androidx.compose.runtime.Composable
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
 * Разрешения запрашиваются здесь же — в момент, когда сигнал впервые включили.
 */
@Composable
fun SignalSection(state: EditorState, onChange: (EditorState) -> Unit) {
    if (state.timeMode == TimeMode.NONE) return
    val isRange = state.timeMode == TimeMode.RANGE
    val anySignal = state.signalStart || (isRange && state.signalEnd)
    val alarm = state.signalType == SignalType.ALARM
    SectionCard(title = stringResource(R.string.template_signals)) {
        SignalSwitch(R.string.signal_when_start, state.signalStart) {
            onChange(state.copy(signalStart = it))
        }
        if (isRange) {
            SignalSwitch(R.string.signal_when_end, state.signalEnd) {
                onChange(state.copy(signalEnd = it))
            }
        }
        if (anySignal) {
            SignalSwitch(R.string.signal_use_alarm, alarm) {
                onChange(state.copy(signalType = if (it) SignalType.ALARM else SignalType.NOTIFICATION))
            }
        }
    }
    if (anySignal) PermissionsSection(alarm)
}
