@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package app.gymflow.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.gymflow.GymModel
import app.gymflow.R
import app.gymflow.data.Library
import app.gymflow.data.Preferences
import app.gymflow.domain.*
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun PlanPreview(
    original: Session?, template: WorkoutTemplate?, library: Library, prefs: Preferences,
    repeated: Session?, model: GymModel, back: () -> Unit, start: (String) -> Unit,
) {
    val title = stringResource(R.string.free_session)
    val defaultName = stringResource(R.string.self)
    var draft by remember(original?.id, template?.id, repeated?.id) {
        val profile = library.availableProfiles.find { it.id == prefs.athlete } ?: library.availableProfiles.firstOrNull() ?: Profile(name = defaultName)
        mutableStateOf(original ?: repeated?.let { repeatSession(it, newId(), LocalDate.now(), emptyList()) } ?: Session(
            title = template?.title ?: title, date = LocalDate.now().toString(),
            workouts = listOf(ParticipantWorkout(profile = profile).withTemplate(template)),
        ))
    }
    var personId by rememberSaveable { mutableStateOf(draft.defaultWorkout.profile.id) }
    var editing by remember { mutableStateOf<String?>(null) }
    val current = draft.workout(personId) ?: draft.workouts.first()
    val engine by rememberEngine(library)
    val program = library.programs.find { p -> p.days.any { it.id == current.templateId } }?.id ?: draft.programId
    val continuationTemplate = current.templateId.takeIf { repeated == null }
    fun applyDecision(entryId: String, decision: ProgressionDecision, choice: UserProgressionChoice, final: List<SetPlan>) {
        val recipient = current.profile.id
        draft = draft.editWorkout(recipient) { row ->
            row.editExercise(entryId) { item ->
                item.copy(plan = item.plan.copy(sets = final), athletes = listOf(Athlete(recipient, final.map { SetEntry(plan = it) })))
            }
        }.let { updated -> updated.copy(
            progression = updated.progression.filterNot { it.participantId == recipient && it.exerciseEntryId == entryId } +
                ProgressionReceipt(recipient, entryId, decision, choice, final),
        ) }
    }
    val references by produceState(emptyMap<String, Performance>(), current.profile.id, library.sessions) {
        value = withContext(Dispatchers.Default) {
            performance(library.sessions, current.profile.id).associateBy { it.exercise.id }
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.view_plan)) },
                navigationIcon = { IconButton(onClick = back) { Symbol(R.drawable.ic_back, description = stringResource(R.string.back)) } })
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 18.dp, vertical = 8.dp)) {
                    Button(onClick = { model.confirmPlan(original, draft, true, start) },
                        modifier = Modifier.fillMaxWidth().testTag("confirm_start_plan")) { Text(stringResource(R.string.start_workout)) }
                    if (draft != original) TextButton(onClick = { model.confirmPlan(original, draft, false) { back() } },
                        modifier = Modifier.fillMaxWidth().testTag("save_plan")) { Text(stringResource(R.string.save_preview)) }
                }
            }
        },
    ) { padding ->
        ScreenColumn(Modifier.padding(padding).consumeWindowInsets(padding).testTag("plan_preview")) {
            Text(draft.title, style = MaterialTheme.typography.headlineLarge)
            Muted(shortDate(draft.date))
            if (draft.workouts.size > 1) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    draft.workouts.forEach { row ->
                        FilterChip(current.id == row.id, { personId = row.profile.id }, label = { Text(row.profile.name) })
                    }
                }
            } else Muted(current.profile.name)
            TemplatePicker(library.programs, library.programs.flatMap { it.days }.find { it.id == current.templateId }) { picked ->
                draft = draft.copy(title = if (draft.workouts.size == 1) picked?.title ?: title else draft.title)
                    .editWorkout(current.profile.id) { it.withTemplate(picked) }
                    .let { next -> next.copy(progression = next.progression.filterNot { it.participantId == current.profile.id }) }
            }
            if (current.templateNotes.isNotBlank()) Muted(current.templateNotes)
            val volumes = current.exercises.flatMap { item -> item.athletes.single().sets.map { it.plan.volumeKg(item.exercise) } }
            val known = volumes.filterNotNull()
            if (known.isNotEmpty()) Muted(stringResource(if (known.size == volumes.size) R.string.plan_workload else R.string.plan_known_workload,
                number(known.sum())))
            if (current.exercises.isEmpty()) Muted(stringResource(R.string.plan_empty))
            current.exercises.forEachIndexed { index, item ->
                key(item.id) {
                    var expanded by rememberSaveable { mutableStateOf(false) }
                    val previous = references[item.exercise.id]?.takeIf { it.exercise.load == item.exercise.load }
                    CardBlock {
                        TextButton(onClick = { expanded = !expanded },
                            modifier = Modifier.fillMaxWidth().testTag("plan_exercise_$index")) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("${index + 1}. ${item.exercise.name}", modifier = Modifier.weight(1f),
                                    color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium)
                                Symbol(R.drawable.ic_forward, modifier = Modifier.rotate(if (expanded) 90f else 0f))
                            }
                        }
                        val sets = item.athletes.single().sets
                        if (!expanded) Muted(stringResource(R.string.set_count) + " · " + sets.size)
                        else {
                            sets.forEachIndexed { i, entry -> Text(stringResource(R.string.preview_set, i + 1, plannedSetText(entry.plan, item.exercise))) }
                            previous?.actuals?.lastOrNull()?.let {
                                Muted(stringResource(R.string.last_result, actualText(it, previous.exercise)))
                            }
                            val receipt = draft.progression.find { it.participantId == current.profile.id && it.exerciseEntryId == item.id }
                            val active = engine
                            val decision = receipt?.decision ?: remember(active, current.profile.id, item.plan, program, continuationTemplate) {
                                active?.decide(current.profile.id, program, item.plan, continuationTemplate)
                            }
                            if (decision != null) {
                                HorizontalDivider()
                                DecisionText(decision)
                                if (receipt != null) Muted(stringResource(R.string.selected_progression,
                                    androidx.compose.ui.res.stringArrayResource(R.array.progression_choices)[receipt.choice.ordinal]))
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (decision.proposed != decision.baseline) Button(
                                        onClick = { applyDecision(item.id, decision, UserProgressionChoice.ACCEPTED, decision.proposed) },
                                        modifier = Modifier.testTag("accept_progression_$index"),
                                    ) { Text(stringResource(R.string.accept_progression)) }
                                    OutlinedButton(onClick = { applyDecision(item.id, decision, UserProgressionChoice.KEPT, decision.baseline) },
                                        modifier = Modifier.testTag("keep_progression_$index")) { Text(stringResource(R.string.keep_progression)) }
                                }
                            }
                            TextButton(onClick = { editing = item.id }, modifier = Modifier.testTag("manual_progression_$index")) {
                                Text(stringResource(if (decision == null) R.string.change else R.string.change_progression))
                            }
                        }
                    }
                }
            }
            OutlinedButton(onClick = { editing = "new" }) { Text(stringResource(R.string.add_exercise)) }
        }
    }
    editing?.let { id ->
        ExerciseEditor(stringResource(if (id == "new") R.string.add_exercise else R.string.change), library, prefs,
            current.exercises.find { it.id == id }?.plan, { editing = null }) { plan, _ ->
            val old = current.exercises.find { it.id == id }
            val originalDecision = draft.progression.find { it.participantId == current.profile.id && it.exerciseEntryId == id }?.decision
                ?: old?.let { engine?.decide(current.profile.id, program, it.plan, continuationTemplate) }
            if (old != null && old.exercise.id == plan.exercise.id && originalDecision != null) {
                applyDecision(id, originalDecision, UserProgressionChoice.MANUAL, plan.sets)
            } else {
                draft = draft.editWorkout(current.profile.id) { row ->
                    if (id == "new") row.addExercise(plan) else row.replaceExercise(id, plan)
                }.let { next -> next.copy(progression = next.progression.filterNot {
                    it.participantId == current.profile.id && it.exerciseEntryId == id
                }) }
            }
            editing = null
        }
    }
}

