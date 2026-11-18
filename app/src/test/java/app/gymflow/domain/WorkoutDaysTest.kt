package app.gymflow.domain

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class WorkoutDaysTest {
    private val first = Profile(name = "Первый")
    private val second = Profile(name = "Второй")
    private val date = LocalDate.of(2026, 11, 18)
    private fun plan(name: String) = ExercisePlan(Exercise(name = name, load = Load.MACHINE), List(2) { SetPlan(20.0, 8, 10) })
    private val legs = WorkoutTemplate(title = "Ноги", exercises = listOf(plan("Жим ногами"), plan("Сгибания")))
    private val back = WorkoutTemplate(title = "Спина", exercises = listOf(plan("Тяга")))
    private val program = Program(title = "Программа", days = listOf(legs, back), cycle = true, enabled = true, anchor = date.toString())

    private fun pick(session: Session, profile: Profile, day: WorkoutTemplate, others: List<Session> = emptyList()) =
        startWorkoutDay(session, profile.id, program, day, workoutDays(others, session, profile.id)[day.id] ?: WorkoutDayOptions(), date)

    @Test fun oneOffReplacementKeepsCalendarSlotsAndOtherParticipants() {
        val source = scheduledSession(program, legs, date, listOf(first, second))
        val updated = changePlannedWorkout(source, second.id, program, back, null).single()
        assertEquals(source.id, updated.id)
        assertEquals(legs.id, updated.programDayId)
        assertEquals(source.workout(first.id), updated.workout(first.id))
        assertEquals(back.id, updated.workout(second.id)?.templateId)
        assertEquals(listOf(back), plannedOn(program, date.plusDays(2)))
    }

    @Test fun swappingMaterializesTheNextSlotAndMovesOnlyTheChosenPlan() {
        val source = scheduledSession(program, legs, date, listOf(second, first))
        val edited = source.editWorkout(second.id) { row ->
            row.copy(templateNotes = "Запись к плану")
        }
        val swap = requireNotNull(nextPlannedDay(program, back, date, second.id, listOf(edited)))
        assertNull(swap.session)
        assertEquals(date.plusDays(2), swap.date)
        val (current, future) = changePlannedWorkout(edited, second.id, program, back, swap)
        assertEquals(legs.id, current.programDayId)
        assertEquals(back.id, current.workout(second.id)?.templateId)
        assertEquals(back.id, future.programDayId)
        assertEquals(legs.id, future.workout(second.id)?.templateId)
        assertEquals("Запись к плану", future.workout(second.id)?.templateNotes)
        assertEquals(back.id, future.workout(first.id)?.templateId)
        assertEquals(date.plusDays(6), nextPlannedDay(program, back, date, second.id, listOf(current, future))?.date)
        assertEquals(future, Markdown.read(Markdown.write(future))?.document)
    }

    @Test fun restoresIncompleteWorkoutAndCursorWithoutChangingThePartnerOrSchedule() {
        val source = scheduledSession(program, legs, date, listOf(first, second)).start()
        val exerciseId = source.workout(second.id)!!.exercises.last().id
        val legsStarted = source.editAthlete(exerciseId, second.id) { row ->
            row.copy(sets = listOf(row.sets.first().toggle(), row.sets.last()))
        }.editWorkout(second.id) { it.copy(currentExerciseId = exerciseId) }
        val backStarted = pick(legsStarted, second, back).destination
        val restored = Markdown.read(Markdown.write(backStarted))!!.document as Session
        assertEquals(second.id, restored.switchedProfileId)
        assertEquals(back.id, restored.workout(second.id)!!.templateId)
        assertEquals(WorkoutStatus.PAUSED, restored.otherWorkouts.single().status)
        assertTrue(performance(listOf(restored), second.id).isEmpty())

        val resumed = pick(restored, second, legs).destination
        assertEquals(legsStarted.workout(second.id), resumed.workout(second.id))
        assertEquals(source.workout(first.id), resumed.workout(first.id))
        assertEquals(source.id, resumed.id)
        assertEquals(resumed, pick(resumed, second, legs).destination)
        assertEquals(listOf(legs), plannedOn(program, date))
    }

    @Test fun startsAnExistingPlanForOneParticipantAndReusesItOnRetry() {
        val source = scheduledSession(program, legs, date, listOf(first, second)).start()
        val planned = scheduledSession(program, back, date.plusDays(2), listOf(first, second))
        val change = pick(source, second, back, listOf(planned))
        val started = change.destination
        val paused = change.documents.first()
        assertEquals(source.workout(first.id), paused.workout(first.id))
        assertEquals(WorkoutStatus.PAUSED, paused.workout(second.id)!!.status)
        assertEquals(planned.id, started.id)
        assertEquals(planned.workout(first.id), started.workout(first.id))
        assertEquals(planned.workout(second.id)!!.id, started.workout(second.id)!!.id)
        assertEquals(WorkoutStatus.ACTIVE, started.workout(second.id)!!.status)
        assertEquals(started, pick(source, second, back, listOf(started)).destination)
    }

    @Test fun aClosedDayWithForgottenSetsCanBeReviewedAndCorrected() {
        val closed = scheduledSession(program, back, date, listOf(first)).start().finish()
        val member = closed.defaultWorkout
        assertEquals(WorkoutStatus.CANCELLED, member.status)
        val draft = scheduledSession(program, legs, date, listOf(first)).start()
        val options = workoutDays(listOf(closed), draft, first.id).getValue(back.id)
        assertEquals(member.id, options.completed?.workout?.id)
        assertNull(options.pending)

        val exercise = member.exercises.single()
        val sets = exercise.athletes.single().sets
        val partial = member.recordCorrection(exercise.id, sets.first(), sets.first().toggle())
        assertEquals(WorkoutStatus.CANCELLED, partial.status)
        val corrected = partial.recordCorrection(exercise.id, sets.last(), sets.last().toggle())
        assertEquals(WorkoutStatus.COMPLETED, corrected.status)
        assertEquals(2, corrected.completed)
    }

    @Test fun keepsCompletedResultsWhenRepeatingAndMovesParkedWorkoutFromClosedGroupOnce() {
        val initial = scheduledSession(program, legs, date, listOf(first)).start()
        val switched = pick(initial, first, back).destination
        val closed = switched.editWorkout(first.id) { it.copy(status = WorkoutStatus.COMPLETED) }.finish()
        val resumed = pick(closed, first, legs)
        assertEquals(initial.workout(first.id), resumed.destination.workout(first.id))
        val oldGroup = resumed.documents.first()
        assertTrue(oldGroup.otherWorkouts.isEmpty())
        assertEquals(closed.workout(first.id), oldGroup.workout(first.id))
        val repeated = pick(resumed.destination, first, back, listOf(oldGroup)).destination
        assertNotEquals(closed.workout(first.id)!!.id, repeated.workout(first.id)!!.id)
        assertEquals(repeated, pick(repeated, first, back, listOf(oldGroup)).destination)
    }

    @Test fun switchingBackAndForthPreservesPartialSetsAndDoesNotMarkSkippedDayAsCompleted() {
        val session = scheduledSession(program, legs, date, listOf(first)).start()
        val legEx1 = session.workout(first.id)!!.exercises[0].id
        val legEx2 = session.workout(first.id)!!.exercises[1].id

        // Выполняем 1 подход в жиме ногами и ставим курсор на сгибания
        val leg1Done = session.editAthlete(legEx1, first.id) { row ->
            row.copy(sets = listOf(row.sets[0].toggle(), row.sets[1]))
        }.editWorkout(first.id) { it.copy(currentExerciseId = legEx2) }

        // Переключаемся на Спину
        val toBack = pick(leg1Done, first, back).destination
        assertEquals(back.id, toBack.workout(first.id)!!.templateId)
        assertEquals(WorkoutStatus.ACTIVE, toBack.workout(first.id)!!.status)
        val parkedLegs = toBack.otherWorkouts.single { it.templateId == legs.id }
        assertEquals(WorkoutStatus.PAUSED, parkedLegs.status)
        assertFalse("Ноги не должны быть отмечены завершенными", parkedLegs.status == WorkoutStatus.COMPLETED)
        assertEquals(1, parkedLegs.completed)

        // Делаем подход в Спине
        val backEx = toBack.workout(first.id)!!.exercises.single().id
        val backDone = toBack.editAthlete(backEx, first.id) { row ->
            row.copy(sets = listOf(row.sets[0].toggle()))
        }

        // Переключаемся обратно на Ноги
        val backToLegs = pick(backDone, first, legs).destination
        assertEquals(legs.id, backToLegs.workout(first.id)!!.templateId)
        assertEquals(WorkoutStatus.ACTIVE, backToLegs.workout(first.id)!!.status)
        assertEquals(legEx2, backToLegs.workout(first.id)!!.currentExerciseId)
        assertEquals(1, backToLegs.workout(first.id)!!.completed)

        val parkedBack = backToLegs.otherWorkouts.single { it.templateId == back.id }
        assertEquals(WorkoutStatus.PAUSED, parkedBack.status)
        assertEquals(1, parkedBack.completed)

        // Идемпотентность: повторный выбор текущего дня не создает дубликатов
        val repeatPick = pick(backToLegs, first, legs).destination
        assertEquals(backToLegs.id, repeatPick.id)
        assertEquals(1, repeatPick.workouts.size)
        assertEquals(1, repeatPick.otherWorkouts.size)
    }

    @Test fun singleParticipantPlannedSwitchUpdatesProgramDayIdAndMaintainsCycle() {
        val source = scheduledSession(program, legs, date, listOf(first))
        // Замена без сдвига ("Только на эту дату")
        val singleUpdate = changePlannedWorkout(source, first.id, program, back, null).single()
        assertEquals(back.id, singleUpdate.programDayId)
        assertEquals(back.id, singleUpdate.workout(first.id)?.templateId)
        assertEquals("Спина", singleUpdate.title)

        // Цикл не нарушен: на следующую дату цикла все еще запланирована спина
        assertEquals(listOf(back), plannedOn(program, date.plusDays(2)))
    }

    @Test fun restartingAppRestoresSwitchedDayFromMarkdownWithoutDataLoss() {
        val initial = scheduledSession(program, legs, date, listOf(first)).start()
        val switched = pick(initial, first, back).destination
        val markdown = Markdown.write(switched)
        val restored = (Markdown.read(markdown)!!.document as Session)

        assertEquals(switched.id, restored.id)
        assertEquals(first.id, restored.switchedProfileId)
        assertEquals(back.id, restored.defaultWorkout.templateId)
        assertEquals(WorkoutStatus.ACTIVE, restored.defaultWorkout.status)
        assertEquals(legs.id, restored.otherWorkouts.single().templateId)
        assertEquals(WorkoutStatus.PAUSED, restored.otherWorkouts.single().status)
    }
}
