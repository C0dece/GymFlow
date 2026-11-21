package app.gymflow.domain

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.round

class ProgressionIndex(sessions: List<Session>) {
    internal data class Sample(val evidence: ProgressionEvidence, val entry: ExerciseEntry, val finished: Boolean) {
        val work = entry.athletes.single().sets.filter { it.plan.purpose == SetRole.WORK }
        private fun observed(set: SetEntry) =
            set.actual?.takeIf { set.status == SetStatus.COMPLETED && it.repsKnown && it.technique != Technique.CHANGED }

        val full = finished && !entry.athletes.single().skipped && work.isNotEmpty() && work.all {
            observed(it) != null
        }
        private fun reaches(set: SetEntry, top: Boolean, target: Boolean = false): Boolean {
            val actual = observed(set) ?: return false
            if (set.plan.maxEffort && top) return false
            val required = if (top) set.plan.repsMax else if (target) set.plan.targetReps ?: set.plan.repsMin else set.plan.repsMin
            if (!set.plan.maxEffort && (!set.plan.repsKnown || actual.reps < required)) return false
            return entry.exercise.load == Load.BODYWEIGHT ||
                (set.plan.weightKnown && actual.weightKnown && actual.weight + EPSILON >= set.plan.weight)
        }
        val touchesMin = full && work.all { reaches(it, false) }
        val touchesTop = full && work.all { reaches(it, true) }
        val meetsTarget = full && work.all { reaches(it, top = false, target = true) }
        val below = work.any { row ->
            row.plan.repsKnown && !row.plan.maxEffort &&
                observed(row)?.let { it.reps < (row.plan.targetReps ?: row.plan.repsMin) } == true
        }
        fun supports(plan: List<SetPlan>, exercise: Exercise): Boolean {
            val required = plan.filter { it.purpose == SetRole.WORK }
            return required.isNotEmpty() && work.size >= required.size && required.zip(work).all { (target, set) ->
                val actual = observed(set) ?: return@all false
                val reps = target.maxEffort || target.repsKnown && actual.reps >= (target.targetReps ?: target.repsMin)
                val weight = exercise.load == Load.BODYWEIGHT ||
                    (actual.weightKnown && target.weightKnown &&
                        actual.weight * entry.exercise.unit.toKg + EPSILON >= target.weight * exercise.unit.toKg)
                reps && weight
            }
        }
        val score: Pair<Double?, Int?> get() = evidence.maxWeightKg to evidence.minReps
    }

    private val templateReceipts = mutableMapOf<Triple<String, String, String>, ProgressionReceipt>()
    private val history = mutableMapOf<Pair<String, String>, MutableList<Sample>>()
    internal val metrics = mutableMapOf<String, MutableList<Metrics>>()
    private val skipped = mutableMapOf<String, Int>()
    private val sawSession = mutableSetOf<String>()

    init {
        sessions.filterNot(Session::discarded).sortedWith(newestSessionFirst).forEach { session ->
            (session.workouts + session.otherWorkouts.asReversed()).forEach { workout ->
                val profile = workout.profile.id
                if (session.status == SessionStatus.SKIPPED || workout.status == WorkoutStatus.SKIPPED) {
                    if (profile !in sawSession) skipped[profile] = (skipped[profile] ?: 0) + 1
                } else if (workout.status == WorkoutStatus.COMPLETED ||
                    (session.status == SessionStatus.COMPLETED && workout in session.workouts)) {
                    sawSession.add(profile)
                    metrics.getOrPut(profile, ::mutableListOf).add(workout.metrics)
                    val entries = workout.exercises.flatMap { listOf(it) + it.replaced }.distinctBy { it.exercise.id to it.exercise.load }
                    if (workout.status == WorkoutStatus.COMPLETED) {
                        val templateId = workout.templateId
                        if (templateId != null) {
                            val receipts = session.progression.filter { it.participantId == profile }.associateBy { it.exerciseEntryId }
                            workout.exercises.forEach { entry ->
                                receipts[entry.id]?.let { receipt ->
                                    templateReceipts.putIfAbsent(Triple(profile, templateId, entry.exercise.id), receipt)
                                }
                            }
                        }
                    }
                    entries.forEach { entry ->
                        val athlete = entry.athletes.single()
                        val work = athlete.sets.filter { it.plan.purpose == SetRole.WORK }
                        val actual = work.mapNotNull { it.actual?.takeIf { a -> it.status == SetStatus.COMPLETED && a.repsKnown && a.technique != Technique.CHANGED } }
                        val evidence = ProgressionEvidence(session.id, session.date, actual.size, work.size,
                            actual.minOfOrNull { it.reps },
                            actual.filter { it.weightKnown }.maxOfOrNull { it.weight * entry.exercise.unit.toKg }, athlete.rir)
                        history.getOrPut(profile to entry.exercise.id, ::mutableListOf)
                            .add(Sample(evidence, entry, workout.status == WorkoutStatus.COMPLETED))
                    }
                }
            }
        }
    }

