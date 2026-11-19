package app.gymflow.domain

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class MovementsTest {
    private val player = Profile(name = "Первый")
    private val other = Profile(name = "Второй")
    private val family = MovementFamily(name = "Семейство")
    private val base = Exercise(name = "База", load = Load.BODYWEIGHT, movement = MovementDetails(family.id))
    private val variant = Exercise(name = "Вариация", load = Load.BODYWEIGHT, movement = MovementDetails(
        family.id, base.id, VariationType.PAUSE, measures = setOf(Measure.HOLD)))
    private val sets = List(3) { SetPlan(10.0) }
    private fun session(vararg people: Profile) =
        Session(title = "QA", date = "2026-11-18", participants = people.toList()).start()

    @Test fun replacingUsesOnlyUnfinishedSetsAndKeepsResultsSeparate() {
        val started = session(player, other).addExercise(ExercisePlan(base, sets), setOf(player.id, other.id))
        val firstEntry = started.workout(player.id)!!.exercises.single()
        val recorded = started.editAthlete(firstEntry.id, player.id) { row ->
            row.copy(sets = listOf(row.sets.first().toggle()) + row.sets.drop(1))
        }
        val entry = recorded.workout(player.id)!!.exercises.single()
        val change = substitution(entry, variant)
        assertEquals(2, change.sets.size)
        assertTrue(change.sets.none { it.weightKnown || it.repsKnown })
        val replaced = recorded.editWorkout(player.id) { it.replaceExercise(entry.id, change) }
        val finished = replaced.editAthlete(entry.id, player.id) { athlete ->
            athlete.copy(sets = athlete.sets.map {
                it.copy(status = SetStatus.COMPLETED,
                    actual = Actual(0.0, 1, weightKnown = false, repsKnown = false, measurements = mapOf(Measure.HOLD to 14.0)))
            })
        }.finish()
        assertEquals(3, workSets(finished.workout(player.id)!!, setOf(base.id, variant.id), setOf(SetStatus.COMPLETED)))
        assertEquals(listOf(12.0), movementResults(listOf(finished), player.id, base).filter { it.kind == Measure.REPS }.map { it.value })
        assertEquals(listOf(14.0, 14.0), movementResults(listOf(finished), player.id, variant).map { it.value })
        assertTrue(movementResults(listOf(finished), other.id, variant).isEmpty())
        assertTrue(movementResults(listOf(finished), player.id, variant.copy(movement = variant.movement!!.copy(equipment = "Другое"))).isEmpty())
        assertEquals(finished, Markdown.read(Markdown.write(finished))!!.document)
    }

    @Test fun minimumVolumeKeepsOriginalPlanAndRestoresOnlyItsOwnDeferrals() {
        val initial = session(player).addExercise(ExercisePlan(base, sets + SetPlan(role = SetRole.TECHNIQUE)))
            .addExercise(ExercisePlan(variant, sets, block = PlanBlock.OPTIONAL)).workout(player.id)!!
        val manuallyCancelled = initial.editExercise(initial.exercises[0].id) { entry ->
            val row = entry.athletes.single()
            entry.copy(athletes = listOf(row.copy(sets = row.sets.mapIndexed { i, set ->
                if (i == 2) set.copy(status = SetStatus.CANCELLED) else set
            })))
        }
        val minimal = applyVolume(manuallyCancelled, WorkoutVolume.MINIMUM)
        assertEquals(1, workSets(minimal, setOf(base.id, variant.id), setOf(SetStatus.PLANNED)))
        assertEquals(1, minimal.exercises[0].athletes.single().sets.count { it.plan.purpose == SetRole.TECHNIQUE && it.status == SetStatus.PLANNED })
        assertEquals(manuallyCancelled.plans(), minimal.originalPlan)
        assertEquals(manuallyCancelled, applyVolume(minimal, WorkoutVolume.NORMAL).copy(originalPlan = null))
        val removed = minimal.discardExercise(minimal.exercises.last().id)
        assertEquals(1, removed.exercises.size)
        assertEquals(2, removed.originalPlan!!.size)
    }

    @Test fun rotationUsesIndividualRoleAndPriorityAndLeavesOriginalExerciseInPlace() {
        val next = variant.copy(id = newId(), name = "Следующая")
        val paused = variant.copy(id = newId(), name = "На паузе")
        val selections = listOf(
            MovementTrack(player.id, variant.id, MovementRole.MAINTENANCE, repeatDays = 7),
            MovementTrack(player.id, next.id, MovementRole.MAINTENANCE, repeatDays = 7, priority = 10),
            MovementTrack(player.id, paused.id, MovementRole.MAINTENANCE, priority = 20, paused = true),
            MovementTrack(other.id, variant.id, MovementRole.ACTIVE),
        )
        val catalogue = listOf(base, variant, next, paused)
        assertEquals(listOf(next, variant), suggestionsForFamily(base, MovementSelection.MAINTENANCE, catalogue,
            selections, player.id, emptyList(), LocalDate.of(2026, 11, 18)))
        assertTrue(suggestionsForFamily(base, MovementSelection.MAINTENANCE, catalogue, selections, other.id, emptyList()).isEmpty())
    }

    @Test fun skillRequiresIndependentEvidenceAndPrerequisitesAndRoundTrips() {
        val low = SkillLevel(name = "Подготовка", goal = MovementGoal(Measure.HOLD, 10.0))
        val high = SkillLevel(name = "Уровень", goal = MovementGoal(Measure.HOLD, 20.0), requires = setOf(low.id))
        val skill = MovementSkill(name = "Баланс", levels = listOf(high, low))
        val first = SkillAttempt(player.id, skill.id, low.id, "2026-11-18", Measure.HOLD, 10.0)
        val second = first.copy(id = newId(), levelId = high.id, value = 25.0)
        assertEquals(emptySet<String>(), masteredLevels(skill, player.id, listOf(second)))
        assertEquals(setOf(low.id, high.id), masteredLevels(skill, player.id, listOf(first, second)))
        assertEquals(emptySet<String>(), masteredLevels(skill, other.id, listOf(first, second)))
        assertThrows(IllegalArgumentException::class.java) { skill.copy(levels = listOf(low.copy(requires = setOf(high.id)), high)) }
        assertFalse(Measure.SPEED.accepts(Double.NaN))
        assertFalse(Measure.PULSE.accepts(122.5))
        listOf(family, variant, MovementTrack(player.id, variant.id, goal = low.goal), skill, first).forEach {
            assertEquals(it, Markdown.read(Markdown.write(it))?.document)
        }
    }

    @Test fun measurementSuggestionNeedsTwoRecordedDatesAndHonorsGoalAndCaution() {
        val track = MovementTrack(player.id, variant.id, MovementRole.ACTIVE,
            goal = MovementGoal(Measure.HOLD, 15.0), step = 4.0)
        val first = MovementResult("2026-11-17", Measure.HOLD, 9.0)
        val second = MovementResult("2026-11-18", Measure.HOLD, 13.0)
        val rir = MovementResult("2026-11-18", Measure.RIR, 4.0)
        assertTrue(rir.supports(MovementGoal(Measure.RIR, 4.0)))
        assertFalse(rir.supports(MovementGoal(Measure.RIR, 4.0, atLeast = false)))
        assertNull(nextMeasurement(track, listOf(second), caution = false))
        assertNull(nextMeasurement(track, listOf(second, first), caution = true))
        assertEquals(NextMeasurement(second.date, 13.0, 15.0), nextMeasurement(track, listOf(second, first), caution = false))
    }

    @Test fun schemaThreeRecordsUseClassicDefaults() {
        val source = session(player).addExercise(ExercisePlan(Exercise(name = "Классика"), sets))
        val old = Markdown.write(source).replace("gymflow_schema: 6", "gymflow_schema: 3")
        assertEquals(source, Markdown.read(old)?.document)
        assertEquals(WorkoutVolume.NORMAL, source.workout(player.id)!!.volume)
        assertEquals(SetRole.WARMUP, SetPlan(warmup = true).purpose)
    }
}
