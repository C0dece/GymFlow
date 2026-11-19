@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package app.gymflow.domain

import java.time.Instant
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ParticipantWorkout(
    val id: String = newId(),
    val profile: Profile,
    val templateId: String? = null,
    val templateTitle: String? = null,
    val templateNotes: String = "",
    val status: WorkoutStatus = WorkoutStatus.PLANNED,
    val exercises: List<ExerciseEntry> = emptyList(),
    val currentExerciseId: String? = null,
    val metrics: Metrics = Metrics(),
    @EncodeDefault(EncodeDefault.Mode.NEVER) val volume: WorkoutVolume = WorkoutVolume.NORMAL,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val originalPlan: List<ExercisePlan>? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val cycleSlot: CycleSlot? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val cycleCredited: Boolean = false,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val trainingBlocks: List<TrainingBlock> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER) val restUntil: String? = null,
) {
    val currentIndex: Int get() = exercises.indexOfFirst { it.id == currentExerciseId }.takeIf { it >= 0 }
        ?: exercises.indexOfFirst { it.athletes.single().next >= 0 }.coerceAtLeast(0)
    val remaining: Int get() = exercises.sumOf { entry ->
        val athlete = entry.athletes.single()
        if (athlete.skipped) 0 else athlete.sets.count { it.status == SetStatus.PLANNED }
    }
    val completed: Int get() = exercises.sumOf { it.counts.first }
    val canEdit: Boolean get() = status == WorkoutStatus.ACTIVE

    fun withTemplate(template: WorkoutTemplate?) = copy(
        templateId = template?.id,
        templateTitle = template?.title,
        templateNotes = template?.notes.orEmpty(),
        exercises = template?.orderedExercises().orEmpty().map(::entryFor),
        trainingBlocks = template?.blocks.orEmpty(),
        cycleSlot = cycleSlot?.takeIf { it.templateId == template?.id },
        cycleCredited = cycleCredited && cycleSlot?.templateId == template?.id,
        restUntil = null,
        currentExerciseId = null,
    ).let { row -> if (row.trainingBlocks.any { it.expandedOnly && it.enabled }) applyVolume(row, row.volume) else row }

    fun withOriginalPlan() = if (originalPlan == null) copy(originalPlan = plans()) else this

    fun discardExercise(id: String): ParticipantWorkout = withOriginalPlan().let { row ->
        val entry = row.exercises.find { it.id == id } ?: return row
        if ((listOf(entry) + entry.replaced).any { old -> old.athletes.single().sets.any { it.actual != null } }) {
            row.editExercise(id) { item ->
                val athlete = item.athletes.single()
                item.copy(athletes = listOf(athlete.copy(sets = athlete.sets.map {
                    if (it.status == SetStatus.PLANNED || it.deferred) it.copy(status = SetStatus.CANCELLED, deferred = false) else it
                })))
            }
        } else row.copy(exercises = row.exercises - entry, currentExerciseId = row.currentExerciseId.takeUnless { it == id })
    }

    fun addExercise(plan: ExercisePlan) = copy(exercises = exercises + entryFor(plan))

    private fun entryFor(plan: ExercisePlan): ExerciseEntry {
        val athlete = plan.athlete(profile.id)
        return ExerciseEntry(plan = plan.copy(sets = athlete.sets.map { it.plan }, individual = emptyMap()), athletes = listOf(athlete))
    }

    fun editExercise(id: String, transform: (ExerciseEntry) -> ExerciseEntry) =
        copy(exercises = exercises.map { if (it.id == id) transform(it) else it })

    fun recordCorrection(exerciseId: String, original: SetEntry?, result: SetEntry): ParticipantWorkout {
        require(exercises.any { it.id == exerciseId })
        val corrected = editExercise(exerciseId) { entry ->
            val athlete = entry.athletes.single()
            val saved = athlete.sets.find { it.id == result.id }
            check(saved == original) { "Подход уже изменён. Откройте его повторно." }
            val sets = if (saved == null) {
                if (result.status == SetStatus.COMPLETED) athlete.sets + result else athlete.sets
            } else athlete.sets.map { if (it.id == result.id) result else it }
            entry.copy(athletes = listOf(athlete.copy(sets = sets)))
        }
        return if (status == WorkoutStatus.CANCELLED && corrected.completed > 0 && corrected.remaining == 0) {
            corrected.copy(status = WorkoutStatus.COMPLETED)
        } else corrected
    }

    fun replaceExercise(id: String, plan: ExercisePlan) = withOriginalPlan().editExercise(id) { current ->
        entryFor(plan).copy(id = id, replaced = current.replaced + current.copy(replaced = emptyList()))
    }

    fun moveExercise(id: String, offset: Int): ParticipantWorkout {
        val index = exercises.indexOfFirst { it.id == id }
        if (index < 0) return this
        val to = (index + offset).coerceIn(exercises.indices)
        if (to == index) return this
        return withOriginalPlan().copy(currentExerciseId = id, exercises = exercises.toMutableList().apply { add(to, removeAt(index)) })
    }

    fun plans(): List<ExercisePlan> = exercises.map { e ->
        e.plan.copy(sets = e.athletes.single().sets.filter { it.status != SetStatus.CANCELLED }.map { it.plan })
    }
}

