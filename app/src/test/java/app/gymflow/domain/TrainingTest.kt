package app.gymflow.domain

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class TrainingTest {
    private val date = LocalDate.of(2026, 11, 18)
    private val self = Profile(name = "Я")
    private val b = Profile(name = "Оля")
    private val c = Profile(name = "Павел")
    private val exercise = Exercise(name = "Жим", load = Load.DUMBBELL)
    private val plan = ExercisePlan(exercise, List(3) { SetPlan(weight = 16.0, repsMin = 8, repsMax = 10) })
    private fun session() = Session(title = "Силовая", date = date.toString(), participants = listOf(self)).addExercise(plan)

    @Test fun profileAndExerciseIdentifiersStayStable() {
        assertNotEquals(self.id, Profile(name = self.name).id)
        assertEquals(self.id, self.copy(name = "Пётр").id)
        assertEquals(exercise.id, exercise.copy(name = "Новый жим").id)
    }

    @Test fun additionsUsePersonalPlanAndDoNotInventPastResults() {
        val old = session()
        val changed = old.addParticipant(b, WorkoutTemplate(title = "Ноги", exercises = listOf(plan.copy(individual = mapOf(b.id to listOf(SetPlan(32.0)))))))
            .addParticipant(c)
        assertEquals(listOf(self.id, b.id, c.id), changed.participants.map { it.id })
        val athlete = changed.workout(b.id)!!.exercises.single().athletes.single()
        assertEquals(32.0, athlete.sets.single().plan.weight, 0.0)
        assertNull(athlete.sets.single().actual)
        assertEquals(1, old.participants.size)
    }

    @Test fun confirmationUsesPlanAndUncheckingRetainsActual() {
        val planned = plan.athlete(self.id).sets[0]
        assertNull(planned.actual)
        assertEquals(Actual(16.0, 10), planned.toggle().actual)
        val entered = planned.copy(actual = Actual(17.5, 9, "Тяжело"))
        val stored = entered.toggle()
        assertEquals(SetStatus.COMPLETED, stored.status)
        assertEquals(SetStatus.PLANNED, stored.toggle().status)
        assertEquals(entered.actual, stored.toggle().actual)
        assertEquals(stored, stored.toggle().toggle())
    }

    @Test fun rotationUsesIndividualPositionsAndSkipsPausedAthletes() {
        val chest = plan.copy(exercise = Exercise(name = "Грудь"), sets = listOf(SetPlan(10.0)))
        val leg = plan.copy(exercise = Exercise(name = "Ноги"))
        val group = session().addParticipant(b, WorkoutTemplate(title = "Жимы", exercises = listOf(chest)))
            .addParticipant(c, WorkoutTemplate(title = "Ноги", exercises = listOf(leg, chest))).start()
        val target = group.workout(c.id)!!.exercises[1].id
        val positioned = group.editWorkout(c.id) { it.copy(currentExerciseId = target) }
        assertEquals("Грудь", nextWorkout(positioned, self.id)?.exercises?.single()?.exercise?.name)
        assertEquals(target, nextWorkout(positioned, b.id)?.nextExerciseId())
        val paused = positioned.editWorkout(b.id) { it.copy(status = WorkoutStatus.PAUSED) }
        assertEquals(c.id, nextWorkout(paused, self.id)?.profile?.id)
        assertEquals(target, (Markdown.read(Markdown.write(paused))?.document as Session).workout(c.id)?.currentExerciseId)
    }

    @Test fun repeatIsIndependentAndDoesNotCountUnfinishedTrainingAsComplete() {
        val original = session().addParticipant(b).start()
        val id = original.workouts.first().exercises.single().id
        val recorded = original.editAthlete(id, self.id) {
            it.copy(sets = it.sets.mapIndexed { i, row -> if (i == 0) row.copy(actual = Actual(17.0, 9)).toggle() else row }, rir = 2)
        }.editWorkout(self.id) { it.copy(status = WorkoutStatus.COMPLETED) }.finish()
        assertEquals(WorkoutStatus.CANCELLED, recorded.workout(b.id)?.status)
        val next = repeatSession(recorded, newId(), date, listOf(recorded)).start()
        assertNotEquals(recorded.id, next.id)
        val first = next.workouts.first().exercises.single()
        assertNotEquals(id, first.id)
        assertTrue(first.athletes.single().sets.all { it.status == SetStatus.PLANNED && it.actual == null })
        assertEquals(17.0, first.athletes.single().sets.first().plan.weight, 0.0)
        assertNull(first.athletes.single().rir)
        assertEquals(1, recorded.workout(self.id)!!.completed)
    }

    @Test fun fixedAndFlexibleScheduleAndStableMovedId() {
        val w = ProgramDay(title = "Тяги", weekday = DayOfWeek.WEDNESDAY, exercises = listOf(plan))
        val f = ProgramDay(title = "Ноги", weekday = DayOfWeek.FRIDAY)
        val program = Program(title = "Цикл", days = listOf(w, f), enabled = true, anchor = date.toString())
        assertEquals(listOf(w), plannedOn(program, date))
        assertEquals(listOf(f), plannedOn(program, date.plusDays(2)))
        assertEquals(emptyList<ProgramDay>(), plannedOn(program, date.plusDays(1)))
        val flexible = program.copy(cycle = true, interval = 3)
        assertEquals(listOf(f), plannedOn(flexible, date.plusDays(3)))
        assertEquals(listOf(w), plannedOn(flexible, date.plusDays(6)))
        val scheduled = scheduledSession(program, w, date, listOf(self))
        val moved = scheduled.reschedule(date.plusDays(1))
        assertEquals(scheduledSession(program, w, date, listOf(self)).id, moved.id)
        assertEquals(date.plusDays(1).toString(), moved.date)
        assertThrows(IllegalArgumentException::class.java) { moved.start().reschedule(date) }
    }

    @Test fun replacementRetainsSnapshotsAndPerformance() {
        val workout = session()
        val id = workout.workouts.single().exercises[0].id
        val changed = workout.editAthlete(id, self.id) {
            it.copy(sets = listOf(it.sets[0].toggle()), rir = 4)
        }.editWorkout(self.id) { it.replaceExercise(id, plan.copy(exercise = Exercise(name = "Отжимания", load = Load.BODYWEIGHT))) }
        assertEquals("Жим", changed.workouts.single().exercises[0].replaced.single().exercise.name)
        assertEquals("Отжимания", changed.workouts.single().exercises[0].exercise.name)
        val performance = performance(listOf(changed.finish()), self.id).single()
        assertEquals(4, performance.rir)
        assertEquals(320.0, performance.volumeKg!!, 0.0)
    }

    @Test fun statisticsExcludeWarmupSkippedAndUnconfirmedAndConvertUnits() {
        val exercise = exercise.copy(unit = UnitWeight.LB)
        val plans = ExercisePlan(exercise, listOf(SetPlan(10.0, warmup = true), SetPlan(20.0), SetPlan(50.0)))
        val workout = session().editWorkout(self.id) { it.copy(exercises = emptyList()) }.addExercise(plans)
        val recorded = workout.editAthlete(workout.workouts.single().exercises.single().id, self.id) { athlete ->
            athlete.copy(sets = athlete.sets.mapIndexed { i, set ->
                if (i < 2) set.toggle() else set.copy(status = SetStatus.SKIPPED, actual = Actual(50.0, 10))
            }, rir = 2)
        }.start(Instant.EPOCH)
        assertTrue(performance(listOf(recorded), self.id).isEmpty())
        val point = performance(listOf(recorded.finish(Instant.ofEpochSecond(1200))), self.id).single()
        assertEquals(20 * UnitWeight.LB.toKg, point.maxKg, 0.0001)
        assertEquals(20 * 12 * 2 * UnitWeight.LB.toKg, point.volumeKg!!, 0.0001)
        assertEquals(12, point.reps)
        assertEquals(2, point.rir)
        assertNull(point.copy(exercise = exercise.copy(load = Load.BODYWEIGHT)).volumeKg)
        assertNull(point.copy(exercise = exercise.copy(load = Load.OTHER)).volumeKg)
    }
}
