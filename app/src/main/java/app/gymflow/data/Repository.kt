package app.gymflow.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.AtomicFile
import android.util.LruCache
import android.util.Log
import java.time.DateTimeException
import androidx.core.net.toUri
import androidx.room.withTransaction
import app.gymflow.domain.*
import java.io.IOException
import java.io.File
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class SyncStatus(val checking: Boolean = false, val issues: List<String> = emptyList())

data class Library(
    val scope: String = "",
    val records: List<Record> = emptyList(),
    val documents: List<Document> = emptyList(),
    val loaded: Boolean = false,
    val unreadable: Int = 0,
) {
    val sessions = documents.filterIsInstance<Session>().sortedWith(newestSessionFirst)
    val programs = documents.filterIsInstance<Program>().sortedBy { it.title }
    val profiles = documents.filterIsInstance<Profile>().sortedBy { it.name }
    val exercises = documents.filterIsInstance<Exercise>().sortedBy { it.name }
    val availableProfiles = profiles.filterNot { it.archived }
    val policies = documents.filterIsInstance<ProgressionPolicy>()
    val reviews = documents.filterIsInstance<ProgressionReview>().sortedByDescending { it.date }

    val activeSessions = sessions.filter { it.status == SessionStatus.ACTIVE }.sortedByDescending { it.started }
    fun active() = activeSessions.firstOrNull()
}

@OptIn(ExperimentalCoroutinesApi::class)
class Repository(private val context: Context, private val journal: Journal, val settings: Settings) {
    private val records = journal.records()
    private val lock = Mutex()
    private val syncStatus = MutableStateFlow(SyncStatus())
    val status = syncStatus.asStateFlow()

    val library = settings.values.map { it.folder }.distinctUntilChanged().flatMapLatest { scope ->
        flow {
            var cache = emptyMap<String, Pair<String, Document?>>()
            records.observe(scope).collect { rows ->
                cache = rows.associate { row ->
                    val parsed = cache[row.id]?.takeIf { it.first == row.markdown } ?: (row.markdown to decodedOrNull(row))
                    row.id to parsed
                }
                val available = cache.values.mapNotNull { it.second }
                emit(Library(scope, rows, available, loaded = true, unreadable = rows.size - available.size))
            }
        }
    }.catch { failure ->
        if (failure is kotlinx.coroutines.CancellationException) throw failure
        Log.e("GymFlow", "library_error category=${failure.javaClass.simpleName}")
        syncStatus.value = SyncStatus(issues = listOf("Журнал недоступен. Откройте приложение повторно или проверьте хранилище."))
    }.flowOn(Dispatchers.Default)

    private data class Parsed(val markdown: String, val document: MarkdownDocument)
    private val decodedRecords = object : LruCache<Pair<String, String>, Parsed>(8 * 1024 * 1024) {
        override fun sizeOf(key: Pair<String, String>, value: Parsed) = value.markdown.length.coerceAtLeast(1)
    }
    private fun parsed(row: Record): MarkdownDocument {
        val key = row.scope to row.id
        decodedRecords.get(key)?.takeIf { it.markdown == row.markdown }?.let { return it.document }
        val document = requireNotNull(Markdown.read(row.markdown))
        decodedRecords.put(key, Parsed(row.markdown, document))
        return document
    }
    private fun decoded(row: Record) = parsed(row).document
    private fun decodedOrNull(row: Record): Document? = try {
        decoded(row)
    } catch (error: IllegalArgumentException) {
        Log.w("GymFlow", "document_parse_error category=${error.javaClass.simpleName}")
        null
    } catch (error: DateTimeException) {
        Log.w("GymFlow", "document_parse_error category=${error.javaClass.simpleName}")
        null
    }
    private fun folder(scope: String) = SafFolder(context, scope.toUri())
    private val locations = listOf("Тренировки", "Программы", "Упражнения", "Участники", "Прогрессия")
    private fun location(doc: Document) = when (doc) {
        is Session -> locations[0]
        is Program -> locations[1]
        is Exercise -> locations[2]
        is Profile -> locations[3]
        is ProgressionPolicy, is ProgressionReview -> locations[4]
    }

