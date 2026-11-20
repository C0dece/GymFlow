package app.gymflow.domain

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject

interface DocumentCodec {
    fun read(markdown: String): MarkdownDocument?
    fun write(document: Document, notes: String = "", extraYaml: Map<String, String> = emptyMap()): String
}

data class MarkdownDocument(
    val document: Document,
    val freeText: String,
    val extraYaml: Map<String, String> = emptyMap(),
    val fields: JsonObject? = null,
    internal val legacyText: String? = null,
)

object Markdown : DocumentCodec {
    const val schema = 7
    private const val legacySchema = 6
    private const val open = "<!-- gymflow:json"
    private const val close = "gymflow:json -->"
    private const val reportStart = "<!-- gymflow:report -->"
    private const val reportEnd = "<!-- /gymflow:report -->"
    private val json = Json {
        classDiscriminator = "kind"
        encodeDefaults = true
        prettyPrint = true
        ignoreUnknownKeys = true
    }
    private val reserved = setOf("gymflow_schema", "id", "kind", "date", "status", "participants")
    private val yamlKey = Regex("[\\p{L}_][\\p{L}\\p{N}_ -]*")
    private val front = Regex("""\A---\n([\s\S]*?)\n---\n""")
    private val block = Regex("""(?m)^$open\n([\s\S]*?)\n$close$""")
    private val report = Regex("""(?m)^$reportStart\n[\s\S]*?^$reportEnd$""")
    private const val noteEnd = "<!-- /gymflow:note -->"
    private val noteBlock = Regex("""(?m)^<!-- gymflow:note ([0-9a-f-]{36}) -->\n([\s\S]*?)\n$noteEnd$""")
    private const val fieldsStart = "<!-- gymflow:данные -->"
    private const val fieldsEnd = "<!-- /gymflow:данные -->"
    private val fieldsBlock = Regex("""(?m)^$fieldsStart\n([\s\S]*?)\n$fieldsEnd$""")

    private val legacyNegative = Regex("""(?m)^([ \t]*(?:"(?:\\.|[^"\\])*"[ \t]*:[ \t]*)?)\\u002d(?=\d)""")

