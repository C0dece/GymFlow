package app.gymflow.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ProgramSource(
    val uri: String, val name: String, val revision: String, val fingerprint: String,
    val templateFingerprint: String, val original: String = "",
    val exerciseLinks: Map<String, String> = emptyMap(),
)

internal data class ProgramDraft(val template: Program, val revision: String, val original: String)

internal object ProgramImport {
    private val heading = Regex("""^##\s+(.+?)\s*$""")
    private val blockHeading = Regex("""^### \[([A-Z_]+) ([A-Z]+)]\s+(.+)$""")
    private val range = Regex("""([0-9]{1,7})(?:\s*[-\u2013\u2014]\s*([0-9]{1,7}))?""")
    private val sets = Regex("""([0-9]{1,3})\s*[×xXхХ]\s*(.+)""")
    private val rest = Regex("""(.+?)\s*(с|сек|мин|s|min)\.?""", RegexOption.IGNORE_CASE)
    private val separator = Regex("""^:?-{3,}:?$""")
    private val listFields = setOf("rotation", "weekdays", "visit_days", "tags")
    private val fieldsAllowed = listFields + setOf(
        "program_schema", "program_id", "title", "type", "version", "status", "created", "anchor",
        "schedule_mode", "duration_weeks", "sessions_per_week",
    )

    fun read(text: String, today: LocalDate = LocalDate.now(), retainedId: String? = null): ProgramDraft {
        val normalized = text.removePrefix("\uFEFF").replace("\r\n", "\n")
        Markdown.read(normalized)?.let {
            val program = it.document as? Program ?: error("Выберите Markdown программы.")
            return ProgramDraft(program, program.importSource?.revision.orEmpty(), program.importSource?.original.orEmpty())
        }
        val lines = normalized.lines()
        require(lines.firstOrNull() == "---") { "Нужен YAML frontmatter между двумя ---." }
        val end = lines.indices.firstOrNull { it > 0 && lines[it] == "---" } ?: error("YAML frontmatter не закрыт.")
        val fields = frontmatter(lines.subList(1, end))
        fun scalar(key: String) = fields[key]?.single()
        require(scalar("program_schema") in listOf(null, "1")) { "Неподдерживаемая версия program_schema." }
        require(scalar("type") == "training-program") { "YAML: нужен type: training-program." }
        val title = requireNotNull(scalar("title")) { "YAML: укажите title." }
        val rotation = when (scalar("schedule_mode")) {
            "rotation" -> true
            "calendar" -> false
            else -> error("YAML: schedule_mode должен быть calendar или rotation.")
        }
        val names = fields["rotation"].orEmpty()
        require(rotation == names.isNotEmpty() && names.map(::key).distinct().size == names.size) {
            "YAML: rotation задаётся только для цикла и содержит уникальные дни."
        }
        val weekdays = fields["weekdays"].orEmpty().map(::weekday)
        require(!rotation || weekdays.isEmpty()) { "YAML: weekdays относится к календарному расписанию." }
        val visits = scalar("sessions_per_week")?.let { positive(it, 7, "sessions_per_week") }
            ?: if (rotation) error("YAML: укажите sessions_per_week.") else null
        val duration = scalar("duration_weeks")?.let { positive(it, 10000, "duration_weeks") }
        val preferred = fields["visit_days"].orEmpty().map(::weekday)
        require(preferred.isEmpty() || rotation && preferred.toSet().size == visits && preferred.size == visits) {
            "YAML: число разных дней visit_days должно совпадать с sessions_per_week."
        }
        val sections = sections(lines.drop(end + 1))
        val phases = sections.filter { phaseHeading(it.title) }
        require(phases.size <= 1) { "Найдено несколько разделов периодизации." }
        val days = if (rotation) names.map { name ->
            val matches = sections.filter {
                it.title == name || Regex("^" + Regex.escape(name) + "\\s+[-\\u2013\\u2014]\\s+.+$").matches(it.title)
            }
            require(matches.size == 1) { "Нужен один раздел для дня «$name»." }
            matches.single().copy(title = name) to DayOfWeek.MONDAY
        } else {
            val workouts = sections.filter { section -> !phaseHeading(section.title) && !referenceHeading(section.title) &&
                section.lines.any { it.trimStart().startsWith("|") || blockHeading.matches(it) } }
            require(workouts.isNotEmpty() && weekdays.size == workouts.size) {
                "YAML: список weekdays должен соответствовать порядку тренировочных разделов."
            }
            require(visits == null || visits == weekdays.size) { "YAML: sessions_per_week отличается от числа календарных занятий." }
            workouts.zip(weekdays)
        }
        val recognized = days.map { it.first.lines }.toSet()
        val other = sections.filter { it.lines !in recognized && !referenceHeading(it.title) && !phaseHeading(it.title) }
        other.forEach { section ->
            require(section.lines.none { it.trimStart().startsWith("|") }) {
                "Неясное назначение таблицы в разделе «${section.title}»."
            }
        }
        val frequency = visits ?: weekdays.size
        val phaseList = phases.flatMap { parsePhases(it, frequency, duration) }
        require(rotation || phaseList.isEmpty()) { "Для табличной периодизации укажите schedule_mode: rotation." }
        val declaredId = scalar("program_id")?.also {
            require(runCatching { UUID.fromString(it).toString() }.getOrNull() == it) { "YAML: program_id должен быть UUID." }
        }
        val id = declaredId ?: retainedId ?: stableId("markdown-program/${key(title)}")
        val legacyBlocks = generalBlocks(sections, id)
        val templates = days.map { (section, day) -> parseWorkout(section, day, id, legacyBlocks) }
        require(templates.map { it.id }.distinct().size == templates.size) { "Повторяющиеся названия тренировок." }
        val program = Program(id = id, title = title, days = templates, enabled = true, cycle = rotation,
            anchor = (scalar("anchor")?.let(LocalDate::parse) ?: today).toString(),
            completionCycle = if (rotation) CompletionCycle(frequency, preferred.toSet(), duration?.times(frequency), phases = phaseList) else null,
            caution = plain(sections.first().lines.firstOrNull { it.startsWith(">") }?.removePrefix(">").orEmpty()),
            description = other.joinToString("\n\n") { (listOf(it.title) + it.lines).joinToString("\n").trim() },
            durationWeeks = duration)
        return ProgramDraft(program, scalar("version").orEmpty(), normalized)
    }

