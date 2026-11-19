@file:OptIn(ExperimentalLayoutApi::class)

package app.gymflow.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.gymflow.data.Library
import app.gymflow.domain.*

@Composable
internal fun MovementExerciseEditor(
    library: Library, old: Exercise?, source: Exercise?, preferredFamily: MovementFamily?,
    close: () -> Unit, save: (Exercise) -> Unit,
) {
    val model = old?.movement ?: source?.movement ?: MovementDetails()
    val familyOptions = listOf(null) + library.families
    var name by remember { mutableStateOf(old?.name.orEmpty()) }
    var familyId by remember { mutableStateOf(model.familyId ?: preferredFamily?.id) }
    var parentId by remember { mutableStateOf(source?.id ?: model.parentId) }
    var variation by remember { mutableStateOf(model.variation ?: VariationType.OTHER) }
    var quality by remember { mutableStateOf(model.quality) }
    var equipment by remember { mutableStateOf(model.equipment) }
    var gym by remember { mutableStateOf(model.gym) }
    var difficulty by remember { mutableStateOf(model.difficulty?.toString().orEmpty()) }
    var muscle by remember { mutableStateOf(old?.muscle ?: source?.muscle.orEmpty()) }
    var notes by remember { mutableStateOf(old?.notes.orEmpty()) }
    var limits by remember { mutableStateOf(model.limitations) }
    var measures by remember { mutableStateOf(model.measures) }
    var load by remember { mutableStateOf(old?.load ?: source?.load ?: Load.OTHER) }
    var unit by remember { mutableStateOf(old?.unit ?: source?.unit ?: UnitWeight.KG) }
    var extra by remember { mutableStateOf(model.measures.isNotEmpty()) }
    var cardio by remember { mutableStateOf(old?.cardio ?: source?.cardio) }
    val levels = difficulty.toIntOrNull()?.takeIf { it in 1..5 }
    val loadLabels = Load.entries.map { loadText(it) }
    val parents = library.exercises.filter { it.id != old?.id && it.movement?.familyId == familyId && !it.archived }.takeIf { familyId != null }.orEmpty()
    val duplicate = library.exercises.any { it.id != old?.id && it.name.equals(name.trim(), true) }
    EditorSheet(if (source != null) "Новая вариация" else "Упражнение каталога", close) {
        Field(name, "Название", { name = it }, Modifier.testTag("movement_exercise_name"))
        if (duplicate) Muted("Такое название уже есть в каталоге.")
        Choice(familyOptions.find { it?.id == familyId }, familyOptions, { it?.name ?: "Без семейства" },
            { familyId = it?.id; parentId = null }, "movement_family")
        if (familyId != null && parents.isNotEmpty()) {
            Choice(parents.find { it.id == parentId }, listOf(null) + parents, { it?.name ?: "Без исходного упражнения" },
                { parentId = it?.id }, "movement_parent")
            if (parentId != null) Choice(variation, VariationType.entries, { it.label }, { variation = it }, "variation_type")
        }
        Choice(load, Load.entries, { loadLabels[it.ordinal] }, { load = it }, "movement_load")
        Choice(cardio, listOf(null) + CardioType.entries, { it?.label ?: "Без типа кардио" }, { cardio = it }, "movement_cardio")
        Choice(unit, UnitWeight.entries, { if (it == UnitWeight.KG) "кг" else "фунты" }, { unit = it }, "movement_unit")
        Choice(quality, listOf(null) + Quality.entries, { it?.label ?: "Цель не задана" }, { quality = it }, "movement_quality")
        Field(muscle, "Мышцы через запятую", { muscle = it })
        Field(equipment, "Оборудование", { equipment = it })
        Field(gym, "Зал", { gym = it })
        Field(difficulty, "Сложность от 1 до 5", { difficulty = it }, keyboard = KeyboardType.Number)
        Field(notes, "Техника и заметки", { notes = it }, singleLine = false)
        Field(limits, "Ограничения и противопоказания", { limits = it }, singleLine = false)
        TextButton(onClick = { extra = !extra }, modifier = Modifier.testTag("movement_measure_toggle")) { Text("Метрики результата") }
        if (extra) FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Measure.extra.forEach { item ->
                FilterChip(item in measures, { measures = if (item in measures) measures - item else measures + item },
                    label = { Text(item.label) }, modifier = Modifier.testTag("movement_measure_${item.name}"))
            }
        }
        Button(onClick = {
            val details = MovementDetails(familyId, parentId, variation.takeIf { parentId != null }, quality,
                equipment.trim(), gym.trim(), levels, limits.trim(), measures)
            save((old ?: Exercise(name = name.trim(), repsMin = source?.repsMin ?: 8, repsMax = source?.repsMax ?: 12))
                .copy(name = name.trim(), load = load, unit = unit, muscle = muscle.trim(), notes = notes.trim(), movement = details, cardio = cardio))
        }, enabled = name.isNotBlank() && !duplicate && (difficulty.isBlank() || levels != null) &&
            (parentId == null || parents.any { it.id == parentId }),
            modifier = Modifier.fillMaxWidth().testTag("save_movement_exercise")) { Text("Сохранить") }
    }
}

