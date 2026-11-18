package app.gymflow.domain

import java.time.LocalDate
import java.time.temporal.ChronoUnit

fun nextWorkout(session: Session, currentId: String): ParticipantWorkout? {
    val start = session.workouts.indexOfFirst { it.profile.id == currentId }
    if (start < 0) return null
    return (1 until session.workouts.size).asSequence()
        .map { session.workouts[(start + it) % session.workouts.size] }
        .firstOrNull { it.canEdit && it.remaining > 0 }
}

fun ParticipantWorkout.nextExerciseId(): String? {
    val current = exercises.getOrNull(currentIndex)
    return current?.takeIf { it.athletes.single().next >= 0 }?.id
        ?: exercises.firstOrNull { it.athletes.single().next >= 0 }?.id
}

fun SetPlan.volumeKg(exercise: Exercise): Double? {
    if (!weightKnown || !repsKnown || maxEffort) return null
    return loadFactor(exercise)?.let { weight * repsMax * it }
}

private fun loadFactor(exercise: Exercise): Double? = when (exercise.load) {
    Load.DUMBBELL -> 2 * exercise.unit.toKg
    Load.BARBELL, Load.MACHINE -> exercise.unit.toKg
    Load.BODYWEIGHT, Load.OTHER -> null
}

fun repeatSession(source: Session, id: String, date: LocalDate, history: List<Session>): Session {
    val workouts = source.workouts.map { old ->
        val fresh = ParticipantWorkout(profile = old.profile, templateId = old.templateId, templateTitle = old.templateTitle,
            templateNotes = old.templateNotes)
        val prior = performance(history, old.profile.id).groupBy { it.exercise.id }
        old.plans().fold(fresh) { item, plan ->
            val last = prior[plan.exercise.id]?.lastOrNull { it.exercise.load == plan.exercise.load }
            val reference = last?.actuals?.lastOrNull { it.weightKnown && it.repsKnown }
            val sets = plan.sets.map { set ->
                if (set.warmup || reference == null || plan.exercise.load in setOf(Load.OTHER, Load.BODYWEIGHT)) set
                else set.copy(weight = reference.weight * last.exercise.unit.toKg / plan.exercise.unit.toKg, weightKnown = true)
            }
            item.addExercise(plan.copy(sets = sets))
        }
    }
    return Session(id, source.title, date.toString(), workouts = workouts)
}

fun plannedOn(program: Program, date: LocalDate): List<ProgramDay> {
    if (!program.enabled || program.days.isEmpty()) return emptyList()
    val offset = ChronoUnit.DAYS.between(LocalDate.parse(program.anchor), date)
    if (offset < 0) return emptyList()
    if (!program.cycle) return program.days.filter { it.weekday == date.dayOfWeek }
    val spacing = program.interval.coerceAtLeast(1)
    if (offset % spacing != 0L) return emptyList()
    return listOf(program.days[(offset / spacing % program.days.size).toInt()])
}

fun scheduledSession(program: Program, day: ProgramDay, date: LocalDate, profiles: List<Profile>): Session {
    val key = "${program.id}/${day.id}/$date"
    return Session(
        id = stableId(key), title = day.title, date = date.toString(),
        workouts = profiles.map { ParticipantWorkout(profile = it).withTemplate(day) },
        programId = program.id, programDayId = day.id,
    )
}

fun Session.reschedule(date: LocalDate): Session {
    require(status == SessionStatus.PLANNED)
    return copy(date = date.toString())
}

data class Performance(
    val date: String,
    val exercise: Exercise,
    val actuals: List<Actual>,
    val rir: Int?,
) {
    val maxKg get() = actuals.filter { it.weightKnown }.maxOfOrNull { it.weight * exercise.unit.toKg } ?: 0.0
    val reps get() = actuals.filter { it.repsKnown }.sumOf { it.reps }
    val volumeKg: Double? get() {
        val factor = loadFactor(exercise) ?: return null
        if (actuals.any { !it.weightKnown || !it.repsKnown }) return null
        return actuals.sumOf { it.weight * it.reps * factor }
    }
}

fun performance(sessions: List<Session>, profileId: String): List<Performance> = sessions
    .sortedWith(newestSessionFirst.reversed())
    .flatMap { session ->
        session.completedWorkouts(profileId).asReversed().flatMap { it.exercises }.flatMap { listOf(it) + it.replaced }.mapNotNull { entry ->
            val athlete = entry.athletes.single()
            if (athlete.skipped && athlete.sets.none { it.status == SetStatus.COMPLETED }) return@mapNotNull null
            val actuals = athlete.sets.mapNotNull {
                it.actual.takeIf { _ -> it.status == SetStatus.COMPLETED && !it.plan.warmup }
            }
            if (actuals.isEmpty()) null else Performance(session.date, entry.exercise, actuals, athlete.rir)
        }
    }
