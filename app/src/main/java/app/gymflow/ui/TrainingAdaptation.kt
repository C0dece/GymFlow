package app.gymflow.ui

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import app.gymflow.data.Library
import app.gymflow.domain.*

@Composable
internal fun VolumeSheet(row: ParticipantWorkout, close: () -> Unit, save: (WorkoutVolume) -> Unit) {
    EditorSheet("Объём тренировки", close) {
        val all = row.exercises.mapTo(hashSetOf()) { it.exercise.id }
        val total = workSets(row, all, setOf(SetStatus.PLANNED, SetStatus.COMPLETED))
        Muted("Подтверждённые и запланированные рабочие подходы: $total")
        WorkoutVolume.entries.forEach { mode ->
            OutlinedButton(onClick = { save(mode) }, modifier = Modifier.testTag("volume_${mode.name}")) {
                Text(mode.label + if (row.volume == mode) " · Сейчас" else "")
            }
            Muted(when (mode) {
                WorkoutVolume.MINIMUM -> "До одного рабочего подхода на упражнение. Необязательные блоки откладываются."
                WorkoutVolume.NORMAL -> "Возвращает подходы, отложенные минимальным режимом."
                WorkoutVolume.EXTENDED -> "Возвращает полный план. Дополнительные упражнения добавляются вручную."
                WorkoutVolume.FREE -> "Возвращает полный план для свободного редактирования."
            })
        }
    }
}

@Composable
internal fun VariationSheet(
    entry: ExerciseEntry, row: ParticipantWorkout, library: Library, close: () -> Unit, save: (ExercisePlan) -> Unit,
) {
    val source = library.exercises.find { it.id == entry.exercise.id } ?: entry.exercise
    val candidates = remember(source, row.profile.id, entry.plan.selection, library) {
        suggestionsForFamily(source, entry.plan.selection, library.exercises, library.movementTracks, row.profile.id, library.sessions)
    }
    EditorSheet("Вариации · ${source.name}", close) {
        val pending = entry.athletes.single().sets.count { it.status == SetStatus.PLANNED }
        Muted("Переносится число оставшихся подходов ($pending). Вес для новой вариации укажите отдельно.")
        if (candidates.isEmpty()) Muted("Подходящих вариаций в каталоге пока нет.")
        candidates.forEach { target ->
            CardBlock {
                Text(target.name)
                val track = library.movementTracks.firstOrNull { it.profileId == row.profile.id && it.exerciseId == target.id }
                val details = listOfNotNull(track?.role?.label, track?.repeatDays?.let { "Интервал $it дн." },
                    track?.priority?.takeIf { it != 0 }?.let { "Приоритет $it" },
                    target.movement?.limitations?.takeIf { it.isNotBlank() }, track?.limitations?.takeIf { it.isNotBlank() })
                if (details.isNotEmpty()) Muted(details.joinToString(" · "))
                Button(onClick = { save(substitution(entry, target)) }, enabled = pending > 0,
                    modifier = Modifier.testTag("use_variation_${target.id}")) { Text("Заменить") }
            }
        }
    }
}

@Composable
internal fun AdviceSheet(
    entry: ExerciseEntry, row: ParticipantWorkout, library: Library, close: () -> Unit,
    edit: (SetEntry) -> Unit, finish: () -> Unit, variations: () -> Unit,
) {
    val holder = entry.athletes.single()
    val pending = holder.sets.firstOrNull { it.status == SetStatus.PLANNED }
    val catalog = library.exercises.associateBy { it.id }
    val family = (catalog[entry.exercise.id] ?: entry.exercise).movement?.familyId
    val ids = if (family == null) setOf(entry.exercise.id)
        else catalog.values.filter { it.movement?.familyId == family }.mapTo(hashSetOf()) { it.id }
    val current = workSets(row, ids, setOf(SetStatus.PLANNED, SetStatus.COMPLETED))
    val past = library.sessions.asSequence().flatMap { it.completedWorkouts(row.profile.id).asSequence() }
        .filter { it.id != row.id }.map { workSets(it, ids, setOf(SetStatus.COMPLETED)) }
        .filter { it > 0 }.take(6).sorted().toList()
    val median = past.getOrNull(past.size / 2)
    val overload = past.size >= 3 && median != null && current >= median * 1.5 && current - median >= 2
    val reason = when {
        row.metrics.discomfort -> "Отмечен дискомфорт. Рассмотрите остановку упражнения или его замену."
        row.metrics.highFatigue -> "Отмечена высокая усталость. Можно уменьшить нагрузку или закончить упражнение."
        holder.rir == 0 -> "Для упражнения указан RIR 0. Рассмотрите снижение нагрузки."
        overload -> "План содержит $current рабочих подходов. Медиана недавних тренировок: $median."
        pending == null -> "Запланированные подходы закончились."
        holder.rir == null -> "Запас повторений неизвестен. Можно сохранить текущий план."
        else -> "Можно продолжить по плану. Указанный запас повторений: ${holder.rir}."
    }
    EditorSheet("Проверка нагрузки", close) {
        Muted(reason)
        if (family != null) Muted("В расчёте учтены рабочие подходы всего семейства.")
        if (pending != null) {
            OutlinedButton(onClick = { edit(pending) }) { Text("Изменить следующий подход") }
            if (family != null) TextButton(onClick = variations) { Text("Выбрать вариацию") }
            TextButton(onClick = finish) { Text("Закончить упражнение") }
        }
        TextButton(onClick = close) { Text("Оставить как есть") }
    }
}
