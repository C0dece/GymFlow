package app.gymflow.domain

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

interface DocumentCodec {
    fun read(markdown: String): MarkdownDocument?
    fun write(document: Document, notes: String = "", extraYaml: Map<String, String> = emptyMap()): String
}

data class MarkdownDocument(val document: Document, val freeText: String, val extraYaml: Map<String, String> = emptyMap())

object Markdown : DocumentCodec {
    const val schema = 3
    private const val open = "<!-- gymflow:json"
    private const val close = "gymflow:json -->"
    private const val reportStart = "<!-- gymflow:report -->"
    private const val reportEnd = "<!-- /gymflow:report -->"
    private val json = Json {
        classDiscriminator = "kind"
        encodeDefaults = true
        prettyPrint = true
    }
    private val reserved = setOf("gymflow_schema", "id", "kind", "date", "status", "participants")
    private val yamlKey = Regex("[A-Za-z_][A-Za-z0-9_-]*")
    private val front = Regex("""\A---\n([\s\S]*?)\n---\n""")
    private val block = Regex("""(?m)^$open\n([\s\S]*?)\n$close$""")
    private val report = Regex("""(?m)^$reportStart\n[\s\S]*?^$reportEnd$""")

    private val legacyNegative = Regex("""(?m)^([ \t]*(?:"(?:\\.|[^"\\])*"[ \t]*:[ \t]*)?)\\u002d(?=\d)""")

    override fun read(markdown: String): MarkdownDocument? {
        val text = markdown.removePrefix("\uFEFF").replace("\r\n", "\n")
        val frontmatter = front.find(text)
        if (frontmatter == null || !frontmatter.groupValues[1].lineSequence().any {
                it.startsWith("gymflow_schema:")
            }) {
            require(open !in text && reportStart !in text) { "Отсутствует заголовок GymFlow" }
            return null
        }
        val pairs = frontmatter.groupValues[1].lineSequence().map { line ->
            val pieces = line.split(':', limit = 2)
            require(pieces.size == 2 && pieces[0].matches(yamlKey)) { "Повреждён YAML" }
            pieces[0] to pieces[1].trim()
        }.toList()
        val yaml = pairs.toMap()
        require(yaml.size == pairs.size) { "Повторяющиеся поля YAML" }
        val version = requireNotNull(yaml["gymflow_schema"]?.toIntOrNull()) { "Неверная версия схемы" }
        require(version in 0..schema) { "Версия GymFlow $version не поддерживается" }

        val content = text.substring(frontmatter.range.last + 1)
        val structure = block.findAll(content).toList().singleOrNull()
        require(structure != null) { "Отсутствует или дублируется блок GymFlow" }
        val payload = structure.groupValues[1]
        // Older writers escaped numeric minus signs together with hyphens in strings.
        val readablePayload = if (version < schema) legacyNegative.replace(payload) { it.groupValues[1] + "-" } else payload
        val raw = json.parseToJsonElement(readablePayload).jsonObject
        val normalized = migrate(version, raw)
        val document = if (version < 2 && normalized["kind"] == JsonPrimitive("session")) {
            json.decodeFromJsonElement(LegacySession.serializer(), JsonObject(normalized - "kind")).migrate()
        } else json.decodeFromJsonElement(Document.serializer(), normalized)
        validate(document)
        require(yaml["id"] == document.id && yaml["kind"] == kind(document)) { "YAML и JSON описывают разные записи" }
        if (document is Session) {
            require(yaml["date"] == document.date && yaml["status"] == document.status.name.lowercase()) {
                "Дата или статус в YAML отличаются от JSON"
            }
            val participantIds = yaml["participants"]?.let { value ->
                require(value.startsWith("[") && value.endsWith("]")) { "Некорректный список участников в YAML" }
                value.removeSurrounding("[", "]").split(',').map(String::trim)
            }
            require(participantIds == document.participants.map { it.id }) { "Участники в YAML отличаются от JSON" }
        }
        val readable = report.findAll(content).toList().singleOrNull()
        require(readable != null || version == 0) { "Отчёт GymFlow повреждён" }
        return MarkdownDocument(document, block.replace(report.replace(content, ""), "").trim('\n'), yaml.filterKeys { it !in reserved })
    }