    private suspend fun <T> exclusive(action: suspend () -> T): T = withContext(Dispatchers.IO) {
        lock.withLock { action() }
    }

    suspend fun connect(uri: Uri) {
        exclusive {
            val scope = uri.toString()
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            val old = settings.values.first().folder
            if (old.isBlank() && records.folders().isEmpty()) {
                journal.withTransaction {
                    records.all("").forEach { records.put(it.copy(scope = scope)) }
                }
            }
            settings.selectFolder(scope)
        }
        refresh()
    }

    private fun migrationFolder(scope: String) = File(context.filesDir, "markdown-backups/" + digest(scope))

    private fun preserveLegacy(scope: String, id: String, raw: String) {
        val version = Markdown.schemaVersion(raw) ?: return
        if (version >= Markdown.schema) return
        val dir = migrationFolder(scope)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Не удалось создать папку для копии до миграции")
        val file = AtomicFile(File(dir, "$id-${digest(raw)}.md"))
        if (file.baseFile.isFile) return
        val stream = file.startWrite()
        try {
            stream.write(raw.toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (e: IOException) {
            file.failWrite(stream)
            throw e
        }
    }

    private suspend fun stage(scope: String, document: Document): Boolean {
        val old = records.get(scope, document.id)
        val parsed = old?.let(::parsed)
        val markdown = Markdown.write(document, parsed?.freeText.orEmpty(), parsed?.extraYaml.orEmpty())
        require(markdown.toByteArray(Charsets.UTF_8).size <= maxDocumentBytes) { "Файл превышает 512 КиБ" }
        if (old?.markdown == markdown) return false
        old?.let { preserveLegacy(scope, document.id, it.markdown) }
        records.put(old?.copy(markdown = markdown, pending = true) ?: Record(scope, document.id, markdown))
        return true
    }

    suspend fun save(document: Document) = exclusive {
        val scope = settings.values.first().folder
        journal.withTransaction { stage(scope, document) }
    }

    suspend fun update(change: (Library) -> List<Document>) = exclusive {
        val scope = settings.values.first().folder
        val changed = journal.withTransaction {
            val rows = records.all(scope)
            val library = Library(scope, rows, rows.mapNotNull(::decodedOrNull))
            change(library).distinctBy { it.id }.filter { stage(scope, it) }
        }
        changed.map { it.id }.toSet()
    }

    suspend fun editSession(id: String, extras: List<Document> = emptyList(), edit: (Session) -> Session) = exclusive {
        val scope = settings.values.first().folder
        journal.withTransaction {
            val session = records.get(scope, id)?.let(::decoded) as? Session ?: error("Тренировка не найдена")
            check(!session.status.let { it == SessionStatus.COMPLETED || it == SessionStatus.SKIPPED }) {
                "Тренировка закрыта"
            }
            val after = edit(session)
            require(after.id == session.id)
            val groupClosed = after.status == SessionStatus.COMPLETED && session.status != SessionStatus.COMPLETED
            val people = after.workouts.filter { workout ->
                (groupClosed || workout.status == WorkoutStatus.COMPLETED) &&
                    session.workout(workout.profile.id)?.status != WorkoutStatus.COMPLETED
            }.map { it.profile.id }.toSet()
            val reviews = progressionReviews(scope, after, people)
            (extras + after + reviews).filter { stage(scope, it) }.map { it.id }.toSet()
        }
    }

    suspend fun correctResult(sessionId: String, workoutId: String, exerciseId: String, original: SetEntry?, result: SetEntry) = exclusive {
        val scope = settings.values.first().folder
        journal.withTransaction {
            val session = records.get(scope, sessionId)?.let(::decoded) as? Session ?: error("Тренировка не найдена")
            val workout = session.allWorkouts.firstOrNull { it.id == workoutId } ?: error("Тренировка не найдена")
            check(workout in session.completedWorkouts(workout.profile.id)) { "Тренировка ещё не завершена" }
            val after = session.editWorkoutById(workoutId) { row -> row.recordCorrection(exerciseId, original, result) }
            (listOf(after) + progressionReviews(scope, after, setOf(workout.profile.id))).forEach { stage(scope, it) }
        }
    }

    private suspend fun progressionReviews(scope: String, after: Session, people: Set<String>): List<ProgressionReview> {
        if (people.isEmpty()) return emptyList()
        val docs = records.all(scope).mapNotNull(::decodedOrNull)
        val previous = docs.filterIsInstance<Session>().filter { it.id != after.id && newestSessionFirst.compare(it, after) >= 0 }
        val index = ProgressionIndex(previous + after)
        val engine = ProgressionEngine(index, docs.filterIsInstance<ProgressionPolicy>())
        val programs = docs.filterIsInstance<Program>()
        return people.flatMap { person ->
            after.completedWorkouts(person).flatMap { workout ->
                val program = workoutProgram(programs, after, workout)?.id ?: after.programId
                workout.plans().map { engine.decide(person, program, it) }
            }.chunked(8).mapIndexed { part, decisions ->
                ProgressionReview(after.id, person, after.date, decisions, part)
            }
        }
    }

    suspend fun editDocument(id: String, transform: (Document) -> Document) = exclusive {
        val scope = settings.values.first().folder
        val original = records.get(scope, id)?.let(::decoded) ?: error("Запись не найдена")
        val replacement = transform(original)
        require(replacement.id == id)
        if (stage(scope, replacement)) setOf(id) else emptySet()
    }

    suspend fun schedule(today: LocalDate = LocalDate.now()) {
        val primary = settings.values.first().athlete
        update { library ->
            val fallback = library.availableProfiles.find { it.id == primary } ?: library.availableProfiles.firstOrNull()
            val additions = mutableListOf<Document>()
            val self = fallback ?: Profile(name = "Я")
            val sessionIds = library.sessions.map { it.id }.toHashSet()
            val catalog = library.exercises.associateBy { it.id }
            library.programs.forEach { program ->
                val selected = if (program.participantIds.isEmpty()) listOf(self) else
                    program.participantIds.mapNotNull { id -> library.availableProfiles.find { it.id == id } }
                if (selected.isEmpty()) return@forEach
                plannedOn(program, today).filter { it.exercises.isNotEmpty() }.forEach { day ->
                    val duplicate = library.sessions.any {
                        it.programId == program.id && it.programDayId == day.id && it.date == today.toString()
                    }
                    if (!duplicate) {
                        val exercises = day.exercises.map { plan ->
                            val renamed = catalog[plan.exercise.id]?.name ?: plan.exercise.name
                            plan.copy(exercise = plan.exercise.copy(name = renamed))
                        }
                        val missing = selected.filterNot { profile ->
                            library.sessions.any { session -> session.date == today.toString() && session.allWorkouts.any {
                                it.profile.id == profile.id && session.templateOf(it) == day.id
                            } }
                        }
                        if (missing.isNotEmpty()) {
                            val session = scheduledSession(program, day.copy(exercises = exercises), today, missing)
                            if (sessionIds.add(session.id)) additions.add(session)
                        }
                    }
                }
            }
            if (additions.isNotEmpty() && fallback == null) additions.add(self)
            additions
        }
    }

    suspend fun maintain() {
        refresh()
        schedule()
        flush()
    }

    private data class External(val node: Node, val markdown: String, val document: Document)

    suspend fun refresh() = exclusive {
        val scope = settings.values.first().folder
        if (scope.isBlank()) return@exclusive
        syncStatus.value = SyncStatus(checking = true)
        val issues = mutableListOf<String>()
        try {
            val folder = folder(scope)
            val root = folder.children(folder.root)
            val nodes = root + root.filter { it.folder && it.name in locations }.flatMap { folder.children(it.id) }
            val existing = records.all(scope).associateBy { it.id }
            val sources = existing.values.mapNotNull { row -> row.source?.let { it to row } }.toMap()
            val unreadable = mutableSetOf<String>()
            val preparedEmpty = mutableSetOf<String>()
            val remote = nodes.filter { !it.folder && it.name.endsWith(".md", ignoreCase = true) }.mapNotNull { node ->
                try {
                    val text = folder.read(node.id)
                    val cached = sources[node.id]?.takeIf { it.markdown == text }
                    val document = if (cached != null) decoded(cached) else Markdown.read(text)?.document
                    if (document == null && node.id in sources) {
                        val record = sources.getValue(node.id)
                        if (text.isEmpty() && record.expected == null && record.inFlight != null) preparedEmpty.add(node.id)
                        else unreadable.add(node.id)
                    }
                    document?.let { External(node, text, it) }
                } catch (failure: Exception) {
                    if (failure is kotlinx.coroutines.CancellationException) throw failure
                    unreadable.add(node.id)
                    if (issues.size < 12) issues.add("${node.name}: ${failure.message}")
                    null
                }
            }.groupBy { it.document.id }
            journal.withTransaction {
                remote.forEach { (id, files) ->
                    val record = existing[id]
                    if (files.size != 1) {
                        val message = "Несколько файлов с одинаковым ID: $id"
                        issues.add(message)
                        if (record != null) records.put(record.copy(conflict = message))
                        return@forEach
                    }
                    val ext = files.single()
                    val hash = digest(ext.markdown)
                    val next = when {
                        record == null -> Record(scope, id, ext.markdown, false, hash, ext.node.id)
                        record.markdown == ext.markdown ->
                            record.copy(pending = false, expected = hash, source = ext.node.id, inFlight = null, conflict = null)
                        record.pending && (hash == record.expected || hash == record.inFlight) ->
                            record.copy(expected = hash, source = ext.node.id, inFlight = null, conflict = null)
                        record.pending -> record.copy(source = ext.node.id, conflict = "Файл изменён вне GymFlow")
                        else -> record.copy(markdown = ext.markdown, expected = hash,
                            source = ext.node.id, backup = record.markdown, conflict = null)
                    }
                    if (record != next) records.put(next)
                }
                existing.values.filter { it.id !in remote && it.source != null }.forEach { row ->
                    val source = row.source ?: return@forEach
                    if (source !in preparedEmpty) {
                        records.put(row.copy(conflict = if (source in unreadable) "Файл повреждён или заменён" else "Файл исчез из папки"))
                    }
                }
            }
        } catch (failure: Exception) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            Log.w("GymFlow", "saf_read_error category=${failure.javaClass.simpleName}")
            issues.add(failure.message ?: "Папка недоступна")
        } finally {
            syncStatus.value = SyncStatus(issues = issues)
        }
    }

    suspend fun flush(ids: Set<String>? = null) = exclusive {
        val scope = settings.values.first().folder
        if (scope.isBlank()) return@exclusive
        val pending = records.pending(scope).filter { it.conflict == null && (ids == null || it.id in ids) }
        if (pending.isEmpty()) return@exclusive
        val folder = folder(scope)
        val issues = mutableListOf<String>()
        for (row in pending) {
            if (decodedOrNull(row) == null) {
                issues.add("Повреждённая запись сохранена локально. Её можно забрать через резервный архив.")
                continue
            }
            try {
                flushRow(folder, row)
            } catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                Log.w("GymFlow", "saf_write_error category=${failure.javaClass.simpleName}")
                issues.add(failure.message ?: "Запись не удалась")
                break
            }
        }
        if (issues.isNotEmpty()) syncStatus.value = SyncStatus(issues = issues)
    }

