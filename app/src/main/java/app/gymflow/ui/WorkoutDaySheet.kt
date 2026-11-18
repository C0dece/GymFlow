package app.gymflow.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.gymflow.R
import app.gymflow.domain.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WorkoutDaySheet(
    program: Program, profile: Profile, current: ParticipantWorkout, options: Map<String, WorkoutDayOptions>,
    close: () -> Unit, start: (String) -> Unit, review: (WorkoutDayRecord) -> Unit,
) {
    EditorSheet(stringResource(R.string.switch_day), close) {
        Muted(stringResource(R.string.workout_day_for, program.title, profile.name))
        program.days.forEach { day ->
            val choice = options[day.id] ?: WorkoutDayOptions()
            val record = choice.display
            val status = if (record != null && record == choice.completed) WorkoutStatus.COMPLETED
                else record?.workout?.status ?: WorkoutStatus.PLANNED
            val selected = record?.workout?.id == current.id && status == WorkoutStatus.ACTIVE
            CardBlock {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        SectionTitle(day.title)
                        Muted(stringResource(R.string.workout_day_summary,
                            stringResource(R.string.exercise_count, record?.workout?.exercises?.size ?: day.exercises.size), workoutStatus(status)))
                    }
                    if (selected) {
                        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)) {
                            Text(stringResource(R.string.workout_active), Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (choice.completed != null) {
                        OutlinedButton(onClick = { review(choice.completed) }, modifier = Modifier.testTag("review_day_${day.id}")) {
                            Text(stringResource(R.string.review_results))
                        }
                    }
                    if (!selected) {
                        val label = when {
                            choice.pending != null -> R.string.continue_training
                            choice.completed != null -> R.string.new_workout_from_day
                            else -> R.string.start_workout
                        }
                        Button(onClick = { start(day.id) }, modifier = Modifier.testTag("start_day_${day.id}")) {
                            Text(stringResource(label))
                        }
                    }
                }
            }
        }
    }
}
