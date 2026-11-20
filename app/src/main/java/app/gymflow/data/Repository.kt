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
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class SyncStatus(val checking: Boolean = false, val issues: List<String> = emptyList())
const val missingFile = "Файл исчез из папки"

data class Library(
    val scope: String = "",
    val records: List<Record> = emptyList(),
    val documents: List<Document> = emptyList(),
    val loaded: Boolean = false,
    val unreadable: Int = 0,
    val workoutComments: Map<String, String> = emptyMap(),
) {
    val sessions = documents.filterIsInstance<Session>().sortedWith(newestSessionFirst)
    val programs = documents.filterIsInstance<Program>().sortedBy { it.title }
    val profiles = documents.filterIsInstance<Profile>().sortedBy { it.name }
    val exercises = documents.filterIsInstance<Exercise>().sortedBy { it.name }
    val availableProfiles = profiles.filterNot { it.archived }
    val families = documents.filterIsInstance<MovementFamily>().sortedBy { it.name }
    val movementTracks = documents.filterIsInstance<MovementTrack>()
    val skills = documents.filterIsInstance<MovementSkill>().sortedBy { it.name }
    val attempts = documents.filterIsInstance<SkillAttempt>().sortedByDescending { it.date }
    val policies = documents.filterIsInstance<ProgressionPolicy>()
    val reviews = documents.filterIsInstance<ProgressionReview>().sortedByDescending { it.date }
    val notes = documents.filterIsInstance<SportNote>().filterNot { it.deleted }

    val activeSessions = sessions.filter { it.status == SessionStatus.ACTIVE }.sortedByDescending { it.started }
    fun active() = activeSessions.firstOrNull()
}

@OptIn(ExperimentalCoroutinesApi::class)
class Repository(private val context: Context, private val journal: Journal, val settings: Settings) {
    private val records = journal.records()
    private val lock = Mutex()
    private var scannedVault: Pair<String, String>? = null
    private var duplicateIds = emptySet<String>()
    private var documentPaths = emptyMap<String, String>()
    private val syncStatus = MutableStateFlow(SyncStatus())
    val status = syncStatus.asStateFlow()

