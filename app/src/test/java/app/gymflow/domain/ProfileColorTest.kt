package app.gymflow.domain

import org.junit.Assert.*
import org.junit.Test

class ProfileColorTest {
    @Test fun markdownNormalizesValidLegacyColorsAndUsesDefaultForDamage() {
        val profile = Profile(name = "QA")
        val src = Markdown.write(profile)
        val normalized = 0xFFCF91EA.toInt()
        val cases = listOf(
            "#CF91EA" to normalized, "#7FCF91EA" to normalized, "0xFFCF91EA" to normalized,
            "4291793386" to normalized, "#ZZ91EA" to null, "" to null, "unknown" to null,
        )
        cases.forEach { (raw, expected) ->
            val legacy = src.replace("gymflow_schema: 5", "gymflow_schema: 2").replace("\"color\": null", "\"color\": \"$raw\"")
            val read = Markdown.read(legacy)?.document as Profile
            assertEquals(expected, read.color)
            assertEquals(read, Markdown.read(Markdown.write(read))?.document)
        }
        val legacyNegative = Markdown.write(profile.copy(color = normalized))
            .replace("gymflow_schema: 5", "gymflow_schema: 2")
            .replace("\"color\": $normalized", "\"color\": \\u002d${-normalized}")
        assertEquals(normalized, (Markdown.read(legacyNegative)?.document as Profile).color)
        val absent = src.replace("    \"color\": null,\n", "")
        assertNull((Markdown.read(absent)?.document as Profile).color)
        val wrongFormat = src.replace("\"color\": null", "\"color\": {\"red\": 10}")
        assertNull((Markdown.read(wrongFormat)?.document as Profile).color)
    }
}
