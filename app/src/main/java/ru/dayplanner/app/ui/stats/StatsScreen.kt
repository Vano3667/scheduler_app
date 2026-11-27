package ru.dayplanner.app.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.dayplanner.app.R
import ru.dayplanner.app.container
import ru.dayplanner.app.domain.PeriodStat
import ru.dayplanner.app.ui.Fmt
import ru.dayplanner.app.ui.components.ScreenScaffold
import ru.dayplanner.app.ui.components.SectionCard
import ru.dayplanner.app.ui.rememberToday
import java.time.LocalDate
import java.time.YearMonth

/** Статистика за неделю или месяц. Всё считается на устройстве из локальной базы. */
@Composable
fun StatsScreen(onBack: () -> Unit) {
    val container = LocalContext.current.container
    val data by container.repository.data.collectAsStateWithLifecycle()
    val todayDay = rememberToday()
    val today = LocalDate.ofEpochDay(todayDay)
    var monthly by rememberSaveable { mutableStateOf(false) }
    var offset by rememberSaveable { mutableStateOf(0) }

    val first: LocalDate = if (monthly) {
        today.withDayOfMonth(1).plusMonths(offset.toLong())
    } else {
        today.minusDays(today.dayOfWeek.value - 1L).plusWeeks(offset.toLong())
    }
    val last: LocalDate = if (monthly) first.plusMonths(1).minusDays(1) else first.plusDays(6)
    val firstDay = first.toEpochDay()
    val lastDay = last.toEpochDay()
    val stat = remember(data, firstDay, lastDay) { data?.stats(firstDay, lastDay) }
    val title = if (monthly) {
        Fmt.monthTitle(YearMonth.from(first))
    } else {
        Fmt.shortDate(firstDay) + " — " + Fmt.fullDate(lastDay)
    }

    ScreenScaffold(title = stringResource(R.string.open_stats), onBack = onBack) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = !monthly,
                    onClick = {
                        monthly = false
                        offset = 0
                    },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    icon = {},
                ) {
                    Text(stringResource(R.string.stats_week))
                }
                SegmentedButton(
                    selected = monthly,
                    onClick = {
                        monthly = true
                        offset = 0
                    },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    icon = {},
                ) {
                    Text(stringResource(R.string.stats_month))
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { offset -= 1 }, enabled = firstDay > 31) {
                    Icon(Icons.Rounded.ChevronLeft, contentDescription = stringResource(R.string.stats_prev))
                }
                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
                IconButton(onClick = { offset += 1 }, enabled = offset < 0) {
                    Icon(Icons.Rounded.ChevronRight, contentDescription = stringResource(R.string.stats_next))
                }
            }

            if (stat != null) {
                SectionCard {
                    Text(
                        text = stringResource(R.string.stats_percent, stat.percent),
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    LinearProgressIndicator(
                        progress = { stat.percent / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(CircleShape),
                    )
                    NumberRow(label = stringResource(R.string.stats_planned), value = stat.planned)
                    NumberRow(label = stringResource(R.string.stats_done), value = stat.done)
                }

                SectionCard(title = stringResource(R.string.stats_by_day)) {
                    if (stat.planned == 0) {
                        Text(
                            text = stringResource(R.string.stats_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            LegendItem(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                label = stringResource(R.string.stats_planned),
                            )
                            LegendItem(
                                color = MaterialTheme.colorScheme.primary,
                                label = stringResource(R.string.stats_done),
                            )
                        }
                        DayBars(stat = stat, today = todayDay)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun NumberRow(label: String, value: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value.toString(), style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Простая столбчатая диаграмма: светлый столбец — запланировано, тёмный — выполнено. */
@Composable
private fun DayBars(stat: PeriodStat, today: Long) {
    val names = stringArrayResource(R.array.weekdays_short)
    val max = (stat.days.maxOfOrNull { it.planned } ?: 0).coerceAtLeast(1)
    val dense = stat.days.size > 7
    val plannedColor = MaterialTheme.colorScheme.primaryContainer
    val doneColor = MaterialTheme.colorScheme.primary
    val baseColor = MaterialTheme.colorScheme.outlineVariant
    val barShape = RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(170.dp),
        horizontalArrangement = Arrangement.spacedBy(if (dense) 2.dp else 8.dp),
    ) {
        for (d in stat.days) {
            val date = LocalDate.ofEpochDay(d.day)
            val description = stringResource(R.string.stats_day, Fmt.shortDate(d.day), d.done, d.planned)
            val label = when {
                !dense -> names[date.dayOfWeek.value - 1]
                date.dayOfMonth == 1 || date.dayOfMonth % 5 == 0 -> date.dayOfMonth.toString()
                else -> ""
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .semantics { contentDescription = description },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(d.planned.toFloat() / max)
                            .clip(barShape)
                            .background(plannedColor),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(d.done.toFloat() / max)
                            .clip(barShape)
                            .background(doneColor),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .background(baseColor),
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = label,
                    modifier = Modifier.wrapContentWidth(unbounded = true),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (d.day == today) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}
