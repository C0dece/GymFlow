@file:OptIn(ExperimentalLayoutApi::class)

package app.gymflow.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gymflow.GymModel
import app.gymflow.data.Library
import app.gymflow.domain.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private data class NotePreview(
    val id: String,
    val title: String,
    val body: String,
    val created: String,
    val updated: String,
    val context: String,
    val sessionId: String? = null,
)

private enum class NoteFilter(val label: String) { ALL("Все"), SPORT("Личные"), WORKOUT("Тренировки") }
private enum class NoteOrder(val label: String) { UPDATED("По изменению"), CREATED("По созданию"), TITLE("По названию") }

@Composable
fun NotesPage(library: Library, model: GymModel) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(NoteFilter.ALL) }
    var order by rememberSaveable { mutableStateOf(NoteOrder.UPDATED) }
    var editId by rememberSaveable(library.scope) { mutableStateOf<String?>(null) }
    var workoutId by rememberSaveable(library.scope) { mutableStateOf<String?>(null) }
    val previews = remember(library.documents, query, filter, order) {
        val programs = library.programs.associateBy { it.id }
        val sessions = library.sessions.associateBy { it.id }
        val sport = if (filter == NoteFilter.WORKOUT) emptyList() else library.notes.map { note ->
            val context = listOfNotNull(sessions[note.sessionId]?.title, programs[note.programId]?.title).joinToString(" · ")
            NotePreview(note.id, note.title, note.body, note.created, note.updated, context)
        }
        val workouts = if (filter == NoteFilter.SPORT) emptyList() else library.sessions.flatMap { session ->
            session.workoutNotes.map { note ->
                NotePreview(note.id, session.title, note.body, note.created, note.updated, shortDate(session.date), session.id)
            }
        }
        val comparator = when (order) {
            NoteOrder.UPDATED -> compareByDescending<NotePreview> { Instant.parse(it.updated) }
            NoteOrder.CREATED -> compareByDescending { Instant.parse(it.created) }
            NoteOrder.TITLE -> compareBy { it.title.lowercase() }
        }
        (sport + workouts).filter { note ->
            val text = query.trim()
            listOf(note.title, note.body, note.context).any { it.contains(text, ignoreCase = true) }
        }.sortedWith(comparator.thenBy { it.id })
    }
    LazyColumn(
        Modifier.fillMaxSize().testTag("notes_page"),
        contentPadding = PaddingValues(18.dp, 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(query, "Поиск заметок", { query = it }, Modifier.testTag("notes_search"))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NoteFilter.entries.forEach {
                        FilterChip(filter == it, { filter = it }, label = { Text(it.label) })
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { workoutId = null; editId = newId() }, modifier = Modifier.testTag("new_sport_note")) { Text("Новая заметка") }
                    NoteChoice(order.label, NoteOrder.entries.map { it to it.label }) { order = it }
                }
            }
        }
        if (previews.isEmpty()) item {
            Muted(if (query.isBlank()) "Здесь будут личные заметки и записи из тренировок." else "Заметок по запросу нет.")
        }
        items(previews, key = { "${it.sessionId.orEmpty()}/${it.id}" }) { note ->
            CardBlock(Modifier.testTag("note_card_${note.id}"), onClick = {
                workoutId = note.sessionId
                editId = note.id
            }) {
                Text(note.title, style = MaterialTheme.typography.titleMedium)
                if (note.body.isNotBlank()) Text(note.body, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Muted(listOf(note.context, noteTime(note.updated)).filter(String::isNotBlank).joinToString(" · "))
            }
        }
    }
    editId?.let { id ->
        key(workoutId, id) {
            val session = library.sessions.find { it.id == workoutId }
            val close = { editId = null; workoutId = null }
            if (session != null) WorkoutNoteEditor(id, session, library, model, close)
            else if (workoutId == null) SportNoteEditor(id, library, model, close)
        }
    }
}

