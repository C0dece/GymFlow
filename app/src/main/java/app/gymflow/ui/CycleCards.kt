package app.gymflow.ui

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import app.gymflow.data.Library
import app.gymflow.domain.*
import java.time.LocalDate

@Composable
internal fun CycleCards(
    library: Library, preferred: String, prepare: (Program, Profile, String?) -> Unit, extend: (Program, Profile) -> Unit,
) {
    val default = library.availableProfiles.find { it.id == preferred } ?: library.availableProfiles.firstOrNull()
    val options = library.programs.filter { !it.archived && it.enabled && it.completionCycle != null }.flatMap { program ->
        val people = if (program.participantIds.isEmpty()) listOfNotNull(default)
            else library.availableProfiles.filter { it.id in program.participantIds }
        people.map { program to it }
    }
    if (options.isEmpty()) return
    var select by remember { mutableStateOf<Pair<String, String>?>(null) }
    options.forEach { (program, profile) ->
        val position = remember(program, profile.id, library.sessions) { cyclePosition(program, profile.id, library.sessions) }
        CardBlock {
            SectionTitle("Следующая по циклу")
            Muted(program.title + if (options.size > 1) " · " + profile.name else "")
            val total = position.limit?.let { " из $it" }.orEmpty()
            Text("Завершено ${position.completed}$total")
            if (position.next != null) {
                Text(position.next.title)
                val date = position.pending?.session?.date ?: remember(program, profile.id, position.completed, library.sessions) {
                    val today = LocalDate.now()
                    val doneToday = library.sessions.any { session -> session.date == today.toString() && session.allWorkouts.any {
                        it.profile.id == profile.id && it.cycleSlot?.programId == program.id &&
                            it.status == WorkoutStatus.COMPLETED && (it.cycleCredited || it.hasCycleResult())
                    } }
                    val first = maxOf(LocalDate.parse(program.anchor), if (doneToday) today.plusDays(1) else today)
                    generateSequence(first) { it.plusDays(1) }.first(requireNotNull(program.completionCycle)::visitsOn).toString()
                }
                Muted("Визит · " + shortDate(date))
                position.phase?.let { Muted(it.name + " · " + it.guidance) }
                Button(onClick = { prepare(program, profile, null) }, modifier = Modifier.testTag("prepare_cycle_${profile.id}")) {
                    Text(if (position.pending?.workout?.status in setOf(WorkoutStatus.ACTIVE, WorkoutStatus.PAUSED)) "Продолжить по циклу" else "Открыть план")
                }
                if (program.days.any { it.id != position.next.id && it.orderedExercises().isNotEmpty() }) {
                    TextButton(onClick = { select = program.id to profile.id }) { Text("Выбрать другой день") }
                }
            } else if (position.limit != null && position.completed >= position.limit) {
                Text("Цикл завершён")
                OutlinedButton(onClick = { extend(program, profile) }) { Text("Продолжить цикл") }
            } else Muted("Добавьте упражнения в дни программы.")
        }
        if (select == (program.id to profile.id)) {
            EditorSheet("Другой день цикла", { select = null }) {
                Muted("Тренировка вне очереди. Положение в цикле сохранится.")
                program.days.filter { it.id != position.next?.id && it.orderedExercises().isNotEmpty() }.forEach { day ->
                    OutlinedButton(onClick = { select = null; prepare(program, profile, day.id) }) { Text(day.title) }
                }
            }
        }
    }
}
