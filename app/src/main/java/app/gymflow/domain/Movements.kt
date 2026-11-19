package app.gymflow.domain

import java.time.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class Measure(val label: String, val min: Double, val max: Double) {
    WEIGHT("Вес, кг", 0.0, 5000.0), REPS("Повторения", 1.0, 100000.0), RIR("RIR", 0.0, 4.0),
    RELATIVE_WEIGHT("Отягощение, % массы тела", 0.0, 5000.0), RPE("RPE", 0.0, 10.0),
    HOLD("Удержание, с", 0.01, 86400.0), RANGE("Амплитуда, °", 0.0, 360.0),
    TEMPO("Темп, с/повтор", 0.01, 3600.0), PAUSE("Пауза, с", 0.0, 3600.0),
    SPEED("Скорость, м/с", 0.0, 100.0), BALANCE("Баланс, с", 0.01, 86400.0),
    DURATION("Длительность, с", 0.01, 86400.0), DISTANCE("Дистанция, м", 0.0, 1000000.0),
    POWER("Мощность, Вт", 0.0, 10000.0), PULSE("Средний пульс, уд/мин", 20.0, 250.0),
    MAX_PULSE("Макс. пульс, уд/мин", 20.0, 250.0), PACE("Темп, с/км", 1.0, 86400.0),
    INCLINE("Уклон, %", -40.0, 100.0), RESISTANCE("Сопротивление / уровень", 0.0, 10000.0);

    fun accepts(value: Double) = value.isFinite() && value in min..max &&
        (this !in setOf(REPS, RIR, PULSE, MAX_PULSE) || value % 1 == 0.0)

    companion object {
        val extra = entries.filter { it !in setOf(WEIGHT, REPS, RIR, RELATIVE_WEIGHT) }
    }
}

@Serializable
enum class VariationType(val label: String) {
    GRIP("Хват"), RANGE("Амплитуда"), TEMPO("Темп"), PAUSE("Пауза"), POSITION("Положение"),
    EQUIPMENT("Оборудование"), UNILATERAL("Одностороннее"), WEIGHT("Доп. вес"), OTHER("Другое"),
}

@Serializable
enum class Quality(val label: String) {
    STRENGTH("Сила"), HYPERTROPHY("Гипертрофия"), ENDURANCE("Выносливость"),
    POWER("Мощность"), CONTROL("Контроль"), MOBILITY("Подвижность"), SKILL("Навык"),
}

@Serializable
data class MovementDetails(
    val familyId: String? = null,
    val parentId: String? = null,
    val variation: VariationType? = null,
    val quality: Quality? = null,
    val equipment: String = "",
    val gym: String = "",
    val difficulty: Int? = null,
    val limitations: String = "",
    val measures: Set<Measure> = emptySet(),
) {
    init {
        require(parentId == null || familyId != null && variation != null)
        require(difficulty == null || difficulty in 1..5)
        require(measures.all { it in Measure.extra })
    }
}

@Serializable
@SerialName("movement_family")
data class MovementFamily(override val id: String = newId(), val name: String, val notes: String = "") : Document {
    init { require(name.isNotBlank()) }
}

@Serializable
enum class MovementRole(val label: String) {
    ANCHOR("Силовой якорь"), ACTIVE("Активная"), MAINTENANCE("Поддержание"), RESERVE("Резерв"), RELEARN("Повторное освоение"),
}

@Serializable
data class MovementGoal(val measure: Measure, val target: Double, val atLeast: Boolean = true) {
    init { require(measure.accepts(target)) }
    fun met(value: Double) = if (atLeast) value >= target else value <= target
}

@Serializable
@SerialName("movement_track")
data class MovementTrack(
    val profileId: String, val exerciseId: String, val role: MovementRole = MovementRole.RESERVE,
    val goal: MovementGoal? = null, val repeatDays: Int? = null, val priority: Int = 0, val paused: Boolean = false,
    override val id: String = stableId("movement/$profileId/$exerciseId"),
    val limitations: String = "",
    val step: Double? = null,
) : Document {
    init {
        require(id == stableId("movement/$profileId/$exerciseId"))
        require(repeatDays == null || repeatDays > 0)
        require(step == null || step.isFinite() && step > 0 && goal?.measure in Measure.extra)
    }
}

