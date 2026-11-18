package app.gymflow.domain

import org.junit.Assert.*
import org.junit.Test

private val alice = Profile(name = "QA Alice")
private val bob = Profile(name = "QA Bob")
private val pull = Exercise(name = "QA Pull", load = Load.MACHINE)
private val base = ExercisePlan(pull, List(3) { SetPlan(20.0, 8, 12) })

private fun sample(
    day: Int, reps: List<Int> = listOf(12, 12, 12), rir: Int? = 2,
    profile: Profile = alice, plan: ExercisePlan = base, metrics: Metrics = Metrics(),
    status: SessionStatus = SessionStatus.COMPLETED,
): Session {
    val session = Session(id = stableId("test/$day/${profile.id}"), title = "QA", date = "2026-10-${day.toString().padStart(2, '0')}",
        status = status, workouts = listOf(ParticipantWorkout(profile = profile, status = WorkoutStatus.COMPLETED, metrics = metrics).addExercise(plan)))
    val entry = session.workouts.single().exercises.single()
    var work = 0
    return session.editAthlete(entry.id, profile.id) { a ->
        a.copy(rir = rir, sets = a.sets.map { row ->
            val result = if (row.plan.warmup) null else reps.getOrNull(work++)
            if (result == null) row else row.copy(actual = Actual(row.plan.weight, result), status = SetStatus.COMPLETED)
        })
    }
}

private fun preset(strategy: ProgressionStrategy, constraint: Constraint = Constraint()) =
    ProgressionPolicy(ruleSet = RuleSet(strategy, constraint))

class ProgressionTest {
    private val good = listOf(sample(1), sample(2))
    private fun evaluate(history: List<Session>, policy: ProgressionPolicy = preset(ProgressionStrategy.DOUBLE), plan: ExercisePlan = base) =
        ProgressionEngine(ProgressionIndex(history), listOf(policy)).decide(alice.id, null, plan)

    @Test fun doubleProgressionUsesAllSetsAndPreservesRangeAfterIncrease() {
        val decision = evaluate(good)
        assertEquals(ActionKind.INCREASE_WEIGHT, decision.action)
        val next = decision.proposed.first()
        assertEquals(21.0, next.weight, 0.0)
        assertEquals(8, next.targetReps)
        assertEquals(12, next.repsMax)
        val future = base.copy(sets = decision.proposed)
        val ramp = evaluate(good + sample(3, listOf(8, 8, 8), plan = future), plan = future)
        assertEquals(21.0, ramp.proposed.first().weight, 0.0)
        assertEquals(9, ramp.proposed.first().targetReps)
        assertEquals(ProgressionReason.TOP_NOT_REACHED, ramp.reason)
    }

    @Test fun repeatsOfTheSameTemplateContinueFromTheLastConfirmedPlan() {
        val template = stableId("test/template")
        val linear = preset(ProgressionStrategy.LINEAR)
        val first = ProgressionEngine(ProgressionIndex(good), listOf(linear)).decide(alice.id, null, base, template)
        val trained = sample(3, plan = base.copy(sets = first.proposed))
        val member = trained.workouts.single()
        val receipt = ProgressionReceipt(alice.id, member.exercises.single().id, first, UserProgressionChoice.ACCEPTED, first.proposed)
        val completed = trained.copy(workouts = listOf(member.copy(templateId = template)), progression = listOf(receipt))
        val engine = ProgressionEngine(ProgressionIndex(good + completed), listOf(linear))
        val next = engine.decide(alice.id, null, base, template)
        assertEquals(22.0, next.proposed.first().weight, 0.0)
        assertEquals(20.0, next.baseline.first().weight, 0.0)
        assertEquals(21.0, engine.decide(alice.id, null, base, stableId("test/other-template")).proposed.first().weight, 0.0)
        val heavier = base.copy(sets = base.sets.map { it.copy(weight = 40.0) })
        assertEquals(ProgressionReason.BELOW_MIN, engine.decide(alice.id, null, heavier, template).reason)
    }

    @Test fun missingSetAndLowerRangeDoNotRaiseWeight() {
        assertEquals(ProgressionReason.INCOMPLETE, evaluate(good + sample(3, listOf(12, 12))).reason)
        assertEquals(ProgressionReason.TOP_NOT_REACHED, evaluate(good + sample(3, listOf(8, 9, 10))).reason)
        assertEquals(ProgressionReason.INSUFFICIENT, evaluate(listOf(good.first())).reason)
        assertEquals(ProgressionReason.INSUFFICIENT, evaluate(emptyList()).reason)
    }

