package app.gymflow.domain

import java.time.DayOfWeek
import java.time.LocalDate
import kotlinx.serialization.Serializable

@Serializable
data class CyclePhase(val through: Int, val name: String, val guidance: String) {
    init { require(through > 0 && name.isNotBlank()) }
}

@Serializable
data class CompletionCycle(
    val visitsPerWeek: Int = 3,
    val visitDays: Set<DayOfWeek> = emptySet(),
    val targetSessions: Int? = null,
    val extensions: Map<String, Int> = emptyMap(),
    val phases: List<CyclePhase> = emptyList(),
) {
    init {
        require(visitsPerWeek in 1..7 && (visitDays.isEmpty() || visitDays.size == visitsPerWeek))
        require(targetSessions == null || targetSessions > 0)
        require(extensions.values.all { it >= 0 } && phases.zipWithNext().all { (a, b) -> a.through < b.through })
    }

    fun visitsOn(date: LocalDate): Boolean {
        val days = visitDays.ifEmpty { (0 until visitsPerWeek).mapTo(hashSetOf()) { DayOfWeek.of(1 + it * 7 / visitsPerWeek) } }
        return date.dayOfWeek in days
    }

    fun limit(profileId: String): Long? = targetSessions?.toLong()?.times(1L + (extensions[profileId] ?: 0))
}

@Serializable
data class CycleSlot(val programId: String, val templateId: String, val sequence: Int) {
    init { require(sequence > 0) }
}

internal data class CyclePosition(
    val completed: Int, val limit: Long?, val next: WorkoutTemplate?, val phase: CyclePhase?,
    val pending: WorkoutDayRecord?,
)

internal fun ParticipantWorkout.hasCycleResult(): Boolean {
    val entries = exercises.flatMap { listOf(it) + it.replaced }
    val primary = entries.filter { it.plan.block == PlanBlock.REQUIRED }.ifEmpty { entries }
    val sets = primary.flatMap { it.athletes.single().sets }
    val working = sets.filter { it.plan.purpose == SetRole.WORK }.ifEmpty {
        sets.filter { it.plan.purpose == SetRole.TECHNIQUE || it.plan.purpose == SetRole.TEST }
    }
    return working.any { set -> set.status == SetStatus.COMPLETED && set.actual?.let {
        it.weightKnown || it.repsKnown || it.measurements.isNotEmpty()
    } == true }
}

internal fun ParticipantWorkout.creditCycle(): ParticipantWorkout =
    if (cycleSlot != null && status == WorkoutStatus.COMPLETED && !cycleCredited && hasCycleResult()) copy(cycleCredited = true) else this

internal fun cyclePosition(program: Program, profileId: String, sessions: List<Session>): CyclePosition {
    val config = requireNotNull(program.completionCycle)
    val records = sessions.sortedWith(newestSessionFirst.reversed()).flatMap { session ->
        session.allWorkouts.filter { it.profile.id == profileId && it.cycleSlot?.programId == program.id &&
            it.cycleSlot.templateId == it.templateId }.map { WorkoutDayRecord(session, it) }
    }
    val completed = records.filter { it.workout.status == WorkoutStatus.COMPLETED && (it.workout.cycleCredited || it.workout.hasCycleResult()) }
        .distinctBy { it.workout.cycleSlot!!.sequence }.associateBy { it.workout.cycleSlot!!.sequence }
    var count = 0
    while (count + 1 in completed) count++
    val limit = config.limit(profileId)
    if (limit != null && count >= limit) return CyclePosition(count, limit, null, null, null)
    val pending = records.lastOrNull { record ->
        record.workout.cycleSlot!!.sequence == count + 1 &&
            record.workout.status in setOf(WorkoutStatus.PLANNED, WorkoutStatus.ACTIVE, WorkoutStatus.PAUSED) &&
            (record.session.status != SessionStatus.COMPLETED || record.workout in record.session.otherWorkouts) &&
            record.session.status != SessionStatus.SKIPPED
    }
    val days = program.days.filter { it.orderedExercises().isNotEmpty() }
    val last = completed[count]?.workout?.templateId
    val lastIndex = last?.let { id -> days.indexOfFirst { it.id == id } } ?: -1
    val next = days.find { it.id == pending?.workout?.templateId }
        ?: days.takeIf { it.isNotEmpty() }?.let { it[(if (lastIndex >= 0) lastIndex + 1 else count) % it.size] }
    val unit = config.targetSessions?.let { count % it } ?: count
    return CyclePosition(count, limit, next, config.phases.firstOrNull { it.through > unit }, pending)
}

internal fun Session.onCycleVisit(today: LocalDate): Session =
    if (status == SessionStatus.PLANNED && date != today.toString()) copy(date = today.toString(), scheduledFor = date) else this

internal fun cycleAppointment(
    program: Program, profile: Profile, day: WorkoutTemplate, date: LocalDate, position: CyclePosition, advance: Boolean,
): Session {
    val slot = CycleSlot(program.id, day.id, position.completed + 1).takeIf { advance && position.next?.id == day.id }
    val session = scheduledSession(program, day, date, listOf(profile))
    val key = "cycle/${program.id}/${profile.id}/${position.completed + 1}/${day.id}/$date/$advance"
    return session.copy(id = stableId(key), workouts = session.workouts.map { it.copy(cycleSlot = slot) })
}
