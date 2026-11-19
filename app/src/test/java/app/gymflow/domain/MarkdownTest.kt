package app.gymflow.domain

import org.junit.Assert.*
import org.junit.Test

class MarkdownTest {
    private val golden get() = fixture("session-v1.md")

    private fun fixture(path: String) =
        requireNotNull(javaClass.getResourceAsStream("/golden/$path")).bufferedReader().use { it.readText() }.replace("\r\n", "\n")

    @Test fun goldenRoundTripPreservesObsidianNotesAndReport() {
        val decoded = requireNotNull(Markdown.read(golden))
        val workout = decoded.document as Session
        assertEquals("Тренировка А", workout.title)
        assertEquals(18.0, workout.workouts.single().exercises.single().athletes.single().sets.single().actual!!.weight, 0.0)
        assertEquals("## Моя заметка\n\nПосле жима [[растяжка]].", decoded.freeText)
        assertTrue(golden.contains("> [!info]"))
        assertTrue(golden.contains("| 1 | 16 × 8-12 | 18 × 10 | Выполнен |  |"))
        val upgraded = Markdown.write(workout, decoded.freeText)
        assertEquals(decoded, Markdown.read(upgraded))
        assertTrue(upgraded.contains("gymflow_schema: 6"))
    }

    @Test fun oneTwoThreeAndManyParticipants() {
        listOf(1, 2, 3, 19).forEach { count ->
            val original = Session(title = "Имя _** | [[<Тяга>]]", date = "2026-11-18",
                participants = (1..count).map { Profile(name = "Участник $it") })
                .addExercise(ExercisePlan(Exercise(name = "Тяга | ряд"), listOf(SetPlan(weight = 26.5))))
            val markup = Markdown.write(original)
            assertEquals(original, Markdown.read(markup)?.document)
            assertTrue(markup.contains("Тяга &#124; ряд"))
            assertTrue(markup.contains("&#95;&#42;&#42;"))
        }
    }

    @Test fun incompleteEmptyAndReplacedAreRoundTrippable() {
        val person = Profile(name = "Иван")
        val plan = ExercisePlan(Exercise(name = "Жим"), listOf(SetPlan()))
        val session = Session(title = "А", date = "2026-11-18", participants = listOf(person)).start()
        assertEquals(session, Markdown.read(Markdown.write(session))?.document)
        val workout = session.addExercise(plan)
        val id = workout.workouts.single().exercises.single().id
        val replaced = workout.editAthlete(id, person.id) { it.copy(skipped = true) }
            .editWorkout(person.id) { it.replaceExercise(id, plan.copy(exercise = Exercise(name = "Тяга"))) }
        assertEquals(replaced, Markdown.read(Markdown.write(replaced))?.document)
        assertTrue(Markdown.write(replaced).contains("#### Заменено: Жим"))
    }

    @Test fun interruptedOrMalformedDataAreRejected() {
        listOf(
            golden.substringBefore("gymflow:json -->"),
            golden.replaceFirst("gymflow_schema: 1", "gymflow_schema: ["),
            golden.replaceFirst("gymflow_schema: 1", "gymflow_schema: 1\ngymflow_schema: 1"),
            golden.replaceFirst("\"status\": \"ACTIVE\"", "\"status\": []"),
            golden.replaceFirst("status: active", "status: completed"),
            golden.replaceFirst("<!-- /gymflow:report -->", ""),
        ).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { Markdown.read(invalid) }
        }
        assertNull(Markdown.read("# Обычная заметка\n\nЧужой текст."))
    }

    @Test fun futureSchemaIsRejectedWithoutProducingReplacement() {
        assertThrows(IllegalArgumentException::class.java) { Markdown.read(golden.replace("schema: 1", "schema: 205")) }
    }

    @Test fun migrationNormalizesOldStructuredSchema() {
        val v0 = requireNotNull(Markdown.read(fixture("session-v0.md")))
        val v1 = requireNotNull(Markdown.read(golden))
        assertEquals(v1.document, v0.document)
        val saved = Markdown.write(v0.document)
        assertTrue(saved.startsWith("---\ngymflow_schema: 6"))
        assertEquals(v0.document, Markdown.read(saved)?.document)
    }

    @Test fun documentsAreDeterministicAndUserMarkersCannotBreakComment() {
        val source = requireNotNull(Markdown.read(golden))
        val once = Markdown.write(source.document, source.freeText)
        val reloaded = requireNotNull(Markdown.read(once))
        assertEquals(once, Markdown.write(reloaded.document, reloaded.freeText))
        val text = "<!-- gymflow:json\n\uD83D\uDE00 -->"
        val exercise = Exercise(name = text)
        assertEquals(exercise, Markdown.read(Markdown.write(exercise))?.document)
    }

    @Test fun acceptsCrlfAndBom() {
        assertEquals(Markdown.read(golden), Markdown.read("\uFEFF" + golden.replace("\n", "\r\n")))
    }

    @Test fun importedObsidianFilesAreReadWithoutErrors() {
        val dirs = listOf(
            java.io.File("qa-output/test-import"),
            java.io.File("d:/Заметки/Obsidian/Codece/Личное/Здоровье/Тренировки")
        )
        for (dir in dirs) {
            if (!dir.isDirectory) continue
            val files = dir.walkTopDown().filter { it.isFile && it.extension == "md" }.toList()
            for (file in files) {
                val text = file.readText()
                val doc = Markdown.read(text)?.document ?: continue
                when (doc) {
                    is Program -> assertEquals("Возвращение в зал", doc.title)
                    is Exercise -> assertTrue(doc.name.isNotBlank())
                    is Profile -> assertTrue(doc.name in listOf("Алексей", "Партнёр"))
                    else -> Unit
                }
            }
        }
    }
}