    @Test fun rirBelowAndMissingAreConservativeAndExplicitOptOutWorks() {
        assertEquals(ProgressionReason.RIR_LOW, evaluate(good + sample(3, rir = 0)).reason)
        assertEquals(ProgressionReason.RIR_MISSING, evaluate(good + sample(3, rir = null)).reason)
        assertEquals(ActionKind.INCREASE_WEIGHT, evaluate(good + sample(3, rir = null),
            preset(ProgressionStrategy.DOUBLE, Constraint(requireRir = false))).action)
        assertEquals(ActionKind.HOLD, evaluate(good, preset(ProgressionStrategy.RIR)).action)
        assertEquals(ActionKind.INCREASE_WEIGHT, evaluate(good + sample(3, rir = 3), preset(ProgressionStrategy.RIR)).action)
    }

    @Test fun historiesStayIndependentOfNamesProgramsAndOtherParticipants() {
        val renamed = base.copy(exercise = pull.copy(name = "Новое название"))
        val policy = ProgressionPolicy(ProgressionScope(profileId = bob.id, exerciseId = pull.id),
            ruleSet = RuleSet(ProgressionStrategy.MAINTAIN))
        val engine = ProgressionEngine(ProgressionIndex(good + sample(3, profile = bob, reps = listOf(8, 8, 8))), listOf(policy))
        assertEquals(ActionKind.INCREASE_WEIGHT, engine.decide(alice.id, newId(), renamed).action)
        assertEquals(ProgressionReason.DISABLED, engine.decide(bob.id, null, renamed).reason)
        assertEquals(ProgressionReason.INSUFFICIENT, engine.decide(newId(), null, renamed).reason)
        assertEquals(ProgressionReason.INSUFFICIENT, engine.decide(alice.id, null, base.copy(exercise = pull.copy(id = newId()))).reason)
    }

    @Test fun discomfortOverridesLocksAndLocksOverrideSpecialRules() {
        val lock = ProgressionPolicy(locked = true)
        val special = ProgressionPolicy(ProgressionScope(exerciseId = pull.id), ruleSet = RuleSet(ProgressionStrategy.LINEAR))
        val engine = ProgressionEngine(ProgressionIndex(good), listOf(special, lock))
        assertEquals(ProgressionReason.LOCKED, engine.decide(alice.id, null, base).reason)
        val caution = ProgressionEngine(ProgressionIndex(good + sample(3, metrics = Metrics(discomfort = true))), listOf(lock, special))
        assertEquals(ActionKind.REVIEW_EXERCISE, caution.decide(alice.id, null, base).action)
    }

    @Test fun repeatedFailuresAndFatigueSuggestDeloadWithoutInventingFacts() {
        val low = listOf(sample(1, listOf(6, 7, 7)), sample(2, listOf(7, 7, 7)))
        val deload = evaluate(low)
        assertEquals(ProgressionReason.FAILURE_STREAK, deload.reason)
        assertEquals(18.0, deload.proposed.first().weight, 0.0)
        assertEquals(ProgressionReason.FATIGUE, evaluate(good + sample(3, metrics = Metrics(highFatigue = true))).reason)
        assertEquals(ProgressionReason.MANUAL_DELOAD, evaluate(emptyList(), preset(ProgressionStrategy.DELOAD)).reason)
    }

    @Test fun severalSkippedSessionsRepeatWithoutUsingCalendarWeeks() {
        val skip = good + sample(9, status = SessionStatus.SKIPPED) + sample(20, status = SessionStatus.SKIPPED)
        assertEquals(ProgressionReason.SKIPPED, evaluate(skip).reason)
        assertEquals(ActionKind.INCREASE_WEIGHT, evaluate(listOf(sample(1), sample(25))).action)
    }

    @Test fun equipmentAndBoundsPreventUnjustifiedJump() {
        val blocked = preset(ProgressionStrategy.LINEAR, Constraint(equipmentKg = listOf(20.0, 25.0)))
        assertEquals(ProgressionReason.EQUIPMENT_OR_LIMIT, evaluate(good, blocked).reason)
        val available = preset(ProgressionStrategy.LINEAR, Constraint(equipmentKg = listOf(20.0, 21.5, 25.0)))
        assertEquals(21.5, evaluate(good, available).proposed.first().weight, 0.0)
        val percent = ProgressionPolicy(ruleSet = RuleSet(ProgressionStrategy.LINEAR, linearPercent = 10.0))
        assertEquals(22.0, evaluate(good, percent).proposed.first().weight, 0.0)
    }