    override fun read(markdown: String): MarkdownDocument? {
        val text = markdown.removePrefix("\uFEFF").replace("\r\n", "\n")
        val frontmatter = front.find(text)
        if (frontmatter == null || !frontmatter.groupValues[1].lineSequence().any {
                it.startsWith("gymflow_schema:")
            }) {
            require(open !in text && reportStart !in text && fieldsStart !in text) { "Отсутствует заголовок GymFlow" }
            return null
        }
        val yaml = frontmatterFields(frontmatter.groupValues[1])
        fun scalar(key: String) = yaml[key]?.lineSequence()?.first()?.trim()?.let(::yamlScalar)
        val version = requireNotNull(scalar("gymflow_schema")?.toIntOrNull()) { "Неверная версия схемы" }
        require(version in 0..schema) { "Версия GymFlow $version не поддерживается" }

        val content = text.substring(frontmatter.range.last + 1)
        if (version == schema) {
            val id = requireNotNull(scalar("id"))
            val kind = requireNotNull(scalar("kind"))
            val region = requireNotNull(fieldsBlock.findAll(content).singleOrNull()) { "Нужен один раздел данных GymFlow" }
            val source = region.groupValues[1]
            val title = source.lineSequence().first()
            require(title.startsWith("# ")) { "В начале раздела данных нужен заголовок" }
            val fields = MarkdownFields.read(source.substringAfter('\n', ""))
            val titleField = titleField(kind)
            require("kind" !in fields && "id" !in fields && (titleField == null || titleField !in fields)) { "Дублируются ID, тип или заголовок" }
            val tree = JsonObject(fields + mapOf("id" to JsonPrimitive(id), "kind" to JsonPrimitive(kind)) +
                if (titleField == null) emptyMap() else mapOf(titleField to JsonPrimitive(MarkdownFields.decodeText(title.drop(2)))))
            val metadata = json.decodeFromJsonElement(Document.serializer(), tree)
            val document = withNoteBodies(metadata, fieldsBlock.replace(content, ""))
            validate(document)
            requireIdentifiers(tree, treeOf(document))
            return MarkdownDocument(document, stripNotes(fieldsBlock.replace(content, "")), yaml.filterKeys { it !in reserved }, tree)
        }
        val structure = block.findAll(content).toList().singleOrNull()
        require(structure != null) { "Отсутствует или дублируется блок GymFlow" }
        val payload = structure.groupValues[1]
        // Older writers escaped numeric minus signs together with hyphens in strings.
        val readablePayload = if (version < legacySchema) legacyNegative.replace(payload) { it.groupValues[1] + "-" } else payload
        val raw = json.parseToJsonElement(readablePayload).jsonObject
        val normalized = migrate(version, raw)
        val metadata = if (version < 2 && normalized["kind"] == JsonPrimitive("session")) {
            json.decodeFromJsonElement(LegacySession.serializer(), JsonObject(normalized - "kind")).migrate()
        } else json.decodeFromJsonElement(Document.serializer(), normalized)
        val bodyContent = block.replace(report.replace(content, ""), "")
        val document = withNoteBodies(metadata, bodyContent)
        validate(document)
        require(scalar("id") == document.id && scalar("kind") == kind(document)) { "YAML и JSON описывают разные записи" }
        if (document is Session) {
            require(scalar("date") == document.date && scalar("status") == document.status.name.lowercase()) {
                "Дата или статус в YAML отличаются от JSON"
            }
            val participantIds = yaml["participants"]?.trim()?.let { value ->
                if (value.startsWith("[") && value.endsWith("]")) {
                    value.removeSurrounding("[", "]").split(',').map { yamlScalar(it.trim()) }
                } else value.lineSequence().filter { it.isNotBlank() && !it.trimStart().startsWith("#") }.map {
                    val item = it.trim()
                    require(item.startsWith("- ")) { "Некорректный список участников в YAML" }
                    yamlScalar(item.removePrefix("- ").trim())
                }.toList()
            }
            require(participantIds == document.participants.map { it.id }) { "Участники в YAML отличаются от JSON" }
        }
        val readable = report.findAll(content).singleOrNull()
        require(readable != null || version == 0) { "Отчёт GymFlow повреждён" }
        val priorReport = readable?.value?.substringAfter('\n')?.substringBeforeLast('\n')
        val amendedReport = priorReport?.takeIf { it.trim() != render(document).trim() }
        val fields = if (version >= 2) normalized else null
        return MarkdownDocument(document, stripNotes(bodyContent), yaml.filterKeys { it !in reserved }, fields, amendedReport)
    }

    private fun stripNotes(content: String) = noteBlock.replace(content, "").trim('\n')

    private fun withNoteBodies(metadata: Document, content: String): Document {
        val entries = noteBlock.findAll(content).toList()
        val bodies = entries.associate { it.groupValues[1] to it.groupValues[2] }
        val document = when (metadata) {
            is Session -> metadata.copy(workoutNotes = metadata.workoutNotes.map {
                it.copy(body = requireNotNull(bodies[it.id]).removePrefix(noteHeading(it.created)))
            })
            is SportNote -> if (metadata.deleted) metadata else metadata.copy(body = requireNotNull(bodies[metadata.id]))
            else -> metadata
        }
        require(entries.size == bodies.size && bodies.keys == noteBodies(document).keys) { "Повреждён блок заметок" }
        return document
    }

    private fun titleField(kind: String): String? = when (kind) {
        "program", "session", "sport_note" -> "title"
        "profile", "exercise", "movement_family", "movement_skill" -> "name"
        else -> null
    }

    private fun treeOf(document: Document) = json.encodeToJsonElement(Document.serializer(), document).jsonObject

    private fun requireIdentifiers(raw: JsonObject, decoded: JsonObject) {
        if ("id" in decoded) require(raw["id"] == decoded["id"]) { "У записи отсутствует стабильный ID" }
        decoded.forEach { (key, value) ->
            val old = raw[key]
            if (old is JsonObject && value is JsonObject) requireIdentifiers(old, value)
            if (old is JsonArray && value is JsonArray) old.zip(value).forEach { (a, b) ->
                if (a is JsonObject && b is JsonObject) requireIdentifiers(a, b)
            }
        }
    }