@Composable
fun TemplatePicker(programs: List<Program>, selected: WorkoutTemplate?, choose: (WorkoutTemplate?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val days = remember(programs) { programs.flatMap { program -> program.days.map { program.title to it } } }
    if (days.isEmpty()) return
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().testTag("pick_template")) {
            Text(selected?.title ?: stringResource(R.string.select_template))
        }
        DropdownMenu(expanded, { expanded = false }, modifier = Modifier.heightIn(max = 350.dp)) {
            DropdownMenuItem(text = { Text(stringResource(R.string.without_program)) }, onClick = { choose(null); expanded = false })
            days.forEach { (program, day) ->
                DropdownMenuItem(text = { Column { Text(day.title); Muted(program) } }, onClick = { choose(day); expanded = false },
                    modifier = Modifier.testTag("template_${day.title}"))
            }
        }
    }
}

@Composable
fun workoutStatus(value: WorkoutStatus): String = stringResource(when (value) {
    WorkoutStatus.PLANNED -> R.string.workout_planned
    WorkoutStatus.ACTIVE -> R.string.workout_active
    WorkoutStatus.PAUSED -> R.string.workout_paused
    WorkoutStatus.COMPLETED -> R.string.workout_completed
    WorkoutStatus.SKIPPED -> R.string.workout_skipped
    WorkoutStatus.CANCELLED -> R.string.workout_cancelled
})

@Composable
fun plannedSetText(plan: SetPlan, exercise: Exercise): String {
    val weight = if (plan.weightKnown) number(plan.weight) else stringResource(R.string.unknown_value)
    val reps = when {
        plan.maxEffort -> stringResource(R.string.max_effort)
        !plan.repsKnown -> stringResource(R.string.unknown_value)
        else -> repRange(plan.repsMin, plan.repsMax) + (plan.targetReps?.let {
            " (" + stringResource(R.string.target_reps_value, it) + ")"
        } ?: "")
    }
    return "$weight ${unitText(exercise.unit)} × $reps"
}

@Composable
fun actualText(actual: Actual, exercise: Exercise): String {
    val weight = if (actual.weightKnown) number(actual.weight) else stringResource(R.string.unknown_value)
    val reps = if (actual.repsKnown) actual.reps.toString() else stringResource(R.string.unknown_value)
    return "$weight ${unitText(exercise.unit)} × $reps"
}
