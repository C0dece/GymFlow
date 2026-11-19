@file:OptIn(ExperimentalLayoutApi::class)

package app.gymflow.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.gymflow.GymModel
import app.gymflow.R
import app.gymflow.data.Library
import app.gymflow.data.Preferences
import app.gymflow.domain.*
import java.time.DayOfWeek
import java.time.LocalDate

@Composable
fun Programs(library: Library, prefs: Preferences, model: GymModel, preview: (String?) -> Unit) {
    var input by remember { mutableStateOf<ProgramInput?>(null) }
    var importTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var showSource by remember { mutableStateOf(false) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.readProgramFile(uri, importTarget) { input = it }
    }
    fun chooseFile(target: String?) {
        importTarget = target
        filePicker.launch(arrayOf("*/*"))
    }
    var currentId by rememberSaveable { mutableStateOf<String?>(null) }
    var currentDay by rememberSaveable { mutableStateOf<String?>(null) }
    var editProgram by rememberSaveable { mutableStateOf(false) }
    var editDay by rememberSaveable { mutableStateOf(false) }
    var editingDayId by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmDeleteDayId by rememberSaveable { mutableStateOf<String?>(null) }
    var blockId by rememberSaveable { mutableStateOf<String?>(null) }
    var editPlan by rememberSaveable { mutableStateOf<Int?>(null) }
    var individual by rememberSaveable { mutableStateOf<Int?>(null) }
    var moveId by rememberSaveable { mutableStateOf<String?>(null) }
    val program = library.programs.find { it.id == currentId }
    val day = program?.days?.find { it.id == currentDay }
    val dateSession = library.sessions.find { it.id == moveId }
    fun back() { if (currentDay != null) currentDay = null else currentId = null }
    BackHandler(currentId != null) { back() }

    ScreenColumn(Modifier.testTag("programs")) {
        if (program == null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { editProgram = true },
                    modifier = Modifier.weight(1f).testTag("new_program")
                ) {
                    Symbol(R.drawable.ic_add)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.new_program))
                }
                OutlinedButton(
                    onClick = { chooseFile(null) },
                    modifier = Modifier.testTag("import_program_file")
                ) {
                    Text("Импорт Markdown")
                }
            }
            if (library.programs.isEmpty()) {
                CardBlock {
                    SectionTitle(stringResource(R.string.no_programs))
                    Muted(stringResource(R.string.program_intro))
                }
            }
            library.programs.forEach { item ->
                CardBlock(onClick = { currentId = item.id }) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(item.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (item.enabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest
                        ) {
                            Text(
                                if (item.enabled) "Активна" else "Отключена",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (item.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    if (item.description.isNotBlank()) {
                        Text(
                            item.description.take(120) + if (item.description.length > 120) "…" else "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Muted(stringResource(R.string.days_count, item.days.size))
                        val participantNames = item.participantIds.mapNotNull { id -> library.profiles.find { it.id == id }?.name }
                        if (participantNames.isNotEmpty()) {
                            Muted("Участники: ${participantNames.joinToString(", ")}")
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { currentId = item.id }) {
                            Text("Открыть программу")
                            Spacer(Modifier.width(4.dp))
                            Symbol(R.drawable.ic_forward, modifier = Modifier.size(16.dp))
                        }
                        TextButton(onClick = { currentId = item.id; editProgram = true }) {
                            Symbol(R.drawable.ic_settings, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Настройки")
                        }
                    }
                }
            }
            val scheduled = library.sessions.filter { it.status == SessionStatus.PLANNED }.sortedBy { it.date }
            if (scheduled.isNotEmpty()) SectionTitle(stringResource(R.string.planned_sessions))
            scheduled.forEach { item ->
                CardBlock {
                    Text(item.title, style = MaterialTheme.typography.titleMedium)
                    Muted(shortDate(item.date))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { preview(item.id) }) { Text(stringResource(R.string.view_plan)) }
                        TextButton(onClick = { moveId = item.id }) { Text(stringResource(R.string.reschedule)) }
                        TextButton(onClick = { model.session(item.id) { session -> session.copy(status = SessionStatus.SKIPPED,
                                workouts = session.workouts.map { it.copy(status = WorkoutStatus.SKIPPED) }) } }) {
                            Text(stringResource(R.string.skip))
                        }
                    }
                }
            }
        } else if (day == null) {
            // Program Details View
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = ::back) {
                    Symbol(R.drawable.ic_back)
                    Spacer(Modifier.width(4.dp))
                    Text("К программам")
                }
                Spacer(Modifier.weight(1f))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (program.enabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest
                ) {
                    Text(
                        if (program.enabled) "Расписание активно" else "Расписание выключено",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (program.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Text(program.title, style = MaterialTheme.typography.headlineLarge)
            if (program.caution.isNotBlank()) InlineStatus("Ограничения: ${program.caution}", warning = true)

            program.importSource?.let { source ->
                Muted(source.name + source.revision.takeIf { it.isNotBlank() }?.let { " · Версия $it" }.orEmpty())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { model.readProgramFile(source.uri.toUri(), program.id) { input = it } }) { Text("Проверить источник") }
                    TextButton(onClick = { chooseFile(program.id) }) { Text("Выбрать файл источника") }
                }
            }

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { editProgram = true },
                    modifier = Modifier.weight(1f).testTag("edit_program_btn")
                ) {
                    Symbol(R.drawable.ic_settings)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.edit_program))
                }
                OutlinedButton(
                    onClick = { editingDayId = null; editDay = true },
                    modifier = Modifier.weight(1f).testTag("add_day_btn")
                ) {
                    Symbol(R.drawable.ic_add)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.add_day))
                }
            }

            // Description Card
            if (program.description.isNotBlank()) {
                CardBlock {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Описание и указания", style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = { editProgram = true }) {
                            Text(stringResource(R.string.change))
                        }
                    }
                    Text(program.description, style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                OutlinedCard(
                    onClick = { editProgram = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Symbol(R.drawable.ic_add, modifier = Modifier.size(18.dp))
                        Text("+ Добавить описание к программе", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            // Schedule & Participants Info Card
            CardBlock {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val scheduleType = if (program.cycle) {
                            program.completionCycle?.let { "Цикл: ${it.visitsPerWeek} посещ./нед." } ?: "Цикл каждые ${program.interval} дн."
                        } else {
                            "По дням недели"
                        }
                        Text("Режим: $scheduleType", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        val participantNames = program.participantIds.mapNotNull { id -> library.profiles.find { it.id == id }?.name }
                        if (participantNames.isNotEmpty()) {
                            Muted("Участники: ${participantNames.joinToString(", ")}")
                        }
                    }
                    TextButton(onClick = { editProgram = true }) {
                        Text("Настроить")
                    }
                }
            }

            // Days section
            SectionTitle("Тренировочные дни (${program.days.size})")

            if (program.days.isEmpty()) {
                CardBlock {
                    Muted("В программе пока нет тренировочных дней. Добавьте первый день.")
                    Button(onClick = { editingDayId = null; editDay = true }) {
                        Text(stringResource(R.string.add_day))
                    }
                }
            }

            program.days.forEachIndexed { index, item ->
                CardBlock(onClick = { currentDay = item.id }) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(item.title, style = MaterialTheme.typography.titleLarge)
                            val dayBadge = if (program.cycle) stringResource(R.string.cycle_day, index + 1) else weekday(item.weekday)
                            Muted("$dayBadge · ${stringResource(R.string.exercise_count, item.exercises.size)}")
                        }
                        Symbol(R.drawable.ic_forward)
                    }

                    if (item.notes.isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                item.notes.take(120) + if (item.notes.length > 120) "…" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                            )
                        }
                    }

                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { currentDay = item.id }) {
                            Text("Открыть день")
                        }
                        TextButton(onClick = { editingDayId = item.id; editDay = true }) {
                            Text(stringResource(R.string.change))
                        }
                        if (index > 0) {
                            TextButton(onClick = { model.moveDay(program.id, item.id, -1) }) {
                                Text(stringResource(R.string.move_up))
                            }
                        }
                        if (index < program.days.lastIndex) {
                            TextButton(onClick = { model.moveDay(program.id, item.id, 1) }) {
                                Text(stringResource(R.string.move_down))
                            }
                        }
                        TextButton(onClick = { model.duplicateDay(program.id, item.id) }) {
                            Text(stringResource(R.string.duplicate))
                        }
                        TextButton(onClick = { confirmDeleteDayId = item.id }) {
                            Text(stringResource(R.string.remove), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            Button(
                onClick = { editingDayId = null; editDay = true },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
            ) {
                Symbol(R.drawable.ic_add)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.add_day))
            }
        } else {
            // Day Details View
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = ::back) {
                    Symbol(R.drawable.ic_back)
                    Spacer(Modifier.width(4.dp))
                    Text("К программе")
                }
                Spacer(Modifier.weight(1f))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest
                ) {
                    val dayIndex = program.days.indexOfFirst { it.id == day.id }
                    val badge = if (program.cycle) stringResource(R.string.cycle_day, dayIndex + 1) else weekday(day.weekday)
                    Text(
                        badge,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Text(day.title, style = MaterialTheme.typography.headlineLarge)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { editPlan = -1 },
                    modifier = Modifier.weight(1f).testTag("program_add_exercise")
                ) {
                    Symbol(R.drawable.ic_add)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.add_exercise))
                }
                OutlinedButton(
                    onClick = { editingDayId = day.id; editDay = true },
                    modifier = Modifier.weight(1f).testTag("edit_day_btn")
                ) {
                    Text(stringResource(R.string.edit_day))
                }
                OutlinedButton(onClick = { preview(day.id) }) {
                    Text(stringResource(R.string.view_plan))
                }
            }

            // Day Notes Card (Warmup/Cooldown)
            if (day.notes.isNotBlank()) {
                CardBlock {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(stringResource(R.string.day_notes), style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = { editingDayId = day.id; editDay = true }) {
                            Text(stringResource(R.string.change))
                        }
                    }
                    Text(day.notes, style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                OutlinedCard(
                    onClick = { editingDayId = day.id; editDay = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Symbol(R.drawable.ic_add, modifier = Modifier.size(18.dp))
                        Text("+ Добавить разминку, заминку или примечания", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            if (day.blocks.isNotEmpty()) {
                SectionTitle("Блоки тренировки")
                day.blocks.forEachIndexed { index, block ->
                    CardBlock {
                        Text(block.name, style = MaterialTheme.typography.titleMedium)
                        Muted(block.requirement.label + if (block.enabled) "" else " · Отключён")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { blockId = block.id }) { Text("Изменить блок") }
                            if (index > 0) TextButton(onClick = {
                                model.changeDay(program.id, day.id) { current ->
                                    current.copy(blocks = current.blocks.toMutableList().apply { add(index - 1, removeAt(index)) })
                                }
                            }) { Text(stringResource(R.string.move_up)) }
                            TextButton(onClick = {
                                model.changeDay(program.id, day.id) { current ->
                                    current.copy(blocks = current.blocks.filter { it.id != block.id })
                                }
                            }) { Text(stringResource(R.string.remove), color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SectionTitle(stringResource(R.string.exercise_count, day.exercises.size))
                TextButton(onClick = { blockId = "new" }, modifier = Modifier.testTag("add_training_block")) {
                    Text("+ Блок/суперсет")
                }
            }

            if (day.exercises.isEmpty()) {
                CardBlock {
                    Muted("В этом дне пока нет упражнений. Добавьте упражнения и укажите рабочие подходы.")
                    Button(onClick = { editPlan = -1 }) {
                        Text(stringResource(R.string.add_exercise))
                    }
                }
            }

            day.exercises.forEachIndexed { index, plan ->
                CardBlock {
                    val exerciseName = library.exercises.find { it.id == plan.exercise.id }?.name ?: plan.exercise.name
                    Text(exerciseName, style = MaterialTheme.typography.titleMedium)
                    day.blocks.find { it.id == plan.blockId }?.let {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                        ) {
                            Text(it.name, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    val sample = plan.sets.firstOrNull { it.purpose == SetRole.WORK } ?: plan.sets.firstOrNull()
                    if (sample != null) {
                        val repsText = if (sample.maxEffort) stringResource(R.string.max_effort) else if (sample.repsKnown) repRange(sample.repsMin, sample.repsMax) else "?"
                        val weightText = if (sample.weightKnown) "${number(sample.weight)} ${unitText(plan.exercise.unit)}" else "вес не указан"
                        Muted("${plan.sets.size} подх. · $repsText повт. · $weightText")
                    }

                    if (plan.individual.isNotEmpty()) {
                        val details = plan.individual.mapNotNull { (pid, sets) ->
                            val name = library.profiles.find { it.id == pid }?.name ?: return@mapNotNull null
                            val firstSet = sets.firstOrNull { it.purpose == SetRole.WORK } ?: sets.firstOrNull() ?: return@mapNotNull null
                            val w = if (firstSet.weightKnown) "${number(firstSet.weight)} ${unitText(plan.exercise.unit)}" else "?"
                            "$name: $w"
                        }
                        if (details.isNotEmpty()) {
                            Muted("Индивидуально: " + details.joinToString(" · "))
                        }
                    }

                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { editPlan = index }) { Text(stringResource(R.string.change)) }
                        if (library.profiles.isNotEmpty()) {
                            TextButton(onClick = { individual = index }) { Text(stringResource(R.string.personal_load)) }
                        }
                        if (index > 0) TextButton(onClick = {
                            model.changeDay(program.id, day.id) { item ->
                                item.copy(exercises = item.exercises.toMutableList().apply { add(index - 1, removeAt(index)) })
                            }
                        }) { Text(stringResource(R.string.move_up)) }
                        if (index < day.exercises.lastIndex) TextButton(onClick = {
                            model.changeDay(program.id, day.id) { item ->
                                item.copy(exercises = item.exercises.toMutableList().apply { add(index + 1, removeAt(index)) })
                            }
                        }) { Text(stringResource(R.string.move_down)) }
                        TextButton(onClick = {
                            model.changeDay(program.id, day.id) { d ->
                                d.copy(exercises = d.exercises.toMutableList().apply { add(index + 1, plan.copy()) })
                            }
                        }) { Text(stringResource(R.string.duplicate)) }
                        TextButton(onClick = {
                            model.changeDay(program.id, day.id) { it.copy(exercises = it.exercises.filterIndexed { i, _ -> i != index }) }
                        }) { Text(stringResource(R.string.remove), color = MaterialTheme.colorScheme.error) }
                    }
                }
            }

            if (day.exercises.isNotEmpty()) {
                Button(
                    onClick = { editPlan = -1 },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                ) {
                    Symbol(R.drawable.ic_add)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.add_exercise))
                }
            }
        }
    }

    input?.let { selected ->
        ProgramImportSheet(selected, library, { input = null }) { review ->
            model.saveImportedProgram(review, library.scope) { id ->
                input = null
                currentId = id
                currentDay = null
            }
        }
    }
    if (showSource && program != null) EditorSheet("Указания и исходный текст", { showSource = false }) {
        Text(program.importSource?.original?.takeIf { it.isNotBlank() } ?: program.description)
    }
    if (editProgram) {
        ProgramEditor(program, library.profiles, { editProgram = false }) {
            currentId = it.id
            model.save(it)
            editProgram = false
        }
    }
    if (editDay && program != null) {
        val targetDay = program.days.find { it.id == editingDayId } ?: day
        DayEditor(targetDay, program.cycle, { editDay = false; editingDayId = null }) { updated ->
            model.edit(program.id) { doc ->
                val p = doc as Program
                val exists = p.days.any { it.id == updated.id }
                p.copy(days = if (exists) p.days.map { if (it.id == updated.id) updated else it } else p.days + updated)
            }
            if (day != null) currentDay = updated.id
            editDay = false
            editingDayId = null
        }
    }
    if (confirmDeleteDayId != null && program != null) {
        val dayToDelete = program.days.find { it.id == confirmDeleteDayId }
        AlertDialog(
            onDismissRequest = { confirmDeleteDayId = null },
            title = { Text(stringResource(R.string.delete_day_confirm_title)) },
            text = { Text(stringResource(R.string.delete_day_confirm_text, dayToDelete?.title.orEmpty())) },
            confirmButton = {
                Button(
                    onClick = {
                        val id = confirmDeleteDayId!!
                        model.deleteDay(program.id, id)
                        if (currentDay == id) currentDay = null
                        confirmDeleteDayId = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.remove))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteDayId = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
    if (blockId != null && day != null) {
        BlockEditor(day.blocks.find { it.id == blockId }, { blockId = null }) { block ->
            model.changeDay(program.id, day.id) { current ->
                current.copy(blocks = if (current.blocks.any { it.id == block.id }) {
                    current.blocks.map { if (it.id == block.id) block else it }
                } else current.blocks + block)
            }
            blockId = null
        }
    }
    val editIndex = editPlan
    if (editIndex != null && day != null) {
        ExerciseEditor(if (editIndex < 0) stringResource(R.string.add_exercise) else stringResource(R.string.change_plan),
            library, prefs, day.exercises.getOrNull(editIndex), { editPlan = null }, programOptions = true, trainingBlocks = day.blocks) { plan, created ->
            model.changeDay(program.id, day.id, created) { item ->
                item.copy(exercises = if (editIndex < 0) item.exercises + plan else item.exercises.mapIndexed { i, existing ->
                    if (i == editIndex) plan else existing
                })
            }
            editPlan = null
        }
    }
    val personalIndex = individual
    val personalPlan = personalIndex?.let { day?.exercises?.getOrNull(it) }
    if (personalIndex != null && personalPlan != null && day != null) {
        PersonalPlan(personalPlan, library.profiles, { individual = null }) { result ->
            model.changeDay(program.id, day.id) { item ->
                item.copy(exercises = item.exercises.mapIndexed { i, old -> if (i == personalIndex) result else old })
            }
            individual = null
        }
    }
    if (dateSession != null) {
        var value by rememberSaveable(dateSession.id) { mutableStateOf(dateSession.date) }
        val valid = dateOrNull(value)
        EditorSheet(stringResource(R.string.reschedule), { moveId = null }) {
            Field(value, stringResource(R.string.date_field), { value = it })
            Button(onClick = {
                if (valid != null) model.session(dateSession.id) { it.reschedule(valid) }
                moveId = null
            }, enabled = valid != null, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.save)) }
        }
    }
}

private fun GymModel.moveDay(programId: String, id: String, step: Int) {
    edit(programId) { doc ->
        val program = doc as Program
        val index = program.days.indexOfFirst { it.id == id }
        if (index < 0 || index + step !in program.days.indices) program
        else program.copy(days = program.days.toMutableList().apply { add(index + step, removeAt(index)) })
    }
}

private fun GymModel.deleteDay(programId: String, id: String) {
    edit(programId) { doc ->
        val program = doc as Program
        program.copy(days = program.days.filter { it.id != id })
    }
}

private fun GymModel.duplicateDay(programId: String, id: String) {
    edit(programId) { doc ->
        val program = doc as Program
        val source = program.days.find { it.id == id } ?: return@edit program
        val copy = source.copy(id = newId(), title = "${source.title} (копия)")
        program.copy(days = program.days + copy)
    }
}

private fun GymModel.changeDay(programId: String, dayId: String, created: Exercise? = null, change: (ProgramDay) -> ProgramDay) {
    if (created != null) save(created)
    edit(programId) { doc ->
        val program = doc as Program
        program.copy(days = program.days.map { if (it.id == dayId) change(it) else it })
    }
}

@Composable
private fun ProgramEditor(program: Program?, profiles: List<Profile>, close: () -> Unit, save: (Program) -> Unit) {
    var title by rememberSaveable { mutableStateOf(program?.title.orEmpty()) }
    var description by rememberSaveable { mutableStateOf(program?.description.orEmpty()) }
    var caution by rememberSaveable { mutableStateOf(program?.caution.orEmpty()) }
    var fixed by rememberSaveable { mutableStateOf(program?.cycle != true) }
    var enabled by rememberSaveable { mutableStateOf(program?.enabled ?: true) }
    var anchor by rememberSaveable { mutableStateOf(program?.anchor ?: LocalDate.now().toString()) }
    var interval by rememberSaveable { mutableStateOf((program?.interval ?: 2).toString()) }
    var legacy by rememberSaveable { mutableStateOf(program?.cycle == true && program.completionCycle == null) }
    var visits by rememberSaveable { mutableStateOf((program?.completionCycle?.visitsPerWeek ?: 3).toString()) }
    var target by rememberSaveable { mutableStateOf(program?.completionCycle?.targetSessions?.toString().orEmpty()) }
    var preferred by remember { mutableStateOf(program?.completionCycle?.visitDays.orEmpty()) }
    val times = visits.toIntOrNull()?.takeIf { it in 1..7 }
    val sessions = positiveInt(target)
    var people by remember { mutableStateOf(program?.participantIds.orEmpty()) }
    val isValid = title.isNotBlank() && (fixed || dateOrNull(anchor) != null &&
        (if (legacy) positiveInt(interval) != null else times != null &&
            (preferred.isEmpty() || preferred.size == times) && (target.isBlank() || sessions != null)))

    EditorSheet(if (program == null) stringResource(R.string.new_program) else stringResource(R.string.edit_program), close) {
        Field(title, stringResource(R.string.program_name), { title = it }, Modifier.fillMaxWidth().testTag("program_name"))
        Field(description, stringResource(R.string.program_description), { description = it }, singleLine = false)
        Field(caution, "Ограничения программы", { caution = it }, singleLine = false)

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(vertical = 4.dp)
        ) {
            Switch(enabled, { enabled = it })
            Column {
                Text(if (enabled) stringResource(R.string.schedule_on) else stringResource(R.string.schedule_off), style = MaterialTheme.typography.bodyLarge)
                Muted("Управляет включением программы в расписание занятий")
            }
        }

        SectionTitle("Режим расписания")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(fixed, { fixed = true }, label = { Text(stringResource(R.string.weekdays)) })
            FilterChip(!fixed && !legacy, { fixed = false; legacy = false }, label = { Text("По циклу тренировок") },
                modifier = Modifier.testTag("completion_schedule"))
            if (program?.cycle == true && program.completionCycle == null) {
                FilterChip(!fixed && legacy, { fixed = false; legacy = true }, label = { Text("Прежний интервал") })
            }
        }
        if (!fixed) {
            Field(anchor, stringResource(R.string.cycle_start), { anchor = it })
            if (legacy) Field(interval, stringResource(R.string.cycle_interval), { interval = it }, keyboard = KeyboardType.Number)
            else {
                Field(visits, "Посещений в неделю", { visits = it }, Modifier.testTag("cycle_visits"), KeyboardType.Number)
                Field(target, "Цель по завершённым занятиям (необязательно)", { target = it },
                    Modifier.testTag("cycle_target"), KeyboardType.Number)
                Muted("Предпочтительные дни. Если выбраны, их число должно совпасть с числом посещений.")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DayOfWeek.entries.forEach { day ->
                        FilterChip(day in preferred, { preferred = if (day in preferred) preferred - day else preferred + day },
                            label = { Text(weekday(day)) })
                    }
                }
            }
        }
        if (profiles.isNotEmpty()) {
            SectionTitle(stringResource(R.string.participants))
            Muted("Атлеты, для которых составляется эта программа:")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                profiles.forEach { profile ->
                    FilterChip(profile.id in people, { people = if (profile.id in people) people - profile.id else people + profile.id },
                        label = { Text(profile.name) })
                }
            }
        }
        Button(onClick = {
            val initial = program ?: Program(title = title.trim())
            val completion = if (fixed || legacy) null else (initial.completionCycle ?: CompletionCycle()).copy(
                visitsPerWeek = requireNotNull(times), visitDays = preferred, targetSessions = sessions)
            save(initial.copy(
                title = title.trim(),
                description = description.trim(),
                caution = caution.trim(),
                enabled = enabled,
                cycle = !fixed,
                anchor = dateOrNull(anchor)?.toString() ?: LocalDate.now().toString(),
                interval = positiveInt(interval) ?: 2,
                participantIds = people,
                completionCycle = completion
            ))
        }, enabled = isValid, modifier = Modifier.fillMaxWidth().testTag("save_program")) { Text(stringResource(R.string.save)) }
    }
}