    private fun preserveUnknown(raw: kotlinx.serialization.json.JsonElement?, before: kotlinx.serialization.json.JsonElement?,
        after: kotlinx.serialization.json.JsonElement): kotlinx.serialization.json.JsonElement {
        if (raw is JsonObject && before is JsonObject && after is JsonObject) {
            return JsonObject(after.mapValues { (key, value) -> preserveUnknown(raw[key], before[key], value) } +
                raw.filterKeys { it !in before && it !in after })
        }
        if (raw is JsonArray && before is JsonArray && after is JsonArray) {
            val remaining = before.indices.toMutableSet()
            return JsonArray(after.mapIndexed { index, value ->
                val id = (value as? JsonObject)?.get("id")
                val previous = remaining.firstOrNull { before[it] == value }
                    ?: remaining.firstOrNull { id != null && (before[it] as? JsonObject)?.get("id") == id }
                    ?: index.takeIf { id == null && before.size == after.size && it in remaining }
                if (previous == null) value else {
                    remaining.remove(previous)
                    preserveUnknown(raw.getOrNull(previous), before[previous], value)
                }
            })
        }
        return after
    }

    private fun yamlScalar(value: String): String = when {
        value.startsWith("'") && value.endsWith("'") -> value.removeSurrounding("'").replace("''", "'")
        value.startsWith("\"") && value.endsWith("\"") -> json.decodeFromString<String>(value)
        else -> value.substringBefore(" #").trimEnd()
    }

    private fun frontmatterFields(source: String): Map<String, String> = buildMap {
        val fields = LinkedHashMap<String, StringBuilder>()
        var field: StringBuilder? = null
        source.lineSequence().forEach { line ->
            val key = line.substringBefore(':')
            if (':' in line && key.matches(yamlKey)) {
                require(key !in fields) { "Повторяющиеся поля YAML" }
                field = StringBuilder(line.substringAfter(':').removePrefix(" "))
                fields[key] = requireNotNull(field)
            } else {
                require(line.isBlank() || line.first().isWhitespace() || line.startsWith('#') || line.startsWith("- ")) {
                    "Повреждён YAML"
                }
                field?.append('\n')?.append(line)
            }
        }
        fields.forEach { (key, value) -> put(key, value.toString()) }
    }

    private fun noteBodies(document: Document): Map<String, String> = when (document) {
        is Session -> document.workoutNotes.associate { it.id to it.body }
        is SportNote -> if (document.deleted) emptyMap() else mapOf(document.id to document.body)
        else -> emptyMap()
    }

    private fun noteHeading(created: String) = "### Заметка от $created\n\n"

    override fun write(document: Document, notes: String, extraYaml: Map<String, String>) =
        writeCurrent(document, notes, extraYaml, treeOf(document))

    fun rewrite(document: Document, previous: MarkdownDocument?, notes: String? = null,
        extraYaml: Map<String, String>? = null): String {
        val next = treeOf(document)
        val tree = previous?.fields?.let { preserveUnknown(it, treeOf(previous.document), next).jsonObject } ?: next
        val text = notes ?: listOfNotNull(previous?.freeText?.takeIf { it.isNotBlank() }, previous?.legacyText?.let {
            "## Прежний текст отчёта\n\n$it"
        }).joinToString("\n\n")
        return writeCurrent(document, text, extraYaml ?: previous?.extraYaml.orEmpty(), tree)
    }

    private fun writeCurrent(document: Document, notes: String, extraYaml: Map<String, String>, tree: JsonObject) =
        writeDocument(document, notes, extraYaml, tree)

    internal fun writeLegacy(document: Document, notes: String = "", extraYaml: Map<String, String> = emptyMap()) =
        writeDocument(document, notes, extraYaml, null)

