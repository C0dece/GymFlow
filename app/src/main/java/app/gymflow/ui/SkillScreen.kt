@file:OptIn(ExperimentalLayoutApi::class)

package app.gymflow.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.gymflow.GymModel
import app.gymflow.data.Library
import app.gymflow.domain.*
import java.time.LocalDate

@Composable
internal fun SkillsPanel(library: Library, profile: Profile?, model: GymModel) {
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf(false) }
    var levelId by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf<String?>(null) }
    val skill = library.skills.find { it.id == selected }
    if (skill == null) {
        Button(onClick = { editing = true }, modifier = Modifier.testTag("new_skill")) { Text("Создать навык") }
        if (library.skills.isEmpty()) Muted("Задайте уровни и проверяемые критерии освоения.")
        library.skills.forEach { item ->
            CardBlock(onClick = { selected = item.id }) { Text(item.name) }
        }
    } else {
        TextButton(onClick = { selected = null }) { Text("Все навыки") }
        SectionTitle(skill.name)
        if (skill.limitations.isNotBlank()) Muted(skill.limitations)
        val links = library.skills.filter { it.id in skill.relatedSkills }
        if (links.isNotEmpty()) Muted("Связанные навыки: " + links.joinToString { it.name })
        val evidence = profile?.let { p -> library.attempts.filter { it.skillId == skill.id && it.profileId == p.id } }.orEmpty()
        val mastered = profile?.let { masteredLevels(skill, it.id, evidence) }.orEmpty()
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { editing = true }) { Text("Изменить навык") }
            Button(onClick = { levelId = "new" }, modifier = Modifier.testTag("new_skill_level")) { Text("Добавить уровень") }
        }
        skill.levels.forEach { level ->
            CardBlock {
                Text(level.name, style = MaterialTheme.typography.titleMedium)
                Muted(goalText(level.goal))
                Muted(when {
                    level.id in mastered -> "Критерий подтверждён"
                    mastered.containsAll(level.requires) -> "Доступен для проверки"
                    else -> "Требуются предыдущие уровни"
                })
                if (level.description.isNotBlank()) Muted(level.description)
                if (level.limitations.isNotBlank()) Muted(level.limitations)
                val exercises = library.exercises.filter { it.id in level.exerciseIds }
                if (exercises.isNotEmpty()) Muted("Подготовка: " + exercises.joinToString { it.name })
                val requirements = skill.levels.filter { it.id in level.requires }
                if (requirements.isNotEmpty()) Muted("Перед этим: " + requirements.joinToString { it.name })
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (profile != null) Button(onClick = { attempt = level.id }, modifier = Modifier.testTag("add_skill_attempt")) { Text("Записать попытку") }
                    TextButton(onClick = { levelId = level.id }) { Text("Изменить уровень") }
                }
                val log = evidence.filter { it.levelId == level.id }
                var show by remember(level.id) { mutableStateOf(false) }
                if (log.isNotEmpty()) TextButton(onClick = { show = !show }) { Text("Попытки · ${log.size}") }
                if (show) log.forEach { row ->
                    Muted("${shortDate(row.date)} · ${row.measure.label} · ${number(row.value)}" +
                        row.note.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty())
                }
            }
        }
    }
    if (editing) {
        var name by remember { mutableStateOf(skill?.name.orEmpty()) }
        var limits by remember { mutableStateOf(skill?.limitations.orEmpty()) }
        var related by remember { mutableStateOf(skill?.relatedSkills.orEmpty()) }
        EditorSheet("Навык", { editing = false }) {
            Field(name, "Название", { name = it }, Modifier.testTag("skill_name"))
            Field(limits, "Ограничения и условия профессиональной оценки", { limits = it }, singleLine = false)
            PickLinks("Связанные навыки", library.skills.filter { it.id != skill?.id }.map { it.id to it.name }, related) { related = it }
            Button(onClick = {
                val value = (skill ?: MovementSkill(name = name.trim())).copy(name = name.trim(), limitations = limits.trim(), relatedSkills = related)
                model.save(value)
                selected = value.id
                editing = false
            }, enabled = name.isNotBlank() && library.skills.none { it.id != skill?.id && it.name.equals(name.trim(), true) },
                modifier = Modifier.testTag("save_skill")) { Text("Сохранить") }
        }
    }
    if (skill != null && levelId != null) {
        LevelEditor(library, skill, skill.levels.find { it.id == levelId }, { levelId = null }) { value ->
            model.save(value)
            levelId = null
        }
    }
    val level = skill?.levels?.find { it.id == attempt }
    if (level != null && profile != null) {
        var amount by remember { mutableStateOf("") }
        var note by remember { mutableStateOf("") }
        val value = decimal(amount)?.takeIf(level.goal.measure::accepts)
        EditorSheet("Попытка · ${level.name}", { attempt = null }) {
            Muted(goalText(level.goal))
            Field(amount, level.goal.measure.label, { amount = it }, Modifier.testTag("skill_attempt_value"), KeyboardType.Decimal)
            Field(note, "Условия попытки", { note = it }, singleLine = false)
            Button(onClick = {
                model.save(SkillAttempt(profile.id, requireNotNull(skill).id, level.id, LocalDate.now().toString(),
                    level.goal.measure, requireNotNull(value), note.trim()))
                attempt = null
            }, enabled = value != null, modifier = Modifier.testTag("save_skill_attempt")) { Text("Записать") }
        }
    }
}