@Composable
private fun SportNoteEditor(id: String, library: Library, model: GymModel, close: () -> Unit) {
    var previous by remember { mutableStateOf(library.notes.find { it.id == id }) }
    val initial = remember { previous ?: SportNote(id = id) }
    var title by rememberSaveable { mutableStateOf(initial.title) }
    var body by rememberSaveable { mutableStateOf(initial.body) }
    var sessionId by rememberSaveable { mutableStateOf(initial.sessionId) }
    var programId by rememberSaveable { mutableStateOf(initial.programId) }
    var link by rememberSaveable { mutableStateOf<String?>(null) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var saving by remember { mutableIntStateOf(0) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    var deleteInProgress by remember { mutableStateOf(false) }
    var discard by rememberSaveable { mutableStateOf(false) }
    fun finish() {
        if (saving > 0) return
        if (error != null || !validNoteBody(body)) discard = true else close()
    }

    fun persist(deleted: Boolean = false) {
        if (!deleted && !validNoteBody(body)) return
        val current = previous
        val next = initial.copy(
            title = title.trim().ifEmpty { "Новая заметка" },
            body = if (deleted) "" else body,
            sessionId = sessionId,
            programId = programId,
            updated = updatedNow(current?.updated ?: initial.created),
            deleted = deleted,
        )
        previous = next
        saving++
        model.saveNote(library.scope, current, next) { failure ->
            saving--
            if (failure != null) {
                if (previous == next) previous = current
                error = failure
            } else if (deleted) close() else error = null
            deleteInProgress = false
        }
    }

    val picker = link
    BackHandler(picker != null) { link = null }
    EditorSheet(if (picker == null) "Личная заметка" else "Выберите ${if (picker == "session") "тренировку" else "программу"}",
        { if (picker != null) link = null else finish() }) {
        if (picker != null) {
            val choices = if (picker == "session") library.sessions.map { it.id to "${shortDate(it.date)} · ${it.title}" }
                else library.programs.map { it.id to it.title }
            LinkPicker(choices, { link = null }) { selected ->
                if (picker == "session") sessionId = selected else programId = selected
                link = null
                persist()
            }
        } else {
            Field(title, "Название", { title = it.take(160); persist() }, Modifier.testTag("sport_note_title"))
            NoteBody(body, { body = it.replace("\r\n", "\n"); persist() })
            val session = library.sessions.find { it.id == sessionId }
            val program = library.programs.find { it.id == programId }
            OutlinedButton(onClick = { link = "session" }, modifier = Modifier.fillMaxWidth()) {
                Text(if (sessionId == null) "Привязать тренировку" else session?.title ?: "Тренировка недоступна",
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            OutlinedButton(onClick = { link = "program" }, modifier = Modifier.fillMaxWidth()) {
                Text(if (programId == null) "Привязать программу" else program?.title ?: "Программа недоступна",
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            NoteState(error, saving, previous != null, body)
            Button(onClick = ::finish, enabled = saving == 0, modifier = Modifier.fillMaxWidth()) { Text("Готово") }
            if (previous != null) TextButton(onClick = { deleting = true }, enabled = !deleteInProgress) { Text("Удалить заметку") }
        }
    }
    if (deleting) DeleteNoteDialog({ deleting = false }) {
        deleting = false
        deleteInProgress = true
        persist(deleted = true)
    }
    if (discard) DiscardNoteDialog({ discard = false }, close)
}

@Composable
fun WorkoutNotesSheet(session: Session, library: Library, model: GymModel, close: () -> Unit) {
    var editing by rememberSaveable(session.id) { mutableStateOf<String?>(null) }
    val activeId = editing
    if (activeId == null) {
        EditorSheet("Заметки к тренировке", close) {
            Text(session.title, style = MaterialTheme.typography.titleMedium)
            Button(onClick = { editing = newId() }, modifier = Modifier.fillMaxWidth().testTag("new_workout_note")) { Text("Добавить запись") }
            if (session.notes.isNotBlank()) Muted(session.notes)
            library.workoutComments[session.id]?.takeIf { it.isNotBlank() }?.let { Muted(it) }
            session.workoutNotes.sortedByDescending { Instant.parse(it.created) }.forEach { note ->
                key(note.id) {
                    CardBlock(onClick = { editing = note.id }) {
                        Text(noteTime(note.created), style = MaterialTheme.typography.titleMedium)
                        Text(note.body.ifBlank { "Пустая запись" }, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    } else key(activeId) {
        WorkoutNoteEditor(activeId, session, library, model) { editing = null }
    }
}

@Composable
private fun WorkoutNoteEditor(id: String, session: Session, library: Library, model: GymModel, close: () -> Unit) {
    var previous by remember { mutableStateOf(session.workoutNotes.find { it.id == id }) }
    val initial = remember { previous ?: WorkoutNote(id = id) }
    var body by rememberSaveable { mutableStateOf(initial.body) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var saving by remember { mutableIntStateOf(0) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    var discard by rememberSaveable { mutableStateOf(false) }
    fun finish() {
        if (saving > 0) return
        if (error != null || !validNoteBody(body)) discard = true else close()
    }

    fun persist(deleted: Boolean = false) {
        if (!deleted && !validNoteBody(body)) return
        val current = previous
        val next = if (deleted) null else initial.copy(body = body, updated = updatedNow(current?.updated ?: initial.created))
        if (current == null && next == null) return
        previous = next
        saving++
        model.saveWorkoutNote(library.scope, session.id, current, next) { failure ->
            saving--
            if (failure != null) {
                if (previous == next) previous = current
                error = failure
            } else if (deleted) close() else error = null
        }
    }

    EditorSheet("Заметка к тренировке", ::finish) {
        Muted(noteTime(initial.created))
        NoteBody(body, { body = it.replace("\r\n", "\n"); persist() })
        NoteState(error, saving, previous != null, body)
        Button(onClick = ::finish, enabled = saving == 0, modifier = Modifier.fillMaxWidth().testTag("finish_note")) { Text("Готово") }
        if (previous != null) TextButton(onClick = { deleting = true }, enabled = saving == 0) { Text("Удалить заметку") }
    }
    if (deleting) DeleteNoteDialog({ deleting = false }) { deleting = false; persist(deleted = true) }
    if (discard) DiscardNoteDialog({ discard = false }, close)
}

@Composable
private fun NoteBody(body: String, change: (String) -> Unit) {
    OutlinedTextField(
        body, change, Modifier.fillMaxWidth().testTag("note_body"),
        label = { Text("Текст Markdown") }, minLines = 3, maxLines = 9,
        isError = !validNoteBody(body),
    )
}

@Composable
private fun NoteState(error: String?, saving: Int, saved: Boolean, body: String) {
    when {
        !validNoteBody(body) -> InlineStatus("Сократите текст до 32 000 символов или уберите служебные маркеры GymFlow.", true)
        error != null -> InlineStatus(error, true)
        saving > 0 -> Muted("Сохраняется…")
        saved -> Muted("Сохранено на устройстве")
    }
}

@Composable
private fun NoteChoice(label: String, choices: List<Pair<NoteOrder, String>>, choose: (NoteOrder) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text(label) }
        DropdownMenu(expanded, { expanded = false }) {
            choices.forEach { (value, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { expanded = false; choose(value) })
            }
        }
    }
}

@Composable
private fun LinkPicker(choices: List<Pair<String, String>>, back: () -> Unit, choose: (String?) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    Field(query, "Поиск", { query = it })
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = back) { Text("Назад") }
        TextButton(onClick = { choose(null) }) { Text("Убрать привязку") }
    }
    val matches = choices.filter { it.second.contains(query.trim(), ignoreCase = true) }
    matches.take(30).forEach { (id, title) ->
        OutlinedButton(onClick = { choose(id) }, modifier = Modifier.fillMaxWidth().testTag("note_link_$id")) { Text(title) }
    }
    if (matches.size > 30) Muted("Уточните поиск, чтобы увидеть остальные результаты.")
}

@Composable
private fun DeleteNoteDialog(close: () -> Unit, delete: () -> Unit) {
    AlertDialog(
        onDismissRequest = close, title = { Text("Удалить заметку?") },
        confirmButton = { Button(onClick = delete) { Text("Удалить") } },
        dismissButton = { TextButton(onClick = close) { Text("Отмена") } },
    )
}

@Composable
private fun DiscardNoteDialog(close: () -> Unit, discard: () -> Unit) {
    AlertDialog(
        onDismissRequest = close, title = { Text("Отбросить несохранённые правки?") },
        confirmButton = { TextButton(onClick = discard) { Text("Отбросить") } },
        dismissButton = { Button(onClick = close) { Text("Продолжить правку") } },
    )
}

private fun validNoteBody(body: String) = body.length <= 32_000 &&
    listOf("<!-- gymflow:", "<!-- /gymflow:", "gymflow:json -->").none { it in body }

private val noteDateTime = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
private fun noteTime(value: String) = Instant.parse(value).atZone(ZoneId.systemDefault()).format(noteDateTime)
private fun updatedNow(previous: String) = maxOf(Instant.now(), Instant.parse(previous)).toString()