    override fun write(document: Document, notes: String, extraYaml: Map<String, String>): String {
        validate(document)
        require(listOf(open, close, reportStart, reportEnd).none { it in notes }) { "Заметка содержит служебный маркер" }
        require(extraYaml.all { (key, value) -> key !in reserved && key.matches(yamlKey) && value.none { it == '\n' || it == '\r' } })
        return buildString {
            appendLine("---")
            appendLine("gymflow_schema: $schema")
            appendLine("id: ${document.id}")
            appendLine("kind: ${kind(document)}")
            if (document is Session) {
                appendLine("date: ${document.date}")
                appendLine("status: ${document.status.name.lowercase()}")
                appendLine("participants: [${document.participants.joinToString { it.id }}]")
            }
            extraYaml.forEach { (key, value) -> appendLine("$key: $value") }
            appendLine("---")
            appendLine()
            appendLine(reportStart)
            appendLine(render(document))
            appendLine(reportEnd)
            if (notes.isNotBlank()) {
                appendLine()
                appendLine(notes)
            }
            appendLine()
            appendLine(open)
            appendLine(
                json.encodeToString(Document.serializer(), document).replace("-->", "--\\u003e")
            )
            appendLine(close)
        }
    }

    private fun migrate(version: Int, raw: JsonObject): JsonObject = when (version) {
        0 -> JsonObject(raw.toMutableMap().apply {
            remove("type")?.let { put("kind", it) }
            if (get("kind") == JsonPrimitive("session")) remove("name")?.let { put("title", it) }
        })
        else -> raw
    }

    private fun kind(document: Document) = when (document) {
        is Session -> "session"
        is Program -> "program"
        is Profile -> "profile"
        is Exercise -> "exercise"
        is ProgressionPolicy -> "progression_policy"
        is ProgressionReview -> "progression_review"
    }

    private fun validate(document: Document) {
        require(UUID.fromString(document.id).toString() == document.id) { "Некорректный идентификатор" }
        when (document) {
            is Session -> {
                LocalDate.parse(document.date)
                document.started?.let(Instant::parse)
                document.finished?.let(Instant::parse)
                require(document.participants.isNotEmpty())
                val ids = document.participants.map { validate(it); it.id }.toSet()
                require(ids.size == document.participants.size)
                require(document.switchedProfileId == null || document.switchedProfileId in ids)
                require(document.allWorkouts.distinctBy { it.id }.size == document.allWorkouts.size)
                require(document.otherWorkouts.all { it.profile.id in ids && it.status != WorkoutStatus.ACTIVE })
                require(document.progression.distinctBy { it.participantId to it.exerciseEntryId }.size == document.progression.size)
                document.progression.forEach {
                    require(it.participantId in ids && it.participantId == it.decision.profileId)
                    (it.decision.baseline + it.decision.proposed + it.decision.templateBaseline.orEmpty() + it.finalPlan).forEach(::validateSet)
                }
                document.allWorkouts.forEach { workout ->
                    require(UUID.fromString(workout.id).toString() == workout.id)
                    require(workout.exercises.distinctBy { it.id }.size == workout.exercises.size)
                    require(workout.currentExerciseId == null || workout.exercises.any { it.id == workout.currentExerciseId })
                    workout.exercises.forEach { validateEntry(it, setOf(workout.profile.id)) }
                }
            }
            is Program -> {
                LocalDate.parse(document.anchor)
                require(document.interval > 0 && document.days.distinctBy { it.id }.size == document.days.size)
                document.days.flatMap { it.exercises }.forEach(::validatePlan)
            }
            is ProgressionPolicy -> require(document.id == document.scope.documentId())
            is ProgressionReview -> {
                LocalDate.parse(document.date)
                require(document.part >= 0)
                require(document.decisions.all { it.profileId == document.profileId })
                document.decisions.forEach { (it.baseline + it.proposed).forEach(::validateSet) }
            }
            is Profile -> require(document.name.isNotBlank())
            is Exercise -> require(document.name.isNotBlank() && document.repsMin in 1..document.repsMax)
        }
    }