    private data class Section(val title: String, val lines: List<String>)

    private fun sections(lines: List<String>): List<Section> {
        val titles = lines.indices.filter { heading.matches(lines[it]) }
        return listOf(Section("", lines.take(titles.firstOrNull() ?: lines.size))) + titles.mapIndexed { i, index ->
            Section(heading.matchEntire(lines[index])!!.groupValues[1], lines.subList(index + 1, titles.getOrElse(i + 1) { lines.size }))
        }
    }

    private fun frontmatter(lines: List<String>): Map<String, List<String>> {
        val result = linkedMapOf<String, MutableList<String>>()
        var list: String? = null
        lines.forEachIndexed { i, line ->
            if (line.isBlank() || line.trimStart().startsWith("#")) return@forEachIndexed
            val item = Regex("""^\s+-\s+(.+)$""").matchEntire(line)
            if (item != null) {
                val target = requireNotNull(list) { "YAML, строка ${i + 2}: неожиданный элемент списка." }
                result.getValue(target).add(yamlValue(item.groupValues[1]))
            } else {
                val field = Regex("""^([a-z_]+):[ \t]*(.*)$""").matchEntire(line)
                    ?: throw IllegalArgumentException("YAML, строка ${i + 2}: ожидалось поле или элемент списка.")
                val (key, value) = field.destructured
                require(key in fieldsAllowed && key !in result) { "YAML, строка ${i + 2}: неизвестное или повторное поле $key." }
                require((key in listFields) == value.isBlank()) { "YAML: проверьте значение $key." }
                result[key] = if (value.isBlank()) mutableListOf() else mutableListOf(yamlValue(value))
                list = key.takeIf { it in listFields }
            }
        }
        return result
    }

    private fun yamlValue(raw: String): String {
        val value = raw.trim()
        if (value.startsWith("\"") || value.startsWith("'")) {
            require(value.length >= 2 && value.last() == value.first()) { "YAML: незакрытые кавычки." }
            val nested = value.substring(1, value.length - 1)
            require(nested.isNotBlank() && value.first() !in nested && '\\' !in nested) { "YAML: проверьте значение в кавычках." }
            return nested
        }
        require(value.isNotBlank() && value.first() !in "[{&*!>|#%@\u0060" && ": " !in value &&
            " #" !in value && !value.endsWith("\"") && !value.endsWith("'")) { "YAML: неоднозначное значение $value." }
        return value
    }

    private fun generalBlocks(sections: List<Section>, id: String): List<TrainingBlock> =
        sections.filter { key(it.title) == "общая структура занятия" }.flatMap { it.lines }.mapNotNull { line ->
            val row = Regex("""^\d+\.\s+(.+)$""").matchEntire(line)?.groupValues?.get(1) ?: return@mapNotNull null
            val name = Regex("""^\*\*(.+?)\*\*""").find(row)?.groupValues?.get(1) ?: row.removeSuffix(".")
            val type = blockType(name) ?: BlockType.CUSTOM
            TrainingBlock(stableId("$id/general/${key(name)}"), type, name, requirement(type), plain(row))
        }