@Composable
private fun DayEditor(day: ProgramDay?, cycle: Boolean, close: () -> Unit, save: (ProgramDay) -> Unit) {
    var title by rememberSaveable { mutableStateOf(day?.title.orEmpty()) }
    var notes by rememberSaveable { mutableStateOf(day?.notes.orEmpty()) }
    var date by rememberSaveable { mutableStateOf(day?.weekday ?: LocalDate.now().dayOfWeek) }
    EditorSheet(if (day == null) stringResource(R.string.add_day) else stringResource(R.string.edit_day), close) {
        Field(title, stringResource(R.string.day_name), { title = it }, Modifier.fillMaxWidth().testTag("day_name"))
        if (!cycle) {
            Text("День недели:", style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DayOfWeek.entries.forEach { option -> FilterChip(date == option, { date = option }, label = { Text(weekday(option)) }) }
            }
        }
        Field(notes, stringResource(R.string.day_notes), { notes = it }, singleLine = false)
        Muted("Укажите разминку, параметры дорожки, заминку или рекомендации к тренировочному дню.")
        Button(
            onClick = {
                save((day ?: ProgramDay(title = title.trim())).copy(
                    title = title.trim(),
                    weekday = date,
                    notes = notes.trim()
                ))
            },
            enabled = title.isNotBlank(),
            modifier = Modifier.fillMaxWidth().testTag("save_day")
        ) {
            Text(stringResource(R.string.save))
        }
    }
}