    private fun validatePlan(plan: ExercisePlan) {
        validate(plan.exercise)
        (plan.sets + plan.individual.values.flatten()).forEach(::validateSet)
    }

    private fun validateSet(plan: SetPlan) {
        require(plan.weight.isFinite() && plan.weight >= 0 && plan.repsMin in 1..plan.repsMax)
        require(plan.targetReps == null || plan.targetReps in plan.repsMin..plan.repsMax)
    }

    private fun validateEntry(entry: ExerciseEntry, participants: Set<String>) {
        validatePlan(entry.plan)
        require(entry.athletes.singleOrNull()?.participantId in participants)
        entry.athletes.forEach { athlete ->
            require(athlete.rir == null || athlete.rir in 0..4)
            require(athlete.sets.distinctBy { it.id }.size == athlete.sets.size)
            athlete.sets.forEach {
                validateSet(it.plan)
                require(it.status != SetStatus.COMPLETED || it.actual != null)
                it.actual?.let { actual -> require(actual.weight.isFinite() && actual.weight >= 0 && actual.reps > 0) }
            }
        }
        entry.replaced.forEach { validateEntry(it, participants) }
    }

    private fun md(value: String) = value.replace("&", "&amp;")
        .replace("<", "&lt;").replace(">", "&gt;").replace("|", "&#124;")
        .replace("\\", "&#92;").replace("[", "&#91;").replace("]", "&#93;")
        .replace("*", "&#42;").replace("_", "&#95;").replace("~", "&#126;")
        .replace("\r", " ").replace("\n", " ")

    private fun unit(unit: UnitWeight) = if (unit == UnitWeight.KG) "кг" else "фунт"
    private fun load(exercise: Exercise) = when (exercise.load) {
        Load.DUMBBELL -> "на одну гантель"
        Load.BARBELL -> "полный вес штанги"
        Load.MACHINE -> "нагрузка тренажёра"
        Load.BODYWEIGHT -> "добавочная нагрузка"
        Load.OTHER -> "тип нагрузки не указан"
    }
    private fun number(value: Double) = value.toString().removeSuffix(".0")
    private fun reps(min: Int, max: Int) = if (min == max) "$max" else "$min-$max"
    private fun planText(p: SetPlan) = "${if (p.weightKnown) number(p.weight) else "?"} × " +
        when {
            p.maxEffort -> "макс"
            !p.repsKnown -> "?"
            else -> reps(p.repsMin, p.repsMax) + (p.targetReps?.let { " (цель $it)" } ?: "")
        }

    fun schemaVersion(markdown: String): Int? = front.find(markdown.removePrefix("\uFEFF").replace("\r\n", "\n"))
        ?.groupValues?.get(1)?.lineSequence()?.firstOrNull { it.startsWith("gymflow_schema:") }
        ?.substringAfter(':')?.trim()?.toIntOrNull()

    private fun render(document: Document): String = when (document) {
        is Session -> sessionReport(document)
        is Profile -> "# ${md(document.name)}\n\nПрофиль участника"
        is ProgressionPolicy -> "# Правила прогрессии\n\nСтратегия: ${document.ruleSet.strategy}. Версия: ${document.version}."
        is ProgressionReview -> buildString {
            appendLine("# Рекомендации от ${document.date}")
            document.decisions.forEach { item ->
                appendLine("- ${md(item.exerciseName)}: ${item.action} / ${item.reason}")
            }
        }.trimEnd()
        is Exercise -> "# ${md(document.name)}\n\n${load(document)}, ${unit(document.unit)}. Повторы: " +
            reps(document.repsMin, document.repsMax) + "\n\n" + md(document.notes)
        is Program -> buildString {
            appendLine("# ${md(document.title)}")
            appendLine()
            if (document.cycle) appendLine("Цикл с интервалом ${document.interval} дн., начало ${document.anchor}.")
            document.days.forEach { day ->
                val weekday = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")[day.weekday.ordinal]
                appendLine()
                appendLine("## ${md(day.title)}${if (document.cycle) "" else " ($weekday)"}")
                day.exercises.forEach { plan ->
                    appendLine("- ${md(plan.exercise.name)}: ${plan.sets.joinToString(transform = ::planText)}")
                }
            }
        }.trimEnd()
    }

