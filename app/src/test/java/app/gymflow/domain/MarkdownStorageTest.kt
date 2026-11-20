package app.gymflow.domain

import app.gymflow.data.migrateWikiLinks
import org.junit.Assert.*
import org.junit.Test

class MarkdownStorageTest {
    @Test fun editsTitleAndResultsDirectlyInMarkdown() {
        val exercise = Exercise(name = "Тяга")
        val workout = Session(title = "День спины", date = "2026-11-20", participants = listOf(Profile(name = "Я")))
            .addExercise(ExercisePlan(exercise, listOf(SetPlan(16.0))))
        val completed = workout.copy(workouts = workout.workouts.map { person ->
            person.editExercise(person.exercises.single().id) { entry ->
                entry.copy(athletes = entry.athletes.map { athlete ->
                    athlete.copy(sets = athlete.sets.map { it.copy(status = SetStatus.COMPLETED, actual = Actual(18.0, 10)) })
                })
            }
        })
        val markdown = Markdown.write(completed)
        assertFalse(markdown.contains("gymflow:json"))
        assertTrue(markdown.contains("Факт / Вес | Факт / Повторы"))
        val edited = markdown.replace("# День спины", "# Новая тренировка")
            .replace(" | 18.0 | 10 | ", " | 24.0 | 11 | ")
        val session = Markdown.read(edited)?.document as Session
        assertEquals(completed.id, session.id)
        assertEquals("Новая тренировка", session.title)
        assertEquals(Actual(24.0, 11), session.defaultWorkout.exercises.single().athletes.single().sets.single().actual)
    }

    @Test fun previousReportAndUnknownFieldsSurviveTheNextWrite() {
        val exercise = Exercise(name = "Тяга", notes = "Личные указания")
        val legacy = Markdown.writeLegacy(exercise, "## Мой раздел\n\n[[Сон]]", mapOf("tags" to "\n- зал"))
            .replace("# Тяга", "# Тяга\n\nДополнение извне")
        val before = requireNotNull(Markdown.read(legacy))
        val v7 = Markdown.rewrite(exercise, before).replace("<!-- /gymflow:данные -->",
            "- Примечание тренера: индивидуальный хват\n<!-- /gymflow:данные -->")
        val parsed = requireNotNull(Markdown.read(v7))
        val next = Markdown.rewrite(exercise.copy(favorite = true), parsed)
        assertTrue(next.contains("Дополнение извне"))
        assertTrue(next.contains("## Мой раздел\n\n[[Сон]]"))
        assertTrue(next.contains("Примечание тренера: индивидуальный хват"))
        assertEquals(mapOf("tags" to "\n- зал"), Markdown.read(next)?.extraYaml)
    }

    @Test fun namesUseDatesAndFallbackCountersInsteadOfIds() {
        val program = Program(title = "Силовая / программа?", anchor = "2026-11-20")
        assertEquals("Силовая программа.md", markdownName(program, emptyList()))
        assertEquals("Силовая программа - 2026-11-20.md", markdownName(program, listOf("СИЛОВАЯ ПРОГРАММА.MD")))
        val workout = Session(title = "Грудь", date = "2026-11-20", participants = listOf(Profile(name = "Иван")))
        assertEquals("2026-11-20 - Грудь - Иван.md", markdownName(workout, listOf("2026-11-20 - Грудь.md")))
        assertEquals("Заметка.md", markdownName(SportNote(title = newId()), emptyList()))
    }

    @Test fun migrationUpdatesExistingWikiLinksWithoutEditingCode() {
        val id = newId()
        val paths = mapOf(id to "Упражнения/Тяга")
        assertEquals("[[Упражнения/Тяга#План|Тяга]]\n`[[$id]]`",
            migrateWikiLinks("[[$id#План|Тяга]]\n`[[$id]]`", paths))
    }
}