    private fun writeDocument(document: Document, notes: String, extraYaml: Map<String, String>, tree: JsonObject?): String {
        validate(document)
        require(listOf(open, close, reportStart, reportEnd, fieldsStart, fieldsEnd).none { it in notes }) { "Заметка содержит служебный маркер" }
        require(extraYaml.all { (key, value) ->
            key !in reserved && key.matches(yamlKey) && frontmatterFields("$key: $value").keys == setOf(key)
        })
        return buildString {
            appendLine("---")
            appendLine("gymflow_schema: ${if (tree == null) legacySchema else schema}")
            appendLine("id: ${document.id}")
            appendLine("kind: ${kind(document)}")
            if (tree == null && document is Session) {
                appendLine("date: ${document.date}")
                appendLine("status: ${document.status.name.lowercase()}")
                appendLine("participants: [${document.participants.joinToString { it.id }}]")
            }
            extraYaml.forEach { (key, value) ->
                append("$key:")
                if (value.isNotEmpty() && !value.startsWith('\n')) append(' ')
                appendLine(value)
            }
            appendLine("---")
            appendLine()
            if (tree == null) {
                appendLine(reportStart)
                appendLine(render(document))
                appendLine(reportEnd)
            } else {
                val field = titleField(kind(document))
                val title = field?.let { (tree[it] as JsonPrimitive).content } ?: documentTitle(document)
                appendLine(fieldsStart)
                appendLine("# ${MarkdownFields.encodeText(title)}")
                appendLine()
                val body = MarkdownFields.write(JsonObject(tree - setOfNotNull("id", "kind", field)))
                if (body.isNotEmpty()) appendLine(body)
                appendLine(fieldsEnd)
            }
            noteBodies(document).forEach { (id, body) ->
                require(listOf(open, close, reportStart, reportEnd, fieldsStart, fieldsEnd, "<!-- gymflow:note ", noteEnd).none { it in body }) {
                    "Заметка содержит служебный маркер"
                }
                appendLine()
                appendLine("<!-- gymflow:note $id -->")
                if (document is Session) {
                    append(noteHeading(document.workoutNotes.first { it.id == id }.created))
                }
                appendLine(body.replace("\r\n", "\n"))
                appendLine(noteEnd)
            }
            if (notes.isNotBlank()) {
                appendLine()
                appendLine(notes)
            }
            if (tree == null) {
                appendLine()
                appendLine(open)
                appendLine(json.encodeToString(Document.serializer(), document).replace("-->", "--\\u003e"))
                appendLine(close)
            }
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
        is MovementFamily -> "movement_family"
        is MovementTrack -> "movement_track"
        is MovementSkill -> "movement_skill"
        is SkillAttempt -> "skill_attempt"
        is SportNote -> "sport_note"
    }

    private fun validate(document: Document) {
        require(UUID.fromString(document.id).toString() == document.id) { "Некорректный идентификатор" }
        when (document) {
            is Session -> {
                require(document.workoutNotes.distinctBy { it.id }.size == document.workoutNotes.size)
                document.workoutNotes.forEach { validateNote(it.id, it.created, it.updated) }
                LocalDate.parse(document.date)
                document.scheduledFor?.let(LocalDate::parse)
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
                    workout.originalPlan.orEmpty().forEach(::validatePlan)
                    workout.restUntil?.let(Instant::parse)
                    require(workout.cycleSlot == null || workout.cycleSlot.templateId == workout.templateId)
                    require(!workout.cycleCredited || workout.cycleSlot != null && workout.status == WorkoutStatus.COMPLETED)
                    require(workout.trainingBlocks.distinctBy { it.id }.size == workout.trainingBlocks.size)
                }
            }
            is Program -> {
                LocalDate.parse(document.anchor)
                require(document.interval > 0 && document.days.distinctBy { it.id }.size == document.days.size)
                require(document.completionCycle == null || document.cycle)
                require(document.durationWeeks == null || document.durationWeeks > 0)
                document.days.forEach { day ->
                    val blocks = day.blocks.map { it.id }.toSet()
                    require(blocks.size == day.blocks.size)
                    require(day.exercises.all { it.blockId == null || it.blockId in blocks })
                    day.exercises.forEach(::validatePlan)
                }
            }
            is ProgressionPolicy -> require(document.id == document.scope.documentId())
            is ProgressionReview -> {
                LocalDate.parse(document.date)
                require(document.part >= 0)
                require(document.decisions.all { it.profileId == document.profileId })
                document.decisions.forEach { (it.baseline + it.proposed).forEach(::validateSet) }
            }
            is MovementFamily, is MovementSkill, is MovementTrack, is SkillAttempt -> Unit
            is Profile -> require(document.name.isNotBlank())
            is Exercise -> require(document.name.isNotBlank() && document.repsMin in 1..document.repsMax)
            is SportNote -> {
                require(document.title.isNotBlank())
                validateNote(document.id, document.created, document.updated)
            }
        }
    }