@Composable
private fun PersonalPlan(plan: ExercisePlan, profiles: List<Profile>, close: () -> Unit, save: (ExercisePlan) -> Unit) {
    var id by rememberSaveable { mutableStateOf(profiles.first().id) }
    val baseline = plan.individual[id] ?: plan.sets
    var weight by rememberSaveable(id) { mutableStateOf(number(baseline.firstOrNull()?.weight ?: 0.0)) }
    var count by rememberSaveable(id) { mutableStateOf(baseline.size.toString()) }
    var low by rememberSaveable(id) { mutableStateOf((baseline.firstOrNull()?.repsMin ?: plan.exercise.repsMin).toString()) }
    var high by rememberSaveable(id) { mutableStateOf((baseline.firstOrNull()?.repsMax ?: plan.exercise.repsMax).toString()) }
    val amount = decimal(weight)
    val number = count.toIntOrNull()?.takeIf { it in 1..30 }
    val min = positiveInt(low)
    val max = positiveInt(high)
    EditorSheet(stringResource(R.string.personal_load), close) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            profiles.forEach { profile -> FilterChip(id == profile.id, { id = profile.id }, label = { Text(profile.name) }) }
        }
        Field(weight, stringResource(R.string.weight_unit, unitText(plan.exercise.unit)), { weight = it }, keyboard = KeyboardType.Decimal)
        Field(count, stringResource(R.string.set_count), { count = it }, keyboard = KeyboardType.Number)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field(low, stringResource(R.string.min_reps), { low = it }, Modifier.weight(1f), KeyboardType.Number)
            Field(high, stringResource(R.string.max_reps), { high = it }, Modifier.weight(1f), KeyboardType.Number)
        }
        Button(onClick = {
            if (amount != null && number != null && min != null && max != null) {
                val sets = List(number) { index ->
                    baseline.getOrNull(index)?.copy(weight = amount, weightKnown = true, repsMin = min, repsMax = max,
                        repsKnown = true, maxEffort = false, targetReps = null) ?: SetPlan(amount, min, max)
                }
                save(plan.copy(individual = plan.individual + (id to sets)))
            }
        }, enabled = amount != null && number != null && min != null && max != null && min <= max, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.save))
        }
        if (id in plan.individual) TextButton(onClick = { save(plan.copy(individual = plan.individual - id)) }) {
            Text(stringResource(R.string.reset_individual))
        }
    }
}

@Composable
private fun weekday(day: DayOfWeek): String {
    val labels = listOf(stringResource(R.string.mon), stringResource(R.string.tue), stringResource(R.string.wed), stringResource(R.string.thu), stringResource(R.string.fri), stringResource(R.string.sat), stringResource(R.string.sun))
    return labels[day.ordinal]
}