    private fun parseWorkout(section: Section, weekday: DayOfWeek, programId: String, general: List<TrainingBlock>): WorkoutTemplate {
        val id = stableId("$programId/day/${key(section.title)}")
        val blocks = general.map { it.copy(id = stableId("$id/${it.id}")) }.toMutableList()
        val plans = mutableListOf<ExercisePlan>()
        val notes = mutableListOf<String>()
        var active: TrainingBlock? = null
        var index = 0
        while (index < section.lines.size) {
            val line = section.lines[index].trim()
            val blockHeader = blockHeading.matchEntire(line)
            when {
                blockHeader != null -> {
                    val (type, required, name) = blockHeader.destructured
                    val blockType = BlockType.entries.find { it.name == type } ?: error("Неизвестный тип блока $type.")
                    val requirement = PlanBlock.entries.find { it.name == required } ?: error("Неизвестная обязательность $required.")
                    if (active == null) {
                        require(plans.isEmpty()) { "В «${section.title}» смешаны обычная таблица и явные блоки." }
                        blocks.clear()
                    }
                    val block = TrainingBlock(stableId("$id/block/${key(name)}"), blockType, name.trim(), requirement)
                    require(blocks.none { it.id == block.id }) { "Повтор блока «$name»." }
                    blocks.add(block)
                    active = block
                    index++
                }
                line.startsWith("###") -> error("В «${section.title}» ожидается ### [ТИП ОБЯЗАТЕЛЬНОСТЬ] Название.")
                line.startsWith("|") -> {
                    val block = active ?: blocks.firstOrNull { it.type == BlockType.MAIN }
                        ?: TrainingBlock(stableId("$id/main"), BlockType.MAIN, BlockType.MAIN.label).also(blocks::add)
                    val table = section.lines.drop(index).map(String::trim).takeWhile { it.startsWith("|") }
                    plans.addAll(readExercises(table, block, section.title))
                    index += table.size
                }
                else -> {
                    val type = active?.type ?: blockType(line.removePrefix("- ").substringBefore(":"))
                    if (line.isNotBlank() && type != null) {
                        val block = active ?: blocks.firstOrNull { it.type == type }
                            ?: TrainingBlock(stableId("$id/${type.name}"), type, type.label, requirement(type)).also(blocks::add)
                        val at = blocks.indexOfFirst { it.id == block.id }
                        blocks[at] = blocks[at].copy(guidance = listOf(blocks[at].guidance, plain(line.removePrefix("- ")))
                            .filter(String::isNotBlank).joinToString("\n"))
                    } else notes.add(line)
                    index++
                }
            }
        }
        require(plans.isNotEmpty()) { "В «${section.title}» нужна непустая таблица упражнений." }
        require(plans.groupBy { it.blockId }.values.all { entries -> entries.map { it.exercise.id }.distinct().size == entries.size }) {
            "Повтор упражнения в одном блоке «${section.title}». Разделите назначения на блоки."
        }
        return WorkoutTemplate(id, section.title, weekday, plans, notes.joinToString("\n").trim(), blocks)
    }

    private fun readExercises(lines: List<String>, block: TrainingBlock, title: String): List<ExercisePlan> {
        require(cells(lines.first(), title).map(::key) in listOf(
            listOf("упражнение", "рабочие подходы × повторы", "отдых"),
            listOf("упражнение", "подходы × повторы", "отдых"),
        )) { "У «$title» ожидаются столбцы «Упражнение», «Подходы × повторы», «Отдых»." }
        return rows(lines, 3, title).map { (nameRaw, prescription, restText) ->
            val name = plain(nameRaw)
            val (countText, repsText) = (sets.matchEntire(plain(prescription))
                ?: error("У «$name» в «$title» укажите подходы × повторы.")).destructured
            val reps = interval(repsText, 9999, "повторы у $name")
            val count = positive(countText, 100, "подходы у $name")
            val seconds = if (plain(restText) in listOf("?", "-")) null else {
                val (length, unit) = (rest.matchEntire(plain(restText)) ?: error("Неясный отдых у «$name».")).destructured
                val mult = if (unit.lowercase() in setOf("мин", "min")) 60 else 1
                interval(length, 86400 / mult, "отдых у $name").let { (a, b) -> a * mult to b * mult }
            }
            val role = when (block.type) {
                BlockType.WARMUP -> SetRole.WARMUP
                BlockType.PREPARATION -> SetRole.RAMP
                BlockType.MOBILITY -> SetRole.TECHNIQUE
                else -> null
            }
            ExercisePlan(Exercise(stableId("markdown-exercise/${key(name)}"), name),
                List(count) { SetPlan(repsMin = reps.first, repsMax = reps.second, weightKnown = false, role = role) },
                block = block.requirement, blockId = block.id, restSeconds = seconds?.first, restMaxSeconds = seconds?.second)
        }
    }

