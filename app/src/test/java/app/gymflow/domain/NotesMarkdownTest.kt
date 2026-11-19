package app.gymflow.domain

import org.junit.Assert.*
import org.junit.Test

class NotesMarkdownTest {
    @Test fun workoutNotesKeepStableIdsAndUseTheMarkdownBody() {
        val original = Session(title = "Силовая", date = "2026-11-20", participants = listOf(Profile(name = "Я")))
        val morning = WorkoutNote(body = "## Самочувствие\n\n[[Сон]]")
        val evening = WorkoutNote(body = "    отступ\n\n")
        val workout = original.copy(workoutNotes = listOf(morning, evening))
        val raw = Markdown.write(workout, "Свободный комментарий")
        assertTrue(raw.contains("### Заметка от ${morning.created}"))
        assertFalse(raw.contains("\"body\""))
        val external = assertNotNullDocument(Markdown.read(raw.replace("[[Сон]]", "[[Восстановление]]")))
        assertEquals(workout.copy(workoutNotes = listOf(morning.copy(body = "## Самочувствие\n\n[[Восстановление]]"), evening)),
            external.document)
        assertEquals("Свободный комментарий", external.freeText)
        assertThrows(IllegalArgumentException::class.java) {
            Markdown.read(raw.replace("<!-- /gymflow:note -->", ""))
        }
    }

    @Test fun sportNoteRoundTripsWithObsidianPropertiesAndDeletionMarker() {
        val note = SportNote(title = "Цель", body = "### Заметка от автора\n\n- [[Прогресс]]\n", programId = newId())
        val tags = mapOf("tags" to "\n- спорт\n- наблюдения", "aliases" to "[\"Цель недели\"]",
            "Состояние утром" to "|\n  строка 1\n  строка 2")
        val raw = Markdown.write(note, "Также [[личное]]", tags)
        val decoded = assertNotNullDocument(Markdown.read(raw))
        assertEquals(note, decoded.document)
        assertEquals(tags, decoded.extraYaml)
        assertEquals("Также [[личное]]", decoded.freeText)
        assertEquals(raw, Markdown.write(decoded.document, decoded.freeText, decoded.extraYaml))
        val deleted = note.copy(body = "", deleted = true)
        val tombstone = Markdown.write(deleted)
        assertFalse(tombstone.contains("[[Прогресс]]"))
        assertEquals(deleted, Markdown.read(tombstone)?.document)
    }

    @Test fun readsYamlSequenceFromObsidianProperties() {
        val profile = Profile(name = "Я")
        val original = Session(title = "Силовая", date = "2026-11-20", participants = listOf(profile))
        val raw = Markdown.write(original).replace("participants: [${profile.id}]", "participants:\n  - '${profile.id}'")
            .replace("id: ${original.id}", "id: \"${original.id}\"")
        assertEquals(original, Markdown.read(raw)?.document)
    }

    private fun assertNotNullDocument(document: MarkdownDocument?) = requireNotNull(document)
}