    private fun validateNote(id: String, created: String, updated: String) {
        require(UUID.fromString(id).toString() == id)
        require(Instant.parse(updated) >= Instant.parse(created))
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
                require(!it.deferred || it.status == SetStatus.CANCELLED)
                it.actual?.let { actual ->
                    require(actual.weight.isFinite() && actual.weight >= 0 && actual.reps > 0)
                    require(actual.measurements.all { (kind, value) -> kind in Measure.extra && kind.accepts(value) })
                }
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
        is SportNote -> "# ${md(document.title)}\n\n" +
            if (document.deleted) "Заметка удалена" else "Создана: ${document.created}"
        is Session -> sessionReport(document)
        is Profile -> "# ${md(document.name)}\n\nПрофиль участника"
        is ProgressionPolicy -> "# Правила прогрессии\n\nСтратегия: ${document.ruleSet.strategy}. Версия: ${document.version}."
        is MovementFamily -> "# ${md(document.name)}\n\n${md(document.notes)}"
        is MovementTrack -> "# ${document.role.label}\n\n${document.exerciseId} · ${document.profileId}"
        is MovementSkill -> "# ${md(document.name)}\n\n" + document.levels.joinToString("\n") { "- ${md(it.name)} · ${it.goal.measure.label} · ${it.goal.target}" }
        is SkillAttempt -> "# ${document.date}\n\n${document.measure.label} · ${document.value}\n\n${md(document.note)}"
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
            val cycle = document.completionCycle
            if (cycle != null) {
                appendLine("Цикл по завершённым занятиям. Посещений в неделю: ${cycle.visitsPerWeek}.")
                cycle.targetSessions?.let { appendLine("Цель: $it завершённых занятий.") }
            } else if (document.cycle) appendLine("Цикл с интервалом ${document.interval} дн., начало ${document.anchor}.")
            if (document.caution.isNotBlank()) appendLine("> " + md(document.caution))
            document.days.forEach { day ->
                val weekday = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")[day.weekday.ordinal]
                appendLine()
                appendLine("## ${md(day.title)}${if (document.cycle) "" else " ($weekday)"}")
                day.blocks.forEach { block ->
                    appendLine("- ${md(block.name)}: ${block.requirement.label}. ${md(block.guidance)}")
                }
                day.exercises.forEach { plan ->
                    val details = plan.restLabel()?.let { ". Отдых $it" }.orEmpty()
                    appendLine("- ${md(plan.exercise.name)}: ${plan.sets.joinToString(transform = ::planText)}$details")
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
                        val label = if (set.plan.purpose == SetRole.WORK) (index + 1).toString() else set.plan.purpose.label
                        val actual = set.actual?.takeIf { set.status == SetStatus.COMPLETED }
                        val result = actual?.let {
                            val weights = "${if (it.weightKnown) number(it.weight) else "?"} × ${if (it.repsKnown) it.reps else "?"}"
                            val measured = it.measurements.entries.joinToString(" · ") { (kind, value) -> "${kind.label}: ${number(value)}" }
                            val showWeights = it.weightKnown || it.repsKnown || measured.isEmpty()
                            listOfNotNull(weights.takeIf { showWeights }, measured.takeIf { it.isNotEmpty() }).joinToString(" · ")
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

internal fun digest(value: String): String {
    val bytes = java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    val hex = "0123456789abcdef"
    return buildString(bytes.size * 2) {
        bytes.forEach { byte ->
            val n = byte.toInt() and 0xff
            append(hex[n ushr 4])
            append(hex[n and 0xf])
        }
    }
}
