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

private enum class ExerciseMode(val label: String) {
    STRENGTH("Вес и повторы"), TIMED("На время"), CARDIO("Кардио")
}

private fun Exercise?.mode() = when {
    this?.cardio != null -> ExerciseMode.CARDIO
    this?.tracksDuration == true -> ExerciseMode.TIMED
    else -> ExerciseMode.STRENGTH
}

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
    val fallback = SetPlan(prefs.initialWeight, prefs.minReps, prefs.maxReps)
    var rows by rememberPlanSets(initial?.sets ?: List(prefs.initialSets) { fallback })
    var name by rememberSaveable { mutableStateOf(initial?.exercise?.name.orEmpty()) }
    var picked by rememberSaveable { mutableStateOf(initial?.exercise?.id) }
    var catalogOpen by rememberSaveable { mutableStateOf(false) }
    var catalogQuery by rememberSaveable { mutableStateOf("") }
    var showOptions by rememberSaveable { mutableStateOf(false) }
    var load by rememberSaveable { mutableStateOf(initial?.exercise?.load ?: Load.OTHER) }
    var unit by rememberSaveable { mutableStateOf(initial?.exercise?.unit ?: prefs.unit) }
    var instructions by rememberSaveable { mutableStateOf(initial?.instructions.orEmpty()) }
    var block by rememberSaveable { mutableStateOf(initial?.block ?: PlanBlock.REQUIRED) }
    var selection by rememberSaveable { mutableStateOf(initial?.selection ?: MovementSelection.FIXED) }
    var skillId by rememberSaveable { mutableStateOf(initial?.skillId) }
    var blockId by rememberSaveable { mutableStateOf(initial?.blockId) }
    var cardio by rememberSaveable { mutableStateOf<CardioType?>(initial?.exercise?.cardio) }
    var mode by rememberSaveable { mutableStateOf(initial?.exercise.mode()) }
    var overrideMode by rememberSaveable { mutableStateOf(false) }
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
    val candidate = (initial?.exercise ?: Exercise(id = "draft", name = name)).copy(name = name, unit = unit, load = load)
    val source = initial?.exercise?.takeIf { it.id == selected?.id } ?: selected
    val base = source ?: candidate
    val effective = if (source != null && !overrideMode) source else when (mode) {
        ExerciseMode.CARDIO -> base.copy(cardio = cardio ?: CardioType.OTHER)
        ExerciseMode.TIMED -> base.copy(cardio = null, movement = (base.movement ?: MovementDetails()).let {
            it.copy(measures = it.measures + Measure.DURATION)
        })
        ExerciseMode.STRENGTH -> base.copy(cardio = null, movement = base.movement?.let {
            it.copy(measures = it.measures - setOf(Measure.DURATION, Measure.HOLD, Measure.BALANCE, Measure.DISTANCE))
        })
    }
    val shownMode = if (overrideMode || source == null) mode else source.mode()
    val catalog = library.exercises.filterNot(Exercise::archived)
    val suggestions = if (selected == null && name.isNotBlank() && !catalogOpen) {
        catalog.filter { it.name.contains(name.trim(), true) }.take(4)
    } else emptyList()
    val plans = rows.plansOrNull(effective.tracksDuration)
    val restValue = positiveInt(rest)?.takeIf { it <= 86400 }
    val restMaxValue = positiveInt(restMax)?.takeIf { restValue != null && it in restValue..86400 }
    val warm = positiveInt(warmCount)?.takeIf { it <= 100 }
    val ramp = positiveInt(rampCount)?.takeIf { it <= 100 }
    val warmRepCount = positiveInt(warmReps)
    val preparationValid = (warmCount.isBlank() || warm != null) && (rampCount.isBlank() || ramp != null) &&
        (warmReps.isBlank() || warmRepCount != null) && rows.size + (warm ?: 0) + (ramp ?: 0) <= 100
    val ready = name.isNotBlank() && plans != null && (rest.isBlank() || restValue != null) &&
        (restMax.isBlank() || restMaxValue != null)
    fun useTimedDefaults() {
        if (initial == null && rows.all { it.result() == fallback }) {
            rows = listOf(PlanSetDraft(rows.first().key, fallback.copy(weightKnown = false, repsKnown = false)))
        }
    }
    fun select(choice: Exercise) {
        picked = choice.id
        name = choice.name
        load = choice.load
        unit = choice.unit
        overrideMode = false
        if (choice.tracksDuration) useTimedDefaults()
        catalogOpen = false
    }
    EditorSheet(title, close, footer = {
        PlanEditorActions(close, stringResource(if (initial == null) R.string.add else R.string.save), ready, "save_exercise") {
            if (plans != null && ready) {
                val savedPlans = if (effective.tracksDuration && initial == null) plans.map {
                    it.copy(weightKnown = false, repsKnown = false, targetReps = null, maxEffort = false)
                } else plans
                val first = savedPlans.first()
                val exercise = if (selected != null) effective else effective.copy(id = newId(), name = name.trim(),
                    repsMin = first.repsMin, repsMax = first.repsMax)
                save(ExercisePlan(exercise, savedPlans, initial?.individual.orEmpty(), block,
                    selection.takeIf { exercise.movement?.familyId != null } ?: MovementSelection.FIXED, skillId,
                    blockId, restValue, autoRest && restValue != null, caution.trim(),
                    restMaxValue, instructions.trim()), exercise.takeIf { selected == null })
            }
        }
    }) {
        Field(name, stringResource(R.string.exercise_name), { name = it; picked = null; overrideMode = false },
            Modifier.fillMaxWidth().testTag("exercise_name"))
        suggestions.forEach { choice ->
            TextButton(onClick = { select(choice) }) { Text(choice.name) }
        }
        if (catalog.isNotEmpty()) {
            TextButton(onClick = { catalogOpen = !catalogOpen; catalogQuery = "" },
                modifier = Modifier.testTag("choose_plan_catalog")) {
                Text(if (catalogOpen) "Закрыть каталог" else "Выбрать из каталога")
            }
            if (catalogOpen) {
                Field(catalogQuery, "Поиск в каталоге", { catalogQuery = it }, Modifier.testTag("plan_catalog_search"))
                val matching = catalog.filter { it.name.contains(catalogQuery.trim(), true) }
                matching.take(12).forEach { choice -> TextButton(onClick = { select(choice) }) { Text(choice.name) } }
                if (matching.isEmpty()) Muted("Упражнения не найдены.")
                if (matching.size > 12) Muted("Уточните название, чтобы найти нужное упражнение.")
            }
        }
        Choice(shownMode, ExerciseMode.entries, { it.label }, {
            mode = it
            overrideMode = true
            if (it == ExerciseMode.CARDIO) cardio = effective.cardio ?: CardioType.OTHER
            if (it != ExerciseMode.STRENGTH) { load = Load.OTHER; useTimedDefaults() }
            else rows = rows.map { row -> row.copy(base = row.base.copy(durationSeconds = null), duration = "") }
        }, "exercise_mode")
        if (shownMode == ExerciseMode.CARDIO) Choice(effective.cardio ?: CardioType.OTHER, CardioType.entries, { it.label }, {
            mode = ExerciseMode.CARDIO
            overrideMode = true
            cardio = it
        }, "exercise_cardio")
        if (showOptions) {
            if (selected == null && !effective.tracksDuration) {
                val loads = Load.entries.map { it to loadText(it) }.toMap()
                val units = UnitWeight.entries.map { it to unitText(it) }.toMap()
                Choice(load, Load.entries, loads::getValue, { load = it }, "exercise_load")
                Choice(unit, UnitWeight.entries, units::getValue, { unit = it }, "exercise_weight_unit")
            }
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
            if (!effective.tracksDuration) TextButton(onClick = { warmupTemplate = !warmupTemplate }) { Text("Шаблон разминочных подходов") }
            if (warmupTemplate && !effective.tracksDuration) {
                Field(warmCount, "Разминочных подходов", { warmCount = it }, keyboard = KeyboardType.Number)
                Field(rampCount, "Подводящих подходов", { rampCount = it }, keyboard = KeyboardType.Number)
                Field(warmReps, "Повторов в этих подходах (необязательно)", { warmReps = it }, keyboard = KeyboardType.Number)
                OutlinedButton(onClick = {
                    val reps = warmRepCount ?: 1
                    val base = SetPlan(repsMin = reps, repsMax = reps, weightKnown = false, repsKnown = warmRepCount != null)
                    val added = List(warm ?: 0) { base.copy(role = SetRole.WARMUP, warmup = true) } +
                        List(ramp ?: 0) { base.copy(role = SetRole.RAMP) }
                    rows = added.map { PlanSetDraft(newId(), it) } + rows
                    warmupTemplate = false
                    warmCount = ""
                    rampCount = ""
                    warmReps = ""
                }, enabled = preparationValid && (warm != null || ramp != null)) { Text("Добавить перед рабочими") }

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
        Field(instructions, "Примечания к упражнению в плане", { instructions = it }, singleLine = false)
        PlanSets(rows, effective, fallback.copy(weightKnown = !effective.tracksDuration, repsKnown = !effective.tracksDuration)) { rows = it }
        options()

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
