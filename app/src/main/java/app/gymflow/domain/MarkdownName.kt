package app.gymflow.domain

import java.time.LocalDate
import java.util.Locale

internal val markdownFolders = listOf("Тренировки", "Программы", "Упражнения", "Участники", "Прогрессия", "Движения", "Заметки")

internal fun markdownFolder(document: Document) = when (document) {
    is Session -> markdownFolders[0]
    is Program -> markdownFolders[1]
    is Exercise -> markdownFolders[2]
    is Profile -> markdownFolders[3]
    is ProgressionPolicy, is ProgressionReview -> markdownFolders[4]
    is MovementFamily, is MovementTrack, is MovementSkill, is SkillAttempt -> markdownFolders[5]
    is SportNote -> markdownFolders[6]
}

internal fun documentTitle(document: Document) = when (document) {
    is Session -> document.title
    is Program -> document.title
    is Exercise -> document.name
    is Profile -> document.name
    is SportNote -> document.title
    is MovementFamily -> document.name
    is MovementTrack -> "Отслеживание движения - ${document.role.label}"
    is MovementSkill -> document.name
    is SkillAttempt -> "Попытка - ${document.measure.label}"
    is ProgressionPolicy -> "Правила прогрессии"
    is ProgressionReview -> "Рекомендации по прогрессии"
}

private val unsafe = Regex("[\\\\/:*?\"<>|#^\\[\\]\\p{Cntrl}]")
private val opaque = Regex("(?i)(?<![0-9a-f])(?:[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}|[0-9a-f]{16,})(?![0-9a-f])")
private val windowsDevice = Regex("(?i)^(?:CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?$")

private fun readable(value: String): String = unsafe.replace(opaque.replace(value, ""), " ")
    .replace('\u2014', '-').replace('\u2013', '-')
    .replace(Regex("\\s+"), " ").trim(' ', '.')

internal fun markdownName(document: Document, occupied: Collection<String>, context: Map<String, Document> = emptyMap()): String {
    fun title(id: String?): String? = id?.let(context::get)?.let(::documentTitle)?.let(::readable)?.takeIf(String::isNotBlank)
    val base = readable(documentTitle(document)).ifBlank {
        when (document) {
            is Session -> "Тренировка"
            is Program -> "Программа"
            is Exercise -> "Упражнение"
            is Profile -> "Участник"
            is SportNote -> "Заметка"
            else -> markdownFolder(document)
        }
    }
    val date = when (document) {
        is Session -> document.date
        is ProgressionReview -> document.date
        is SkillAttempt -> document.date
        is Program -> document.anchor
        is SportNote -> document.created.substringBefore('T').takeIf { runCatching { LocalDate.parse(it) }.isSuccess }
        else -> null
    }
    val detail = when (document) {
        is Session -> document.participants.joinToString(", ") { readable(it.name) }.takeIf { it.isNotBlank() }
        is MovementTrack -> listOfNotNull(title(document.exerciseId), title(document.profileId)).joinToString(" - ")
        is ProgressionPolicy -> listOfNotNull(title(document.scope.profileId), title(document.scope.programId),
            title(document.scope.exerciseId)).joinToString(" - ").ifBlank { "Общие" }
        is ProgressionReview -> title(document.profileId)
        is SkillAttempt -> title(document.skillId)
        else -> date
    }
    val prefix = if (document is Session || document is ProgressionReview || document is SkillAttempt) "$date - " else ""
    val named = when (document) {
        is MovementTrack, is ProgressionPolicy, is ProgressionReview, is SkillAttempt ->
            listOfNotNull(base, detail?.takeIf(String::isNotBlank)).joinToString(" - ")
        else -> base
    }
    val stem = prefix + named.let { if (windowsDevice.matches(it)) "Запись - $it" else it }
    val occupiedKeys = occupied.mapTo(hashSetOf()) { it.lowercase(Locale.ROOT) }
    fun candidate(suffix: String) = stem.take((100 - suffix.length).coerceAtLeast(15)).trimEnd(' ', '.', '-') + suffix + ".md"
    val preferred = candidate("")
    if (preferred.lowercase(Locale.ROOT) !in occupiedKeys) return preferred
    val qualifier = detail?.takeIf { it.isNotBlank() && it != named && !named.contains(it) }?.take(32)
    if (qualifier != null) {
        val described = candidate(" - $qualifier")
        if (described.lowercase(Locale.ROOT) !in occupiedKeys) return described
    }
    return generateSequence(2) { it + 1 }.map { candidate(" ($it)") }.first { it.lowercase(Locale.ROOT) !in occupiedKeys }
}

internal fun documentLinks(document: Document): List<String> = when (document) {
    is Session -> listOfNotNull(document.programId) + document.participants.map { it.id } +
        document.allWorkouts.flatMap { it.exercises.map { item -> item.exercise.id } }
    is Program -> document.participantIds + document.days.flatMap { it.exercises.map { plan -> plan.exercise.id } }
    is SportNote -> listOfNotNull(document.sessionId, document.programId)
    is Exercise -> listOfNotNull(document.movement?.familyId, document.movement?.parentId)
    is MovementTrack -> listOf(document.exerciseId, document.profileId)
    is SkillAttempt -> listOf(document.skillId, document.profileId)
    is ProgressionReview -> listOf(document.sessionId, document.profileId)
    is ProgressionPolicy -> listOfNotNull(document.scope.profileId, document.scope.programId, document.scope.exerciseId)
    is MovementSkill -> document.relatedSkills.toList()
    else -> emptyList()
}.distinct()
