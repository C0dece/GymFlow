package app.gymflow.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.gymflow.R
import app.gymflow.domain.*
import java.time.LocalDate

@Composable
internal fun PlannedDaySheet(
    program: Program, source: Session, member: ParticipantWorkout, sessions: List<Session>,
    close: () -> Unit, save: (String, LocalDate?) -> Unit,
) {
    val originalId = source.templateOf(member)
    val original = program.days.firstOrNull { it.id == originalId } ?: program.days.firstOrNull()
    var dayId by remember { mutableStateOf<String?>(null) }
    val selected = program.days.find { it.id == dayId }
    val busy = remember(source, member.profile.id, sessions) { otherPlannedDayIds(source, member.profile.id, sessions) }
    val swap = remember(program, selected, source.date, member.profile.id, sessions) {
        selected?.takeIf { program.completionCycle == null }
            ?.let { nextPlannedDay(program, it, LocalDate.parse(source.date), member.profile.id, sessions) }
    }
    val options = remember(sessions, source, member.profile.id) { workoutDays(sessions, source, member.profile.id) }

    EditorSheet(stringResource(R.string.switch_day), close) {
        Muted(stringResource(R.string.planned_day_context, shortDate(source.date), member.profile.name))
        program.days.forEach { day ->
            val choice = options[day.id] ?: WorkoutDayOptions()
            val record = choice.display
            val status = when {
                day.id == originalId -> WorkoutStatus.PLANNED
                record != null && record == choice.completed -> WorkoutStatus.COMPLETED
                else -> record?.workout?.status ?: WorkoutStatus.PLANNED
            }
            val isCurrent = day.id == originalId
            val isSelected = day == selected
            CardBlock(
                modifier = Modifier.fillMaxWidth().testTag("planned_day_${day.id}"),
                onClick = { if (day.id !in busy && !isCurrent) dayId = day.id }
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        SectionTitle(day.title)
                        Muted(stringResource(R.string.workout_day_summary,
                            stringResource(R.string.exercise_count, day.exercises.size), workoutStatus(status)))
                    }
                    if (isCurrent) {
                        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(8.dp)) {
                            Text(stringResource(R.string.workout_planned), Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
                        }
                    } else if (day.id in busy) {
                        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp)) {
                            Text(stringResource(R.string.workout_planned), Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
                        }
                    } else {
                        RadioButton(selected = isSelected, onClick = { dayId = day.id })
                    }
                }
            }
        }
        if (selected != null && selected.id !in busy && selected.id != originalId) {
            Button(onClick = { save(selected.id, null) }, modifier = Modifier.fillMaxWidth().testTag("day_only_once")) {
                Text(stringResource(R.string.planned_day_once))
            }
            Muted(stringResource(R.string.planned_day_once_help, original?.title.orEmpty()))
            if (swap != null) {
                OutlinedButton(onClick = { save(selected.id, swap.date) },
                    modifier = Modifier.fillMaxWidth().testTag("day_swap")) {
                    Text(stringResource(R.string.planned_day_swap))
                }
                Muted(stringResource(R.string.planned_day_swap_help, original?.title.orEmpty(), shortDate(swap.date.toString())))
            }
        }
    }
}