@Composable
private fun LevelEditor(library: Library, skill: MovementSkill, level: SkillLevel?, close: () -> Unit, save: (MovementSkill) -> Unit) {
    val id = remember { level?.id ?: newId() }
    var name by remember { mutableStateOf(level?.name.orEmpty()) }
    var desc by remember { mutableStateOf(level?.description.orEmpty()) }
    var limits by remember { mutableStateOf(level?.limitations.orEmpty()) }
    var measure by remember { mutableStateOf(level?.goal?.measure ?: Measure.HOLD) }
    var target by remember { mutableStateOf(level?.goal?.target?.let(::number).orEmpty()) }
    var atLeast by remember { mutableStateOf(level?.goal?.atLeast ?: true) }
    var exercises by remember { mutableStateOf(level?.exerciseIds.orEmpty()) }
    var depends by remember { mutableStateOf(level?.requires.orEmpty()) }
    val value = decimal(target)?.takeIf(measure::accepts)
    val verified = remember(id, name, desc, limits, value, measure, atLeast, exercises, depends, skill) {
        if (name.isBlank() || value == null) null else runCatching {
            val item = SkillLevel(id, name.trim(), MovementGoal(measure, value, atLeast), desc.trim(), exercises, depends, limits.trim())
            val levels = if (level == null) skill.levels + item else skill.levels.map { if (it.id == id) item else it }
            skill.copy(levels = levels)
        }.getOrNull()
    }
    EditorSheet("Уровень навыка", close) {
        Field(name, "Название уровня", { name = it }, Modifier.testTag("level_name"))
        Field(desc, "Описание", { desc = it }, singleLine = false)
        Field(limits, "Ограничения", { limits = it }, singleLine = false)
        Choice(measure, Measure.entries, { it.label }, { measure = it }, "skill_measure")
        Field(target, "Критерий", { target = it }, Modifier.testTag("level_goal"), KeyboardType.Decimal)
        FilterChip(atLeast, { atLeast = !atLeast }, label = { Text(if (atLeast) "Не меньше" else "Не больше") })
        PickLinks("Предыдущие уровни", skill.levels.filter { it.id != id }.map { it.id to it.name }, depends) { depends = it }
        PickLinks("Подготовительные упражнения", library.exercises.filterNot { it.archived }.map { it.id to it.name }, exercises) { exercises = it }
        if (name.isNotBlank() && value != null && verified == null) Muted("Проверьте, что уровни не зависят друг от друга по циклу.")
        Button(onClick = { verified?.let(save) }, enabled = verified != null, modifier = Modifier.testTag("save_skill_level")) {
            Text("Сохранить уровень")
        }
    }
}

@Composable
private fun PickLinks(title: String, options: List<Pair<String, String>>, selected: Set<String>, select: (Set<String>) -> Unit) {
    if (options.isEmpty()) return
    val remaining = options.filterNot { it.first in selected }
    val shown = options.filter { it.first in selected }
    if (remaining.isNotEmpty()) Choice(null, listOf(null) + remaining, { it?.second ?: title }, { pair ->
        if (pair != null) select(selected + pair.first)
    }, title)
    if (shown.isNotEmpty()) {
        Muted(title)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            shown.forEach { (id, name) -> FilterChip(true, { select(selected - id) }, label = { Text(name) }) }
        }
    }
}
