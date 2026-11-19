@file:OptIn(ExperimentalLayoutApi::class)

package app.gymflow.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.gymflow.R
import app.gymflow.data.Library
import app.gymflow.data.Preferences
import app.gymflow.domain.*

@Composable
fun ExerciseEditor(
    title: String,
    library: Library,
    prefs: Preferences,
    initial: ExercisePlan? = null,
    close: () -> Unit,
    options: @Composable () -> Unit = {},
    programOptions: Boolean = false,
    trainingBlocks: List<TrainingBlock> = emptyList(),
    save: (ExercisePlan, Exercise?) -> Unit,
) {
    val recent = remember(library.sessions) {
        library.sessions.asSequence().flatMap { session -> session.workouts.asSequence() }
            .flatMap { it.exercises.asSequence() }.map { it.exercise.id }.distinct().take(8).toList()
    }
    val preferred = remember(library.programs) {
        library.programs.flatMap { it.days }.flatMap { it.exercises }.map { it.exercise.id }.toSet()
    }
    val firstPlan = initial?.sets?.firstOrNull()
    var name by rememberSaveable { mutableStateOf(initial?.exercise?.name.orEmpty()) }
    var picked by rememberSaveable { mutableStateOf(initial?.exercise?.id) }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var showOptions by rememberSaveable { mutableStateOf(initial != null) }
    var load by rememberSaveable { mutableStateOf(initial?.exercise?.load ?: Load.OTHER) }
    var unit by rememberSaveable { mutableStateOf(initial?.exercise?.unit ?: prefs.unit) }
    var count by rememberSaveable { mutableStateOf((initial?.sets?.size ?: prefs.initialSets).toString()) }
    var first by rememberSaveable { mutableStateOf(if (firstPlan?.repsKnown == false) "" else (firstPlan?.repsMin ?: prefs.minReps).toString()) }
    var last by rememberSaveable { mutableStateOf(if (firstPlan?.repsKnown == false) "" else (firstPlan?.repsMax ?: prefs.maxReps).toString()) }
    var weight by rememberSaveable { mutableStateOf(if (firstPlan?.weightKnown == false) "" else number(firstPlan?.weight ?: prefs.initialWeight)) }
    var warmup by rememberSaveable { mutableStateOf(firstPlan?.warmup ?: false) }
    var block by rememberSaveable { mutableStateOf(initial?.block ?: PlanBlock.REQUIRED) }
    var selection by rememberSaveable { mutableStateOf(initial?.selection ?: MovementSelection.FIXED) }
    var skillId by rememberSaveable { mutableStateOf(initial?.skillId) }
    var blockId by rememberSaveable { mutableStateOf(initial?.blockId) }
    var cardio by rememberSaveable { mutableStateOf<CardioType?>(initial?.exercise?.cardio) }
    var rest by rememberSaveable { mutableStateOf(initial?.restSeconds?.toString().orEmpty()) }
    var restMax by rememberSaveable { mutableStateOf(initial?.restMaxSeconds?.toString().orEmpty()) }
    var autoRest by rememberSaveable { mutableStateOf(initial?.autoRest ?: false) }
    var caution by rememberSaveable { mutableStateOf(initial?.caution.orEmpty()) }
    var warmupTemplate by rememberSaveable { mutableStateOf(false) }
    var warmCount by rememberSaveable { mutableStateOf("") }
    var rampCount by rememberSaveable { mutableStateOf("") }
    var warmReps by rememberSaveable { mutableStateOf("") }
    val selected = library.exercises.find { it.id == picked }
        ?: initial?.exercise?.takeIf { it.id == picked }
        ?: library.exercises.find { !it.archived && it.name.trim().equals(name.trim(), ignoreCase = true) }
    val suggestions = library.exercises.filter { exercise ->
        !exercise.archived && exercise.id != picked && when {
            name.isNotBlank() && picked == null -> exercise.name.contains(name.trim(), true)
            filter == 1 -> exercise.favorite
            filter == 2 -> exercise.id in preferred
            else -> exercise.id in recent
        }
    }.take(6)
    val sets = count.toIntOrNull()?.takeIf { it in 1..30 }
    val min = positiveInt(first)
    val max = positiveInt(last)
    val amount = decimal(weight)
    val numericRange = min != null && max != null && min <= max
    val unspecifiedRange = first.isBlank() && last.isBlank()
    val restValue = positiveInt(rest)?.takeIf { it <= 86400 }
    val restMaxValue = positiveInt(restMax)?.takeIf { restValue != null && it in restValue..86400 }
    val warm = positiveInt(warmCount)?.takeIf { it <= 30 }
    val ramp = positiveInt(rampCount)?.takeIf { it <= 30 }
    val warmRepCount = positiveInt(warmReps)
    val ready = name.isNotBlank() && sets != null && (numericRange || unspecifiedRange) && (amount != null || weight.isBlank()) &&
        (rest.isBlank() || restValue != null) && (restMax.isBlank() || restMaxValue != null) && (!warmupTemplate || (warmCount.isBlank() || warm != null) &&
            (rampCount.isBlank() || ramp != null) && (warmReps.isBlank() || warmRepCount != null))
    EditorSheet(title, close) {
        Field(name, stringResource(R.string.exercise_name), { name = it; picked = null }, Modifier.fillMaxWidth().testTag("exercise_name"))
        if (initial == null && name.isBlank() && library.exercises.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(stringResource(R.string.recent), stringResource(R.string.favorites), stringResource(R.string.from_program)).forEachIndexed { index, text ->
                    FilterChip(filter == index, { filter = index }, label = { Text(text) })
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            suggestions.forEach { choice ->
                SuggestionChip(onClick = {
                    picked = choice.id
                    name = choice.name
                    load = choice.load
                    unit = choice.unit
                    val timed = choice.tracksDuration
                    first = if (timed) "" else choice.repsMin.toString()
                    last = if (timed) "" else choice.repsMax.toString()
                    if (timed) weight = ""
                }, label = { Text(choice.name) })
            }
        }
        if (showOptions) {
            if (selected == null) {
                Choice(cardio, listOf(null) + CardioType.entries, { it?.label ?: "Силовое или другое упражнение" }, {
                    cardio = it
                    if (it != null) { load = Load.OTHER; first = ""; last = ""; weight = "" }
                }, "exercise_cardio")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Load.entries.forEach { option ->
                        FilterChip(load == option, { load = option }, label = { Text(loadText(option)) })
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    UnitWeight.entries.forEach { option ->
                        FilterChip(unit == option, { unit = option }, label = { Text(unitText(option)) })
                    }
                }
            } else Muted("${loadText(selected.load)} · ${unitText(selected.unit)}")
            Field(count, stringResource(R.string.set_count), { count = it }, keyboard = KeyboardType.Number)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(first, stringResource(R.string.min_reps), { first = it }, Modifier.weight(1f), KeyboardType.Number)
                Field(last, stringResource(R.string.max_reps), { last = it }, Modifier.weight(1f), KeyboardType.Number)
            }
            Field(weight, stringResource(R.string.weight_unit, unitText(unit)), { weight = it }, keyboard = KeyboardType.Decimal)
            FilterChip(warmup, { warmup = !warmup }, label = { Text(stringResource(R.string.warmup_first)) })
        } else if (sets != null && min != null && max != null && amount != null) {
            Muted(stringResource(R.string.plan_details, sets, repRange(min, max), number(amount), unitText(unit)))
        }
        OutlinedButton(onClick = { showOptions = !showOptions }, modifier = Modifier.testTag("exercise_options")) {
            Text(stringResource(if (showOptions) R.string.hide_options else R.string.exercise_options))
        }
        if (programOptions && showOptions) {
            if (trainingBlocks.isNotEmpty()) Choice(trainingBlocks.find { it.id == blockId }, listOf(null) + trainingBlocks,
                { it?.name ?: "Без блока" }, { blockId = it?.id }, "training_block")
            if (blockId == null) Choice(block, PlanBlock.entries, { it.label }, { block = it }, "plan_block")
            Field(rest, "Отдых, с", { rest = it }, Modifier.testTag("planned_rest"), KeyboardType.Number)
            if (restValue != null || restMax.isNotBlank()) {
                Field(restMax, "Верхняя граница отдыха, с", { restMax = it }, keyboard = KeyboardType.Number)
            }
            if (restValue != null) FilterChip(autoRest, { autoRest = !autoRest }, label = { Text("Запускать таймер после подхода") })
            Field(caution, "Ограничения упражнения", { caution = it }, singleLine = false)
            TextButton(onClick = { warmupTemplate = !warmupTemplate }) { Text("Шаблон разминочных подходов") }
            if (warmupTemplate) {
                Field(warmCount, "Разминочных подходов", { warmCount = it }, keyboard = KeyboardType.Number)
                Field(rampCount, "Подводящих подходов", { rampCount = it }, keyboard = KeyboardType.Number)
                Field(warmReps, "Повторов в этих подходах (необязательно)", { warmReps = it }, keyboard = KeyboardType.Number)
                Muted("Вес этих подходов указывается вручную.")
            }
            if (selected?.movement?.familyId != null) {
                Choice(selection, MovementSelection.entries, { it.label }, { selection = it }, "movement_selection")
            }
            if (library.skills.isNotEmpty()) {
                val skills = listOf(null) + library.skills
                Choice(skills.find { it?.id == skillId }, skills, { it?.name ?: "Без блока навыка" },
                    { skillId = it?.id }, "plan_skill")
            }
        }
        options()
        Button(enabled = ready, onClick = {
            if (sets != null && ready) {
                val low = min ?: firstPlan?.repsMin ?: prefs.minReps
                val high = max ?: firstPlan?.repsMax ?: prefs.maxReps
                val setPlan = SetPlan(amount ?: 0.0, low, high, warmup, weightKnown = amount != null,
                    maxEffort = unspecifiedRange && firstPlan?.maxEffort == true, repsKnown = numericRange,
                    targetReps = firstPlan?.targetReps?.takeIf { numericRange && it in low..high },
                    role = firstPlan?.role?.takeUnless { it == SetRole.WARMUP && !warmup })
                val exercise = selected ?: Exercise(name = name.trim(), unit = unit, load = load, repsMin = low, repsMax = high, cardio = cardio)
                val same = initial != null && sets == initial.sets.size && firstPlan == setPlan
                val plans = if (same) initial.sets else List(sets) { index ->
                    setPlan.copy(warmup = warmup && index == 0, role = setPlan.role?.takeUnless { it == SetRole.WARMUP && index > 0 })
                }
                val planned = if (warmupTemplate && (warm != null || ramp != null)) {
                    val reps = warmRepCount ?: 1
                    val prepare = SetPlan(repsMin = reps, repsMax = reps, weightKnown = false, repsKnown = warmRepCount != null)
                    List(warm ?: 0) { prepare.copy(role = SetRole.WARMUP, warmup = true) } +
                        List(ramp ?: 0) { prepare.copy(role = SetRole.RAMP) } +
                        plans.filter { it.purpose !in setOf(SetRole.WARMUP, SetRole.RAMP) }
                } else plans
                save(ExercisePlan(exercise, planned, initial?.individual.orEmpty(), block,
                    selection.takeIf { exercise.movement?.familyId != null } ?: MovementSelection.FIXED, skillId,
                    blockId, restValue, autoRest && restValue != null, caution.trim(),
                    restMaxValue), exercise.takeIf { selected == null })
            }
        }, modifier = Modifier.fillMaxWidth().testTag("save_exercise")) {
            Text(if (initial == null) stringResource(R.string.add) else stringResource(R.string.save))
        }
        TextButton(onClick = close, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.cancel)) }
    }
}

@Composable
fun PersonEditor(
    library: Library, session: Session?, close: () -> Unit,
    options: @Composable () -> Unit = {},
    save: (Profile, Boolean) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var permanent by rememberSaveable { mutableStateOf(session == null) }
    val candidates = library.availableProfiles.filter { p -> session != null && session.participants.none { it.id == p.id } }
    EditorSheet(stringResource(R.string.add_person), close) {
        options()
        if (candidates.isNotEmpty()) {
            SectionTitle(stringResource(R.string.choose_profile))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                candidates.forEach { profile -> SuggestionChip({ save(profile, false) }, label = { Text(profile.name) }) }
            }
        }
        Field(name, stringResource(R.string.name), { name = it }, Modifier.fillMaxWidth().testTag("person_name"))
        if (session != null) {
            FilterChip(permanent, { permanent = !permanent }, label = { Text(stringResource(R.string.permanent_profile)) })
        }
        Button(onClick = { save(Profile(name = name.trim()), permanent) }, enabled = name.isNotBlank(),
            modifier = Modifier.fillMaxWidth().testTag("save_person")) { Text(stringResource(R.string.add)) }
        TextButton(onClick = close, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.cancel)) }
    }
}
