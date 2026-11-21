package app.gymflow.domain

import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class ProgramImportTest {
    private val date = LocalDate.of(2026, 11, 16)
    private val profile = Profile(name = "QA")

    private fun source(days: List<String>, frequency: Int, calendar: Boolean = false) = buildString {
        appendLine("---")
        appendLine("program_schema: 1")
        appendLine("title: Программа QA")
        appendLine("type: training-program")
        appendLine("schedule_mode: " + if (calendar) "calendar" else "rotation")
        appendLine("sessions_per_week: $frequency")
        appendLine(if (calendar) "weekdays:" else "rotation:")
        (if (calendar) listOf("MONDAY", "THURSDAY") else days).forEach { appendLine("  - $it") }
        appendLine("---")
        days.forEach { name ->
            appendLine("## $name")
            appendLine("### [MAIN REQUIRED] Работа")
            appendLine("| Упражнение | Подходы × повторы | Отдых |")
            appendLine("| --- | --- | --- |")
            appendLine("| Тяга QA | 2 × 5-7 | 1-2 мин |")
            appendLine()
            appendLine("### [MOBILITY OPTIONAL] Навык")
            appendLine("Свободная практика.")
        }
    }

    private fun apply(raw: String, programs: List<Program> = emptyList(), catalog: List<Exercise> = emptyList()): ImportReview {
        val input = ProgramInput("content://qa/one", "qa.md", raw, null)
        val draft = ProgramImport.read(raw, date)
        val choices = defaultMapping(draft.exercises(), catalog, input.currentProgram(draft, programs)).toMutableMap()
        draft.exercises().forEach { choices.putIfAbsent(it.id, NEW_EXERCISE) }
        return reviewImport(input, draft, raw, programs, catalog, choices)
    }

    @Test fun importsTimedSetsAndWarmupWithoutInventingWeightOrRepetitions() {
        val raw = source(listOf("Занятие"), 1).substringBefore("### [MAIN") + """
            ### [WARMUP REQUIRED] Подготовка
            Время: 5 мин
            Лёгкие движения
            ### [CARDIO OPTIONAL] Выносливость
            | Упражнение | Длительность | Отдых |
            | --- | --- | --- |
            | Ходьба QA | 2 × 1,5 мин | 30 с |
        """.trimIndent()
        val existing = Exercise(name = "Ходьба QA")
        val imported = apply(raw, catalog = listOf(existing)).program.days.single()
        assertEquals(300.0, imported.blocks.first().durationSeconds!!, 0.0)
        assertEquals("Лёгкие движения", imported.blocks.first().guidance)
        val plan = imported.exercises.single()
        assertEquals(existing.id, plan.exercise.id)
        assertEquals(CardioType.OTHER, plan.exercise.cardio)
        assertEquals(listOf(90.0, 90.0), plan.sets.map(SetPlan::durationSeconds))
        assertTrue(plan.sets.none { it.weightKnown || it.repsKnown })
    }

    @Test fun calendarAndDifferentRotationSizesUseExistingScheduler() {
        val calendar = apply(source(listOf("Тяга", "Корпус"), 2, calendar = true)).program
        assertEquals(listOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY), calendar.days.map { it.weekday })
        assertEquals(calendar.days.first(), plannedOn(calendar, date).single())
        assertTrue(plannedOn(calendar, date.plusDays(1)).isEmpty())
        assertEquals(calendar.days.last(), plannedOn(calendar, date.plusDays(3)).single())
        listOf(2 to 1, 3 to 2, 4 to 3, 5 to 4, 8 to 6).forEach { (size, frequency) ->
            val names = (1..size).map { "Занятие $it" }
            val program = apply(source(names, frequency)).program
            val visits = (0L..6L).count { program.completionCycle!!.visitsOn(date.plusDays(it)) }
            assertEquals(frequency, visits)
            val history = mutableListOf<Session>()
            repeat(size * 2) { index ->
                val position = cyclePosition(program, profile.id, history)
                assertEquals(names[index % size], position.next!!.title)
                val plan = cycleAppointment(program, profile, position.next, date.plusDays(index.toLong()), position, true).start()
                val row = plan.defaultWorkout
                val entry = row.exercises.first()
                val fact = plan.editAthlete(entry.id, profile.id) { athlete ->
                    athlete.copy(sets = athlete.sets.map { set ->
                        set.copy(status = SetStatus.COMPLETED, actual = Actual(10.0, 6))
                    })
                }.finish()
                history.add(fact)
            }
        }
    }

    @Test fun rejectsBadYamlAmbiguousTablesAndUnresolvedExercises() {
        val raw = source(listOf("Первая", "Вторая"), 3)
        val input = ProgramInput("content://qa/one", "qa.md", raw, null)
        val draft = ProgramImport.read(raw, date)
        assertThrows(IllegalArgumentException::class.java) { reviewImport(input, draft, raw, emptyList(), emptyList(), emptyMap()) }
        assertThrows(IllegalArgumentException::class.java) { ProgramImport.read(raw.replace("title:", "  title:")) }
        assertThrows(IllegalArgumentException::class.java) { ProgramImport.read(raw.replace("1-2 мин |", "1-2 мин | лишний |")) }
        assertThrows(IllegalArgumentException::class.java) { ProgramImport.read(raw.replace("2 × 5-7", "2 × 7-5")) }
        assertThrows(IllegalArgumentException::class.java) { ProgramImport.read(raw + "\n## Дополнительно\n| X | Y |\n") }
    }

    @Test fun updateKeepsIdentityWorkoutsAndSchedulePreferencesAndRefusesLocalEdits() {
        val raw = source(listOf("Альфа", "Бета", "Гамма"), 4)
        val first = apply(raw)
        val program = first.program.copy(enabled = false, participantIds = listOf(profile.id), anchor = date.plusDays(3).toString())
        val snapshot = scheduledSession(program, program.days.first(), date, listOf(profile)).start()
        val same = apply(raw, listOf(program), first.newExercises)
        assertEquals(program, same.program)
        assertTrue(same.newExercises.isEmpty())
        val next = apply(source(listOf("Гамма", "Альфа"), 4), listOf(program), first.newExercises).program
        assertEquals(program.id, next.id)
        assertFalse(next.enabled)
        assertEquals(program.anchor, next.anchor)
        assertEquals(program.participantIds, next.participantIds)
        assertEquals(listOf(program.days[2].id, program.days[0].id), next.days.map { it.id })
        assertEquals("Альфа", snapshot.defaultWorkout.templateTitle)
        assertThrows(IllegalArgumentException::class.java) {
            apply(raw, listOf(program.copy(days = program.days.reversed())), first.newExercises)
        }
        assertEquals(next, Markdown.read(Markdown.write(next))?.document)
        assertEquals(next, ProgramImport.read(Markdown.write(next)).template)
    }

    @Test fun verifiesOriginalFileWhenAvailable() {
        val file = System.getenv("GYMFLOW_IMPORT_SOURCE")?.let(::File)
        assumeTrue(file?.isFile == true)
        val raw = file!!.readText()
        val imported = apply(raw)
        val program = imported.program
        assertEquals(4, program.days.size)
        assertEquals(listOf(6, 4, 6, 4), program.days.map { it.exercises.size })
        assertEquals(listOf(18, 14, 18, 14), program.days.map { day -> day.exercises.sumOf { it.sets.size } })
        assertEquals(3, program.completionCycle?.visitsPerWeek)
        assertEquals(24, program.completionCycle?.targetSessions)
        assertEquals(listOf(6, 12, 18, 24), program.completionCycle!!.phases.map { it.through })
        assertEquals(listOf(180, 120, 120, 90, 60, 60), program.days.first().exercises.map { it.restSeconds })
        assertEquals(listOf(240, 180, 120, 120, 90, 90), program.days.first().exercises.map { it.restMaxSeconds })
        assertTrue(program.days.all { day ->
            day.blocks.any { it.type == BlockType.WARMUP && it.guidance.isNotBlank() } &&
                day.blocks.any { it.type == BlockType.CARDIO && it.requirement == PlanBlock.OPTIONAL && it.guidance.isNotBlank() } &&
                day.blocks.any { it.type == BlockType.MOBILITY && it.requirement == PlanBlock.OPTIONAL && it.guidance.isNotBlank() }
        })
        assertTrue(program.days.flatMap { it.exercises }.flatMap { it.sets }.none { it.weightKnown })
        assertTrue(program.caution.isNotBlank())
        assertEquals(raw, program.importSource!!.original)
        assertEquals(program, Markdown.read(Markdown.write(program))?.document)
    }
}
