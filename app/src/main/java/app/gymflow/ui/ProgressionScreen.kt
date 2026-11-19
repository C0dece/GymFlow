@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package app.gymflow.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.gymflow.GymModel
import app.gymflow.R
import app.gymflow.data.Library
import app.gymflow.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ProgressionScreen(library: Library, model: GymModel, back: () -> Unit, prepare: (String?) -> Unit, movements: () -> Unit) {
    var editing by rememberSaveable { mutableStateOf(false) }
    var profileId by rememberSaveable { mutableStateOf<String?>(null) }
    var exerciseId by rememberSaveable { mutableStateOf<String?>(null) }
    var reviewLimit by rememberSaveable { mutableIntStateOf(4) }
    val people = remember(library.profiles, library.sessions) {
        (library.profiles + library.sessions.flatMap { it.participants }).distinctBy { it.id }
    }
    val person = people.find { it.id == profileId } ?: people.firstOrNull()
    val exercise = library.exercises.find { it.id == exerciseId } ?: library.exercises.firstOrNull()
    val engine by rememberEngine(library)
    fun leave() { if (editing) editing = false else back() }
    BackHandler(editing) { editing = false }
    Scaffold(topBar = {
        TopAppBar(title = { BarTitle(stringResource(if (editing) R.string.configure_progression else R.string.progression)) },
            navigationIcon = { IconButton(onClick = ::leave, modifier = Modifier.testTag("progression_back")) { Symbol(R.drawable.ic_back, description = stringResource(R.string.back)) } })
    }) { padding ->
        ScreenColumn(Modifier.padding(padding).consumeWindowInsets(padding).testTag("progression_page")) {
            if (editing) {
                PolicyEditor(library, person, exercise, { editing = false }) { model.save(it); editing = false }
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { editing = true }, modifier = Modifier.testTag("configure_progression")) {
                        Text(stringResource(R.string.configure_progression))
                    }
                    OutlinedButton(onClick = {
                        prepare(library.sessions.filter { it.status == SessionStatus.PLANNED }.minByOrNull { it.date }?.id)
                    }) { Text(stringResource(R.string.prepare_training)) }
                    OutlinedButton(onClick = movements, modifier = Modifier.testTag("open_movements")) {
                        Text("Движения и навыки")
                    }
                }
                if (person != null) Choice(person, people, { it.name }, { profileId = it.id }, "progression_profile")
                if (exercise != null) Choice(exercise, library.exercises, { it.name }, { exerciseId = it.id }, "progression_exercise")
                if (person != null && exercise != null) {
                    val source = latestPlan(library, person.id, exercise.id)
                    val active = engine
                    if (source != null && active != null) {
                        val decision = remember(active, person.id, source) { active.decide(person.id, source.first, source.second) }
                        CardBlock {
                            SectionTitle(stringResource(R.string.practice_progression))
                            Text(exercise.name)
                            DecisionText(decision, detailByDefault = true)
                        }
                    }
                }
                SectionTitle(stringResource(R.string.progression_history))
                val reviews = library.reviews.filter { person == null || it.profileId == person.id }
                if (reviews.isEmpty()) Muted(stringResource(R.string.progression_no_history))
                reviews.take(reviewLimit).forEach { review ->
                    CardBlock {
                        Text(shortDate(review.date), style = MaterialTheme.typography.titleMedium)
                        review.decisions.forEach { decision ->
                            Text(decision.exerciseName, style = MaterialTheme.typography.labelLarge)
                            DecisionText(decision)
                        }
                    }
                }
                if (reviews.size > reviewLimit) TextButton(onClick = { reviewLimit += 4 }) { Text(stringResource(R.string.show_details)) }
                val receipts = library.sessions.asSequence().flatMap { session ->
                    session.progression.asSequence().filter { it.participantId == person?.id }.map { session.date to it }
                }.take(reviewLimit).toList()
                if (receipts.isNotEmpty()) {
                    SectionTitle(stringResource(R.string.progression_applied))
                    receipts.forEach { (date, receipt) ->
                        CardBlock {
                            Text(receipt.decision.exerciseName, style = MaterialTheme.typography.titleMedium)
                            Muted(shortDate(date) + " · " + stringArrayResource(R.array.progression_choices)[receipt.choice.ordinal])
                            Muted(planSummary(receipt.finalPlan, receipt.decision.unit))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun rememberEngine(library: Library): State<ProgressionEngine?> {
    val sessions = remember(library.sessions) { library.sessions.filter {
        it.status == SessionStatus.COMPLETED || it.status == SessionStatus.SKIPPED ||
            it.allWorkouts.any { row -> row.status in setOf(WorkoutStatus.COMPLETED, WorkoutStatus.SKIPPED) }
    } }
    return produceState<ProgressionEngine?>(null, sessions, library.policies) {
        value = withContext(Dispatchers.Default) { ProgressionEngine(ProgressionIndex(sessions), library.policies) }
    }
}

private fun latestPlan(library: Library, profile: String, id: String): Pair<String?, ExercisePlan>? =
    library.sessions.firstNotNullOfOrNull { session ->
        session.completedWorkouts(profile).firstNotNullOfOrNull { member ->
            member.plans().find { it.exercise.id == id }?.let { plan ->
                workoutProgram(library.programs, session, member)?.id to plan
            }
        }
    }

@Composable
fun DecisionText(decision: ProgressionDecision, detailByDefault: Boolean = false) {
    val reasons = stringArrayResource(R.array.progression_reasons)
    val actions = stringArrayResource(R.array.progression_actions)
    val strategies = stringArrayResource(R.array.progression_strategies)
    var details by remember(decision) { mutableStateOf(detailByDefault) }
    Text(actions[decision.action.ordinal], style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.testTag("decision_${decision.action.name}"))
    Muted(strategies[decision.strategy.ordinal] + " · " + reasons[decision.reason.ordinal])
    Muted(stringResource(R.string.current_plan) + " · " + planSummary(decision.baseline, decision.unit))
    if (decision.proposed != decision.baseline) {
        Text(stringResource(R.string.suggested_plan) + " · " + planSummary(decision.proposed, decision.unit))
    }
    if (decision.ruleIds.isNotEmpty()) Muted(stringResource(R.string.rule_matches, decision.ruleNames.ifEmpty { decision.ruleIds.map { it.take(8) } }.joinToString()))
    if (details) {
        if (decision.evidence.isEmpty()) Muted(stringResource(R.string.no_evidence))
        else {
            Muted(stringResource(R.string.used_evidence))
            decision.evidence.forEach { data ->
                Muted(stringResource(R.string.decision_stat, shortDate(data.date), data.confirmed, data.required,
                    data.minReps?.toString() ?: "?", data.maxWeightKg?.let(::number) ?: "?", data.rir?.toString() ?: "?"))
            }
        }
    } else TextButton(onClick = { details = true }) { Text(stringResource(R.string.show_details)) }
}

@Composable
private fun planSummary(sets: List<SetPlan>, unit: UnitWeight): String {
    val work = sets.filterNot { it.warmup }
    val weights = work.map { if (it.weightKnown) number(it.weight) else "?" }.distinct().joinToString(" / ")
    val reps = work.map {
        when {
            it.maxEffort -> stringResource(R.string.max_effort)
            !it.repsKnown -> "?"
            else -> repRange(it.repsMin, it.repsMax) + (it.targetReps?.let { target ->
                " (" + stringResource(R.string.target_reps_value, target) + ")"
            } ?: "")
        }
    }.distinct().joinToString(" / ")
    return stringResource(R.string.exercise_plan_summary, work.size, weights.ifBlank { "?" }, unitText(unit), reps.ifBlank { "?" })
}

@Composable
fun <T> Choice(value: T, options: List<T>, title: (T) -> String, select: (T) -> Unit, tag: String) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth().testTag(tag), enabled = options.isNotEmpty()) {
            Text(title(value))
        }
        DropdownMenu(open, { open = false }, modifier = Modifier.heightIn(max = 310.dp)) {
            options.forEachIndexed { index, row ->
                DropdownMenuItem(text = { Text(title(row)) }, onClick = { select(row); open = false },
                    modifier = Modifier.testTag("${tag}_${if (row is Enum<*>) row.name else index}"))
            }
        }
    }
}
