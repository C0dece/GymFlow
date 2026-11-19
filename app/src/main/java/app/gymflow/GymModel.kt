package app.gymflow

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.gymflow.data.*
import app.gymflow.domain.*
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.provider.OpenableColumns
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class GymModel(private val app: Application) : AndroidViewModel(app) {
    private val repo = (app as GymFlowApp).repository
    val library = repo.library.stateIn(viewModelScope, SharingStarted.Eagerly, Library())
    val preferences = repo.settings.values.stateIn(viewModelScope, SharingStarted.Eagerly, Preferences())
    val sync = repo.status
    val message = MutableStateFlow<String?>(null)
    private val commands = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val flush = Channel<Unit>(Channel.CONFLATED)

    init {
        viewModelScope.launch { for (command in commands) runCommand(command) }
        viewModelScope.launch { for (request in flush) runCommand { repo.flush() } }
        viewModelScope.launch {
            repo.settings.recovered.filter { it }.collect {
                message.value = app.getString(R.string.preferences_restored)
                repo.settings.acknowledgeRecovery()
            }
        }
    }

    private suspend fun runCommand(command: suspend () -> Unit) {
        try {
            command()
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            Log.w("GymFlow", "operation_error category=${failure.javaClass.simpleName}")
            message.value = failure.localizedMessage ?: app.getString(R.string.save_failed)
        }
    }

    fun refresh() { commands.trySend { repo.maintain() } }
    fun connect(uri: Uri) { commands.trySend { repo.connect(uri); repo.maintain() } }
    fun settings(next: Preferences) { commands.trySend { repo.settings.update(next) } }
    fun resolve(id: String, local: Boolean) { commands.trySend { repo.resolve(id, local) } }
    fun export(uri: Uri, backups: Boolean) {
        commands.trySend {
            repo.export(uri, backups)
            message.value = app.getString(R.string.exported)
        }
    }

    fun save(document: Document) { commands.trySend { repo.save(document); if (document is Program) repo.schedule(); flush.trySend(Unit) } }
    fun addMovementExamples() {
        commands.trySend {
            repo.update { state ->
                val names = listOf("Вертикальная тяга", "Горизонтальная тяга", "Горизонтальный жим", "Стабилизация корпуса")
                val families = names.map { name ->
                    state.families.find { it.name.equals(name, true) } ?: MovementFamily(stableId("movement-example/$name"), name)
                }
                val examples = listOf(
                    Exercise(id = stableId("movement-example/pulldown"), name = "Тяга верхнего блока", load = Load.MACHINE,
                        muscle = "Широчайшие, бицепс", movement = MovementDetails(familyId = families[0].id, equipment = "Блок", quality = Quality.STRENGTH)),
                    Exercise(id = stableId("movement-example/plank"), name = "Планка на предплечьях", load = Load.BODYWEIGHT,
                        muscle = "Мышцы корпуса", movement = MovementDetails(familyId = families[3].id, quality = Quality.CONTROL, measures = setOf(Measure.HOLD))),
                ).filter { example -> state.exercises.none { it.id == example.id || it.name.equals(example.name, true) } }
                families.filter { candidate -> state.families.none { it.id == candidate.id } } + examples
            }
            flush.trySend(Unit)
        }
    }

    fun saveMovement(exercise: Exercise, onSaved: () -> Unit) {
        commands.trySend {
            repo.update { state ->
                val details = exercise.movement
                val existing = state.exercises.associateBy { it.id }
                check(existing.values.none { it.id != exercise.id && it.name.equals(exercise.name, true) }) { "Упражнение с таким названием уже есть" }
                check(details?.familyId == null || state.families.any { it.id == details.familyId }) { "Семейство не найдено" }
                val parent = details?.parentId?.let { requireNotNull(existing[it]) }
                check(parent == null || parent.movement?.familyId == details?.familyId) { "Исходное упражнение из другого семейства" }
                val visited = mutableSetOf(exercise.id)
                var cursor = parent
                while (cursor != null) {
                    check(visited.add(cursor.id)) { "Связь с исходным упражнением образует цикл" }
                    cursor = existing[cursor.movement?.parentId]
                }
                check(existing.values.none { it.movement?.parentId == exercise.id && it.movement.familyId != details?.familyId }) {
                    "Сначала перенесите связанные вариации"
                }
                listOf(exercise)
            }
            flush.trySend(Unit)
            onSaved()
        }
    }

    fun saveMovementTrack(track: MovementTrack, onSaved: () -> Unit) {
        commands.trySend {
            repo.update { state ->
                val familyId = state.exercises.find { it.id == track.exerciseId }?.movement?.familyId
                val oldAnchors = if (track.role == MovementRole.ANCHOR && familyId != null) {
                    val members = state.exercises.filter { it.movement?.familyId == familyId }.mapTo(hashSetOf()) { it.id }
                    state.movementTracks.filter { it.id != track.id && it.profileId == track.profileId &&
                        it.role == MovementRole.ANCHOR && it.exerciseId in members }
                } else emptyList()
                oldAnchors.map { it.copy(role = MovementRole.RESERVE) } + track
            }
            flush.trySend(Unit)
            onSaved()
        }
    }

    fun edit(id: String, transform: (Document) -> Document) {
        commands.trySend { repo.editDocument(id, transform); repo.schedule(); flush.trySend(Unit) }
    }
    fun session(
        id: String,
        extras: List<Document> = emptyList(),
        onSaved: () -> Unit = {},
        transform: (Session) -> Session,
    ) {
        commands.trySend {
            repo.editSession(id, extras, transform)
            flush.trySend(Unit)
            onSaved()
        }
    }

    private var creating = false

    internal fun readProgramFile(uri: Uri, target: String?, show: (ProgramInput) -> Unit) {
        commands.trySend {
            val input = withContext(Dispatchers.IO) {
                val resolver = app.contentResolver
                val content = readMarkdownFile(resolver, uri)
                val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                } ?: "Программа.md"
                try {
                    resolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: SecurityException) {
                    // Some providers grant access only for this read.
                }
                ProgramInput(uri.toString(), name, content, target)
            }
            show(input)
        }
    }

    internal fun saveImportedProgram(review: ImportReview, scope: String, onSaved: (String) -> Unit) {
        commands.trySend {
            repo.update { state ->
                check(state.scope == scope) { "Папка GymFlow изменилась. Откройте импорт повторно." }
                check(state.programs.find { it.id == review.program.id } == review.current) {
                    "Программа изменилась. Проверьте импорт повторно."
                }
                val catalog = state.exercises.associateBy { it.id }
                check(review.newExercises.none { ex -> state.exercises.any {
                    it.id == ex.id || ProgramImport.key(it.name) == ProgramImport.key(ex.name)
                } }) { "Каталог изменился. Проверьте сопоставления повторно." }
                val newIds = review.newExercises.mapTo(hashSetOf()) { it.id }
                check(review.program.days.flatMap { it.exercises }.all {
                    it.exercise.id in newIds || catalog[it.exercise.id] == it.exercise
                }) { "Каталог изменился. Проверьте сопоставления повторно." }
                review.newExercises + review.program
            }
            flush.trySend(Unit)
            onSaved(review.program.id)
        }
    }

    fun prepareCycle(programId: String, profileId: String, dayId: String?, open: (String, Boolean) -> Unit) {
        var active = false
        create({ open(it, active) }) { state ->
            val program = requireNotNull(state.programs.find { it.id == programId && it.completionCycle != null })
            val profile = requireNotNull(state.availableProfiles.find { it.id == profileId })
            val position = cyclePosition(program, profileId, state.sessions)
            val day = requireNotNull(dayId?.let { id -> program.days.find { it.id == id } } ?: position.next) {
                "Цикл завершён. Продолжите программу или выберите другой день."
            }
            val advance = position.next?.id == day.id
            val pending = position.pending?.takeIf { advance && it.workout.templateId == day.id }
            if (pending != null) {
                if (pending.workout in pending.session.otherWorkouts) {
                    val change = startWorkoutDay(pending.session, profileId, program, day, WorkoutDayOptions(pending = pending))
                    active = true
                    change.destination to change.documents.filterNot { it.id == change.destination.id }
                } else {
                    active = pending.session.status == SessionStatus.ACTIVE
                    pending.session to emptyList()
                }
            } else {
                val draft = cycleAppointment(program, profile, day, LocalDate.now(), position, advance)
                val old = state.sessions.find { it.id == draft.id }
                when (old?.status) {
                    SessionStatus.ACTIVE -> { active = true; old to emptyList() }
                    SessionStatus.PLANNED -> old to emptyList()
                    SessionStatus.SKIPPED -> old.copy(status = SessionStatus.PLANNED,
                        workouts = old.workouts.map { it.copy(status = WorkoutStatus.PLANNED) }) to emptyList()
                    SessionStatus.COMPLETED -> draft.copy(id = newId()) to emptyList()
                    null -> draft to emptyList()
                }
            }
        }
    }

    fun extendCycle(programId: String, profileId: String) {
        commands.trySend {
            repo.update { state ->
                val program = state.programs.find { it.id == programId } ?: return@update emptyList()
                val config = program.completionCycle ?: return@update emptyList()
                val position = cyclePosition(program, profileId, state.sessions)
                if (position.limit == null || position.completed < position.limit) return@update emptyList()
                listOf(program.copy(completionCycle = config.copy(
                    extensions = config.extensions + (profileId to ((config.extensions[profileId] ?: 0) + 1)))))
            }
            flush.trySend(Unit)
        }
    }

    fun addWorkoutParticipant(sessionId: String, profile: Profile, template: WorkoutTemplate?, permanent: Boolean, onSaved: () -> Unit) {
        commands.trySend {
            repo.update { state ->
                val source = state.sessions.find { it.id == sessionId && it.status == SessionStatus.ACTIVE }
                    ?: error(app.getString(R.string.session_missing))
                val joined = source.addParticipant(profile, template)
                val program = state.programs.find { it.completionCycle != null && it.days.any { day -> day.id == template?.id } }
                val position = program?.let { cyclePosition(it, profile.id, state.sessions) }
                val pending = position?.pending
                val chosen = if (program != null && position?.next?.id == template?.id && template != null &&
                    (pending == null || pending.session.status == SessionStatus.PLANNED)) {
                    joined.editWorkout(profile.id) { row ->
                        pending?.workout?.copy(status = WorkoutStatus.ACTIVE) ?: row.copy(
                            cycleSlot = CycleSlot(program.id, template.id, requireNotNull(position).completed + 1))
                    }
                } else joined
                val moved = pending?.takeIf { it.workout.id == chosen.workout(profile.id)?.id }
                val retired = moved?.session?.copy(status = SessionStatus.SKIPPED,
                    workouts = moved.session.workouts.map { it.copy(status = WorkoutStatus.SKIPPED, cycleSlot = null) })
                val receipts = moved?.session?.progression.orEmpty()
                listOfNotNull(retired, chosen.copy(progression = chosen.progression + receipts), profile.takeIf { permanent })
            }
            flush.trySend(Unit)
            onSaved()
        }
    }

    fun switchDay(sessionId: String, profileId: String, dayId: String, open: (String) -> Unit) {
        if (creating) return
        creating = true
        commands.trySend {
            try {
                lateinit var destination: Session
                repo.update { state ->
                    val source = state.sessions.find { it.id == sessionId } ?: error(app.getString(R.string.session_missing))
                    val program = state.programs.find { it.days.any { day -> day.id == dayId } }
                        ?: error(app.getString(R.string.plan_changed))
                    val day = program.days.first { it.id == dayId }
                    val options = workoutDays(state.sessions, source, profileId)[dayId] ?: WorkoutDayOptions()
                    val changed = startWorkoutDay(source, profileId, program, day, options)
                    val position = program.completionCycle?.let { cyclePosition(program, profileId, state.sessions) }
                    destination = changed.destination
                    if (options.pending == null && options.planned == null && position?.next?.id == day.id && position.pending == null) {
                        destination = destination.editWorkout(profileId) {
                            it.copy(cycleSlot = CycleSlot(program.id, day.id, position.completed + 1))
                        }
                    }
                    changed.documents.filterNot { it.id == destination.id } + destination
                }
                flush.trySend(Unit)
                val target = destination.workout(profileId)?.id
                library.first { state -> state.sessions.any { it.id == destination.id && it.workout(profileId)?.id == target } }
                open(destination.id)
            } finally { creating = false }
        }
    }

    fun changePlannedDay(original: Session, profileId: String, dayId: String, swapDate: LocalDate?, onSaved: () -> Unit) {
        commands.trySend {
            repo.update { state ->
                val source = state.sessions.find { it.id == original.id }
                    ?: error(app.getString(R.string.plan_changed))
                check(source.status == SessionStatus.PLANNED) { app.getString(R.string.plan_changed) }
                val member = requireNotNull(source.workout(profileId) ?: source.allWorkouts.firstOrNull { it.profile.id == profileId })
                val program = requireNotNull(workoutProgram(state.programs, source, member)
                    ?: state.programs.find { p -> p.days.any { it.id == dayId } })
                val day = program.days.first { it.id == dayId }
                check(dayId !in otherPlannedDayIds(source, profileId, state.sessions)) { app.getString(R.string.plan_changed) }
                val swap = swapDate?.takeIf { program.completionCycle == null }?.let {
                    nextPlannedDay(program, day, LocalDate.parse(source.date), profileId, state.sessions).also { slot ->
                        check(slot?.date == it) { app.getString(R.string.plan_changed) }
                    }
                }
                val changed = changePlannedWorkout(source, profileId, program, day, swap)
                val position = program.completionCycle?.let { cyclePosition(program, profileId, state.sessions) }
                if (position?.next?.id == dayId && (position.pending == null || position.pending.session.id == source.id)) {
                    changed.map { session -> session.editWorkout(profileId) {
                        it.copy(cycleSlot = CycleSlot(program.id, dayId, position.completed + 1))
                    } }
                } else changed
            }
            flush.trySend(Unit)
            onSaved()
        }
    }

    fun correctResult(sessionId: String, workoutId: String, exerciseId: String, original: SetEntry?, result: SetEntry, onSaved: () -> Unit) {
        commands.trySend {
            repo.correctResult(sessionId, workoutId, exerciseId, original, result)
            flush.trySend(Unit)
            onSaved()
        }
    }

    private fun create(open: (String) -> Unit, change: (Library) -> Pair<Session, List<Document>>) {
        if (creating) return
        creating = true
        commands.trySend {
            try {
                lateinit var result: Session
                repo.update { state ->
                    val (session, extras) = change(state)
                    result = session
                    extras + session
                }
                flush.trySend(Unit)
                library.first { state -> state.sessions.any { it.id == result.id } }
                open(result.id)
            } finally { creating = false }
        }
    }

    fun start(id: String? = null, open: (String) -> Unit) = create(open) { state ->
        if (id != null) {
            val existing = state.sessions.find { it.id == id && it.status in setOf(SessionStatus.PLANNED, SessionStatus.ACTIVE) }
                ?: error(app.getString(R.string.session_missing))
            val plan = if (existing.workouts.any { it.cycleSlot != null }) existing.onCycleVisit(LocalDate.now()) else existing
            plan.start() to emptyList()
        } else {
            val self = state.availableProfiles.find { it.id == preferences.value.athlete } ?: state.availableProfiles.firstOrNull()
            val profile = self ?: Profile(name = app.getString(R.string.self))
            Session(title = app.getString(R.string.free_session), date = LocalDate.now().toString(),
                participants = listOf(profile)).start() to listOfNotNull(profile.takeIf { self == null })
        }
    }

    fun confirmPlan(original: Session?, draft: Session, start: Boolean, open: (String) -> Unit) = create(open) { state ->
        val existing = state.sessions.find { it.id == draft.id }
        check(existing == original && (existing == null || existing.status == SessionStatus.PLANNED)) {
            app.getString(R.string.plan_changed)
        }
        val people = state.profiles.map { it.id }.toSet()
        val catalog = state.exercises.map { it.id }.toSet()
        val newExercises = draft.workouts.flatMap { it.exercises }.map { it.exercise }.distinctBy { it.id }.filter { it.id !in catalog }
        val cycle = draft.workouts.any { it.cycleSlot != null } ||
            state.programs.any { it.id == draft.programId && it.completionCycle != null }
        val planned = if (start && cycle) draft.onCycleVisit(LocalDate.now()) else draft
        (if (start) planned.start() else planned) to (draft.participants.filter { it.id !in people } + newExercises)
    }

    fun savePersonalPlan(workout: ParticipantWorkout, program: Program?, day: WorkoutTemplate?, sessionTitle: String) {
        val profileId = workout.profile.id
        val canUpdate = program?.participantIds == listOf(profileId) && day?.exercises.orEmpty()
            .all { plan -> plan.individual.keys.all { it == profileId } }
        val programId = if (canUpdate) requireNotNull(program).id else stableId("personal/${program?.id.orEmpty()}/$profileId")
        val dayId = if (canUpdate) requireNotNull(day).id else stableId("$programId/${day?.id ?: sessionTitle}")
        val plan = (day ?: WorkoutTemplate(title = sessionTitle)).copy(id = dayId, exercises = workout.plans())
        commands.trySend {
            repo.update { state ->
                val existing = state.programs.find { it.id == programId }
                val owner = existing ?: Program(id = programId, title = listOfNotNull(program?.title, workout.profile.name).joinToString(" · "),
                    participantIds = listOf(profileId))
                val days = if (owner.days.any { it.id == dayId }) owner.days.map { if (it.id == dayId) plan else it }
                    else owner.days + plan
                listOf(owner.copy(days = days))
            }
            repo.schedule()
            flush.trySend(Unit)
            message.value = app.getString(R.string.personal_plan_saved)
        }
    }
}
