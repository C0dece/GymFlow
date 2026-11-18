package app.gymflow.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

fun newId(): String = UUID.randomUUID().toString()

@Serializable
sealed interface Document {
    val id: String
}

@Serializable
@SerialName("profile")
data class Profile(
    override val id: String = newId(),
    val name: String,
    @Serializable(with = ProfileColor::class) val color: Int? = null,
    val compact: Boolean = false,
    val archived: Boolean = false,
) : Document

@Serializable
enum class Load { BARBELL, DUMBBELL, MACHINE, BODYWEIGHT, OTHER }

@Serializable
enum class UnitWeight(val toKg: Double) { KG(1.0), LB(0.45359237) }

@Serializable
@SerialName("exercise")
data class Exercise(
    override val id: String = newId(),
    val name: String,
    val load: Load = Load.OTHER,
    val unit: UnitWeight = UnitWeight.KG,
    val repsMin: Int = 8,
    val repsMax: Int = 12,
    val muscle: String = "",
    val notes: String = "",
    val archived: Boolean = false,
    val favorite: Boolean = false,
) : Document

@Serializable
enum class SetStatus { PLANNED, COMPLETED, SKIPPED, CANCELLED }

@Serializable
data class SetPlan(
    val weight: Double = 0.0,
    val repsMin: Int = 8,
    val repsMax: Int = 12,
    val warmup: Boolean = false,
    val weightKnown: Boolean = true,
    val maxEffort: Boolean = false,
    val repsKnown: Boolean = true,
    val targetReps: Int? = null,
)

@Serializable
data class Actual(val weight: Double, val reps: Int, val note: String = "", val weightKnown: Boolean = true, val repsKnown: Boolean = true)

@Serializable
data class SetEntry(
    val id: String = newId(),
    val plan: SetPlan,
    val actual: Actual? = null,
    val status: SetStatus = SetStatus.PLANNED,
) {
    val proposed: Actual get() = actual ?: Actual(plan.weight, plan.targetReps ?: plan.repsMax, weightKnown = plan.weightKnown, repsKnown = plan.repsKnown && !plan.maxEffort)

    fun toggle(): SetEntry = if (status == SetStatus.COMPLETED) {
        copy(status = SetStatus.PLANNED)
    } else {
        require(proposed.weightKnown && proposed.repsKnown) { "Укажите вес и повторы" }
        copy(actual = proposed, status = SetStatus.COMPLETED)
    }
}

@Serializable
data class Athlete(
    val participantId: String,
    val sets: List<SetEntry>,
    val rir: Int? = null,
    val skipped: Boolean = false,
) {
    val next: Int get() = if (skipped) -1 else sets.indexOfFirst { it.status == SetStatus.PLANNED }
}

@Serializable
data class ExercisePlan(
    val exercise: Exercise,
    val sets: List<SetPlan>,
    val individual: Map<String, List<SetPlan>> = emptyMap(),
) {
    fun athlete(id: String) = Athlete(id, (individual[id] ?: sets).map { SetEntry(plan = it) })
}

@Serializable
data class ExerciseEntry(
    val id: String = newId(),
    val plan: ExercisePlan,
    val athletes: List<Athlete>,
    val replaced: List<ExerciseEntry> = emptyList(),
) {
    val exercise: Exercise get() = plan.exercise
    val counts: Pair<Int, Int> get() {
        val sets = athletes.filterNot { it.skipped }.flatMap { it.sets }
        return sets.count { it.status == SetStatus.COMPLETED } to sets.count { it.status == SetStatus.PLANNED || it.status == SetStatus.COMPLETED }
    }
}

@Serializable
enum class SessionStatus { PLANNED, ACTIVE, COMPLETED, SKIPPED }

@Serializable
enum class WorkoutStatus { PLANNED, ACTIVE, PAUSED, COMPLETED, SKIPPED, CANCELLED }

@Serializable
data class Metrics(
    val bodyWeightKg: Double? = null,
    val feeling: Int? = null,
    val cardioMinutes: Int? = null,
    val note: String = "",
    val highFatigue: Boolean = false,
    val discomfort: Boolean = false,
)

@Serializable
data class WorkoutTemplate(
    val id: String = newId(),
    val title: String,
    val weekday: DayOfWeek = DayOfWeek.MONDAY,
    val exercises: List<ExercisePlan> = emptyList(),
    val notes: String = "",
)

typealias ProgramDay = WorkoutTemplate

@Serializable
@SerialName("program")
data class Program(
    override val id: String = newId(),
    val title: String,
    val days: List<ProgramDay> = emptyList(),
    val enabled: Boolean = false,
    val cycle: Boolean = false,
    val anchor: String = LocalDate.now().toString(),
    val interval: Int = 2,
    val participantIds: List<String> = emptyList(),
) : Document