    internal fun samples(profile: String, exercise: Exercise) =
        history[profile to exercise.id].orEmpty().filter { it.entry.exercise.comparableWith(exercise) }

    internal fun previousTemplatePlan(profile: String, template: String, exercise: Exercise, baseline: List<SetPlan>): List<SetPlan>? =
        templateReceipts[Triple(profile, template, exercise.id)]
            ?.takeIf { it.decision.unit == exercise.unit && it.decision.templateBaseline == baseline }
            ?.finalPlan

    internal fun skipped(profile: String) = skipped[profile] ?: 0
}

private const val EPSILON = 0.000001

class ProgressionEngine(private val index: ProgressionIndex, private val policies: List<ProgressionPolicy>) {
    fun decide(profileId: String, programId: String?, plan: ExercisePlan, templateId: String? = null): ProgressionDecision {
        val exercise = plan.exercise
        val matched = policies.filter { it.scope.matches(profileId, programId, exercise.id) }
            .sortedWith(compareByDescending<ProgressionPolicy> { it.scope.rank }.thenBy { it.id })
        val policy = matched.firstOrNull()
        val lock = matched.firstOrNull { it.locked }
        val config = policy?.ruleSet ?: RuleSet()
        val rule = config.constraint
        val all = index.samples(profileId, exercise)
        val history = all.take(20)
        val metrics = index.metrics[profileId].orEmpty()
        val latest = history.firstOrNull()
        val baseline = plan.individual[profileId] ?: plan.sets
        fun decision(action: ActionKind, reason: ProgressionReason, proposed: List<SetPlan> = baseline,
            rules: List<String> = emptyList(), selectedPolicy: ProgressionPolicy? = policy): ProgressionDecision {
            return ProgressionDecision(profileId, exercise.id, exercise.name, exercise.unit,
                selectedPolicy?.ruleSet?.strategy ?: config.strategy, selectedPolicy?.id,
                selectedPolicy?.version ?: 1, action, reason, rules, history.map { it.evidence }, baseline, proposed,
                rules.map { id -> config.rules.find { it.id == id }?.name?.takeIf { it.isNotBlank() } ?: id.take(8) },
                baseline.takeIf { templateId != null })
        }
        if (exercise.tracksDuration || baseline.any { it.durationSeconds != null }) {
            return decision(ActionKind.HOLD, ProgressionReason.DISABLED)
        }
        val latestMetrics = metrics.firstOrNull()
        if (latestMetrics?.discomfort == true) return decision(ActionKind.REVIEW_EXERCISE, ProgressionReason.DISCOMFORT)
        if (lock != null) return decision(ActionKind.HOLD, ProgressionReason.LOCKED, selectedPolicy = lock)
        if (config.strategy == ProgressionStrategy.MAINTAIN) return decision(ActionKind.HOLD, ProgressionReason.DISABLED)

        val eligible = history.count { it.evidence.confirmed > 0 }
        val noData = eligible < rule.minSessions
        val misses = history.take(rule.failureWindow).let { it.size == rule.failureWindow && it.all(ProgressionIndex.Sample::below) }
        val highFatigue = latestMetrics?.highFatigue == true
        val rir = latest?.evidence?.rir
        val skipped = index.skipped(profileId)
        val weights = metrics.mapNotNull { it.bodyWeightKg }.take(2)
        val wave = config.wave[all.count { it.full } % config.wave.size]

        fun matches(condition: Condition): Boolean {
            val window = history.take(condition.sessions)
            return when (condition.kind) {
                ConditionKind.ALL_WORK -> latest?.full == true
                ConditionKind.TOP_REPS -> latest?.touchesTop == true
                ConditionKind.MIN_REPS -> latest?.touchesMin == true
                ConditionKind.RIR_AT_LEAST -> rir != null && rir >= condition.threshold
                ConditionKind.RIR_AVERAGE_BELOW -> {
                    val values = window.mapNotNull { it.evidence.rir }
                    values.size == condition.sessions && values.average() < condition.threshold
                }
                ConditionKind.PLATEAU -> window.size == condition.sessions && window.all { it.full } &&
                    window.distinctBy { it.score }.size == 1
                ConditionKind.MISSED_TARGET -> window.size == condition.sessions && window.all { it.below }
                ConditionKind.FIRST_EXPOSURE -> history.none { it.evidence.confirmed > 0 }
                ConditionKind.BODY_MASS_CHANGE -> weights.size == 2 && abs(weights[0] - weights[1]) >= condition.threshold
                ConditionKind.FATIGUE -> highFatigue
                ConditionKind.SKIPPED -> skipped >= condition.sessions
                ConditionKind.INSUFFICIENT -> eligible < condition.sessions
            }
        }

        val candidate = when {
            config.strategy == ProgressionStrategy.DELOAD -> Action(ActionKind.DELOAD, rule.deloadPercent) to ProgressionReason.MANUAL_DELOAD
            config.strategy == ProgressionStrategy.CUSTOM -> null
            highFatigue -> Action(ActionKind.DELOAD, rule.deloadPercent) to ProgressionReason.FATIGUE
            misses -> Action(ActionKind.DELOAD, rule.deloadPercent) to ProgressionReason.FAILURE_STREAK
            skipped >= 2 -> Action(ActionKind.REPEAT) to ProgressionReason.SKIPPED
            noData -> Action(ActionKind.NO_DATA) to ProgressionReason.INSUFFICIENT
            latest?.full != true -> Action(ActionKind.REPEAT) to ProgressionReason.INCOMPLETE
            !latest.meetsTarget -> Action(ActionKind.REPEAT) to ProgressionReason.BELOW_MIN
            config.strategy in setOf(ProgressionStrategy.DOUBLE, ProgressionStrategy.AUTO) && !latest.touchesTop ->
                Action(ActionKind.INCREASE_REPS, 1.0) to ProgressionReason.TOP_NOT_REACHED
            config.strategy == ProgressionStrategy.RIR && (rir == null || rir <= rule.targetRir) ->
                Action(ActionKind.HOLD) to if (rir == null) ProgressionReason.RIR_MISSING else ProgressionReason.RIR_LOW
            config.strategy == ProgressionStrategy.WAVE -> Action(ActionKind.NEXT_WAVE) to ProgressionReason.PERIODIC
            config.strategy == ProgressionStrategy.REPS -> Action(ActionKind.INCREASE_REPS, 1.0) to ProgressionReason.SUCCESS
            config.strategy == ProgressionStrategy.VOLUME -> Action(ActionKind.CHANGE_SETS, 1.0) to ProgressionReason.SUCCESS
            else -> Action(ActionKind.INCREASE_WEIGHT, config.linearPercent ?: rule.stepKg, config.linearPercent != null) to ProgressionReason.SUCCESS
        }

        val applicable = if (candidate == null) config.rules.filter { it.conditions.all(::matches) } else emptyList()
        val highest = applicable.maxOfOrNull { it.priority }
        val winners = applicable.filter { it.priority == highest }.sortedBy { it.id }
        val ids = winners.map { it.id }
        if (winners.map { it.action }.distinct().size > 1) return decision(ActionKind.HOLD, ProgressionReason.RULE_CONFLICT, rules = ids)
        val (action, reason) = candidate ?: (winners.firstOrNull()?.action ?: return decision(ActionKind.HOLD, ProgressionReason.NO_RULE))
            .let { it to ProgressionReason.RULE }
        val increases = when (action.kind) {
            ActionKind.INCREASE_WEIGHT, ActionKind.INCREASE_REPS -> true
            ActionKind.CHANGE_SETS -> action.amount > 0
            ActionKind.NEXT_WAVE -> wave.weightPercent > 0 || wave.repsDelta > 0 || wave.setsDelta > 0
            else -> false
        }
        val actionable = action.kind !in setOf(ActionKind.HOLD, ActionKind.REPEAT, ActionKind.REVIEW_EXERCISE, ActionKind.NO_DATA)
        val workingPlan = templateId?.takeIf { actionable }?.let {
            index.previousTemplatePlan(profileId, it, exercise, baseline)
        } ?: baseline
        if (increases) {
            val blocked = when {
                highFatigue -> ProgressionReason.FATIGUE
                skipped >= 2 -> ProgressionReason.SKIPPED
                noData -> ProgressionReason.INSUFFICIENT
                latest?.full != true -> ProgressionReason.INCOMPLETE
                !latest.meetsTarget || !latest.supports(workingPlan, exercise) -> ProgressionReason.BELOW_MIN
                rir == null && rule.requireRir -> ProgressionReason.RIR_MISSING
                rir != null && rir < rule.targetRir -> ProgressionReason.RIR_LOW
                else -> null
            }
            if (blocked != null) return decision(ActionKind.HOLD, blocked, rules = ids)
        }
        val work = workingPlan.filter { it.purpose == SetRole.WORK }
        val starting = if (action.kind == ActionKind.INCREASE_REPS && config.strategy !in setOf(ProgressionStrategy.AUTO, ProgressionStrategy.DOUBLE)) {
            val recorded = latest?.evidence?.minReps
            work.map { set ->
                if (set.maxEffort && recorded != null && recorded in rule.minReps until rule.maxReps) {
                    set.copy(repsMin = recorded, repsMax = recorded, repsKnown = true, maxEffort = false, targetReps = null)
                } else set
            }
        } else if (action.kind == ActionKind.INCREASE_REPS) {
            work.map { set -> set.copy(targetReps = set.targetReps ?: latest?.evidence?.minReps
                ?.takeIf { set.repsKnown && !set.maxEffort }?.coerceIn(set.repsMin, set.repsMax)) }
        } else work
        val applied = applyAction(starting, exercise, config, action, wave)
        val merged = if (applied == work) workingPlan else mergeWork(workingPlan, applied)
        return if (merged == workingPlan && actionable && action.kind != ActionKind.NEXT_WAVE) {
            decision(ActionKind.HOLD, if (reason == ProgressionReason.TOP_NOT_REACHED) reason else ProgressionReason.EQUIPMENT_OR_LIMIT, rules = ids)
        } else decision(action.kind, reason, merged, ids)
    }
}

