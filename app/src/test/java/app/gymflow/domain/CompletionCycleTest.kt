package app.gymflow.domain

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class CompletionCycleTest {
    private val monday = LocalDate.of(2026, 11, 16)
    private val first = Profile(name = "Цикл QA")
    private val second = Profile(name = "Календарь QA")
    private val exercise = Exercise(name = "Жим QA", load = Load.MACHINE)
    private val days = listOf("Верх A", "Низ A", "Верх B", "Низ B").map {
        WorkoutTemplate(title = it, exercises = listOf(ExercisePlan(exercise, listOf(SetPlan(20.0)))))
    }
    private val program = Program(title = "Проверка", days = days, enabled = true, cycle = true, anchor = monday.toString(),
        completionCycle = CompletionCycle(targetSessions = 24, phases = listOf(CyclePhase(6, "Начало", "Пробный объём"))))

    private fun completed(session: Session, profile: Profile = first): Session {
        val entry = session.workout(profile.id)!!.exercises.first { it.plan.block == PlanBlock.REQUIRED }
        return session.start().editAthlete(entry.id, profile.id) { athlete ->
            athlete.copy(rir = 3, sets = athlete.sets.map(SetEntry::toggle))
        }.editWorkout(profile.id) { it.copy(status = WorkoutStatus.COMPLETED) }
    }

    @Test fun fourDaysAtThreeVisitsCrossMondayAndStopAfterTwentyFourCompletions() {
        val config = program.completionCycle!!
        val visits = (0L..63L).map(monday::plusDays).filter(config::visitsOn)
        val history = mutableListOf<Session>()
        visits.take(24).forEachIndexed { i, date ->
            val position = cyclePosition(program, first.id, history)
            assertEquals(i, position.completed)
            assertEquals(days[i % 4].id, position.next?.id)
            history.add(completed(cycleAppointment(program, first, position.next!!, date, position, true)))
        }
        assertEquals(listOf("Верх A", "Низ A", "Верх B", "Низ B", "Верх A", "Низ A"), history.take(6).map { it.title })
        assertEquals(listOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY, DayOfWeek.MONDAY), visits.take(4).map { it.dayOfWeek })
        assertNull(cyclePosition(program, first.id, history).next)
        assertEquals(days[0], cyclePosition(program.copy(completionCycle = config.copy(extensions = mapOf(first.id to 1))), first.id, history).next)
    }

    @Test fun openingSkippingRepeatingAndOffOrderWorkDoNotAdvance() {
        val initial = cyclePosition(program, first.id, emptyList())
        val planned = cycleAppointment(program, first, days[0], monday, initial, true)
        assertEquals(planned.id, cycleAppointment(program, first, days[0], monday, initial, true).id)
        val early = planned.reschedule(monday.plusDays(2)).onCycleVisit(monday)
        assertEquals(monday.toString(), early.date)
        assertEquals(monday.plusDays(2).toString(), early.scheduledFor)
        assertEquals(planned.id, early.id)
        val started = planned.start()
        val imported = Markdown.read(Markdown.write(started))!!.document as Session
        assertEquals(started, imported)
        assertEquals(imported.id, cyclePosition(program, first.id, listOf(imported)).pending?.session?.id)
        val skipped = planned.copy(status = SessionStatus.SKIPPED, workouts = planned.workouts.map { it.copy(status = WorkoutStatus.SKIPPED) })
        val offOrder = completed(cycleAppointment(program, first, days[2], monday, initial, false))
        val emptyClosed = started.editWorkout(first.id) { it.copy(status = WorkoutStatus.COMPLETED) }
        assertEquals(0, cyclePosition(program, first.id, listOf(skipped, offOrder, emptyClosed)).completed)
        assertEquals(days[0], cyclePosition(program, first.id, listOf(skipped, offOrder)).next)
        val done = completed(planned).editWorkout(first.id, ParticipantWorkout::creditCycle)
        assertTrue(done.defaultWorkout.cycleCredited)
        val corrected = done.editWorkout(first.id) { row ->
            val entry = row.exercises.single()
            val set = entry.athletes.single().sets.single()
            row.recordCorrection(entry.id, set, set.copy(status = SetStatus.SKIPPED))
        }
        assertEquals(1, cyclePosition(program, first.id, listOf(corrected)).completed)
        val repeated = completed(repeatSession(done, newId(), monday, emptyList()))
        assertEquals(1, cyclePosition(program, first.id, listOf(done, repeated, done.copy(id = newId()))).completed)
    }

    @Test fun memberCompletionIsIndependentAndChangeOfOrderDoesNotAlterHistory() {
        val initial = cyclePosition(program, first.id, emptyList())
        val planned = cycleAppointment(program, first, days[0], monday, initial, true)
        val calendar = Program(title = "Календарь", days = listOf(days[3]), enabled = true, anchor = monday.toString())
        val group = planned.start().addParticipant(second, calendar.days.first())
        val partial = completed(group)
        assertEquals(WorkoutStatus.ACTIVE, partial.workout(second.id)?.status)
        assertEquals(1, cyclePosition(program, first.id, listOf(partial)).completed)
        assertEquals(0, cyclePosition(program, second.id, listOf(partial)).completed)
        val partnerSlot = cyclePosition(program, second.id, listOf(partial))
        val partnerDone = completed(cycleAppointment(program, second, days[0], monday.plusDays(1), partnerSlot, true), second)
        val history = listOf(partial, partnerDone)
        assertEquals(days[1], cyclePosition(program, second.id, history).next)
        assertEquals(1, cyclePosition(program, first.id, history).completed)
        assertEquals(calendar.days, plannedOn(calendar, monday))
        val reordered = program.copy(days = listOf(days[0], days[3], days[1], days[2]))
        assertEquals(days[3].id, cyclePosition(reordered, first.id, listOf(partial)).next?.id)
        assertEquals(days[0].id, partial.workout(first.id)?.templateId)
        assertEquals(1, performance(listOf(partial), first.id).size)
    }

    @Test fun optionalBlocksRampSetsAndElapsedRestArePersistedAndSeparated() {
        val cardio = TrainingBlock(type = BlockType.CARDIO, name = "Кардио", requirement = PlanBlock.OPTIONAL)
        val mobility = TrainingBlock(type = BlockType.MOBILITY, name = "Навык", requirement = PlanBlock.OPTIONAL, expandedOnly = true)
        val main = TrainingBlock(type = BlockType.MAIN, name = "Силовая")
        val activeDay = days[0].copy(blocks = listOf(main, cardio, mobility), exercises = listOf(
            ExercisePlan(exercise, listOf(SetPlan(200.0, role = SetRole.RAMP), SetPlan(20.0)), blockId = main.id, restSeconds = 200),
            ExercisePlan(Exercise(name = "Дорожка", cardio = CardioType.TREADMILL), listOf(SetPlan(weightKnown = false, repsKnown = false)), blockId = cardio.id),
            ExercisePlan(Exercise(name = "Баланс"), listOf(SetPlan(role = SetRole.TECHNIQUE)), blockId = mobility.id),
        ))
        val edited = program.copy(days = listOf(activeDay) + days.drop(1))
        assertEquals(edited, Markdown.read(Markdown.write(edited))?.document)
        val planned = cycleAppointment(edited, first, activeDay, monday, cyclePosition(edited, first.id, emptyList()), true)
        assertTrue(planned.defaultWorkout.exercises.last().athletes.single().sets.single().deferred)
        val expanded = planned.editWorkout(first.id) { applyVolume(it, WorkoutVolume.EXTENDED) }
        assertFalse(expanded.defaultWorkout.exercises.last().athletes.single().sets.single().deferred)
        val skipped = expanded.editWorkout(first.id) { it.skipBlock(cardio.id).skipBlock(mobility.id) }
        val lifting = completed(skipped).finish()
        assertEquals(1, cyclePosition(edited, first.id, listOf(lifting)).completed)
        assertEquals(20.0, performance(listOf(lifting), first.id).single().maxKg, 0.001)
        assertEquals(null, SetPlan(100.0, role = SetRole.RAMP).volumeKg(exercise))
        val now = Instant.parse("2026-11-16T10:00:00Z")
        assertEquals(180L, restRemaining(now.plusSeconds(200).toString(), now.plusSeconds(20)))
        assertEquals(0L, restRemaining(now.toString(), now.plusSeconds(1)))
    }

    @Test fun foundationTemplateUsesUnknownWeightsAndOldSchemaKeepsItsSchedule() {
        val (foundation, exercises) = foundationProgram(emptyList(), monday)
        assertEquals(4, foundation.days.size)
        assertEquals(24, foundation.completionCycle?.targetSessions)
        assertEquals(exercises.map { it.id }.distinct().size, exercises.size)
        assertTrue(foundation.days.flatMap { it.exercises }.flatMap { it.sets }.none { it.weightKnown })
        assertTrue(foundation.days.all { day -> day.blocks.any { it.type == BlockType.CARDIO && it.requirement == PlanBlock.OPTIONAL } })
        assertEquals(foundation, Markdown.read(Markdown.write(foundation))?.document)
        val legacy = program.copy(completionCycle = null)
        val fromV4 = Markdown.read(Markdown.write(legacy).replace("gymflow_schema: 5", "gymflow_schema: 4"))!!.document as Program
        assertEquals(legacy, fromV4)
        assertEquals(listOf(days[1]), plannedOn(fromV4, monday.plusDays(2)))
    }
}
