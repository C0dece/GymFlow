package app.gymflow.domain

import kotlinx.serialization.Serializable

@Serializable
enum class BlockType(val label: String) {
    WARMUP("Общая разминка"), PREPARATION("Специальная разминка"), MAIN("Основная силовая работа"),
    ACCESSORY("Вспомогательные упражнения"), CARDIO("Кардио"), MOBILITY("Мобильность и навыки"), COOLDOWN("Заминка"), CUSTOM("Другой блок"),
}

@Serializable
data class TrainingBlock(
    val id: String = newId(), val type: BlockType, val name: String, val requirement: PlanBlock = PlanBlock.REQUIRED,
    val guidance: String = "", val enabled: Boolean = true, val expandedOnly: Boolean = false,
) {
    init { require(name.isNotBlank()) }
}

@Serializable
enum class CardioType(val label: String) {
    TREADMILL("Дорожка"), BIKE("Велотренажёр"), ELLIPTICAL("Эллипсоид"), ROWING("Гребля"),
    WALK("Ходьба"), RUN("Бег"), OTHER("Другая активность"),
}

@Serializable
enum class Technique(val label: String) {
    COMPARABLE("Сопоставимая техника"), CHANGED("Техника или амплитуда изменились"),
}

internal val cardioMeasures = setOf(Measure.DURATION, Measure.DISTANCE, Measure.PACE, Measure.SPEED, Measure.INCLINE,
    Measure.RESISTANCE, Measure.PULSE, Measure.MAX_PULSE, Measure.RPE)

internal fun WorkoutTemplate.orderedExercises(): List<ExercisePlan> {
    val blocksById = blocks.associateBy { it.id }
    return exercises.filter { blocksById[it.blockId]?.enabled != false }
        .sortedBy { plan -> blocks.indexOfFirst { it.id == plan.blockId }.takeIf { it >= 0 } ?: blocks.size }
        .map { plan -> blocksById[plan.blockId]?.let { plan.copy(block = it.requirement) } ?: plan }
}

internal fun ParticipantWorkout.skipBlock(blockId: String): ParticipantWorkout =
    withOriginalPlan().copy(exercises = exercises.map { entry ->
        if (entry.plan.blockId != blockId) entry else {
            val athlete = entry.athletes.single()
            entry.copy(athletes = listOf(athlete.copy(sets = athlete.sets.map { set ->
                if (set.status == SetStatus.PLANNED || set.deferred) set.copy(status = SetStatus.SKIPPED, deferred = false) else set
            })))
        }
    })
