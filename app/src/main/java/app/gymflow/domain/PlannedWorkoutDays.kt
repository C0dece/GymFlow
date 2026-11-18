package app.gymflow.domain

import java.time.LocalDate
import java.time.temporal.ChronoUnit.DAYS
import java.time.temporal.TemporalAdjusters.nextOrSame

internal data class PlannedDaySlot(val date: LocalDate, val session: Session?)

internal fun otherPlannedDayIds(source: Session, profileId: String, sessions: List<Session>): Set<String> =
    sessions.filter { it.id != source.id && it.programId == source.programId && it.date == source.date }
        .mapNotNull { session -> session.workout(profileId)?.let(session::templateOf) }.toSet()

internal fun nextPlannedDay(
    program: Program, day: WorkoutTemplate, after: LocalDate, profileId: String, sessions: List<Session>,
): PlannedDaySlot? {
    val programSessions = sessions.filter { it.programId == program.id }
    val saved = programSessions.asSequence()
        .filter { it.status == SessionStatus.PLANNED && it.date > after.toString() }
        .filter { session -> session.workout(profileId)?.let {
            it.status == WorkoutStatus.PLANNED && session.templateOf(it) == day.id
        } == true }
        .minByOrNull { it.date }
    if (!program.enabled) return saved?.let { PlannedDaySlot(LocalDate.parse(it.date), it) }

    val anchor = LocalDate.parse(program.anchor)
    val earliest = maxOf(after.plusDays(1), anchor)
    val (first, period) = if (program.cycle) {
        val spacing = program.interval.coerceAtLeast(1).toLong()
        val initial = anchor.plusDays(program.days.indexOfFirst { it.id == day.id } * spacing)
        val cycle = spacing * program.days.size
        val elapsed = DAYS.between(initial, earliest).coerceAtLeast(0)
        initial.plusDays((elapsed + cycle - 1) / cycle * cycle) to cycle
    } else earliest.with(nextOrSame(day.weekday)) to 7L

    val occupied = programSessions.filter { it.programDayId == day.id }.mapTo(hashSetOf()) { it.date }
    val nextDate = generateSequence(first) { it.plusDays(period) }.first { it.toString() !in occupied }
    return if (saved != null && saved.date <= nextDate.toString()) {
        PlannedDaySlot(LocalDate.parse(saved.date), saved)
    } else PlannedDaySlot(nextDate, null)
}

internal fun changePlannedWorkout(
    source: Session, profileId: String, program: Program, day: WorkoutTemplate, swap: PlannedDaySlot?,
): List<Session> {
    val original = requireNotNull(source.workout(profileId))
    require(source.status == SessionStatus.PLANNED && original.status == WorkoutStatus.PLANNED)
    val from = program.days.firstOrNull { it.id == source.templateOf(original) } ?: program.days.first()
    if (from.id == day.id) return listOf(source)
    val next = swap?.let { it.session ?: scheduledSession(program, day, it.date, source.participants) }
    val replacement = next?.let { requireNotNull(it.workout(profileId)) }
        ?: ParticipantWorkout(profile = original.profile).withTemplate(day)
    require(next == null || next.status == SessionStatus.PLANNED && replacement.status == WorkoutStatus.PLANNED)

    fun Session.receiptsFor(row: ParticipantWorkout) = progression.filter { receipt ->
        receipt.participantId == profileId && row.exercises.any { it.id == receipt.exerciseEntryId }
    }
    val originalReceipts = source.receiptsFor(original)
    val replacementReceipts = next?.receiptsFor(replacement).orEmpty()
    val current = source.editWorkout(profileId) { replacement.copy(templateId = day.id, templateTitle = day.title) }
        .copy(title = day.title.takeIf { source.workouts.size == 1 } ?: source.title,
            programDayId = if (source.workouts.size == 1) day.id else source.programDayId,
            progression = (source.progression - originalReceipts.toSet()) + replacementReceipts,
            switchedProfileId = profileId)
    val moved = next?.let { future ->
        future.editWorkout(profileId) { original.copy(templateId = from.id, templateTitle = from.title) }
            .copy(title = from.title.takeIf { future.workouts.size == 1 } ?: future.title,
                programDayId = if (future.workouts.size == 1) from.id else future.programDayId,
                progression = (future.progression - replacementReceipts.toSet()) + originalReceipts)
    }
    return listOfNotNull(current, moved)
}
