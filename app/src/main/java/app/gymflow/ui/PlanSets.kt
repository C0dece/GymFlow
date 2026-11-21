@file:OptIn(ExperimentalLayoutApi::class)

package app.gymflow.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.gymflow.R
import app.gymflow.domain.*

private const val maxNewSets = 100

internal data class PlanSetDraft(
    val key: String,
    val base: SetPlan,
    val weight: String = if (base.weightKnown) number(base.weight) else "",
    val low: String = if (base.repsKnown) base.repsMin.toString() else "",
    val high: String = if (base.repsKnown && base.repsMax != base.repsMin) base.repsMax.toString() else "",
    val target: String = base.targetReps?.toString().orEmpty(),
    val role: SetRole = base.purpose,
    val maxEffort: Boolean = base.maxEffort,
    val duration: String = base.durationSeconds?.let(::number).orEmpty(),
) {
    fun result(timed: Boolean = false): SetPlan? {
        val seconds = decimal(duration)?.takeIf(Measure.DURATION::accepts)
        if (duration.isNotBlank() && seconds == null) return null
        if (timed || base.durationSeconds != null) return base.copy(durationSeconds = seconds,
            role = if (role == base.purpose) base.role else role,
            warmup = if (role == base.purpose) base.warmup else role == SetRole.WARMUP)
        val amount = decimal(weight)
        val minimum = positiveInt(low)
        val maximum = if (high.isBlank()) minimum else positiveInt(high)
        val goal = positiveInt(target)
        val ranged = minimum != null && maximum != null && maximum >= minimum
        if (weight.isNotBlank() && amount == null) return null
        if (!maxEffort && !ranged && (low.isNotBlank() || high.isNotBlank())) return null
        if (!maxEffort && target.isNotBlank() && (!ranged || goal == null || goal !in requireNotNull(minimum)..requireNotNull(maximum))) return null
        return base.copy(
            weight = amount ?: base.weight,
            weightKnown = amount != null,
            repsMin = if (ranged) requireNotNull(minimum) else base.repsMin,
            repsMax = if (ranged) requireNotNull(maximum) else base.repsMax,
            repsKnown = ranged || maxEffort && base.maxEffort && base.repsKnown,
            targetReps = if (maxEffort) base.targetReps?.takeIf { base.maxEffort && it in (minimum ?: base.repsMin)..(maximum ?: base.repsMax) } else goal,
            role = if (role == base.purpose) base.role else role,
            warmup = if (role == base.purpose) base.warmup else role == SetRole.WARMUP,
            maxEffort = maxEffort,
            durationSeconds = seconds,
        )
    }
}

private fun encodePlanSets(rows: List<PlanSetDraft>): List<String> = rows.flatMap { row ->
    val base = row.base
    listOf(row.key, base.weight.toString(), base.repsMin.toString(), base.repsMax.toString(), base.warmup.toString(),
        base.weightKnown.toString(), base.maxEffort.toString(), base.repsKnown.toString(), base.targetReps?.toString().orEmpty(),
        base.role?.name.orEmpty(), row.weight, row.low, row.high, row.target, row.role.name, row.maxEffort.toString(),
        base.durationSeconds?.toString().orEmpty(), row.duration)
}

private fun decodePlanSets(data: List<String>): List<PlanSetDraft> = data.chunked(18).map { row ->
    PlanSetDraft(row[0], SetPlan(row[1].toDouble(), row[2].toInt(), row[3].toInt(), row[4].toBoolean(),
        row[5].toBoolean(), row[6].toBoolean(), row[7].toBoolean(), row[8].toIntOrNull(),
        row[9].takeIf(String::isNotEmpty)?.let(SetRole::valueOf), row[16].toDoubleOrNull()),
        row[10], row[11], row[12], row[13], SetRole.valueOf(row[14]), row[15].toBoolean(), row[17])
}

private val planSetsSaver = listSaver(save = { encodePlanSets(it) }, restore = ::decodePlanSets)
private val personalSetsSaver = listSaver<Map<String, List<PlanSetDraft>>, ArrayList<String>>(
    save = { drafts -> drafts.entries.map { (id, rows) -> ArrayList(listOf(id) + encodePlanSets(rows)) } },
    restore = { drafts -> drafts.associate { row -> row.first() to decodePlanSets(row.drop(1)) } },
)

@Composable
internal fun rememberPersonalSets(): MutableState<Map<String, List<PlanSetDraft>>> =
    rememberSaveable(stateSaver = personalSetsSaver) { mutableStateOf(emptyMap()) }

@Composable
internal fun rememberPlanSets(initial: List<SetPlan>): MutableState<List<PlanSetDraft>> =
    rememberSaveable(stateSaver = planSetsSaver) { mutableStateOf(initial.map { PlanSetDraft(newId(), it) }) }

internal fun List<PlanSetDraft>.plansOrNull(timed: Boolean = false): List<SetPlan>? =
    if (isEmpty()) null else mapNotNull { it.result(timed) }.takeIf { it.size == size }

@Composable
internal fun PlanEditorActions(close: () -> Unit, label: String, enabled: Boolean, tag: String, save: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = close, modifier = Modifier.weight(1f)) { Text("Отмена") }
        Button(onClick = save, enabled = enabled, modifier = Modifier.weight(1.5f).testTag(tag)) { Text(label) }
    }
}