private fun mergeWork(baseline: List<SetPlan>, work: List<SetPlan>): List<SetPlan> = buildList {
    val iterator = work.iterator()
    baseline.forEach { if (it.purpose != SetRole.WORK) add(it) else if (iterator.hasNext()) add(iterator.next()) }
    while (iterator.hasNext()) add(iterator.next())
}

private fun applyAction(
    work: List<SetPlan>, exercise: Exercise, config: RuleSet, action: Action, wave: WaveStep,
): List<SetPlan> {
    if (work.isEmpty()) return work
    val c = config.constraint
    fun reweight(amount: Double, percent: Boolean, resetReps: Boolean = false): List<SetPlan> {
        if (exercise.load !in setOf(Load.BARBELL, Load.DUMBBELL, Load.MACHINE) || work.any { !it.weightKnown }) return work
        val changed = work.map { set ->
            val kg = set.weight * exercise.unit.toKg
            val next = allowedWeight(kg, amount, percent, c) ?: return work
            set.copy(weight = next / exercise.unit.toKg,
                targetReps = if (resetReps && set.repsKnown && !set.maxEffort) set.repsMin else set.targetReps)
        }
        return changed
    }
    fun reps(sets: List<SetPlan>, delta: Int): List<SetPlan> = sets.map { set ->
        if (!set.repsKnown || set.maxEffort || delta == 0) set else {
            val lo = (set.repsMin + delta).coerceIn(c.minReps, c.maxReps)
            val hi = (set.repsMax + delta).coerceIn(lo, c.maxReps)
            if (delta > 0 && (lo < set.repsMin || hi < set.repsMax) ||
                delta < 0 && (lo > set.repsMin || hi > set.repsMax)) set else set.copy(repsMin = lo, repsMax = hi, targetReps = set.targetReps?.let { (it + delta).coerceIn(lo, hi) })
        }
    }
    fun volume(sets: List<SetPlan>, delta: Int): List<SetPlan> {
        val size = (sets.size + delta).coerceIn(c.minSets, c.maxSets)
        if (delta == 0 || (size - sets.size) * delta <= 0) return sets
        return List(size) { index -> sets.getOrElse(index) { sets.last() } }
    }
    val amount = abs(action.amount)
    return when (action.kind) {
        ActionKind.INCREASE_WEIGHT -> reweight(amount.takeIf { it > 0 } ?: c.stepKg, action.percent,
            config.strategy in setOf(ProgressionStrategy.AUTO, ProgressionStrategy.DOUBLE))
        ActionKind.DECREASE_WEIGHT -> reweight(-(amount.takeIf { it > 0 } ?: c.stepKg), action.percent)
        ActionKind.INCREASE_REPS -> if (config.strategy in setOf(ProgressionStrategy.AUTO, ProgressionStrategy.DOUBLE)) {
            work.map { set ->
                val target = set.targetReps
                if (target != null && set.repsKnown && !set.maxEffort)
                    set.copy(targetReps = (target + 1).coerceAtMost(set.repsMax)) else set
            }
        } else reps(work, amount.toInt().coerceIn(1, 10))
        ActionKind.CHANGE_SETS -> volume(work, action.amount.toInt().coerceIn(-5, 5))
        ActionKind.DELOAD -> {
            val lighter = reweight(-(amount.takeIf { it > 0 } ?: c.deloadPercent), true)
            if (lighter != work) lighter else volume(work, -1).takeIf { it != work } ?: reps(work, -1)
        }
        ActionKind.NEXT_WAVE -> {
            val weighted = if (wave.weightPercent == 0.0) work else reweight(wave.weightPercent, true)
            if (wave.weightPercent != 0.0 && weighted == work) work
            else volume(reps(weighted, wave.repsDelta), wave.setsDelta)
        }
        else -> work
    }
}