    @Test fun repsAndVolumeAreBoundedAndWarmupsAreUntouched() {
        val warm = SetPlan(5.0, 5, 5, warmup = true)
        val plan = base.copy(sets = listOf(warm) + base.sets)
        val history = listOf(sample(1, plan = plan), sample(2, plan = plan))
        val volume = evaluate(history, preset(ProgressionStrategy.VOLUME, Constraint(maxSets = 4)), plan)
        assertEquals(5, volume.proposed.size)
        assertEquals(warm, volume.proposed.first())
        val limited = evaluate(history, preset(ProgressionStrategy.VOLUME, Constraint(maxSets = 3)), plan)
        assertEquals(ProgressionReason.EQUIPMENT_OR_LIMIT, limited.reason)
        assertEquals(13, evaluate(history, preset(ProgressionStrategy.REPS), plan).proposed.last().repsMax)
    }

    @Test fun maxEffortBodyweightCanUseObservedRepsAsTarget() {
        val plan = ExercisePlan(pull.copy(load = Load.BODYWEIGHT), List(3) {
            SetPlan(0.0, 1, 1, maxEffort = true, repsKnown = false)
        })
        val history = listOf(sample(1, listOf(6, 5, 4), plan = plan), sample(2, listOf(7, 6, 5), plan = plan))
        val next = evaluate(history, preset(ProgressionStrategy.REPS), plan).proposed
        assertEquals(6, next.first().repsMax)
        assertFalse(next.first().maxEffort)
        assertEquals(0.0, next.first().weight, 0.0)
    }

    @Test fun rulesAreDeterministicAndEqualPriorityContradictionHolds() {
        val rise = Rule(id = stableId("rise"), conditions = listOf(Condition(ConditionKind.TOP_REPS)), action = Action(ActionKind.INCREASE_WEIGHT, 1.0))
        val fall = Rule(id = stableId("fall"), action = Action(ActionKind.DECREASE_WEIGHT, 1.0))
        fun custom(rules: List<Rule>) = ProgressionPolicy(ruleSet = RuleSet(ProgressionStrategy.CUSTOM, rules = rules))
        val forward = evaluate(good, custom(listOf(rise, fall)))
        assertEquals(ProgressionReason.RULE_CONFLICT, forward.reason)
        assertEquals(forward, evaluate(good.reversed(), custom(listOf(fall, rise))))
        val chosen = evaluate(good, custom(listOf(fall, rise.copy(priority = 10))))
        assertEquals(listOf(rise.id), chosen.ruleIds)
        assertEquals(21.0, chosen.proposed.first().weight, 0.0)
    }

    @Test fun wavesFollowActualObservationsAndChangingStrategyKeepsHistory() {
        val wave = ProgressionPolicy(ruleSet = RuleSet(ProgressionStrategy.WAVE, wave = listOf(WaveStep(0.0), WaveStep(5.0), WaveStep(-10.0, 1, -1))))
        val plan = evaluate(listOf(sample(1), sample(21)), wave)
        assertEquals(ActionKind.NEXT_WAVE, plan.action)
        assertEquals(2, plan.proposed.size)
        assertEquals(18.0, plan.proposed.first().weight, 0.0)
        assertEquals(2, plan.evidence.size)
        val maintained = evaluate(good, wave.copy(ruleSet = wave.ruleSet.copy(strategy = ProgressionStrategy.MAINTAIN)))
        assertEquals(plan.evidence.size, maintained.evidence.size)
        assertEquals(ProgressionReason.DISABLED, maintained.reason)
    }

    @Test fun policyAndReviewAndReceiptRoundTripInV3() {
        val policy = preset(ProgressionStrategy.DOUBLE)
        val result = evaluate(good, policy)
        val review = ProgressionReview(good.last().id, alice.id, good.last().date, listOf(result))
        val entry = good.last().workouts.single().exercises.single()
        val receipt = ProgressionReceipt(alice.id, entry.id, result, UserProgressionChoice.MANUAL, base.sets)
        val workout = good.last().copy(progression = listOf(receipt))
        listOf(policy, review, workout).forEach {
            val md = Markdown.write(it)
            assertTrue(md.contains("gymflow_schema: 3"))
            assertEquals(it, Markdown.read(md)?.document)
        }
    }
}
