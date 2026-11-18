package app.gymflow.domain

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class ProgressionParametersTest(
    private val low: Int, private val high: Int, private val step: Double, private val rir: Int?, private val raises: Boolean,
) {
    @Test fun doubleProgressionForDifferentRangesStepsAndRir() {
        val profile = Profile(name = "QA")
        val plan = ExercisePlan(Exercise(name = "QA Leg", load = Load.MACHINE), List(2) { SetPlan(40.0, low, high) })
        val history = (1..2).map { day ->
            val session = Session(title = "QA", date = "2026-10-0$day", participants = listOf(profile)).start().addExercise(plan)
            session.editAthlete(session.workouts[0].exercises[0].id, profile.id) { a ->
                a.copy(sets = a.sets.map { it.toggle() }, rir = rir)
            }.finish()
        }
        val policy = ProgressionPolicy(ruleSet = RuleSet(ProgressionStrategy.DOUBLE,
            Constraint(stepKg = step, maxChangeKg = 5.0)))
        val next = ProgressionEngine(ProgressionIndex(history), listOf(policy)).decide(profile.id, null, plan)
        assertEquals(raises, next.action == ActionKind.INCREASE_WEIGHT)
        assertEquals(if (raises) 40 + step else 40.0, next.proposed.first().weight, 0.01)
        assertEquals(high, next.proposed.first().repsMax)
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}-{1}, +{2} kg, RIR {3}")
        fun cases(): List<Array<Any?>> = listOf(
            arrayOf<Any?>(6, 8, 0.5, 2, true), arrayOf<Any?>(8, 12, 1.0, 4, true), arrayOf<Any?>(10, 15, 2.5, 3, true),
            arrayOf<Any?>(8, 10, 2.0, 1, false), arrayOf<Any?>(12, 15, 1.0, null, false), arrayOf<Any?>(12, 12, 5.0, 0, false),
        )
    }
}