    val library = settings.values.map { it.scope }.distinctUntilChanged().flatMapLatest { scope ->
        flow {
            var cache = emptyMap<String, Pair<String, MarkdownDocument?>>()
            records.observe(scope).collect { rows ->
                cache = rows.associate { row ->
                    val parsed = cache[row.id]?.takeIf { it.first == row.markdown } ?: (row.markdown to parsedOrNull(row))
                    row.id to parsed
                }
                val available = cache.values.mapNotNull { it.second }
                val comments = available.mapNotNull {
                    if (it.document is Session && it.freeText.isNotBlank()) it.document.id to it.freeText else null
                }.toMap()
                emit(Library(scope, rows, available.map { it.document }, loaded = true,
                    unreadable = rows.size - available.size, workoutComments = comments))
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
    private fun decodedOrNull(row: Record): Document? = parsedOrNull(row)?.document
    private fun parsedOrNull(row: Record): MarkdownDocument? = try {
        parsed(row)
    } catch (error: IllegalArgumentException) {
        Log.w("GymFlow", "document_parse_error category=${error.javaClass.simpleName}")
        null
    } catch (error: DateTimeException) {
        Log.w("GymFlow", "document_parse_error category=${error.javaClass.simpleName}")
        null
    }
    private fun folder(scope: String) = SafFolder(context, scope.toUri())

    private suspend fun <T> exclusive(action: suspend () -> T): T = withContext(Dispatchers.IO) {
        lock.withLock { action() }
    }

    suspend fun connect(uri: Uri) {
        exclusive {
            val path = uri.toString()
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            val old = settings.values.first()
            val preferred = old.vaultId.takeIf { path == old.folder && it.isNotBlank() }
            val id = requireNotNull(folder(path).vaultId(create = true, preferred = preferred))
            bind(path, id, old)
        }
        refresh()
    }

    private suspend fun bind(path: String, id: String, old: Preferences) {
        val previous = when {
            old.folder.isBlank() -> ""
            old.vaultId.isBlank() && path == old.folder -> old.scope
            else -> null
        }
        if (previous != null) journal.withTransaction {
            records.all(previous).forEach { row ->
                if (records.get(id, row.id) == null) records.put(row.copy(scope = id))
            }
        }
        settings.selectFolder(path, id)
    }

    private fun verifiedFolder(prefs: Preferences): SafFolder {
        val folder = folder(prefs.folder)
        check(prefs.vaultId.isNotBlank() && folder.vaultId(create = false) == prefs.vaultId) {
            "Папка GymFlow недоступна или её идентификатор изменился. Выберите папку повторно."
        }
        return folder
    }

    private fun managedNodes(folder: SafFolder): List<Node> {
        val root = folder.children(folder.root)
        val result = root.toMutableList()
        val queue = ArrayDeque(root.filter { it.folder && !it.name.startsWith('.') })
        val visited = hashSetOf(folder.root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (!visited.add(node.id)) continue
            val children = folder.children(node.id).map { it.copy(path = "${node.path}/${it.name}") }
            result.addAll(children)
            queue.addAll(children.filter { it.folder && !it.name.startsWith('.') })
        }
        return result.distinctBy { it.id }
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
        val markdown = Markdown.rewrite(document, parsed, extraYaml = linkProperties(document, parsed?.extraYaml.orEmpty(),
            documentPaths.takeIf { scannedVault?.second == scope }.orEmpty()))
        require(markdown.toByteArray(Charsets.UTF_8).size <= maxDocumentBytes) { "Файл превышает 512 КиБ" }
        if (old?.markdown == markdown) return false
        old?.let { preserveLegacy(scope, document.id, it.markdown) }
        records.put(old?.copy(markdown = markdown, pending = true) ?: Record(scope, document.id, markdown))
        return true
    }

    suspend fun save(document: Document) = exclusive {
        val scope = settings.values.first().scope
        journal.withTransaction { stage(scope, document) }
    }

    suspend fun update(change: (Library) -> List<Document>) = exclusive {
        val scope = settings.values.first().scope
        val changed = journal.withTransaction {
            val rows = records.all(scope)
            val library = Library(scope, rows, rows.mapNotNull(::decodedOrNull))
            change(library).distinctBy { it.id }.filter { stage(scope, it) }
        }
        changed.map { it.id }.toSet()
    }

    suspend fun editSession(id: String, extras: List<Document> = emptyList(), edit: (Session) -> Session) = exclusive {
        val scope = settings.values.first().scope
        journal.withTransaction {
            val session = records.get(scope, id)?.let(::decoded) as? Session ?: error("Тренировка не найдена")
            check(!session.status.let { it == SessionStatus.COMPLETED || it == SessionStatus.SKIPPED }) {
                "Тренировка закрыта"
            }
            val edited = edit(session)
            require(edited.id == session.id)
            val after = edited.copy(workouts = edited.workouts.map(ParticipantWorkout::creditCycle))
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
        val scope = settings.values.first().scope
        journal.withTransaction {
            val session = records.get(scope, sessionId)?.let(::decoded) as? Session ?: error("Тренировка не найдена")
            val workout = session.allWorkouts.firstOrNull { it.id == workoutId } ?: error("Тренировка не найдена")
            check(workout in session.completedWorkouts(workout.profile.id)) { "Тренировка ещё не завершена" }
            val after = session.editWorkoutById(workoutId) { row -> row.recordCorrection(exerciseId, original, result).creditCycle() }
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
        val scope = settings.values.first().scope
        val original = records.get(scope, id)?.let(::decoded) ?: error("Запись не найдена")
        val replacement = transform(original)
        require(replacement.id == id)
        if (stage(scope, replacement)) setOf(id) else emptySet()
    }

    suspend fun updateDocument(scope: String, id: String, transform: (Document?) -> Document) = exclusive {
        check(settings.values.first().scope == scope) { "Папка изменилась. Откройте заметку снова." }
        val current = records.get(scope, id)?.let(::decoded)
        val next = transform(current)
        require(next.id == id)
        stage(scope, next)
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
                val cycle = program.completionCycle
                if (cycle != null) {
                    if (program.enabled && today.toString() >= program.anchor && cycle.visitsOn(today)) {
                        selected.forEach { profile ->
                            val position = cyclePosition(program, profile.id, library.sessions)
                            val day = position.next
                            val workedToday = library.sessions.any { session ->
                                session.date == today.toString() && session.allWorkouts.any {
                                    it.profile.id == profile.id && it.cycleSlot?.programId == program.id &&
                                        it.status == WorkoutStatus.COMPLETED && (it.cycleCredited || it.hasCycleResult())
                                }
                            }
                            if (day != null && position.pending == null && !workedToday) {
                                val next = cycleAppointment(program, profile, day, today, position, advance = true)
                                if (sessionIds.add(next.id)) additions.add(next)
                            }
                        }
                    }
                    return@forEach
                }
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
            val needsCycleProfile = library.programs.any { it.enabled && it.completionCycle != null &&
                it.participantIds.isEmpty() && it.days.any { day -> day.orderedExercises().isNotEmpty() } }
            if (fallback == null && (additions.isNotEmpty() || needsCycleProfile)) additions.add(self)
            additions
        }
    }

    suspend fun maintain() {
        refresh()
        schedule()
        flushScanned()
    }

    private data class External(val node: Node, val markdown: String, val document: Document)

    suspend fun refresh() = exclusive {
        var prefs = settings.values.first()
        if (prefs.folder.isBlank()) return@exclusive
        syncStatus.value = SyncStatus(checking = true)
        scannedVault = null
        val issues = mutableListOf<String>()
        try {
            if (prefs.vaultId.isBlank()) {
                val id = requireNotNull(folder(prefs.folder).vaultId(create = true))
                bind(prefs.folder, id, prefs)
                prefs = prefs.copy(vaultId = id)
            }
            val scope = prefs.scope
            val folder = verifiedFolder(prefs)
            val nodes = managedNodes(folder)
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
                    val reattached = next.copy(localOnly = false)
                    if (record != reattached) records.put(reattached)
                }
                existing.values.filter { it.id !in remote && it.source != null && !it.localOnly }.forEach { row ->
                    val source = row.source ?: return@forEach
                    if (source !in preparedEmpty) {
                        val conflict = if (source in unreadable) "Файл нечитаем, повреждён или заменён" else missingFile
                        if (row.conflict != conflict) records.put(row.copy(conflict = conflict))
                    }
                }
            }
            duplicateIds = remote.filterValues { it.size > 1 }.keys
            documentPaths = remote.mapNotNull { (id, entries) -> entries.singleOrNull()?.let { id to it.node.path } }.toMap()
            scannedVault = prefs.folder to scope
        } catch (failure: Exception) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            Log.w("GymFlow", "saf_read_error category=${failure.javaClass.simpleName}")
            issues.add(failure.message ?: "Папка недоступна")
        } finally {
            syncStatus.value = SyncStatus(issues = issues)
        }
    }

    suspend fun flush(ids: Set<String>? = null) {
        val prefs = settings.values.first()
        if (prefs.folder.isNotBlank() && records.pending(prefs.scope).any {
                it.source == null && (ids == null || it.id in ids)
            }) {
            refresh()
        }
        flushScanned(ids)
    }

    private suspend fun flushScanned(ids: Set<String>? = null) = exclusive {
        val prefs = settings.values.first()
        if (prefs.folder.isBlank() || prefs.vaultId.isBlank()) return@exclusive
        val scope = prefs.scope
        if (scannedVault != (prefs.folder to scope)) return@exclusive
        val pending = records.pending(scope).filter { it.conflict == null && it.id !in duplicateIds && (ids == null || it.id in ids) }
        if (pending.isEmpty()) return@exclusive
        val folder = try {
            verifiedFolder(prefs)
        } catch (failure: Exception) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            syncStatus.value = SyncStatus(issues = listOf("Папка недоступна. Изменения сохранены в очереди."))
            return@exclusive
        }
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
                if (issues.size < 12) issues.add(failure.message ?: "Запись не удалась")
            }
        }
        if (issues.isNotEmpty()) syncStatus.value = SyncStatus(issues = issues)
    }

    private suspend fun flushRow(folder: SafFolder, row: Record) {
        var source = row.source
        var before: String? = null
        val desired = digest(row.markdown)
        if (source == null) {
            val document = decoded(row)
            val name = markdownFolder(document)
            val dirs = folder.children(folder.root).filter { it.name == name }
            require(dirs.size <= 1 && dirs.none { !it.folder }) { "Неоднозначная папка $name" }
            val directory = dirs.singleOrNull()?.id ?: folder.create(folder.root, name, folder = true)
            val context = documentLinks(document).mapNotNull { id -> records.get(row.scope, id)?.let(::decodedOrNull) }
                .associateBy(Document::id)
            val fileName = markdownName(document, folder.children(directory).map(Node::name), context)
            source = folder.create(directory, fileName)
            records.put(row.copy(source = source, inFlight = desired))
            val created = folder.children(directory).singleOrNull { it.id == source }
            if (created != null) documentPaths = documentPaths + (row.id to "$name/${created.name}")
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
        val prefs = settings.values.first()
        val scope = prefs.scope
        val row = records.get(scope, id) ?: return@exclusive
        check(row.conflict != null || row.localOnly)
        val folder = verifiedFolder(prefs)
        val all = managedNodes(folder)
        val node = all.singleOrNull { !it.folder && it.id == row.source }
        val matches = all.filter { !it.folder && it.name.endsWith(".md", true) }.filter {
            try {
                Markdown.read(folder.read(it.id))?.document?.id == id
            } catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                false
            }
        }
        check(matches.size <= 1) { "Уберите дублирующий файл из папки и обновите данные" }
        val source = matches.singleOrNull()?.id ?: node?.id
        val raw = source?.let(folder::read)
        if (keepLocal) {
            val external = raw?.let { runCatching { Markdown.read(it) }.getOrNull() }
            check(external == null || external.document.id == id) { "Файл принадлежит другой записи" }
            val local = requireNotNull(Markdown.read(row.markdown))
            val text = Markdown.rewrite(local.document, external ?: local)
            require(text.toByteArray(Charsets.UTF_8).size <= maxDocumentBytes) { "Файл превышает 512 КиБ" }
            val next = row.copy(markdown = text, pending = true, localOnly = false,
                source = source, expected = raw?.let(::digest), backup = raw ?: row.backup, inFlight = null, conflict = null)
            records.put(next)
            flushRow(folder, next)
        } else {
            val remote = requireNotNull(raw?.let(Markdown::read)) { "Внешняя версия недоступна" }
            check(remote.document.id == id)
            records.put(row.copy(markdown = raw, source = source, expected = digest(raw), backup = row.markdown,
                pending = false, inFlight = null, conflict = null, localOnly = false))
        }
    }

    suspend fun keepOnlyOnDevice(id: String) = exclusive {
        val scope = settings.values.first().scope
        val row = records.get(scope, id) ?: return@exclusive
        check(row.conflict == missingFile) { "Статус файла изменился. Обновите данные." }
        records.put(row.copy(localOnly = true, conflict = null))
    }

    suspend fun migrateVault(archive: Uri): Int {
        refresh()
        try {
            return exclusive {
                val prefs = settings.values.first()
                val scope = prefs.scope
                check(prefs.folder.isNotBlank() && scannedVault == (prefs.folder to scope)) { "Папка GymFlow недоступна" }
                check(duplicateIds.isEmpty() && syncStatus.value.issues.isEmpty()) { "Сначала устраните ошибки чтения и повторяющиеся ID" }
                val folder = verifiedFolder(prefs)
                val nodes = managedNodes(folder)
                val bySource = nodes.associateBy(Node::id)
                val names = nodes.groupBy(Node::parent).mapValues { (_, files) -> files.map(Node::name).toMutableList() }
                val rows = records.all(scope).filterNot(Record::localOnly).sortedBy(Record::id)
                check(rows.none { it.conflict != null || it.pending || it.source == null }) {
                    "Сначала завершите синхронизацию и разрешите конфликты"
                }
                val documents = rows.associate { it.id to decoded(it) }
                val targets = rows.map { row ->
                    val node = requireNotNull(bySource[row.source]) { "Файл перемещён. Обновите библиотеку." }
                    val next = if (node.name.equals("${row.id}.md", ignoreCase = true)) {
                        val occupied = names.getValue(node.parent)
                        markdownName(documents.getValue(row.id), occupied, documents).also(occupied::add)
                    } else node.name
                    Triple(row, node, next)
                }
                val paths = targets.associate { (row, node, name) ->
                    val parent = node.path.substringBeforeLast('/', "")
                    row.id to if (parent.isEmpty()) name else "$parent/$name"
                }
                val movedLinks = buildMap {
                    targets.filter { (_, node, name) -> node.name != name }.forEach { (row, node, _) ->
                        val destination = paths.getValue(row.id).dropLast(3)
                        put(node.path.dropLast(3), destination)
                        put(row.id, destination)
                    }
                }
                val changes = targets.mapNotNull { (row, node, name) ->
                    val raw = folder.read(node.id)
                    check(digest(raw) == row.expected) { "Файл изменился. Повторите синхронизацию." }
                    val parsed = parsed(row)
                    fun rewriteLinks(text: String) = migrateWikiLinks(text, movedLinks)
                    val next = when (val doc = parsed.document) {
                        is SportNote -> doc.copy(body = rewriteLinks(doc.body))
                        is Session -> doc.copy(workoutNotes = doc.workoutNotes.map { it.copy(body = rewriteLinks(it.body)) })
                        else -> doc
                    }
                    val preserved = parsed.copy(freeText = rewriteLinks(parsed.freeText),
                        legacyText = parsed.legacyText?.let(::rewriteLinks))
                    val after = Markdown.rewrite(next, preserved, extraYaml = linkProperties(next, parsed.extraYaml, paths))
                    require(after.toByteArray(Charsets.UTF_8).size <= maxDocumentBytes) { "Файл превышает 512 КиБ" }
                    check(Markdown.read(after)?.document == next) { "Не совпали данные после преобразования" }
                    if (after == raw && name == node.name) null else VaultChange(row, node, name, raw, after)
                }
                if (changes.isEmpty()) return@exclusive 0
                val resolver = context.contentResolver
                val out = resolver.openOutputStream(archive, "wt") ?: throw IOException("Архив недоступен")
                val expected = out.use { migrationBackup(it, changes) }
                val input = resolver.openInputStream(archive) ?: throw IOException("Не удалось прочитать созданный архив")
                verifyMigrationBackup(input, expected)
                changes.forEach { change ->
                    val (row, node, name, raw, after) = change
                    check(folder.read(node.id) == raw) { "Файл изменился во время миграции" }
                    if (after != raw) {
                        val pending = row.copy(markdown = after, pending = true)
                        records.put(pending)
                        flushRow(folder, pending)
                        check(records.get(scope, row.id)?.pending == false) { "Запись не подтверждена. Исходники в архиве." }
                    }
                    if (name != node.name) {
                        check(folder.children(node.parent).none { it.name.equals(name, ignoreCase = true) }) { "Имя уже занято: $name" }
                        check(folder.read(node.id) == after) { "Файл изменился во время миграции" }
                        val renamed = folder.rename(node.id, name)
                        check(folder.children(node.parent).any { it.id == renamed && it.name == name } &&
                            folder.read(renamed) == after) { "Провайдер не подтвердил новое имя файла" }
                        records.get(scope, row.id)?.let { records.put(it.copy(source = renamed)) }
                    }
                }
                changes.size
            }
        } finally {
            refresh()
        }
    }

    suspend fun export(uri: Uri, backups: Boolean) = exclusive {
        val prefs = settings.values.first()
        val scope = prefs.scope
        val rows = records.all(scope)
        val stream = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Экспорт недоступен")
        ZipOutputStream(stream).use { zip ->
            if (backups && prefs.vaultId.isNotBlank()) {
                zip.putNextEntry(ZipEntry(vaultMarker))
                zip.write(vaultMetadata(prefs.vaultId).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            if (backups) listOf(scope, prefs.folder).distinct().flatMap { migrationFolder(it).listFiles().orEmpty().toList() }
                .filter { it.extension == "md" }.distinctBy { it.name }.forEach { file ->
                zip.putNextEntry(ZipEntry("До миграции/${file.name}"))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            val docs = rows.mapNotNull(::decodedOrNull).associateBy(Document::id)
            val occupied = mutableMapOf<String, MutableList<String>>()
            rows.sortedBy(Record::id).forEach { row ->
                val document = decodedOrNull(row)
                val path = if (document == null) "Нечитаемые/${digest(row.id)}.md" else {
                    val section = markdownFolder(document)
                    val names = occupied.getOrPut(section) { mutableListOf() }
                    "$section/${markdownName(document, names, docs).also(names::add)}"
                }
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

    data class RestoreResult(val restored: Int, val different: Int)

    suspend fun restore(uri: Uri): RestoreResult {
        refresh()
        return exclusive {
            val prefs = settings.values.first()
            journal.withTransaction {
                val stream = context.contentResolver.openInputStream(uri) ?: throw IOException("Архив недоступен")
                val ids = hashSetOf<String>()
                var restored = 0
                var different = 0
                ZipInputStream(stream).use { zip ->
                    var entries = 0
                    var bytes = 0L
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        require(++entries <= 30_000) { "Слишком много файлов в архиве" }
                        if (!entry.isDirectory) {
                            val raw = readMarkdownStream(zip)
                            bytes += raw.toByteArray(Charsets.UTF_8).size
                            require(bytes <= 256L * 1024 * 1024) { "Архив слишком большой" }
                            val path = entry.name.split('/')
                            if (path.size == 2 && path[0] in markdownFolders && path[1].endsWith(".md", true)) {
                                val document = requireNotNull(Markdown.read(raw)) { "В архиве есть нечитаемый документ" }.document
                                val id = document.id
                                require(ids.add(id)) { "В архиве повторяется ID записи" }
                                val existing = records.get(prefs.scope, id)
                                when {
                                    existing == null -> {
                                        records.put(Record(prefs.scope, id, raw))
                                        restored++
                                    }
                                    existing.markdown != raw -> different++
                                }
                            }
                        } else {
                            check(zip.read() == -1) { "Некорректная папка в архиве" }
                        }
                        zip.closeEntry()
                    }
                }
                require(ids.isNotEmpty()) { "В архиве нет документов GymFlow" }
                RestoreResult(restored, different)
            }
        }
    }
}