    private suspend fun flushRow(folder: SafFolder, row: Record) {
        var source = row.source
        var before: String? = null
        val desired = digest(row.markdown)
        if (source == null) {
            val name = location(decoded(row))
            val dirs = folder.children(folder.root).filter { it.name == name }
            require(dirs.size <= 1 && dirs.none { !it.folder }) { "Неоднозначная папка $name" }
            val directory = dirs.singleOrNull()?.id ?: folder.create(folder.root, name, folder = true)
            val fileName = "${row.id}.md"
            val files = folder.children(directory).filter { it.name == fileName }
            if (files.isNotEmpty()) {
                records.put(row.copy(source = files.singleOrNull()?.takeUnless { it.folder }?.id,
                    conflict = "Имя файла уже занято: $fileName"))
                return
            }
            source = folder.create(directory, fileName)
            records.put(row.copy(source = source, inFlight = desired))
            before = ""
        }
        val current = folder.read(source)
        val hash = digest(current)
        if (hash == desired) {
            records.put(row.copy(pending = false, expected = hash, source = source, inFlight = null, conflict = null))
            return
        }
        val emptyNew = row.expected == null && current.isEmpty() && (row.inFlight != null || before != null)
        if (!emptyNew && hash != row.expected && hash != row.inFlight) {
            records.put(row.copy(source = source, conflict = "Файл изменён или запись прервалась"))
            return
        }
        val prepared = row.copy(source = source, inFlight = desired,
            backup = current.takeIf(String::isNotEmpty) ?: row.backup)
        records.put(prepared)
        if (digest(folder.read(source)) != hash) {
            records.put(prepared.copy(conflict = "Файл изменился во время сохранения"))
            return
        }
        preserveLegacy(row.scope, row.id, current)
        folder.write(source, row.markdown)
        val written = digest(folder.read(source))
        if (written != desired) {
            records.put(prepared.copy(conflict = "Проверка сохранённого файла не пройдена"))
            throw IOException("Провайдер вернул неполную запись")
        }
        records.put(prepared.copy(expected = written, pending = false, inFlight = null, conflict = null))
    }

