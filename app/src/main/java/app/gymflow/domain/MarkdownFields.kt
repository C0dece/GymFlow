@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package app.gymflow.domain

import kotlinx.serialization.json.*

/** Lossless Markdown representation for editable records, including nested historical snapshots. */
internal object MarkdownFields {
    private val labels = mapOf(
        "id" to "ID", "name" to "Имя", "title" to "Название", "date" to "Дата",
        "status" to "Статус", "days" to "Тренировочные дни", "weekday" to "День недели",
        "exercises" to "Упражнения", "exercise" to "Упражнение", "sets" to "Подходы",
        "workouts" to "Тренировки участников", "otherWorkouts" to "Предыдущие тренировки участников",
        "profile" to "Участник", "participants" to "Участники", "participantIds" to "ID участников",
        "athletes" to "Результаты участников", "weight" to "Вес", "reps" to "Повторы",
        "repsMin" to "Повторы от", "repsMax" to "Повторы до", "targetReps" to "Цель повторов",
        "plan" to "План", "actual" to "Факт", "notes" to "Заметки", "note" to "Заметка",
        "restSeconds" to "Отдых в секундах", "restMaxSeconds" to "Максимум отдыха в секундах",
        "unit" to "Единица веса", "load" to "Тип нагрузки", "muscle" to "Мышцы", "metrics" to "Итоги",
        "weightKnown" to "Вес известен", "repsKnown" to "Повторы известны", "maxEffort" to "До отказа",
        "replaced" to "Заменённые упражнения", "warmup" to "Разминка", "role" to "Роль",
        "measurements" to "Измерения", "technique" to "Техника", "deferred" to "Отложен",
        "blocks" to "Блоки", "trainingBlocks" to "Блоки тренировки", "description" to "Описание",
        "enabled" to "Включён", "cycle" to "Цикл", "anchor" to "Дата начала", "interval" to "Интервал в днях",
        "completionCycle" to "Цикл по завершённым занятиям", "progression" to "Прогрессия",
        "workoutNotes" to "Заметки тренировки", "created" to "Создано", "updated" to "Изменено",
        "started" to "Начато", "finished" to "Завершено", "deleted" to "Удалено", "caution" to "Ограничения",
        "originalPlan" to "Исходный план", "importSource" to "Источник импорта", "original" to "Исходный текст",
        "levels" to "Уровни", "decisions" to "Решения", "scope" to "Область применения",
        "ruleSet" to "Правила", "individual" to "Личные планы", "templateTitle" to "Название дня",
        "templateNotes" to "Указания на день", "favorite" to "Избранное", "archived" to "В архиве",
        "programId" to "ID программы", "sessionId" to "ID тренировки", "exerciseId" to "ID упражнения",
        "profileId" to "ID профиля", "participantId" to "ID участника", "programDayId" to "ID дня программы",
        "currentExerciseId" to "ID текущего упражнения", "scheduledFor" to "Первоначальная дата",
        "templateId" to "ID шаблона дня", "skillId" to "ID навыка", "blockId" to "ID блока",
        "bodyWeightKg" to "Масса тела в кг", "feeling" to "Самочувствие", "cardioMinutes" to "Кардио в минутах",
        "highFatigue" to "Сильная усталость", "discomfort" to "Дискомфорт", "compact" to "Компактный режим",
        "color" to "Цвет", "skipped" to "Пропущено", "durationWeeks" to "Длительность в неделях",
        "guidance" to "Указания", "requirement" to "Обязательность", "autoRest" to "Автотаймер отдыха",
        "instructions" to "Примечания к упражнению",
        "durationSeconds" to "Длительность в секундах", "discarded" to "Скрыто из журнала",
        "beforeStart" to "План до начала",
    )
    private val keys = labels.entries.associate { (key, label) -> label to key }
    private val numbers = Regex("""[+-]?(?:\d+(?:[.,]\d+)?|[.,]\d+)(?:[eE][+-]?\d+)?""")
    private const val noValue = "Нет значения"
    private const val emptyText = "Пустой текст"
    private const val emptyList = "Пустой список"
    private const val emptyObject = "Пустой набор"
    private val special = setOf("Да", "Нет", "true", "false", "null", noValue, emptyText, emptyList, emptyObject)
    private val choices = mapOf(
        "status" to mapOf("PLANNED" to "Запланировано", "ACTIVE" to "В процессе", "PAUSED" to "На паузе",
            "COMPLETED" to "Завершено", "SKIPPED" to "Пропущено", "CANCELLED" to "Отменено"),
        "weekday" to listOf("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY")
            .zip(listOf("Понедельник", "Вторник", "Среда", "Четверг", "Пятница", "Суббота", "Воскресенье")).toMap(),
        "unit" to mapOf("KG" to "кг", "LB" to "фунты"),
        "load" to mapOf("BARBELL" to "Штанга", "DUMBBELL" to "Гантели", "MACHINE" to "Тренажёр",
            "BODYWEIGHT" to "Вес тела", "OTHER" to "Другое"),
    )
    private val entities = Regex("&(?:amp|lt|gt|#\\d+);")
    private val characters = setOf('&', '<', '>', '|', '\\', '`', '*', '_', '[', ']', '~')

