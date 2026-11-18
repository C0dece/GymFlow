package app.gymflow.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ProgressionStrategy { AUTO, DOUBLE, LINEAR, REPS, VOLUME, RIR, WAVE, MAINTAIN, DELOAD, CUSTOM }

@Serializable
enum class ConditionKind {
    ALL_WORK, TOP_REPS, MIN_REPS, RIR_AT_LEAST, RIR_AVERAGE_BELOW,
    PLATEAU, MISSED_TARGET, FIRST_EXPOSURE, BODY_MASS_CHANGE, FATIGUE, SKIPPED, INSUFFICIENT,
}

@Serializable
data class Condition(val kind: ConditionKind, val sessions: Int = 2, val threshold: Double = 1.0) {
    init { require(sessions in 1..20 && threshold.isFinite() && threshold >= 0.0) }
}

@Serializable
enum class ActionKind { INCREASE_WEIGHT, DECREASE_WEIGHT, HOLD, INCREASE_REPS, CHANGE_SETS, DELOAD, REPEAT, NEXT_WAVE, REVIEW_EXERCISE, NO_DATA }

@Serializable
data class Action(val kind: ActionKind, val amount: Double = 0.0, val percent: Boolean = false) {
    init { require(amount.isFinite() && amount in -1000.0..1000.0) }
}

@Serializable
data class Constraint(
    val stepKg: Double = 1.0,
    val minChangeKg: Double = 0.25,
    val maxChangeKg: Double = 5.0,
    val maxChangePercent: Double = 15.0,
    val minWeightKg: Double = 0.0,
    val maxWeightKg: Double = 500.0,
    val equipmentKg: List<Double> = emptyList(),
    val minReps: Int = 1,
    val maxReps: Int = 20,
    val minSets: Int = 1,
    val maxSets: Int = 6,
    val targetRir: Int = 2,
    val requireRir: Boolean = true,
    val minSessions: Int = 2,
    val failureWindow: Int = 2,
    val deloadPercent: Double = 10.0,
) {
    init {
        require(listOf(stepKg, minChangeKg, maxChangeKg, maxChangePercent, minWeightKg, maxWeightKg, deloadPercent).all { it.isFinite() })
        require(stepKg > 0 && minChangeKg > 0 && maxChangeKg >= minChangeKg)
        require(maxChangePercent in 0.1..50.0 && deloadPercent in 0.1..maxChangePercent)
        require(minWeightKg >= 0 && maxWeightKg >= minWeightKg)
        require(equipmentKg.size <= 128 && equipmentKg.all { it.isFinite() && it in minWeightKg..maxWeightKg })
        require(minReps in 1..99 && maxReps in minReps..99 && minSets in 1..30 && maxSets in minSets..30)
        require(targetRir in 0..4 && minSessions in 1..20 && failureWindow in 2..20)
    }
}

@Serializable
data class WaveStep(val weightPercent: Double, val repsDelta: Int = 0, val setsDelta: Int = 0) {
    init { require(weightPercent.isFinite() && weightPercent in -50.0..50.0 && repsDelta in -10..10 && setsDelta in -5..5) }
}

@Serializable
data class Rule(
    val id: String = newId(),
    val version: Int = 1,
    val priority: Int = 0,
    val conditions: List<Condition> = emptyList(),
    val action: Action,
    val name: String = "",
) {
    init { require(version == 1 && priority in -100..100 && conditions.size <= ConditionKind.entries.size) }
}

@Serializable
data class RuleSet(
    val strategy: ProgressionStrategy = ProgressionStrategy.AUTO,
    val constraint: Constraint = Constraint(),
    val rules: List<Rule> = emptyList(),
    val linearPercent: Double? = null,
    val wave: List<WaveStep> = listOf(WaveStep(0.0), WaveStep(5.0, -1), WaveStep(-10.0, 2, -1)),
) {
    init {
        require(rules.size <= 30 && rules.distinctBy { it.id }.size == rules.size)
        require(linearPercent == null || (linearPercent.isFinite() && linearPercent in 0.1..50.0))
        require(wave.size in 1..12)
    }
}

@Serializable
data class ProgressionScope(val profileId: String? = null, val programId: String? = null, val exerciseId: String? = null) {
    val rank get() = (if (exerciseId != null) 4 else 0) + (if (profileId != null) 2 else 0) + (if (programId != null) 1 else 0)

    fun matches(profile: String, program: String?, exercise: String) =
        (profileId == null || profileId == profile) && (programId == null || programId == program) &&
            (exerciseId == null || exerciseId == exercise)

    fun documentId() = stableId("progression/${profileId.orEmpty()}/${programId.orEmpty()}/${exerciseId.orEmpty()}")
}

@Serializable
@SerialName("progression_policy")
data class ProgressionPolicy(
    val scope: ProgressionScope = ProgressionScope(),
    override val id: String = scope.documentId(),
    val version: Int = 1,
    val ruleSet: RuleSet = RuleSet(),
    val locked: Boolean = false,
) : Document {
    init { require(version == 1) }
}

@Serializable
data class ProgressionEvidence(
    val sessionId: String,
    val date: String,
    val confirmed: Int,
    val required: Int,
    val minReps: Int?,
    val maxWeightKg: Double?,
    val rir: Int?,
)

@Serializable
enum class ProgressionReason {
    DISCOMFORT, LOCKED, DISABLED, INSUFFICIENT, INCOMPLETE, BELOW_MIN, TOP_NOT_REACHED,
    RIR_MISSING, RIR_LOW, FATIGUE, FAILURE_STREAK, SKIPPED, SUCCESS, RULE, RULE_CONFLICT,
    PERIODIC, MANUAL_DELOAD, EQUIPMENT_OR_LIMIT, NO_RULE,
}

@Serializable
data class ProgressionDecision(
    val profileId: String,
    val exerciseId: String,
    val exerciseName: String,
    val unit: UnitWeight,
    val strategy: ProgressionStrategy,
    val policyId: String?,
    val policyVersion: Int = 1,
    val action: ActionKind,
    val reason: ProgressionReason,
    val ruleIds: List<String> = emptyList(),
    val evidence: List<ProgressionEvidence> = emptyList(),
    val baseline: List<SetPlan>,
    val proposed: List<SetPlan> = baseline,
    val ruleNames: List<String> = emptyList(),
    val templateBaseline: List<SetPlan>? = null,
)

@Serializable
enum class UserProgressionChoice { ACCEPTED, KEPT, MANUAL }

@Serializable
data class ProgressionReceipt(
    val participantId: String,
    val exerciseEntryId: String,
    val decision: ProgressionDecision,
    val choice: UserProgressionChoice,
    val finalPlan: List<SetPlan>,
)

@Serializable
@SerialName("progression_review")
data class ProgressionReview(
    val sessionId: String,
    val profileId: String,
    val date: String,
    val decisions: List<ProgressionDecision>,
    val part: Int = 0,
    override val id: String = stableId("progression-review/$sessionId/$profileId" + if (part == 0) "" else "/$part"),
    val version: Int = 1,
) : Document {
    init { require(version == 1) }
}
