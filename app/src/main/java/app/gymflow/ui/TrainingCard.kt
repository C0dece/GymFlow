@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package app.gymflow.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.gymflow.R
import app.gymflow.data.Library
import app.gymflow.data.Preferences
import app.gymflow.domain.*

@Composable
fun ExerciseCard(
    entry: ExerciseEntry, session: Session, profile: Profile, athlete: Athlete?, library: Library,
    editable: Boolean, focusSet: String?, actions: @Composable () -> Unit,
    toggle: (SetEntry) -> Unit, edit: (SetEntry) -> Unit, rir: (Int) -> Unit, addSet: () -> Unit,
    correcting: Boolean = false,
    header: @Composable ColumnScope.() -> Unit = {},
) {
    val exercise = entry.exercise
    val previous = remember(library.sessions, exercise.id, profile.id) {
        library.sessions.asSequence().filter { it.id != session.id }.firstNotNullOfOrNull { old ->
            old.completedWorkouts(profile.id).flatMap { it.exercises }.firstNotNullOfOrNull { item ->
                if (!item.exercise.comparableWith(exercise) || item.exercise.unit != exercise.unit) null
                else item.athletes.find { it.participantId == profile.id && !it.skipped }?.sets
                    ?.lastOrNull { it.status == SetStatus.COMPLETED && it.plan.purpose == SetRole.WORK && it.actual?.technique != Technique.CHANGED }?.actual
            }
        }
    }
    ScreenColumn(Modifier.testTag("exercise_card_${entry.id}")) {
        header()
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(exercise.name, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.testTag("exercise_title"))
                Muted(exercise.cardio?.label ?: if (exercise.tracksDuration) "На время" else "${loadText(exercise.load)} · ${unitText(exercise.unit)}")
            }
            if (editable) actions()
        }
        if (entry.plan.caution.isNotBlank()) InlineStatus(entry.plan.caution, warning = true)
        if (entry.plan.instructions.isNotBlank()) Muted(entry.plan.instructions)
        library.movementTracks.find { it.profileId == profile.id && it.exerciseId == exercise.id }
            ?.limitations?.takeIf { it.isNotBlank() }?.let { InlineStatus(it, warning = true) }
        if (previous != null && (previous.weightKnown && previous.repsKnown || previous.measurements.isNotEmpty())) {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(10.dp)) {
                Text(if (previous.measurements.isEmpty()) stringResource(R.string.previous_result, number(previous.weight), unitText(exercise.unit), previous.reps)
                    else "Ранее · " + actualText(previous, exercise),
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (athlete?.skipped == true) InlineStatus(stringResource(R.string.exercise_skipped), true)
        if (athlete != null) {
            val isActive = editable && !athlete.skipped
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                SectionTitle(stringResource(R.string.sets), Modifier.weight(1f))
                if (isActive || correcting) TextButton(onClick = addSet, modifier = Modifier.testTag("add_set")) { Text(stringResource(R.string.add_set)) }
            }
            var work = 0
            athlete.sets.forEachIndexed { index, set ->
                if (set.plan.purpose == SetRole.WORK) work++
                val label = if (set.plan.purpose == SetRole.WORK) stringResource(R.string.set_number, work) else set.plan.purpose.label
                SetRow(set, label, exercise, isActive, set.id == focusSet && set.status == SetStatus.PLANNED,
                    index, profile.compact, { edit(set) }, { toggle(set) }, correcting)
            }
            if (!athlete.skipped && (athlete.rir != null || !exercise.tracksDuration && athlete.sets.none { it.plan.durationSeconds != null } &&
                    (editable || correcting))) {
                CardBlock {
                    Text(stringResource(R.string.rir), style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        (0..4).forEach { value ->
                            FilterChip(athlete.rir == value, { rir(value) }, modifier = Modifier.testTag("rir_$value"),
                                enabled = editable || correcting, label = { Text(if (value == 4) "4+" else "$value") })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SetRow(set: SetEntry, label: String, exercise: Exercise, enabled: Boolean, highlighted: Boolean, index: Int, compact: Boolean, edit: () -> Unit, toggle: () -> Unit, correcting: Boolean) {
    val actual = set.actual
    val plan = plannedSetText(set.plan, exercise)
    val result = if (actual == null) plan else actualText(actual, exercise)
    val completed = set.status == SetStatus.COMPLETED
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("set_$index"), shape = RoundedCornerShape(13.dp),
        color = if (completed) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        border = if (highlighted) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Row(Modifier.padding(start = 4.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            val details = if (correcting) Modifier else Modifier.clickable(enabled = enabled, onClick = edit).testTag("edit_set_$index")
            Column(Modifier.weight(1f).then(details)
                .padding(horizontal = 10.dp, vertical = if (compact) 5.dp else 11.dp)) {
                Muted(label)
                Text(result, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                when (set.status) {
                    SetStatus.COMPLETED -> Muted(stringResource(R.string.set_completed))
                    SetStatus.SKIPPED -> Muted(stringResource(R.string.set_skipped))
                    SetStatus.CANCELLED -> Muted(stringResource(R.string.set_cancelled))
                    else -> if (result != plan) Muted(stringResource(R.string.set_original_plan, plan))
                }
            }
            if (correcting) TextButton(onClick = edit, modifier = Modifier.testTag("edit_set_$index")) {
                Text(stringResource(R.string.change))
            } else Checkbox(checked = completed, onCheckedChange = { toggle() },
                enabled = enabled && set.status in listOf(SetStatus.PLANNED, SetStatus.COMPLETED),
                colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.secondary, checkmarkColor = MaterialTheme.colorScheme.onSecondary),
                modifier = Modifier.size(50.dp).testTag("complete_set_$index"))
        }
    }
}

@Composable
fun SetEditor(set: SetEntry, exercise: Exercise, prefs: Preferences, close: () -> Unit,
    newResult: Boolean = false, history: Boolean = false,
    orderActions: @Composable () -> Unit = {}, save: (SetEntry) -> Unit) {
    val timed = exercise.tracksDuration || set.plan.durationSeconds != null
    val initializingTimed = set.actual == null && timed
    var weight by rememberSaveable { mutableStateOf(if (!initializingTimed && set.proposed.weightKnown) number(set.proposed.weight) else "") }
    var reps by rememberSaveable { mutableStateOf(if (!initializingTimed && set.proposed.repsKnown) set.proposed.reps.toString() else "") }
    var note by rememberSaveable { mutableStateOf(set.proposed.note) }
    val amount = decimal(weight)
    val repCount = positiveInt(reps)
    var planning by rememberSaveable { mutableStateOf(false) }
    var confirmRemoval by rememberSaveable { mutableStateOf(false) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var planDrafts by rememberPlanSets(listOf(set.plan))
    val planDraft = planDrafts.single()
    val canPlan = !newResult && !history && set.status == SetStatus.PLANNED
    var technique by rememberSaveable { mutableStateOf(set.actual?.technique) }
    val kinds = (exercise.movement?.measures.orEmpty() + set.proposed.measurements.keys +
        when {
            exercise.cardio != null -> cardioMeasures
            timed && exercise.movement?.measures.orEmpty().none { it in setOf(Measure.HOLD, Measure.BALANCE) } -> setOf(Measure.DURATION)
            else -> emptySet()
        }).sortedBy { it.ordinal }
    val observed = remember(set.id) { mutableStateMapOf<Measure, String>().apply {
        set.proposed.measurements.forEach { (kind, value) -> put(kind, number(value)) }
    } }
    val measurements = observed.filterValues { it.isNotBlank() }.mapValues { it.value.trim().replace(',', '.').toDoubleOrNull() }
    val measured = measurements.mapNotNull { (kind, value) -> value?.takeIf(kind::accepts)?.let { kind to it } }.toMap()
    val valid = measurements.size == measured.size && (weight.isBlank() || amount != null) &&
        (reps.isBlank() || repCount != null) && (measured.isNotEmpty() || !timed && amount != null && repCount != null)
    EditorSheet(stringResource(R.string.edit_set), close) {
        Muted(exercise.cardio?.label ?: if (timed) "На время" else loadText(exercise.load))
        if (canPlan) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(!planning, { planning = false }, label = { Text("Результат") })
            FilterChip(planning, { planning = true }, modifier = Modifier.testTag("set_plan_tab"), label = { Text("План") })
        }
        if (planning && canPlan) {
            PlanSetFields(planDraft, exercise, "set_plan") { planDrafts = listOf(it) }
            val plan = planDraft.result(timed)
            Button(onClick = { plan?.let { save(set.copy(plan = it, deferred = false)) } },
                enabled = plan != null, modifier = Modifier.fillMaxWidth().testTag("save_active_set_plan")) { Text("Сохранить план подхода") }
            TextButton(onClick = close) { Text(stringResource(R.string.cancel)) }
            return@EditorSheet
        }
        if (!canPlan) Muted("План · " + plannedSetText(set.plan, exercise))
        if (!timed) StepField(weight, stringResource(R.string.weight_unit, unitText(exercise.unit)), { weight = it }, "set_weight", KeyboardType.Decimal) { step ->
            val delta = prefs.step * if (prefs.unit == exercise.unit) 1.0 else prefs.unit.toKg / exercise.unit.toKg
            val value = ((amount ?: 0.0) + delta * step).coerceAtLeast(0.0)
            if (value.isFinite()) weight = number(value.toBigDecimal().setScale(3, java.math.RoundingMode.HALF_UP).toDouble())
        }
        if (!timed) StepField(reps, stringResource(R.string.reps), { reps = it }, "set_reps", KeyboardType.Number) { step ->
            reps = ((repCount ?: 1).toLong() + step).coerceIn(1, Int.MAX_VALUE.toLong()).toString()
        }
        kinds.forEach { kind ->
            Field(observed[kind].orEmpty(), kind.label, { observed[kind] = it },
                Modifier.testTag("measure_${kind.name}"), keyboard = KeyboardType.Decimal)
        }
        Field(note, stringResource(R.string.note), { note = it }, Modifier.testTag("set_result_note"), singleLine = false)
        TextButton(onClick = { advanced = !advanced }) { Text("Параметры результата") }
        if (advanced) {
            Choice(technique, listOf(null) + Technique.entries, { it?.label ?: "Без отметки техники" },
                { technique = it }, "set_technique")
            if (newResult) Choice(planDraft.role, SetRole.entries, { it.label },
                { planDrafts = listOf(planDraft.copy(role = it)) }, "set_role")
        }
        Button(onClick = {
            if (valid) save(set.copy(plan = if (newResult) planDraft.result(timed) ?: set.plan else set.plan, actual = Actual(amount ?: 0.0, repCount ?: 1, note.trim(),
                weightKnown = amount != null, repsKnown = repCount != null, measurements = measured, technique = technique),
                status = SetStatus.COMPLETED, deferred = false))
        }, enabled = valid, modifier = Modifier.fillMaxWidth().testTag("save_set")) {
            Text(stringResource(R.string.record_result))
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!newResult) {
                if (!history) TextButton(onClick = { save(set.copy(status = SetStatus.SKIPPED, deferred = false)) }) {
                    Text(stringResource(R.string.skip))
                }
                TextButton(onClick = { if (set.status == SetStatus.COMPLETED && set.actual != null) confirmRemoval = true
                    else save(set.copy(status = SetStatus.CANCELLED, deferred = false)) },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Text(stringResource(R.string.remove_set))
                }
                if (set.status == SetStatus.SKIPPED || set.status == SetStatus.CANCELLED) {
                    if (history && set.actual != null) TextButton(onClick = {
                        save(set.copy(status = SetStatus.COMPLETED, deferred = false))
                    }) { Text("Вернуть результат") }
                    if (!history) TextButton(onClick = { save(set.copy(status = SetStatus.PLANNED, deferred = false)) }) {
                        Text(stringResource(R.string.return_to_plan))
                    }
                }
            }
            TextButton(onClick = close) { Text(stringResource(R.string.cancel)) }
        }
        orderActions()
    }
    if (confirmRemoval) AlertDialog(onDismissRequest = { confirmRemoval = false }, title = { Text("Убрать выполненный подход?") },
        text = { Text("Результат исключится из итогов. Его можно будет вернуть.") },
        confirmButton = { Button(onClick = {
            confirmRemoval = false
            save(set.copy(status = SetStatus.CANCELLED, deferred = false))
        }) { Text("Убрать") } },
        dismissButton = { TextButton(onClick = { confirmRemoval = false }) { Text("Отмена") } })
}

@Composable
private fun StepField(value: String, label: String, change: (String) -> Unit, tag: String, keyboard: KeyboardType, step: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        FilledTonalButton(onClick = { step(-1) }, modifier = Modifier.widthIn(min = 52.dp)) { Text("−") }
        Field(value, label, change, Modifier.weight(1f).testTag(tag), keyboard)
        FilledTonalButton(onClick = { step(1) }, modifier = Modifier.widthIn(min = 52.dp)) { Text("+") }
    }
}

@Composable
fun MetricsEditor(session: Session, profile: Profile, close: () -> Unit,
    initial: Metrics = session.workout(profile.id)?.metrics ?: Metrics(), save: (Metrics, String) -> Unit) {
    var body by rememberSaveable { mutableStateOf(initial.bodyWeightKg?.let(::number).orEmpty()) }
    var cardio by rememberSaveable { mutableStateOf(initial.cardioMinutes?.toString().orEmpty()) }
    var feeling by rememberSaveable { mutableStateOf(initial.feeling) }
    var note by rememberSaveable { mutableStateOf(initial.note) }
    var sessionNote by rememberSaveable { mutableStateOf(session.notes) }
    var fatigue by rememberSaveable { mutableStateOf(initial.highFatigue) }
    var discomfort by rememberSaveable { mutableStateOf(initial.discomfort) }
    EditorSheet(stringResource(R.string.workout_details), close) {
        Text(profile.name, style = MaterialTheme.typography.titleMedium)
        Field(body, stringResource(R.string.body_mass), { body = it }, keyboard = KeyboardType.Decimal)
        Field(cardio, stringResource(R.string.cardio_minutes), { cardio = it }, keyboard = KeyboardType.Number)
        Text(stringResource(R.string.feeling))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (1..5).forEach { value ->
                FilterChip(feeling == value, { feeling = if (feeling == value) null else value }, label = { Text("$value") })
            }
        }
        FilterChip(fatigue, { fatigue = !fatigue }, label = { Text(stringResource(R.string.high_fatigue)) })
        FilterChip(discomfort, { discomfort = !discomfort }, label = { Text(stringResource(R.string.body_discomfort)) })
        Field(note, stringResource(R.string.note), { note = it }, singleLine = false)
        Field(sessionNote, stringResource(R.string.session_note), { sessionNote = it }, singleLine = false)
        Button(onClick = { save(Metrics(decimal(body), feeling, cardio.toIntOrNull(), note, fatigue, discomfort), sessionNote) },
            enabled = (body.isBlank() || decimal(body) != null) && (cardio.isBlank() || positiveInt(cardio) != null),
            modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.save)) }
    }
}
