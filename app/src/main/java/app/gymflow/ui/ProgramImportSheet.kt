package app.gymflow.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import app.gymflow.data.Library
import app.gymflow.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun ProgramImportSheet(input: ProgramInput, library: Library, close: () -> Unit, save: (ImportReview) -> Unit) {
    var source by remember(input) { mutableStateOf(input.text) }
    var editing by remember(input) { mutableStateOf(false) }
    val retained = library.programs.firstOrNull { it.importSource?.uri == input.uri }
    val parsed by produceState<Result<ProgramDraft>?>(null, source, retained?.id) {
        value = withContext(Dispatchers.Default) { runCatching { ProgramImport.read(source, retainedId = retained?.id) } }
    }
    val draft = parsed?.getOrNull()
    val current = draft?.let { input.currentProgram(it, library.programs) }
    val exercises = remember(draft) { draft?.exercises().orEmpty() }
    val defaults = remember(exercises, library.exercises, current) { defaultMapping(exercises, library.exercises, current) }
    val selections = remember(input) { mutableStateMapOf<String, String>() }
    val loads = remember(input) { mutableStateMapOf<String, Load>() }
    val loadChoices = loads.toMap()
    val chosen = defaults + selections
    val inputDigest = remember(source) { digest(source) }
    val catalog = library.exercises.filterNot { it.archived }
    val reviewed by produceState<Result<ImportReview>?>(null, input, draft, source, library.exercises, library.programs, chosen, loadChoices) {
        value = if (draft == null || exercises.any { it.id !in chosen }) null else withContext(Dispatchers.Default) {
            runCatching { reviewImport(input, draft, source, library.programs, library.exercises, chosen, loadChoices) }
        }
    }

    EditorSheet("Импорт программы", close) {
        Muted(input.name)
        if (parsed == null) CircularProgressIndicator()
        parsed?.exceptionOrNull()?.let { InlineStatus(it.message ?: "Не удалось разобрать Markdown.", warning = true) }
        reviewed?.exceptionOrNull()?.let { InlineStatus(it.message ?: "Проверьте программу.", warning = true) }
        TextButton(onClick = { editing = !editing }) { Text(if (editing) "Скрыть Markdown" else "Редактировать копию Markdown") }
        if (editing) Field(source, "Markdown для импорта", { source = it }, singleLine = false)
        if (draft != null) {
            val program = draft.template
            SectionTitle(program.title)
            Muted(if (program.cycle) "Цикл" + program.completionCycle?.let { " · ${it.visitsPerWeek} посещения в неделю" }.orEmpty()
                else "По дням недели")
            program.durationWeeks?.let { Muted("Недели · $it") }
            if (current != null) ImportChanges(current, reviewed?.getOrNull()?.program ?: program)
            if (program.caution.isNotBlank()) InlineStatus(program.caution, warning = true)
            program.completionCycle?.phases.orEmpty().forEach { Muted(it.name + " · " + it.guidance) }
            program.days.forEach { day ->
                CardBlock {
                    Text(day.title + if (program.cycle) "" else " · ${day.weekday}")
                    day.exercises.forEach { plan ->
                        val work = plan.sets.firstOrNull()
                        val reps = work?.let { "${plan.sets.size} × ${repRange(it.repsMin, it.repsMax)}" }.orEmpty()
                        Muted(listOfNotNull(plan.exercise.name, reps.takeIf { it.isNotEmpty() }, plan.restLabel()).joinToString(" · "))
                    }
                    day.blocks.filter { it.guidance.isNotBlank() }.forEach { block ->
                        Muted("${block.name} · ${block.requirement.label}\n${block.guidance}")
                    }
                    if (day.notes.isNotBlank()) Muted(day.notes)
                }
            }
            if (exercises.isNotEmpty()) SectionTitle("Сопоставление с каталогом")
            val missing = exercises.filter { chosen[it.id].isNullOrEmpty() && library.exercises.none { existing ->
                ProgramImport.key(existing.name) == ProgramImport.key(it.name)
            } }
            if (missing.isNotEmpty()) OutlinedButton(onClick = {
                missing.forEach { selections[it.id] = NEW_EXERCISE }
            }, modifier = Modifier.testTag("create_missing_exercises")) { Text("Создать упражнения без совпадений") }
            val labels = Load.entries.associateWith { loadText(it) }
            if (missing.isNotEmpty()) Muted("Тип каждого нового упражнения можно уточнить в сопоставлении.")
            exercises.forEachIndexed { index, exercise ->
                Text(exercise.name)
                val items = catalog.map { it.id }
                Choice(chosen[exercise.id].orEmpty(), listOf("", NEW_EXERCISE) + items, { id ->
                    when (id) {
                        "" -> "Выбрать упражнение"
                        NEW_EXERCISE -> "Создать «${exercise.name}»"
                        else -> library.exercises.find { it.id == id }?.name ?: "Упражнение недоступно"
                    }
                }, { id -> selections[exercise.id] = id }, "match_program_$index")
                if (chosen[exercise.id] == NEW_EXERCISE) Choice(loadChoices[exercise.id] ?: exercise.load,
                    Load.entries, { labels.getValue(it) }, { loads[exercise.id] = it }, "import_load_$index")
            }
            val review = reviewed?.getOrNull()?.takeIf { exercises.none { chosen[it.id].isNullOrEmpty() } &&
                it.program.importSource?.fingerprint == inputDigest && it.current == current &&
                it.program.importSource.exerciseLinks.all { (key, value) -> chosen[key] == value ||
                    chosen[key] == NEW_EXERCISE && value == key } && it.newExercises.all { ex ->
                        loadChoices[ex.id]?.let { chosenLoad -> chosenLoad == ex.load } ?: true
                    } }
            Button(onClick = { review?.let(save) }, enabled = review != null, modifier = Modifier.fillMaxWidth().testTag("confirm_program_import")) {
                Text(if (current == null) "Импортировать программу" else "Подтвердить обновление")
            }
        }
    }
}