    fun encodeText(value: String): String = buildString {
        value.forEachIndexed { index, char ->
            when {
                char == '\n' -> append("<br>")
                char == '\r' || char in characters || (char == ' ' && (index == 0 || index == value.lastIndex)) ||
                    (index == 0 && (value in special || numbers.matches(value))) -> append("&#${char.code};")
                else -> append(char)
            }
        }
    }

    fun decodeText(value: String): String = entities.replace(value.replace("<br>", "\n")) {
        when (it.value) {
            "&amp;" -> "&"
            "&lt;" -> "<"
            "&gt;" -> ">"
            else -> it.value.substring(2, it.value.lastIndex).toInt().toChar().toString()
        }
    }

    private fun label(key: String): String = labels[key] ?: if (
        key in keys || key in setOf("Запись", "Список", "Значение") || key.startsWith('@')
    ) "@$key" else key
    private fun key(label: String): String = if (label.startsWith('@')) label.drop(1) else keys[label] ?: label

    private fun scalar(value: JsonElement, field: String? = null): String = when (value) {
        JsonNull -> noValue
        is JsonPrimitive -> when {
            value.isString -> if (value.content.isEmpty()) emptyText
                else choices[field]?.get(value.content) ?: encodeText(value.content).let { text ->
                    if (choices[field]?.values?.any { it.equals(text, ignoreCase = true) } == true) {
                        "&#${text.first().code};${text.drop(1)}"
                    } else text
                }
            value.booleanOrNull != null -> if (value.boolean) "Да" else "Нет"
            else -> value.content
        }
        is JsonArray -> emptyList.also { require(value.isEmpty()) }
        is JsonObject -> emptyObject.also { require(value.isEmpty()) }
    }

    private fun value(raw: String, field: String? = null): JsonElement {
        val choice = choices[field]?.entries?.firstOrNull { it.value.equals(raw, ignoreCase = true) }
        if (choice != null) return JsonPrimitive(choice.key)
        return when (raw) {
            noValue, "null" -> JsonNull
            emptyText -> JsonPrimitive("")
            emptyList -> JsonArray(emptyList())
            emptyObject -> JsonObject(emptyMap())
            "Да", "true" -> JsonPrimitive(true)
            "Нет", "false" -> JsonPrimitive(false)
            else -> if (numbers.matches(raw)) JsonUnquotedLiteral(raw.replace(',', '.').removePrefix("+").let {
                if (it.startsWith('.')) "0$it" else if (it.startsWith("-.")) "-0${it.drop(1)}" else it
            }) else JsonPrimitive(decodeText(raw))
        }
    }

    fun write(tree: JsonObject): String = buildString { fields(tree, 0) }.trimEnd()

    private fun StringBuilder.fields(tree: JsonObject, indent: Int) {
        tree.forEach { (key, value) ->
            val title = encodeText(label(key)).replace(":", "&#58;")
            when {
                value is JsonObject && value.isNotEmpty() -> {
                    line(indent, "- $title:")
                    fields(value, indent + 2)
                }
                value is JsonArray && value.isNotEmpty() -> {
                    line(indent, "- $title:")
                    if (key == "sets" && value.all { it is JsonObject && it.isNotEmpty() && flatten(it) != null }) {
                        table(value.map { flatten(it.jsonObject)!! }, indent + 2)
                    } else items(value, indent + 2)
                }
                else -> line(indent, "- $title: ${scalar(value, key)}")
            }
        }
    }

    private fun StringBuilder.items(values: JsonArray, indent: Int) {
        values.forEach { item ->
            when {
                item is JsonObject && item.isNotEmpty() -> {
                    line(indent, "- Запись")
                    fields(item, indent + 2)
                }
                item is JsonArray && item.isNotEmpty() -> {
                    line(indent, "- Список")
                    items(item, indent + 2)
                }
                else -> line(indent, "- Значение: ${scalar(item)}")
            }
        }
    }

    private fun StringBuilder.line(indent: Int, text: String) {
        append(" ".repeat(indent)).appendLine(text)
    }

