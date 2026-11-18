package app.gymflow.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.gymflow.R
import app.gymflow.domain.*

@Composable
fun ConstraintsEditor(config: RuleSet, close: () -> Unit, save: (RuleSet) -> Unit) {
    val c = config.constraint
    val inputs = remember(c, config.linearPercent) {
        mutableStateMapOf(
            R.string.weight_step_kg to number(c.stepKg),
            R.string.min_delta to number(c.minChangeKg),
            R.string.max_delta to number(c.maxChangeKg),
            R.string.max_delta_pct to number(c.maxChangePercent),
            R.string.weight_min to number(c.minWeightKg),
            R.string.weight_max to number(c.maxWeightKg),
            R.string.equipment_weights to c.equipmentKg.joinToString(" ", transform = ::number),
            R.string.rule_reps_min to c.minReps.toString(),
            R.string.rule_reps_max to c.maxReps.toString(),
            R.string.rule_sets_min to c.minSets.toString(),
            R.string.rule_sets_max to c.maxSets.toString(),
            R.string.target_rir to c.targetRir.toString(),
            R.string.min_observations to c.minSessions.toString(),
            R.string.failure_window to c.failureWindow.toString(),
            R.string.deload_value to number(c.deloadPercent),
            R.string.linear_pct to config.linearPercent?.let(::number).orEmpty(),
        )
    }
    var requireRir by remember { mutableStateOf(c.requireRir) }
    fun value(key: Int) = inputs.getValue(key).trim()
    fun count(key: Int) = value(key).toInt()
    fun decimalNumber(key: Int) = value(key).replace(',', '.').toDouble()
    val next = runCatching {
        config.copy(
            constraint = Constraint(
                stepKg = decimalNumber(R.string.weight_step_kg),
                minChangeKg = decimalNumber(R.string.min_delta),
                maxChangeKg = decimalNumber(R.string.max_delta),
                maxChangePercent = decimalNumber(R.string.max_delta_pct),
                minWeightKg = decimalNumber(R.string.weight_min),
                maxWeightKg = decimalNumber(R.string.weight_max),
                equipmentKg = value(R.string.equipment_weights).split(Regex("\\s+")).filter(String::isNotBlank)
                    .map { it.replace(',', '.').toDouble() }.distinct().sorted(),
                minReps = count(R.string.rule_reps_min), maxReps = count(R.string.rule_reps_max),
                minSets = count(R.string.rule_sets_min), maxSets = count(R.string.rule_sets_max),
                targetRir = count(R.string.target_rir), requireRir = requireRir,
                minSessions = count(R.string.min_observations), failureWindow = count(R.string.failure_window),
                deloadPercent = decimalNumber(R.string.deload_value),
            ),
            linearPercent = if (value(R.string.linear_pct).isBlank()) null else decimalNumber(R.string.linear_pct),
        )
    }.getOrNull()
    EditorSheet(stringResource(R.string.advanced_progression), close) {
        inputs.keys.sortedBy { id ->
            val order = listOf(R.string.weight_step_kg, R.string.equipment_weights, R.string.min_delta, R.string.max_delta,
                R.string.max_delta_pct, R.string.weight_min, R.string.weight_max, R.string.rule_reps_min, R.string.rule_reps_max,
                R.string.rule_sets_min, R.string.rule_sets_max, R.string.target_rir, R.string.min_observations,
                R.string.failure_window, R.string.deload_value, R.string.linear_pct)
            order.indexOf(id)
        }.forEach { label ->
            Field(inputs.getValue(label), stringResource(label), { inputs[label] = it },
                keyboard = if (label == R.string.equipment_weights) KeyboardType.Text else KeyboardType.Decimal)
        }
        FilterChip(requireRir, { requireRir = !requireRir }, label = { Text(stringResource(R.string.rir_required)) })
        if (next == null) Muted(stringResource(R.string.invalid_progression))
        Button(onClick = { next?.let(save) }, enabled = next != null, modifier = Modifier.fillMaxWidth().testTag("save_constraints")) {
            Text(stringResource(R.string.save))
        }
    }
}

private data class WaveFields(val weight: String, val reps: String, val sets: String)

@Composable
fun WaveEditor(steps: List<WaveStep>, close: () -> Unit, save: (List<WaveStep>) -> Unit) {
    val items = remember(steps) {
        mutableStateListOf<WaveFields>().apply {
            addAll(steps.map { WaveFields(number(it.weightPercent), it.repsDelta.toString(), it.setsDelta.toString()) })
        }
    }
    val next = runCatching {
        items.map { WaveStep(it.weight.replace(',', '.').toDouble(), it.reps.toInt(), it.sets.toInt()) }
    }.getOrNull()
    EditorSheet(stringResource(R.string.wave), close) {
        items.forEachIndexed { i, row ->
            CardBlock {
                Text(stringResource(R.string.cycle_day, i + 1))
                Field(row.weight, stringResource(R.string.wave_weight), { items[i] = row.copy(weight = it) })
                Field(row.reps, stringResource(R.string.wave_reps), { items[i] = row.copy(reps = it) })
                Field(row.sets, stringResource(R.string.wave_sets), { items[i] = row.copy(sets = it) })
                if (items.size > 1) TextButton(onClick = { items.removeAt(i) }) { Text(stringResource(R.string.remove)) }
            }
        }
        if (items.size < 12) OutlinedButton(onClick = { items.add(WaveFields("0", "0", "0")) }) {
            Text(stringResource(R.string.add_wave))
        }
        if (next == null) Muted(stringResource(R.string.invalid_progression))
        Button(onClick = { next?.let(save) }, enabled = next != null) { Text(stringResource(R.string.save)) }
    }
}