@Serializable
enum class SetRole(val label: String) {
    WORK("Рабочий"), WARMUP("Разминка"), TECHNIQUE("Технический"), TEST("Проверочный"), RAMP("Подводящий"),
}

@Serializable
enum class WorkoutVolume(val label: String) {
    MINIMUM("Минимальная"), NORMAL("Обычная"), EXTENDED("Расширенная"), FREE("Свободная"),
}

@Serializable
enum class PlanBlock(val label: String) {
    REQUIRED("Обязательный"), RECOMMENDED("Рекомендованный"), OPTIONAL("Необязательный"),
}

@Serializable
enum class MovementSelection(val label: String) {
    FIXED("Фиксированное упражнение"), FAMILY("Вариант семейства"), ANCHOR("Якорь"), ACTIVE("Активная вариация"), MAINTENANCE("Поддержание"),
}

internal val Exercise.tracksDuration: Boolean get() = cardio != null || movement?.measures.orEmpty().any {
    it in setOf(Measure.HOLD, Measure.BALANCE, Measure.DURATION, Measure.DISTANCE)
}

internal fun Exercise.comparableWith(other: Exercise): Boolean =
    id == other.id && load == other.load && cardio == other.cardio && movement?.equipment.orEmpty().equals(other.movement?.equipment.orEmpty(), true) &&
        movement?.variation == other.movement?.variation && movement?.parentId == other.movement?.parentId

internal data class MovementResult(val date: String, val kind: Measure, val value: Double) {
    val isLowerBound get() = kind == Measure.RIR && value == 4.0
    fun supports(goal: MovementGoal) = kind == goal.measure && (goal.atLeast || !isLowerBound) && goal.met(value)
}

internal fun movementResults(sessions: List<Session>, profileId: String, exercise: Exercise): List<MovementResult> =
    sessions.sortedWith(newestSessionFirst).flatMap { session ->
        session.completedWorkouts(profileId).flatMap { workout ->
            workout.exercises.flatMap { listOf(it) + it.replaced }.filter { it.exercise.comparableWith(exercise) }
                .distinctBy { entry -> entry.athletes.single().sets.map { it.id } }.flatMap { entry ->
                    val athlete = entry.athletes.single()
                    val results = athlete.sets.filter { it.status == SetStatus.COMPLETED && it.plan.purpose in setOf(SetRole.WORK, SetRole.TEST) }
                        .mapNotNull { it.actual?.takeIf { fact -> fact.technique != Technique.CHANGED } }
                    buildList {
                        fun fact(kind: Measure, value: Double) { if (kind.accepts(value)) add(MovementResult(session.date, kind, value)) }
                        if (results.isNotEmpty()) athlete.rir?.let { fact(Measure.RIR, it.toDouble()) }
                        results.forEach { actual ->
                            if (actual.weightKnown) {
                                val kg = actual.weight * entry.exercise.unit.toKg
                                fact(Measure.WEIGHT, kg)
                                val mass = workout.metrics.bodyWeightKg
                                if (exercise.load == Load.BODYWEIGHT && mass != null && mass > 0) fact(Measure.RELATIVE_WEIGHT, 100 * kg / mass)
                            }
                            if (actual.repsKnown) fact(Measure.REPS, actual.reps.toDouble())
                            actual.measurements.forEach(::fact)
                        }
                    }
                }
        }
    }

internal data class NextMeasurement(val date: String, val current: Double, val suggested: Double)

internal fun nextMeasurement(track: MovementTrack, history: List<MovementResult>, caution: Boolean): NextMeasurement? {
    val goal = track.goal ?: return null
    val step = track.step ?: return null
    if (caution || track.paused || track.role !in setOf(MovementRole.ACTIVE, MovementRole.RELEARN)) return null
    val recent = history.filter { it.kind == goal.measure }
    val date = recent.firstOrNull()?.date ?: return null
    if (recent.none { it.date < date }) return null
    val values = recent.filter { it.date == date }.map { it.value }
    val current = if (goal.atLeast) values.max() else values.min()
    if (goal.met(current)) return null
    val suggested = if (goal.atLeast) (current + step).coerceAtMost(goal.target) else (current - step).coerceAtLeast(goal.target)
    return NextMeasurement(date, current, suggested)
}

