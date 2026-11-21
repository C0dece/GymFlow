package app.gymflow.domain

import app.gymflow.ui.PlanSetDraft
import app.gymflow.ui.plansOrNull
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class ProgramPlanEditingTest {
    @Test fun timedActivityAndGuidanceRoundTripAndSupportExternalEditing() {
        val draft = PlanSetDraft("time", SetPlan(weightKnown = false, repsKnown = false), duration = "120,5")
        assertNull(draft.copy(duration = "0").result(timed = true))
        assertNull(draft.copy(duration = "86401").result(timed = true))
        val timed = requireNotNull(draft.result(timed = true))
        val activity = ExercisePlan(Exercise(name = "Ходьба в горку", cardio = CardioType.WALK), listOf(timed))
        val warmup = TrainingBlock(type = BlockType.WARMUP, name = "Разогрев", guidance = "Пройтись", durationSeconds = 300.0)
        val day = ProgramDay(title = "Выносливость", exercises = listOf(activity), blocks = listOf(warmup))
        val program = Program(title = "Выносливость", days = listOf(day))
        val markdown = Markdown.write(program)
        assertTrue(markdown.contains("Длительность в секундах"))
        assertEquals(program, Markdown.read(markdown)?.document)
        val edited = Markdown.read(markdown.replace("120.5", "150"))?.document as Program
        assertEquals(150.0, edited.days.single().exercises.single().sets.single().durationSeconds!!, 0.0)
        assertEquals(300.0, edited.days.single().blocks.single().durationSeconds!!, 0.0)
        assertNull(timed.volumeKg(activity.exercise))
        assertTrue(day.copy(exercises = emptyList()).hasActivities())
    }

    @Test fun cancelRestoresSavedPlanAndKeepsNotesAfterMarkdownRestart() {
        val day = ProgramDay(title = "День", exercises = listOf(ExercisePlan(Exercise(name = "Тяга"), listOf(SetPlan(20.0)))))
        val program = Program(title = "План", days = listOf(day))
        val person = Profile(name = "Атлет")
        val planned = scheduledSession(program, day, LocalDate.of(2026, 11, 20), listOf(person))
        val active = planned.reschedule(LocalDate.of(2026, 11, 21)).start(previous = planned)
            .copy(title = "Правка после старта", notes = "Заметка")
            .editWorkout(person.id) { it.copy(exercises = emptyList()) }
        val restarted = Markdown.read(Markdown.write(active))?.document as Session
        assertEquals(planned.copy(notes = "Заметка"), restarted.cancelStart())
        val free = Session("Свободная", "2026-11-20", listOf(person)).start().cancelStart()
        assertTrue(free.discarded)
        val deleted = planned.start().finish().copy(discarded = true)
        assertEquals(deleted, Markdown.read(Markdown.write(deleted))?.document)
        assertTrue(deleted.completedWorkouts(person.id).isEmpty())
        assertTrue(app.gymflow.data.Library(documents = listOf(free, deleted, planned)).sessions == listOf(planned))
    }

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