    private fun flatten(tree: JsonObject): Map<List<String>, JsonElement>? {
        val output = linkedMapOf<List<String>, JsonElement>()
        fun visit(node: JsonObject, path: List<String>): Boolean = node.all { (key, child) ->
            val next = path + key
            if (child is JsonObject && child.isNotEmpty()) visit(child, next)
            else if (child is JsonArray && child.isNotEmpty()) false
            else { output[next] = child; true }
        }
        return output.takeIf { visit(tree, emptyList()) }
    }

    private fun StringBuilder.table(rows: List<Map<List<String>, JsonElement>>, indent: Int) {
        val columns = rows.flatMap { it.keys }.distinct()
        fun row(cells: List<String>) = line(indent, cells.joinToString(" | ", "| ", " |"))
        appendLine()
        row(columns.map { path -> path.joinToString(" / ") { encodeText(label(it)).replace("/", "&#47;") } })
        row(columns.map { "---" })
        rows.forEach { map -> row(columns.map { path -> map[path]?.let { scalar(it, path.last()) }.orEmpty() }) }
        appendLine()
    }

    fun read(text: String): JsonObject = Parser(text.lineSequence().filterNot { it.isBlank() }.toList()).read()

    private class Parser(private val lines: List<String>) {
        var at = 0
        private fun line() = lines.getOrNull(at)
        private fun indent() = line()?.takeWhile { it == ' ' }?.length ?: -1
        private fun error(): Nothing = throw IllegalArgumentException("Некорректная структура Markdown около строки ${at + 1}")

        fun read(): JsonObject = fields(0).also { if (at < lines.size) error() }

        private fun fields(depth: Int): JsonObject {
            require(depth <= 100) { "Слишком глубокая вложенность Markdown" }
            val result = linkedMapOf<String, JsonElement>()
            while (indent() == depth) {
                val source = line()!!.drop(depth)
                if (!source.startsWith("- ") || ':' !in source) error()
                val field = key(decodeText(source.substring(2).substringBefore(':').trim()))
                if (field.isEmpty() || field in result) error()
                val raw = source.substringAfter(':').trim()
                at++
                result[field] = if (raw.isNotEmpty()) value(raw, field) else {
                    if (indent() != depth + 2) error()
                    when {
                        line()!!.trimStart().startsWith("|") -> table(depth + 2)
                        line()!!.trimStart().let { it == "- Запись" || it == "- Список" || it.startsWith("- Значение:") } -> array(depth + 2)
                        else -> fields(depth + 2)
                    }
                }
            }
            return JsonObject(result)
        }

        private fun array(depth: Int): JsonArray = JsonArray(buildList {
            require(depth <= 100) { "Слишком глубокая вложенность Markdown" }
            while (indent() == depth) {
                val item = line()!!.trim()
                at++
                add(when {
                    item == "- Запись" && indent() == depth + 2 -> fields(depth + 2)
                    item == "- Список" && indent() == depth + 2 -> array(depth + 2)
                    item.startsWith("- Значение: ") -> value(item.substringAfter(':').trim())
                    else -> error()
                })
            }
        })

        private fun table(depth: Int): JsonArray {
            fun cells(): List<String> {
                val line = line()?.trim() ?: error()
                if (indent() != depth || !line.startsWith('|') || !line.endsWith('|')) error()
                at++
                return line.removeSurrounding("|").split('|').map(String::trim)
            }
            val columns = cells().map { it.split(" / ").map { part -> key(decodeText(part)) } }
            if (columns.toSet().size != columns.size || cells().let { it.size != columns.size || it.any { c -> !c.matches(Regex(":?-{3,}:?")) } }) error()
            return JsonArray(buildList {
                while (indent() == depth && line()!!.trimStart().startsWith('|')) {
                    val row = cells()
                    if (row.size != columns.size) error()
                    var fields = JsonObject(emptyMap())
                    columns.zip(row).filter { it.second.isNotEmpty() }.forEach { (path, cell) ->
                        fields = insert(fields, path, value(cell, path.last()))
                    }
                    add(fields)
                }
                if (isEmpty()) error()
            })
        }

        private fun insert(tree: JsonObject, path: List<String>, value: JsonElement): JsonObject {
            if (path.size > 50 || path.first().isBlank()) error()
            val key = path.first()
            val old = tree[key]
            val next = if (path.size == 1) {
                if (old != null) error()
                value
            } else {
                if (old != null && old !is JsonObject) error()
                insert(old ?: JsonObject(emptyMap()), path.drop(1), value)
            }
            return JsonObject(tree + (key to next))
        }
    }
}
