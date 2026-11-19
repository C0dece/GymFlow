package app.gymflow.domain

import java.time.Instant
import java.time.LocalDate

internal data class WorkoutDayRecord(val session: Session, val workout: ParticipantWorkout)

internal fun Session.templateOf(workout: ParticipantWorkout): String? = workout.templateId
    ?: programDayId.takeIf { this.workout(workout.profile.id)?.id == workout.id }

internal fun workoutProgram(programs: List<Program>, session: Session, workout: ParticipantWorkout): Program? {
    val day = session.templateOf(workout)
    return programs.firstOrNull { program -> program.days.any { it.id == day } }
        ?: programs.firstOrNull { it.id == session.programId && day == null }
}

internal data class WorkoutDayOptions(
    val pending: WorkoutDayRecord? = null,
    val completed: WorkoutDayRecord? = null,
    val planned: WorkoutDayRecord? = null,
) {
    val display get() = pending ?: completed ?: planned
}

internal fun workoutDays(sessions: List<Session>, source: Session, profileId: String): Map<String, WorkoutDayOptions> {
    val ordered = listOf(source) + sessions.filterNot { it.id == source.id }
    return ordered.flatMap { session ->
        (session.workouts + session.otherWorkouts.asReversed()).filter { it.profile.id == profileId }.mapNotNull { row ->
            session.templateOf(row)?.let { it to WorkoutDayRecord(session, row) }
        }
    }.groupBy({ it.first }, { it.second }).mapValues { (_, records) ->
        WorkoutDayOptions(
            pending = records.firstOrNull { (session, row) ->
                row.status in setOf(WorkoutStatus.ACTIVE, WorkoutStatus.PAUSED, WorkoutStatus.SKIPPED) &&
                    (session.status == SessionStatus.ACTIVE || row in session.otherWorkouts)
            },
            completed = records.firstOrNull { (session, row) ->
                row.status != WorkoutStatus.SKIPPED && row in session.completedWorkouts(profileId)
            },
            planned = records.filter { (session, row) ->
                session.status in setOf(SessionStatus.PLANNED, SessionStatus.ACTIVE) && row.status == WorkoutStatus.PLANNED
            }.minByOrNull { it.session.date },
        )
    }
}

internal data class WorkoutDayChange(val destination: Session, val documents: List<Session>)

internal fun startWorkoutDay(
    source: Session, profileId: String, program: Program, day: WorkoutTemplate, options: WorkoutDayOptions,
    today: LocalDate = LocalDate.now(), now: Instant = Instant.now(),
): WorkoutDayChange {
    val existing = options.pending ?: options.planned
    val origin = existing?.session
    val memberProfile = source.allWorkouts.firstOrNull { it.profile.id == profileId }?.profile
        ?: existing?.workout?.profile
        ?: Profile(id = profileId, name = "")
    val workout = existing?.workout ?: ParticipantWorkout(profile = memberProfile).withTemplate(day)
    if (origin?.status == SessionStatus.ACTIVE || origin?.status == SessionStatus.PLANNED) {
        val plan = if (program.completionCycle != null) origin.onCycleVisit(today) else origin
        val active = if (plan.status == SessionStatus.PLANNED) {
            plan.copy(status = SessionStatus.ACTIVE, started = now.toString(), date = today.toString())
        } else plan
        val updated = active.selectWorkout(workout)
        val paused = source.takeIf { it.id != origin.id && it.status == SessionStatus.ACTIVE }?.editWorkout(profileId) {
            if (it.canEdit) it.copy(status = WorkoutStatus.PAUSED) else it
        }
        return WorkoutDayChange(updated, listOfNotNull(paused?.takeIf { it != source }, updated))
    }

    // A parked workout from a closed group keeps its identity and moves with its receipts.
    val receipts = origin?.progression.orEmpty().filter { receipt ->
        receipt.participantId == profileId && workout.exercises.any { it.id == receipt.exerciseEntryId }
    }
    val detached = origin?.copy(otherWorkouts = origin.otherWorkouts.filterNot { it.id == workout.id },
        progression = origin.progression - receipts.toSet())
    val updated = if (source.status == SessionStatus.ACTIVE) {
        source.selectWorkout(workout).copy(progression = source.progression + receipts)
    } else {
        Session(title = day.title, date = today.toString(), workouts = listOf(workout.copy(status = WorkoutStatus.ACTIVE)),
            programId = program.id, programDayId = day.id, progression = receipts).start(now)
    }
    return WorkoutDayChange(updated, listOfNotNull(detached, updated))
}