    private fun parsePhases(section: Section, frequency: Int, duration: Int?): List<CyclePhase> {
        val positions = section.lines.indices.filter { section.lines[it].trimStart().startsWith("|") }
        if (positions.isEmpty()) return emptyList()
        require(positions.zipWithNext().all { (a, b) -> b == a + 1 }) { "В разделе периодизации ожидается одна таблица." }
        val table = positions.map { section.lines[it].trim() }
        require(cells(table.first(), section.title).map(::key) == listOf("недели", "объём и интенсивность", "задача")) {
            "Для фаз нужны столбцы «Недели», «Объём и интенсивность», «Задача»."
        }
        var previous = 0
        return rows(table, 3, section.title).map { (weeks, instructions, purpose) ->
            val (first, last) = interval(weeks, duration ?: 10000, "недели фазы")
            require(first == previous + 1) { "Фазы должны начинаться с 1 и идти без пропусков и пересечений." }
            previous = last
            CyclePhase(last * frequency, "Недели $first-$last", "$instructions. $purpose")
        }
    }

    private fun rows(lines: List<String>, size: Int, title: String): List<List<String>> {
        require(lines.size >= 3) { "Пустая таблица в «$title»." }
        val divider = cells(lines[1], title)
        require(divider.size == size && divider.all(separator::matches)) { "Повреждён разделитель таблицы «$title»." }
        return lines.drop(2).map { row ->
            cells(row, title).also { require(it.size == size) { "Неверное число столбцов в «$title»: $row" } }
        }
    }

    private fun cells(line: String, title: String): List<String> {
        require(line.endsWith("|") && "\\|" !in line) { "Неоднозначная строка таблицы в «$title»: $line" }
        return line.removeSurrounding("|").split('|').map { it.trim().also { cell ->
            require(cell.isNotBlank()) { "Пустая ячейка в таблице «$title»." }
        } }
    }

    private fun interval(input: String, maximum: Int, name: String): Pair<Int, Int> {
        val (a, b) = (range.matchEntire(input.trim()) ?: error("Неверный диапазон: $name.")).destructured
        val first = positive(a, maximum, name)
        val last = b.takeIf { it.isNotEmpty() }?.let { positive(it, maximum, name) } ?: first
        require(first <= last) { "Перевёрнутый диапазон: $name." }
        return first to last
    }

    private fun positive(input: String, max: Int, name: String): Int =
        input.toIntOrNull()?.takeIf { it in 1..max } ?: error("Недопустимое значение: $name.")
    private fun weekday(value: String): DayOfWeek =
        DayOfWeek.entries.find { it.name.equals(value, true) } ?: error("Неизвестный день недели: $value.")
    private fun phaseHeading(title: String) = key(title) in setOf("прогрессия и периодизация", "периодизация", "periodization")
    private fun referenceHeading(title: String) = key(title).startsWith("исторические результаты") || key(title) == "справочные данные"
    private fun blockType(label: String): BlockType? = when (key(label)) {
        "разминка", "общая разминка" -> BlockType.WARMUP
        "специальная подготовка", "специальная разминка" -> BlockType.PREPARATION
        "основная силовая работа" -> BlockType.MAIN
        "дополнительная работа" -> BlockType.ACCESSORY
        "кардио" -> BlockType.CARDIO
        "необязательный навык", "навык или мобильность" -> BlockType.MOBILITY
        "заминка", "заминка необязательна" -> BlockType.COOLDOWN
        else -> null
    }
    private fun requirement(type: BlockType) = when (type) {
        BlockType.CARDIO, BlockType.MOBILITY, BlockType.COOLDOWN -> PlanBlock.OPTIONAL
        BlockType.WARMUP, BlockType.PREPARATION -> PlanBlock.RECOMMENDED
        else -> PlanBlock.REQUIRED
    }

    internal fun key(value: String) = plain(value).lowercase().replace(Regex("\\s+"), " ")
    private fun plain(value: String) = value.replace("**", "").trim()
}

internal fun Program.importTemplateFingerprint(): String = digest(Json.encodeToString(
    Program.serializer(), copy(anchor = "", enabled = false, archived = false, participantIds = emptyList(), importSource = null,
        completionCycle = completionCycle?.copy(extensions = emptyMap()))
))