internal fun workSets(workout: ParticipantWorkout, exerciseIds: Set<String>, statuses: Set<SetStatus>): Int =
    workout.exercises.flatMap { entry ->
        listOf(entry to statuses) + entry.replaced.map { it to (statuses intersect setOf(SetStatus.COMPLETED)) }
    }.filter { (entry, _) -> entry.exercise.id in exerciseIds }
        .flatMap { (entry, allowed) -> entry.athletes.single().let { athlete ->
            athlete.sets.filter { it.status in allowed && (!athlete.skipped || it.status == SetStatus.COMPLETED) }
        } }.distinctBy { it.id }
        .count { it.plan.purpose == SetRole.WORK }

internal fun applyVolume(workout: ParticipantWorkout, volume: WorkoutVolume): ParticipantWorkout {
    val baseline = workout.withOriginalPlan()
    return baseline.copy(volume = volume, exercises = baseline.exercises.map { entry ->
        val athlete = entry.athletes.single()
        val restored = athlete.sets.map { if (it.deferred) it.copy(status = SetStatus.PLANNED, deferred = false) else it }
        val keep = restored.firstOrNull { it.plan.purpose == SetRole.WORK && it.status in setOf(SetStatus.COMPLETED, SetStatus.PLANNED) }?.id
        val expanded = rowExpandedOnly(baseline, entry) && volume in setOf(WorkoutVolume.MINIMUM, WorkoutVolume.NORMAL)
        val sets = restored.map { set ->
            if (set.status == SetStatus.PLANNED && (expanded || volume == WorkoutVolume.MINIMUM &&
                (entry.plan.block == PlanBlock.OPTIONAL || set.plan.purpose == SetRole.WORK && set.id != keep))) {
                set.copy(status = SetStatus.CANCELLED, deferred = true)
            } else set
        }
        entry.copy(athletes = listOf(athlete.copy(sets = sets)))
    })
}

private fun rowExpandedOnly(row: ParticipantWorkout, entry: ExerciseEntry) =
    row.trainingBlocks.any { it.id == entry.plan.blockId && it.expandedOnly }

internal fun substitution(entry: ExerciseEntry, target: Exercise): ExercisePlan {
    val untouched = entry.athletes.single().sets.filter { it.status == SetStatus.PLANNED }
    return entry.plan.copy(exercise = target, individual = emptyMap(), selection = MovementSelection.FIXED,
        sets = untouched.map { it.plan.copy(weight = 0.0, weightKnown = false, repsMin = target.repsMin,
            repsMax = target.repsMax, repsKnown = !target.tracksDuration, maxEffort = false, targetReps = null) })
}

internal fun suggestionsForFamily(
    source: Exercise, selection: MovementSelection, exercises: List<Exercise>, tracks: List<MovementTrack>,
    profileId: String, sessions: List<Session>, today: LocalDate = LocalDate.now(),
): List<Exercise> {
    val familyId = source.movement?.familyId ?: return emptyList()
    val preferences = tracks.filter { it.profileId == profileId }.associateBy { it.exerciseId }
    val role = when (selection) {
        MovementSelection.ANCHOR -> MovementRole.ANCHOR
        MovementSelection.ACTIVE -> MovementRole.ACTIVE
        MovementSelection.MAINTENANCE -> MovementRole.MAINTENANCE
        else -> null
    }
    return exercises.filter { exercise ->
        val track = preferences[exercise.id]
        exercise.id != source.id && !exercise.archived && exercise.movement?.familyId == familyId &&
            track?.paused != true && (role == null || track?.role == role)
    }.let { candidates ->
        val dates = candidates.associate { exercise ->
            exercise.id to sessions.firstOrNull { session ->
                session.completedWorkouts(profileId).any { workSets(it, setOf(exercise.id), setOf(SetStatus.COMPLETED)) > 0 }
            }?.date
        }
        fun due(exercise: Exercise): Boolean {
            val days = preferences[exercise.id]?.repeatDays ?: return false
            val last = dates[exercise.id]?.let(LocalDate::parse)
            return last == null || today >= last.plusDays(days.toLong())
        }
        candidates.sortedWith(compareByDescending<Exercise>(::due).thenByDescending { preferences[it.id]?.priority ?: 0 }
            .thenBy { dates[it.id].orEmpty() }.thenBy { it.name })
    }
}