internal fun allowedWeight(current: Double, amount: Double, percent: Boolean, c: Constraint): Double? {
    val change = if (percent) current * amount / 100.0 else amount
    if (!change.isFinite() || abs(change) < EPSILON) return null
    val cap = minOf(c.maxChangeKg, current * c.maxChangePercent / 100.0)
    val lower = maxOf(c.minWeightKg, current + if (change > 0) c.minChangeKg else -cap)
    val upper = minOf(c.maxWeightKg, current + if (change > 0) cap else -c.minChangeKg)
    if (lower > upper + EPSILON) return null
    val desired = current + change
    return if (c.equipmentKg.isNotEmpty()) {
        c.equipmentKg.filter { it >= lower - EPSILON && it <= upper + EPSILON && abs(it - current) >= c.minChangeKg - EPSILON }
            .minWithOrNull(compareBy<Double> { abs(it - desired) }.thenBy { abs(it - current) }.thenBy { it })
    } else {
        val low = ceil((lower - EPSILON) / c.stepKg)
        val high = floor((upper + EPSILON) / c.stepKg)
        if (low > high) null else (round(desired / c.stepKg).coerceIn(low, high) * c.stepKg)
            .takeIf { it.isFinite() && abs(it - current) >= c.minChangeKg - EPSILON }
    }
}
