@file:OptIn(ExperimentalLayoutApi::class)

package app.gymflow.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.gymflow.R
import app.gymflow.data.Library
import app.gymflow.domain.*

private enum class Binding { GLOBAL, PROGRAM, PROFILE, EXERCISE, PROFILE_EXERCISE }

@Composable
fun PolicyEditor(library: Library, preferred: Profile?, preferredExercise: Exercise?, close: () -> Unit, save: (ProgressionPolicy) -> Unit) {
    var binding by remember { mutableStateOf(if (preferred == null) Binding.GLOBAL else Binding.PROFILE) }
    var person by remember { mutableStateOf(preferred ?: library.availableProfiles.firstOrNull()) }
    var program by remember { mutableStateOf(library.programs.firstOrNull()) }
    var exercise by remember { mutableStateOf(preferredExercise ?: library.exercises.firstOrNull()) }
    val captions = stringArrayResource(R.array.progression_bindings)
    val strategies = stringArrayResource(R.array.progression_strategies)
    val actions = stringArrayResource(R.array.progression_actions)
    val conditions = stringArrayResource(R.array.progression_conditions)
    SectionTitle(stringResource(R.string.policy_scope))
    Choice(binding, Binding.entries, { captions[it.ordinal] }, { binding = it }, "policy_scope")
    val profileBound = binding in setOf(Binding.PROFILE, Binding.PROFILE_EXERCISE)
    val exerciseBound = binding in setOf(Binding.EXERCISE, Binding.PROFILE_EXERCISE)
    if (profileBound) {
        val options = (library.availableProfiles + listOfNotNull(preferred)).distinctBy { it.id }
        Choice(person, options, { it?.name.orEmpty() }, { person = it }, "policy_profile")
    }
    if (binding == Binding.PROGRAM) Choice(program, library.programs, { it?.title.orEmpty() }, { program = it }, "policy_program")
    if (exerciseBound) Choice(exercise, library.exercises, { it?.name.orEmpty() }, { exercise = it }, "policy_exercise")
    val valid = (!profileBound || person != null) && (!exerciseBound || exercise != null) &&
        (binding != Binding.PROGRAM || program != null)
    if (!valid) {
        Muted(stringResource(R.string.progression_no_history))
        return
    }
    val scope = ProgressionScope(
        profileId = person?.id.takeIf { profileBound },
        programId = program?.id.takeIf { binding == Binding.PROGRAM },
        exerciseId = exercise?.id.takeIf { exerciseBound },
    )

    key(scope) {
        val original = library.policies.find { it.id == scope.documentId() } ?: ProgressionPolicy(scope)
        var draft by remember(original) { mutableStateOf(original) }
        var advanced by remember { mutableStateOf(false) }
        var ruleIndex by remember { mutableStateOf<Int?>(null) }
        var showRules by remember { mutableStateOf(false) }
        var showWave by remember { mutableStateOf(false) }
        val config = draft.ruleSet
        SectionTitle(stringResource(R.string.strategy))
        Choice(config.strategy, ProgressionStrategy.entries, { strategies[it.ordinal] },
            { draft = draft.copy(ruleSet = config.copy(strategy = it)) }, "strategy")
        FilterChip(draft.locked, { draft = draft.copy(locked = !draft.locked) }, label = { Text(stringResource(R.string.lock_load)) })
        OutlinedButton(onClick = { advanced = true }, modifier = Modifier.testTag("advanced_progression")) {
            Text(stringResource(R.string.advanced_progression))
        }
        if (config.strategy == ProgressionStrategy.CUSTOM) {
            OutlinedButton(onClick = { showRules = !showRules }, modifier = Modifier.testTag("custom_rules")) {
                Text(stringResource(R.string.custom_rules) + " · " + config.rules.size)
            }
            if (showRules) {
                config.rules.forEachIndexed { i, rule ->
                    CardBlock(onClick = { ruleIndex = i }) {
                        Text(rule.name.ifBlank { actions[rule.action.kind.ordinal] })
                        Muted(rule.conditions.joinToString(" + ") { conditions[it.kind.ordinal] })
                        Muted(stringResource(R.string.rule_priority) + " " + rule.priority)
                    }
                }
                if (config.rules.size < 30) TextButton(onClick = { ruleIndex = -1 }, modifier = Modifier.testTag("add_rule")) {
                    Text(stringResource(R.string.add_rule))
                }
            }
        }
        if (config.strategy == ProgressionStrategy.WAVE) {
            OutlinedButton(onClick = { showWave = true }) { Text(stringResource(R.string.wave) + " · " + config.wave.size) }
        }
        val previewPerson = person
        val previewExercise = exercise
        val base = remember(library.sessions, library.programs, previewPerson?.id, previewExercise?.id, program?.id, binding) {
            if (previewPerson == null || previewExercise == null) null else {
                val sample = library.sessions.firstNotNullOfOrNull { session ->
                    val member = session.workout(previewPerson.id)
                    if (session.status != SessionStatus.COMPLETED && member?.status != WorkoutStatus.COMPLETED) null
                    else member?.plans()?.find { it.exercise.id == previewExercise.id }?.let { session.programId to it }
                }
                if (binding == Binding.PROGRAM) {
                    program?.days?.flatMap { it.exercises }?.find { it.exercise.id == previewExercise.id }
                        ?.let { program?.id to it } ?: sample?.let { program?.id to it.second }
                } else sample
            }
        }
        if (base != null && previewPerson != null) {
            val localLibrary = remember(library, draft) {
                library.copy(documents = library.documents.filterNot { it.id == draft.id } + draft)
            }
            val engine by rememberEngine(localLibrary)
            val active = engine
            if (active != null) CardBlock {
                SectionTitle(stringResource(R.string.practice_progression))
                DecisionText(remember(active, previewPerson.id, base) { active.decide(previewPerson.id, base.first, base.second) })
            }
        }
        Button(onClick = { save(draft) }, modifier = Modifier.fillMaxWidth().testTag("save_policy")) { Text(stringResource(R.string.save)) }
        TextButton(onClick = close) { Text(stringResource(R.string.cancel)) }

        if (advanced) ConstraintsEditor(config, { advanced = false }) {
            draft = draft.copy(ruleSet = it)
            advanced = false
        }
        if (showWave) WaveEditor(config.wave, { showWave = false }) {
            draft = draft.copy(ruleSet = config.copy(wave = it))
            showWave = false
        }
        ruleIndex?.let { i ->
            val existing = config.rules.getOrNull(i)
            RuleEditor(existing, { ruleIndex = null }) { updated ->
                val rules = if (i < 0) config.rules + listOfNotNull(updated)
                    else config.rules.toMutableList().apply { if (updated == null) removeAt(i) else set(i, updated) }
                draft = draft.copy(ruleSet = config.copy(rules = rules))
                ruleIndex = null
            }
        }
    }
}