@Composable
internal fun PlanSets(rows: List<PlanSetDraft>, exercise: Exercise, fallback: SetPlan, change: (List<PlanSetDraft>) -> Unit) {
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var resize by rememberSaveable { mutableStateOf(false) }
    var count by rememberSaveable { mutableStateOf("") }
    var undo by remember { mutableStateOf<List<PlanSetDraft>?>(null) }
    fun apply(next: List<PlanSetDraft>) { undo = null; change(next) }
    fun append() {
        val draft = PlanSetDraft(newId(), rows.lastOrNull()?.result(exercise.tracksDuration) ?: fallback)
        apply(rows + draft)
        selected = draft.key
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text("Подходы · ${rows.size}", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = { resize = !resize; count = rows.size.toString() }, modifier = Modifier.testTag("resize_plan_sets")) {
            Text(if (resize) "Скрыть" else "Количество")
        }
    }
    if (rows.isEmpty()) Muted("Добавьте хотя бы один подход.")
    if (resize) {
        val size = positiveInt(count)?.takeIf { it <= maxOf(maxNewSets, rows.size) }
        Field(count, "Количество подходов", { count = it }, Modifier.testTag("plan_set_count"), KeyboardType.Number,
            error = count.isNotBlank() && size == null)
        OutlinedButton(onClick = {
            if (size != null) {
                if (size < rows.size) {
                    undo = rows
                    change(rows.take(size))
                } else {
                    val base = rows.lastOrNull()?.result(exercise.tracksDuration) ?: fallback
                    apply(rows + List(size - rows.size) { PlanSetDraft(newId(), base) })
                }
                resize = false
            }
        }, enabled = size != null && size != rows.size, modifier = Modifier.testTag("apply_plan_count")) {
            Text(if (size != null && size < rows.size) "Убрать последние ${rows.size - size}" else "Применить")
        }
    }
    rows.forEachIndexed { index, row ->
        key(row.key) {
            val valid = row.result(exercise.tracksDuration)
            val expanded = selected == row.key
            CardBlock {
                TextButton(onClick = { selected = if (expanded) null else row.key },
                    modifier = Modifier.fillMaxWidth().testTag("plan_set_$index")) {
                    Column(Modifier.fillMaxWidth()) {
                        Text("Подход ${index + 1} · ${row.role.label}", style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface)
                        if (valid == null) Text("Проверьте значения", color = MaterialTheme.colorScheme.error)
                        else Muted(plannedSetText(valid, exercise))
                    }
                }
                if (expanded) {
                    PlanSetFields(row, exercise, "plan_$index") { next -> apply(rows.map { if (it.key == row.key) next else it }) }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick = { apply(rows.toMutableList().apply { add(index - 1, removeAt(index)) }) },
                            enabled = index > 0, modifier = Modifier.testTag("plan_up_$index")) { Text("Выше") }
                        TextButton(onClick = { apply(rows.toMutableList().apply { add(index + 1, removeAt(index)) }) },
                            enabled = index < rows.lastIndex, modifier = Modifier.testTag("plan_down_$index")) { Text("Ниже") }
                        TextButton(onClick = {
                            val copy = row.copy(key = newId())
                            apply(rows.toMutableList().apply { add(index + 1, copy) })
                            selected = copy.key
                        }, enabled = rows.size < maxNewSets, modifier = Modifier.testTag("plan_copy_$index")) { Text("Копировать") }
                        TextButton(onClick = {
                            undo = rows
                            change(rows.filterNot { it.key == row.key })
                        }, enabled = rows.size > 1, modifier = Modifier.testTag("plan_remove_$index")) { Text("Убрать") }
                    }
                }
            }
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = ::append, enabled = rows.size < maxNewSets, modifier = Modifier.testTag("plan_add_set")) {
            Text("Добавить подход")
        }
        undo?.let { before ->
            TextButton(onClick = { apply(before) }, modifier = Modifier.testTag("plan_undo")) { Text("Вернуть подход") }
        }
    }
}

@Composable
internal fun PlanSetFields(row: PlanSetDraft, exercise: Exercise, tag: String, change: (PlanSetDraft) -> Unit) {
    val timed = exercise.tracksDuration || row.base.durationSeconds != null
    if (timed) {
        Field(row.duration, "Плановое время, с", { change(row.copy(duration = it)) }, Modifier.testTag("${tag}_duration"),
            KeyboardType.Decimal, error = row.duration.isNotBlank() && decimal(row.duration)?.let(Measure.DURATION::accepts) != true)
    } else {
        Field(row.weight, "Вес, ${unitText(exercise.unit)}", { change(row.copy(weight = it)) }, Modifier.testTag("${tag}_weight"),
            KeyboardType.Decimal, error = row.weight.isNotBlank() && decimal(row.weight) == null)
        if (!row.maxEffort) {
            AdaptivePair {
                Field(row.low, "Повторы", { change(row.copy(low = it)) }, Modifier.weight(1f).testTag("${tag}_reps"), KeyboardType.Number)
                Field(row.high, "До (необяз.)", { change(row.copy(high = it)) }, Modifier.weight(1f).testTag("${tag}_reps_max"), KeyboardType.Number)
            }
            Field(row.target, "Цель повторов", { change(row.copy(target = it)) }, Modifier.testTag("${tag}_target"), KeyboardType.Number)
        }
        FilterChip(row.maxEffort, { change(row.copy(maxEffort = !row.maxEffort)) }, label = { Text("До отказа") })
    }
    Choice(row.role, SetRole.entries, { it.label }, { change(row.copy(role = it)) }, "${tag}_role")
    if (row.result(timed) == null) InlineStatus(if (timed) "Укажите время от 0,01 до 86400 секунд." else
        "Проверьте вес, диапазон и цель повторов.", warning = true)
}