    private fun sessionReport(session: Session) = buildString {
        appendLine("# ${md(session.title)}")
        appendLine()
        appendLine("> [!info] ${session.date}")
        appendLine("> ${stateText(WorkoutStatus.valueOf(session.status.name))}. Подтверждено подходов: ${session.allWorkouts.sumOf { it.completed }}.")
        if (session.started != null && session.finished != null) {
            val minutes = Duration.between(Instant.parse(session.started), Instant.parse(session.finished)).toMinutes()
            appendLine("> Длительность: ${minutes.coerceAtLeast(0)} мин.")
        }
        if (session.notes.isNotBlank()) { appendLine(); appendLine(md(session.notes)) }
        session.allWorkouts.forEach { workout ->
            appendLine()
            appendLine("## " + listOfNotNull(workout.profile.name, workout.templateTitle).joinToString(" · ", transform = ::md))
            appendLine()
            appendLine(stateText(workout.status))
            workout.exercises.forEach { entry ->
                appendLine()
                appendLine("### ${md(entry.exercise.name)}")
                appendLine("${load(entry.exercise)} · ${unit(entry.exercise.unit)}")
                (listOf(entry) + entry.replaced).forEach { record ->
                    if (record !== entry) appendLine("#### Заменено: ${md(record.exercise.name)}")
                    val athlete = record.athletes.single()
                    if (athlete.skipped) appendLine("Упражнение пропущено.")
                    athlete.rir?.let { appendLine("RIR ${if (it == 4) "4+" else it}") }
                    appendLine()
                    appendLine("| Подход | План | Факт | Статус | Заметка |")
                    appendLine("| --- | ---: | ---: | --- | --- |")
                    athlete.sets.forEachIndexed { index, set ->
                        val label = if (set.plan.warmup) "Разминка" else (index + 1).toString()
                        val actual = set.actual?.takeIf { set.status == SetStatus.COMPLETED }
                        val result = actual?.let {
                            "${if (it.weightKnown) number(it.weight) else "?"} × ${if (it.repsKnown) it.reps else "?"}"
                        }.orEmpty()
                        val status = when (set.status) {
                            SetStatus.PLANNED -> "План"
                            SetStatus.COMPLETED -> "Выполнен"
                            SetStatus.SKIPPED -> "Пропущен"
                            SetStatus.CANCELLED -> "Отменён"
                        }
                        appendLine("| $label | ${planText(set.plan)} | $result | $status | ${md(actual?.note.orEmpty())} |")
                    }
                }
            }
        }
        appendLine()
        appendLine("## Итоги")
        session.allWorkouts.forEach { workout ->
            val metrics = workout.metrics
            val values = listOfNotNull(
                metrics.bodyWeightKg?.let { "масса ${number(it)} кг" },
                metrics.feeling?.let { "самочувствие $it/5" },
                metrics.cardioMinutes?.let { "кардио $it мин" },
                metrics.note.takeIf(String::isNotBlank)?.let(::md),
            )
            appendLine("- ${md(workout.profile.name)} · ${stateText(workout.status)} · подходов ${workout.completed}" +
                (if (values.isEmpty()) "" else " · " + values.joinToString()))
        }
    }.trimEnd()

    private fun stateText(status: WorkoutStatus) = when (status) {
        WorkoutStatus.PLANNED -> "Запланирована"
        WorkoutStatus.ACTIVE -> "В процессе"
        WorkoutStatus.PAUSED -> "На паузе"
        WorkoutStatus.COMPLETED -> "Завершена"
        WorkoutStatus.SKIPPED -> "Пропущена"
        WorkoutStatus.CANCELLED -> "Отменена"
    }
}