    suspend fun resolve(id: String, keepLocal: Boolean) = exclusive {
        val scope = settings.values.first().folder
        val row = records.get(scope, id) ?: return@exclusive
        check(row.conflict != null)
        val folder = folder(scope)
        val roots = folder.children(folder.root)
        val all = roots + roots.filter { it.folder && it.name in locations }.flatMap { folder.children(it.id) }
        val node = all.singleOrNull { !it.folder && it.id == row.source }
        val matches = all.filter { !it.folder && it.name.endsWith(".md", true) }.filter {
            try {
                Markdown.read(folder.read(it.id))?.document?.id == id
            } catch (_: IllegalArgumentException) {
                false
            } catch (_: IOException) {
                false
            }
        }
        check(matches.size <= 1) { "Уберите дублирующий файл из папки и обновите данные" }
        val source = node?.id ?: matches.singleOrNull()?.id
        val raw = source?.let(folder::read)
        if (keepLocal) {
            val external = raw?.let { runCatching { Markdown.read(it) }.getOrNull() }
            check(external == null || external.document.id == id) { "Файл принадлежит другой записи" }
            val notes = external?.freeText?.takeIf { it.isNotBlank() }
            val local = requireNotNull(Markdown.read(row.markdown))
            val mergedNotes = listOfNotNull(local.freeText.takeIf { it.isNotBlank() }, notes).distinct().joinToString("\n\n")
            val text = Markdown.write(local.document, mergedNotes, external?.extraYaml.orEmpty() + local.extraYaml)
            require(text.toByteArray(Charsets.UTF_8).size <= maxDocumentBytes) { "Файл превышает 512 КиБ" }
            val next = row.copy(markdown = text, pending = true,
                source = source, expected = raw?.let(::digest), backup = raw ?: row.backup, inFlight = null, conflict = null)
            records.put(next)
            flushRow(folder, next)
        } else {
            val remote = requireNotNull(raw?.let(Markdown::read)) { "Внешняя версия недоступна" }
            check(remote.document.id == id)
            records.put(row.copy(markdown = raw, source = source, expected = digest(raw), backup = row.markdown,
                pending = false, inFlight = null, conflict = null))
        }
    }

    suspend fun export(uri: Uri, backups: Boolean) = exclusive {
        val scope = settings.values.first().folder
        val rows = records.all(scope)
        val stream = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Экспорт недоступен")
        ZipOutputStream(stream).use { zip ->
            if (backups) migrationFolder(scope).listFiles().orEmpty().filter { it.extension == "md" }.forEach { file ->
                zip.putNextEntry(ZipEntry("До миграции/${file.name}"))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            rows.forEach { row ->
                val document = decodedOrNull(row)
                val path = if (document == null) "Нечитаемые/${digest(row.id)}.md" else location(document) + "/" + row.id + ".md"
                zip.putNextEntry(ZipEntry(path))
                zip.write(row.markdown.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                if (backups && row.backup != null) {
                    zip.putNextEntry(ZipEntry("Резервные копии/$path"))
                    zip.write(row.backup.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
            }
        }
    }
}
