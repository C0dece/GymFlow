package app.gymflow.domain

import app.gymflow.ui.PlanSetDraft
import app.gymflow.ui.plansOrNull
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class ProgramPlanEditingTest {
    @Test fun independentSetsKeepZeroDecimalsUnknownsAndSpecificTargetsInMarkdown() {
        val base = SetPlan(7.5, 8, 12, targetReps = 10)
        val a = PlanSetDraft("a", base, weight = "0", low = "12", high = "", target = "", role = SetRole.WARMUP)
        val b = PlanSetDraft("b", base, weight = "22,75", low = "7", high = "9", target = "8")
        val c = PlanSetDraft("c", base, weight = "", low = "", high = "", target = "")
        val plans = requireNotNull(listOf(b, a, c).plansOrNull())
        assertEquals(listOf(22.75, 0.0, base.weight), plans.map { it.weight })
        assertEquals(listOf(7 to 9, 12 to 12, 8 to 12), plans.map { it.repsMin to it.repsMax })
        assertEquals(8, plans.first().targetReps)
        assertEquals(SetRole.WARMUP, plans[1].purpose)
        assertTrue(plans[1].weightKnown)
        assertFalse(plans.last().weightKnown)
        assertFalse(plans.last().repsKnown)
        assertNull(b.copy(weight = "-1").result())
        assertNull(b.copy(low = "").result())
        assertNull(b.copy(target = "10").result())
        val exercise = ExercisePlan(Exercise(name = "Тяга"), plans, instructions = "Пауза в верхней точке")
        val program = Program(title = "Силовая", days = listOf(ProgramDay(title = "Спина", exercises = listOf(exercise))))
        assertEquals(program, Markdown.read(Markdown.write(program))?.document)
        assertNotNull(List(100) { b.copy(key = it.toString()) }.plansOrNull())
    }

    @Test fun onlyUnusedScheduledSnapshotsFollowProgramEdits() {
        val exercise = ExercisePlan(Exercise(name = "Тяга"), listOf(SetPlan(18.0)))
        val day = ProgramDay(title = "Спина", exercises = listOf(exercise))
        val program = Program(title = "План", days = listOf(day))
        val profile = Profile(name = "Я")
        fun scheduled(offset: Long) = scheduledSession(program, day, LocalDate.of(2026, 11, 20).plusDays(offset), listOf(profile))
        val planned = scheduled(0)
        val active = scheduled(1).start()
        val completed = scheduled(2).start().finish()
        val customized = scheduled(3).let { session ->
            session.editWorkout(profile.id) { workout ->
                workout.editExercise(workout.exercises.single().id) { old ->
                    old.copy(athletes = listOf(Athlete(profile.id, listOf(SetEntry(plan = SetPlan(42.0))))))
                }
            }
        }
        val newExercise = exercise.copy(sets = listOf(SetPlan(26.5, 5, 6), SetPlan(18.0, 10, 12)))
        val updated = program.copy(days = listOf(day.copy(title = "Спина и руки", exercises = listOf(newExercise))))
        val result = updateUnusedPlans(program, updated, listOf(planned, active, completed, customized)).single()
        assertEquals(planned.id, result.id)
        assertEquals(planned.defaultWorkout.id, result.defaultWorkout.id)
        assertEquals("Спина и руки", result.title)
        assertEquals(newExercise.sets, result.defaultWorkout.exercises.single().athletes.single().sets.map(SetEntry::plan))
        val removed = updateUnusedPlans(program, program.copy(days = emptyList()), listOf(planned, active, completed, customized)).single()
        assertEquals(SessionStatus.SKIPPED, removed.status)
        assertEquals(planned.defaultWorkout.exercises, removed.defaultWorkout.exercises)
    }
}
