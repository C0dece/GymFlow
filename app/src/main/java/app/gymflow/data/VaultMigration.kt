package app.gymflow.data

import app.gymflow.domain.*
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json

internal fun linkProperties(
    document: Document,
    extra: Map<String, String>,
    paths: Map<String, String>,
): Map<String, String> {
    val ids = documentLinks(document)
    if (paths.isEmpty() && ids.isNotEmpty()) return extra
    val links = ids.mapNotNull(paths::get).filter { path ->
        path.none { it in "[]|#\r\n" }
    }.map { "[[${it.dropLast(3)}]]" }
    return if (links.isEmpty()) extra - "gymflow_links"
    else extra + ("gymflow_links" to links.joinToString(separator = "", transform = { "\n  - ${Json.encodeToString(it)}" }))
}

internal data class VaultChange(
    val record: Record,
    val node: Node,
    val targetName: String,
    val before: String,
    val after: String,
) {
    val targetPath get() = node.path.substringBeforeLast('/', "").let { if (it.isEmpty()) targetName else "$it/$targetName" }
}

internal fun migrateWikiLinks(text: String, paths: Map<String, String>): String {
    if (paths.isEmpty()) return text
    val wiki = Regex("""\[\[([^|#\]\r\n]+)(#[^|\]\r\n]+)?(\|[^\]\r\n]+)?]]""")
    var fence: String? = null
    return text.splitToSequence('\n').joinToString("\n") { line ->
        val marks = line.trimStart().takeWhile { it == '`' || it == '~' }
        if (fence != null) {
            if (marks.startsWith(requireNotNull(fence))) fence = null
            line
        } else if (marks.length >= 3) {
            fence = marks
            line
        } else {
            val protected = Regex("`+[^`]+`+").findAll(line).map { it.range }.toList()
            wiki.replace(line) { match ->
                val target = match.groupValues[1]
                val replacement = paths[target.removeSuffix(".md")]
                if (replacement == null || protected.any { match.range.first in it }) match.value
                else "[[$replacement${if (target.endsWith(".md")) ".md" else ""}${match.groupValues[2]}${match.groupValues[3]}]]"
            }
        }
    }
}

internal fun migrationBackup(out: OutputStream, changes: List<VaultChange>): Map<String, String> {
    val originals = changes.associate { change ->
        require(change.node.path.split('/').all { part ->
            part.isNotBlank() && part !in setOf(".", "..") && part.none { it in "\\\t\r\n" }
        })
        "Исходники/${change.node.path}" to change.before
    }
    require(originals.size == changes.size) { "В папке есть одинаковые пути" }
    val mapping = buildString {
        appendLine("ID\tИсходный путь\tПуть после миграции\tSHA-256 до\tSHA-256 после")
        changes.forEach { appendLine("${it.record.id}\t${it.node.path}\t${it.targetPath}\t${digest(it.before)}\t${digest(it.after)}") }
    }
    val entries = originals + ("Переименования.tsv" to mapping)
    ZipOutputStream(out).use { zip ->
        entries.forEach { (name, text) ->
            zip.putNextEntry(ZipEntry(name))
            zip.write(text.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }
    return entries.mapValues { digest(it.value) }
}

internal fun verifyMigrationBackup(input: InputStream, expected: Map<String, String>) {
    val found = mutableSetOf<String>()
    ZipInputStream(input).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            if (!found.add(entry.name) || digest(readMarkdownStream(zip)) != expected[entry.name]) {
                throw IOException("Не удалось проверить резервную копию миграции")
            }
        }
    }
    check(found == expected.keys) { "Резервная копия миграции неполная" }
}