@Composable
private fun RuleEditor(existing: Rule?, close: () -> Unit, save: (Rule?) -> Unit) {
    var action by remember { mutableStateOf(existing?.action?.kind ?: ActionKind.HOLD) }
    var amount by remember { mutableStateOf(existing?.action?.amount?.let(::number).orEmpty()) }
    var percent by remember { mutableStateOf(existing?.action?.percent ?: false) }
    var priority by remember { mutableStateOf((existing?.priority ?: 0).toString()) }
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    val selected = remember { mutableStateMapOf<ConditionKind, Pair<String, String>>().apply {
        existing?.conditions?.forEach { put(it.kind, it.sessions.toString() to number(it.threshold)) }
    } }
    var condition by remember { mutableStateOf(ConditionKind.ALL_WORK) }
    val actions = stringArrayResource(R.array.progression_actions)
    val conditions = stringArrayResource(R.array.progression_conditions)
    val ruleId = remember { existing?.id ?: newId() }
    val candidate = runCatching {
        Rule(ruleId, priority = priority.toInt(), name = name.trim(), conditions = selected.entries.sortedBy { it.key.ordinal }
            .map { (kind, input) -> Condition(kind, input.first.toInt(), input.second.replace(',', '.').toDouble()) },
            action = Action(action, if (amount.isBlank()) 0.0 else amount.replace(',', '.').toDouble(), percent))
    }.getOrNull()
    EditorSheet(stringResource(R.string.edit_rule), close) {
        Field(name, stringResource(R.string.rule_name), { name = it })
        Choice(action, ActionKind.entries, { actions[it.ordinal] }, { action = it }, "rule_action")
        Field(amount, stringResource(R.string.rule_amount), { amount = it })
        if (action in setOf(ActionKind.INCREASE_WEIGHT, ActionKind.DECREASE_WEIGHT)) {
            FilterChip(percent, { percent = !percent }, label = { Text(stringResource(R.string.rule_percent)) })
        }
        Field(priority, stringResource(R.string.rule_priority), { priority = it })
        Muted(stringResource(R.string.all_conditions))
        Choice(condition, ConditionKind.entries, { conditions[it.ordinal] }, { condition = it }, "rule_condition")
        OutlinedButton(onClick = { selected.putIfAbsent(condition, "2" to "1") }, enabled = condition !in selected,
            modifier = Modifier.testTag("add_condition")) {
            Text(stringResource(R.string.add))
        }
        ConditionKind.entries.filter { it in selected }.forEach { kind ->
            val (window, threshold) = selected.getValue(kind)
            CardBlock {
                Text(conditions[kind.ordinal])
                if (kind in setOf(ConditionKind.RIR_AVERAGE_BELOW, ConditionKind.PLATEAU, ConditionKind.MISSED_TARGET,
                        ConditionKind.SKIPPED, ConditionKind.INSUFFICIENT)) {
                    Field(window, stringResource(R.string.condition_window), { selected[kind] = it to threshold }, keyboard = KeyboardType.Number)
                }
                if (kind in setOf(ConditionKind.RIR_AT_LEAST, ConditionKind.RIR_AVERAGE_BELOW, ConditionKind.BODY_MASS_CHANGE)) {
                    Field(threshold, stringResource(R.string.condition_threshold), { selected[kind] = window to it }, keyboard = KeyboardType.Decimal)
                }
                TextButton(onClick = { selected.remove(kind) }) { Text(stringResource(R.string.remove)) }
            }
        }
        if (candidate == null) Muted(stringResource(R.string.invalid_progression))
        Button(onClick = { candidate?.let(save) }, enabled = candidate != null, modifier = Modifier.testTag("save_rule")) {
            Text(stringResource(R.string.save))
        }
        if (existing != null) TextButton(onClick = { save(null) }) { Text(stringResource(R.string.remove)) }
    }
}