@Composable
internal fun TrackEditor(track: MovementTrack, close: () -> Unit, save: (MovementTrack) -> Unit) {
    var role by remember { mutableStateOf(track.role) }
    var measure by remember { mutableStateOf(track.goal?.measure ?: Measure.REPS) }
    var target by remember { mutableStateOf(track.goal?.target?.let(::number).orEmpty()) }
    var atLeast by remember { mutableStateOf(track.goal?.atLeast ?: true) }
    var repeat by remember { mutableStateOf(track.repeatDays?.toString().orEmpty()) }
    var priority by remember { mutableStateOf(track.priority.toString()) }
    var paused by remember { mutableStateOf(track.paused) }
    var limits by remember { mutableStateOf(track.limitations) }
    var step by remember { mutableStateOf(track.step?.let(::number).orEmpty()) }
    val value = decimal(target)?.takeIf(measure::accepts)
    val days = repeat.toIntOrNull()?.takeIf { it > 0 }
    val order = priority.toIntOrNull()
    val increment = decimal(step)?.takeIf { it > 0 }
    EditorSheet("Развитие движения", close) {
        Choice(role, MovementRole.entries, { it.label }, { role = it }, "track_role")
        if (role == MovementRole.ANCHOR) Muted("Прежний якорь в этом семействе перейдёт в резерв.")
        Choice(measure, Measure.entries, { it.label }, { measure = it }, "track_measure")
        Field(target, "Цель (необязательно)", { target = it }, Modifier.testTag("track_target"), KeyboardType.Decimal)
        FilterChip(atLeast, { atLeast = !atLeast }, label = { Text(if (atLeast) "Не меньше цели" else "Не больше цели") })
        Field(repeat, "Интервал в днях (необязательно)", { repeat = it }, keyboard = KeyboardType.Number)
        Field(priority, "Приоритет ротации", { priority = it })
        Field(limits, "Личные ограничения", { limits = it }, singleLine = false)
        if (measure in Measure.extra) Field(step, "Шаг рекомендации (необязательно)", { step = it }, keyboard = KeyboardType.Decimal)
        FilterChip(paused, { paused = !paused }, label = { Text("Приостановить предложения") })
        Button(onClick = {
            save(track.copy(role = role, goal = value?.let { MovementGoal(measure, it, atLeast) }, repeatDays = days,
                priority = requireNotNull(order), paused = paused, limitations = limits.trim(),
                step = increment.takeIf { value != null && measure in Measure.extra }))
        }, enabled = (target.isBlank() || value != null) && (repeat.isBlank() || days != null) && order != null &&
            (measure !in Measure.extra || step.isBlank() || increment != null && value != null),
            modifier = Modifier.testTag("save_track")) { Text("Сохранить") }
    }
}