@Serializable
@SerialName("session")
data class TrainingSession(
    override val id: String = newId(),
    val title: String,
    val date: String,
    val status: SessionStatus = SessionStatus.PLANNED,
    val workouts: List<ParticipantWorkout>,
    val programId: String? = null,
    val programDayId: String? = null,
    val started: String? = null,
    val finished: String? = null,
    val notes: String = "",
    val progression: List<ProgressionReceipt> = emptyList(),
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val otherWorkouts: List<ParticipantWorkout> = emptyList(),
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val switchedProfileId: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val scheduledFor: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val workoutNotes: List<WorkoutNote> = emptyList(),
) : Document {
    constructor(title: String, date: String, participants: List<Profile>) : this(
        title = title, date = date, workouts = participants.map { ParticipantWorkout(profile = it) },
    )

    val participants get() = workouts.map { it.profile }
    val allWorkouts get() = workouts + otherWorkouts
    fun workout(profileId: String?) = workouts.firstOrNull { it.profile.id == profileId }
    val defaultWorkout get() = workout(switchedProfileId) ?: workouts.firstOrNull { it.canEdit } ?: workouts.first()
    fun completedWorkouts(profileId: String) = (workouts + otherWorkouts.asReversed()).filter {
        it.profile.id == profileId && (it.status == WorkoutStatus.COMPLETED ||
            (status == SessionStatus.COMPLETED && it in workouts))
    }

    fun editWorkoutById(id: String, change: (ParticipantWorkout) -> ParticipantWorkout) = copy(
        workouts = workouts.map { if (it.id == id) change(it) else it },
        otherWorkouts = otherWorkouts.map { if (it.id == id) change(it) else it },
    )

    fun selectWorkout(target: ParticipantWorkout): TrainingSession {
        val previous = requireNotNull(workout(target.profile.id))
        require(status == SessionStatus.ACTIVE && target.status != WorkoutStatus.COMPLETED)
        val parked = if (previous.status == WorkoutStatus.ACTIVE) previous.copy(status = WorkoutStatus.PAUSED) else previous
        return copy(
            workouts = workouts.map { if (it.id == previous.id) target.copy(status = WorkoutStatus.ACTIVE) else it },
            otherWorkouts = otherWorkouts.filterNot { it.id == target.id } + listOfNotNull(parked.takeIf { it.id != target.id }),
            switchedProfileId = target.profile.id,
        )
    }

    fun addParticipant(profile: Profile, template: WorkoutTemplate? = null): TrainingSession {
        require(workout(profile.id) == null)
        val state = if (status == SessionStatus.ACTIVE) WorkoutStatus.ACTIVE else WorkoutStatus.PLANNED
        return copy(workouts = workouts + ParticipantWorkout(profile = profile, status = state).withTemplate(template))
    }

    fun editWorkout(profileId: String, change: (ParticipantWorkout) -> ParticipantWorkout) =
        copy(workouts = workouts.map { if (it.profile.id == profileId) change(it) else it })

    fun addExercise(plan: ExercisePlan, people: Set<String> = setOf(workouts.first().profile.id)) =
        copy(workouts = workouts.map { if (it.profile.id in people) it.addExercise(plan) else it })

    fun editAthlete(exerciseId: String, personId: String, change: (Athlete) -> Athlete) =
        editWorkout(personId) { workout ->
            workout.editExercise(exerciseId) { it.copy(athletes = listOf(change(it.athletes.single()))) }
        }

    fun start(now: Instant = Instant.now()): TrainingSession = if (status == SessionStatus.PLANNED) {
        copy(status = SessionStatus.ACTIVE, started = now.toString(), workouts = workouts.map {
            if (it.status == WorkoutStatus.PLANNED) it.copy(status = WorkoutStatus.ACTIVE) else it
        })
    } else this

    fun finish(now: Instant = Instant.now()) = copy(
        status = SessionStatus.COMPLETED, finished = now.toString(), workouts = workouts.map {
            when (it.status) {
                WorkoutStatus.PLANNED, WorkoutStatus.ACTIVE, WorkoutStatus.PAUSED -> it.copy(
                    status = if (it.completed > 0 && (it.cycleSlot != null || it.exercises.none { entry ->
                        entry.plan.block == PlanBlock.REQUIRED && !entry.athletes.single().skipped &&
                            entry.athletes.single().sets.any { set -> set.status == SetStatus.PLANNED }
                    })) WorkoutStatus.COMPLETED else WorkoutStatus.CANCELLED,
                    restUntil = null,
                )
                else -> it
            }
        },
    )
}

typealias Session = TrainingSession

internal val newestSessionFirst = compareByDescending<Session> { it.date }
    .thenByDescending { it.finished ?: it.started.orEmpty() }
    .thenByDescending { it.id }