@Composable
private fun ImportChanges(previous: Program, incoming: Program) {
    val before = previous.days.associateBy { it.id }
    val after = incoming.days.associateBy { it.id }
    val added = after.filterKeys { it !in before }.values.map { it.title }
    val removed = before.filterKeys { it !in after }.values.map { it.title }
    fun WorkoutTemplate.content() = copy(exercises = exercises.map { plan ->
        plan.copy(exercise = Exercise(id = "", name = plan.exercise.name))
    })
    val edited = incoming.days.filter { day -> before[day.id]?.let { it.content() != day.content() } == true }.map { it.title }
    SectionTitle("Изменения шаблона")
    if (previous.importTemplateFingerprint() == incoming.importTemplateFingerprint()) {
        Muted(if (incoming.importSource?.fingerprint != null &&
            previous.importSource?.fingerprint != incoming.importSource.fingerprint) "Обновился исходный текст."
            else "Изменений в шаблоне нет.")
        return
    }
    if (added.isNotEmpty()) Muted("Добавлены · ${added.joinToString()}")
    if (removed.isNotEmpty()) Muted("Убраны · ${removed.joinToString()}")
    if (edited.isNotEmpty()) Muted("Изменены · ${edited.joinToString()}")
    if (previous.days.map { it.id } != incoming.days.map { it.id }) Muted("Порядок · " + incoming.days.joinToString(" → ") { it.title })
    if (previous.cycle != incoming.cycle || previous.completionCycle?.copy(extensions = emptyMap()) !=
        incoming.completionCycle?.copy(extensions = emptyMap())) Muted("Изменилось расписание или периодизация.")
    if (previous.description != incoming.description || previous.caution != incoming.caution) Muted("Изменились указания программы.")
}
