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
                    startWorkoutDay(source, profileId, program, day, options).also { destination = it.destination }.documents
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
                val swap = swapDate?.let {
                    nextPlannedDay(program, day, LocalDate.parse(source.date), profileId, state.sessions).also { slot ->
                        check(slot?.date == it) { app.getString(R.string.plan_changed) }
                    }
                }
                changePlannedWorkout(source, profileId, program, day, swap)
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
            existing.start() to emptyList()
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
        (if (start) draft.start() else draft) to (draft.participants.filter { it.id !in people } + newExercises)
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
