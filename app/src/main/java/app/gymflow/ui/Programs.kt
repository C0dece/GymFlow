@file:OptIn(ExperimentalLayoutApi::class)

package app.gymflow.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.style.TextOverflow
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
    var archiveVisible by rememberSaveable { mutableStateOf(false) }
    var archiveTarget by remember { mutableStateOf<Program?>(null) }
    var deleteExercise by remember { mutableStateOf<Pair<Int, ExercisePlan>?>(null) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    fun submit(original: Program?, updated: Program, created: Exercise? = null, done: () -> Unit) {
        if (saving) return
        saving = true
        model.saveProgram(library.scope, original, updated, created) { error ->
            saving = false
            if (error == null) done() else saveError = error
        }
    }
    fun submitDay(program: Program, day: ProgramDay, updated: ProgramDay, created: Exercise? = null, done: () -> Unit) {
        submit(program, program.copy(days = program.days.map { if (it.id == day.id) updated else it }), created, done)
    }
    val program = library.programs.find { it.id == currentId }
    val day = program?.days?.find { it.id == currentDay }
    val dateSession = library.sessions.find { it.id == moveId }
    fun back() { if (currentDay != null) currentDay = null else currentId = null }
    BackHandler(currentId != null) { back() }

    ScreenColumn(Modifier.testTag("programs")) {
        if (program == null) {
            AdaptivePair {
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
                    modifier = Modifier.weight(1f).testTag("import_program_file")
                ) {
                    Text("Импорт Markdown")
                }
            }
            if (library.programs.any(Program::archived)) {
                FilterChip(archiveVisible, { archiveVisible = !archiveVisible }, label = { Text("Архив программ") })
            }
            val shown = library.programs.filter { it.archived == archiveVisible }
            if (shown.isEmpty()) {
                CardBlock {
                    SectionTitle(stringResource(R.string.no_programs))
                    Muted(stringResource(R.string.program_intro))
                }
            }
            shown.forEach { item ->
                CardBlock(Modifier.testTag("program_${item.id}"), onClick = { currentId = item.id }) {
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
                                if (item.archived) "В архиве" else if (item.enabled) "Активна" else "Отключена",
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

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Muted(stringResource(R.string.days_count, item.days.size))
                        val participantNames = item.participantIds.mapNotNull { id -> library.profiles.find { it.id == id }?.name }
                        if (participantNames.isNotEmpty()) {
                            Muted("Участники: ${participantNames.joinToString(", ")}")
                        }
                    }

                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { currentId = item.id; editProgram = true }) {
                            Symbol(R.drawable.ic_settings, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Настройки")
                        }
                    }
                }
            }
            val archivedIds = library.programs.filter(Program::archived).mapTo(hashSetOf(), Program::id)
            val scheduled = library.sessions.filter { it.status == SessionStatus.PLANNED && it.programId !in archivedIds }.sortedBy { it.date }
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
            AdaptivePair {
                Button(
                    onClick = { editingDayId = null; editDay = true },
                    modifier = Modifier.weight(1f).testTag("add_day_btn")
                ) {
                    Symbol(R.drawable.ic_add)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.add_day))
                }
                OutlinedButton(
                    onClick = { editProgram = true },
                    modifier = Modifier.weight(1f).testTag("edit_program_btn")
                ) {
                    Text(stringResource(R.string.edit_program))
                }
            }

            // Description Card
            TextButton(onClick = {
                if (program.archived) submit(program, program.copy(archived = false)) { archiveVisible = false }
                else archiveTarget = program
            }, modifier = Modifier.testTag("archive_program")) { Text(if (program.archived) "Вернуть программу" else "Убрать в архив") }

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
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val scheduleType = if (program.cycle) {
                        program.completionCycle?.let { "Цикл: ${it.visitsPerWeek} посещ./нед." } ?: "Цикл каждые ${program.interval} дн."
                    } else {
                        "По дням недели"
                    }
                    Text("Режим: $scheduleType", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    val participantNames = program.participantIds.mapNotNull { id -> library.profiles.find { it.id == id }?.name }
                    if (participantNames.isNotEmpty()) Muted("Участники: ${participantNames.joinToString(", ")}")
                }
            }

            // Days section
            SectionTitle("Тренировочные дни (${program.days.size})")

            if (program.days.isEmpty()) {
                CardBlock { Muted("В программе пока нет тренировочных дней.") }
            }

            program.days.forEachIndexed { index, item ->
                CardBlock {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { currentDay = item.id }.testTag("program_day_$index"),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(item.title, style = MaterialTheme.typography.titleLarge)
                            val dayBadge = if (program.cycle) stringResource(R.string.cycle_day, index + 1) else weekday(item.weekday)
                            Muted("$dayBadge · ${stringResource(R.string.exercise_count, item.exercises.size)}")
                        }
                        Symbol(R.drawable.ic_forward, description = "Открыть день")
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
                        TextButton(onClick = { editingDayId = item.id; editDay = true }) {
                            Text(stringResource(R.string.change))
                        }
                        if (index > 0) {
                            TextButton(onClick = { model.moveDay(program.id, item.id, -1) }, modifier = Modifier.testTag("program_day_up_$index")) {
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

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(
                    onClick = { editPlan = -1 },
                    modifier = Modifier.testTag("program_add_exercise")
                ) {
                    Symbol(R.drawable.ic_add)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.add_exercise))
                }
                OutlinedButton(
                    onClick = { editingDayId = day.id; editDay = true },
                    modifier = Modifier.testTag("edit_day_btn")
                ) {
                    Text(stringResource(R.string.edit_day))
                }
                OutlinedButton(onClick = { preview(day.id) }) {
                    Text(stringResource(R.string.view_plan))
                }
            }

            if (day.notes.isNotBlank()) CardBlock {
                Text("Примечания к дню", style = MaterialTheme.typography.titleSmall)
                Muted(day.notes)
            }

            if (day.blocks.isNotEmpty()) {
                SectionTitle("Блоки тренировки")
                day.blocks.forEachIndexed { index, block ->
                    CardBlock {
                        var menu by remember(block.id) { mutableStateOf(false) }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).clickable { blockId = block.id }) {
                                Text(block.name, style = MaterialTheme.typography.titleMedium)
                                val details = listOfNotNull(block.durationSeconds?.let(::durationText),
                                    block.requirement.label.takeIf { block.requirement != PlanBlock.REQUIRED },
                                    "Отключён".takeUnless { block.enabled })
                                if (details.isNotEmpty()) Muted(details.joinToString(" · "))
                            }
                            IconButton(onClick = { menu = true }) { Symbol(R.drawable.ic_more, description = "Действия блока") }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem(text = { Text("Изменить блок") }, onClick = { menu = false; blockId = block.id })
                                listOf(-1 to "Выше", 1 to "Ниже").forEach { (step, label) ->
                                    DropdownMenuItem(text = { Text(label) }, enabled = index + step in day.blocks.indices, onClick = {
                                        menu = false
                                        model.changeDay(program.id, day.id) { current ->
                                            val from = current.blocks.indexOfFirst { it.id == block.id }
                                            val to = from + step
                                            if (from < 0 || to !in current.blocks.indices) current
                                            else current.copy(blocks = current.blocks.toMutableList().apply { add(to, removeAt(from)) })
                                        }
                                    })
                                }
                                DropdownMenuItem(text = { Text("Удалить блок") }, onClick = {
                                    menu = false
                                    model.changeDay(program.id, day.id) { current ->
                                        current.copy(blocks = current.blocks.filter { it.id != block.id },
                                            exercises = current.exercises.map { if (it.blockId == block.id) it.copy(blockId = null) else it })
                                    }
                                })
                            }
                        }
                        if (block.guidance.isNotBlank()) Muted(block.guidance, Modifier.clickable { blockId = block.id })
                    }
                }
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle(stringResource(R.string.exercise_count, day.exercises.size))
                TextButton(onClick = { blockId = "new" }, modifier = Modifier.testTag("add_training_block")) {
                    Text("+ Блок/суперсет")
                }
            }

            if (day.exercises.isEmpty()) {
                CardBlock { Muted("В этом дне пока нет упражнений.") }
            }

            day.exercises.forEachIndexed { index, plan ->
                CardBlock {
                    var menu by remember { mutableStateOf(false) }
                    val exerciseName = library.exercises.find { it.id == plan.exercise.id }?.name ?: plan.exercise.name
                    val blockName = day.blocks.find { it.id == plan.blockId }?.name
                    val common = plan.sets.distinct().singleOrNull()
                    val summary = common?.let { "${plan.sets.size} × ${plannedSetText(it, plan.exercise)}" }
                        ?: "${stringResource(R.string.set_count)} · ${plan.sets.size}"
                    Column(Modifier.fillMaxWidth().clickable { editPlan = index }.testTag("edit_program_exercise_$index")
                        .padding(vertical = 4.dp)) {
                        Text(exerciseName, style = MaterialTheme.typography.titleMedium)
                        Muted(listOfNotNull(summary, blockName).joinToString(" · "))
                        if (plan.instructions.isNotBlank()) Text(plan.instructions, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                        Box {
                            IconButton(onClick = { menu = true }) { Symbol(R.drawable.ic_settings, description = "Действия упражнения") }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.change)) }, onClick = { menu = false; editPlan = index })
                                if (library.profiles.isNotEmpty()) DropdownMenuItem(
                                    text = { Text(stringResource(R.string.personal_load)) }, onClick = { menu = false; individual = index })
                                DropdownMenuItem(text = { Text(stringResource(R.string.duplicate)) }, onClick = {
                                    menu = false
                                    submitDay(program, day, day.copy(exercises = day.exercises.toMutableList().apply { add(index + 1, plan) })) {}
                                })
                            }
                        }
                        listOf(-1 to R.string.move_up, 1 to R.string.move_down).forEach { (step, label) ->
                            val tag = if (step < 0) "program_exercise_up_$index" else "program_exercise_down_$index"
                            IconButton(onClick = {
                                submitDay(program, day, day.copy(exercises = day.exercises.toMutableList().apply { add(index + step, removeAt(index)) })) {}
                            }, enabled = index + step in day.exercises.indices, modifier = Modifier.testTag(tag)) {
                                Symbol(R.drawable.ic_forward, Modifier.rotate(if (step < 0) -90f else 90f), stringResource(label))
                            }
                        }
                        IconButton(onClick = { deleteExercise = index to plan }) { Symbol(R.drawable.ic_delete, description = "Удалить упражнение") }
                    }
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
        val baseline = remember(currentId) { program }
        ProgramEditor(baseline, library.profiles, { if (!saving) editProgram = false }) { updated ->
            submit(baseline, updated) {
                currentId = updated.id
                editProgram = false
            }
        }
    }
    if (editDay && program != null) {
        val baseline = remember(currentId) { program }
        val targetDay = baseline.days.find { it.id == editingDayId }
        DayEditor(targetDay, baseline.cycle, { if (!saving) { editDay = false; editingDayId = null } }) { updated ->
            val days = if (targetDay == null) baseline.days + updated else baseline.days.map { if (it.id == updated.id) updated else it }
            submit(baseline, baseline.copy(days = days)) {
                if (targetDay == null || day != null) currentDay = updated.id
                editDay = false
                editingDayId = null
            }
        }
    }
    archiveTarget?.let { target ->
        AlertDialog(onDismissRequest = { archiveTarget = null }, title = { Text("Убрать программу в архив?") },
            text = { Text("Программу можно вернуть из архива. История тренировок сохранится.") },
            confirmButton = { Button(onClick = { submit(target, target.copy(archived = true)) {
                archiveTarget = null
                currentId = null
            } }, enabled = !saving) { Text("В архив") } },
            dismissButton = { TextButton(onClick = { archiveTarget = null }) { Text("Отмена") } })
    }
    deleteExercise?.let { (index, plan) ->
        AlertDialog(onDismissRequest = { deleteExercise = null }, title = { Text("Убрать упражнение из дня?") },
            text = { Text(plan.exercise.name) },
            confirmButton = { Button(onClick = {
                if (program != null && day?.exercises?.getOrNull(index) == plan) {
                    submitDay(program, day, day.copy(exercises = day.exercises.filterIndexed { i, _ -> i != index })) {
                        deleteExercise = null
                    }
                } else {
                    deleteExercise = null
                    saveError = "Порядок упражнений изменился. Выберите упражнение снова."
                }
            }, enabled = !saving) { Text("Убрать") } },
            dismissButton = { TextButton(onClick = { deleteExercise = null }) { Text("Отмена") } })
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
        val baseline = remember(program.id, day.id, editIndex) { program to day }
        val source = baseline.second
        ExerciseEditor(if (editIndex < 0) stringResource(R.string.add_exercise) else stringResource(R.string.change_plan),
            library, prefs, source.exercises.getOrNull(editIndex), { if (!saving) editPlan = null },
            programOptions = true, trainingBlocks = source.blocks) { plan, created ->
            val next = if (editIndex < 0) source.exercises + plan else source.exercises.mapIndexed { i, existing ->
                if (i == editIndex) plan else existing
            }
            submitDay(baseline.first, source, source.copy(exercises = next), created) { editPlan = null }
        }
    }
    val personalIndex = individual
    if (personalIndex != null && day != null) {
        val baseline = remember(program.id, day.id, personalIndex) { program to day }
        val source = baseline.second
        source.exercises.getOrNull(personalIndex)?.let { personalPlan ->
            PersonalPlan(personalPlan, library.profiles, { if (!saving) individual = null }) { result ->
                val next = source.exercises.mapIndexed { i, old -> if (i == personalIndex) result else old }
                submitDay(baseline.first, source, source.copy(exercises = next)) { individual = null }
            }
        }
    }
    saveError?.let { error ->
        AlertDialog(onDismissRequest = { saveError = null }, title = { Text("Сохранение не завершено") },
            text = { Text(error) }, confirmButton = { TextButton(onClick = { saveError = null }) { Text("Понятно") } })
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
    var duration by rememberSaveable { mutableStateOf(program?.durationWeeks?.toString().orEmpty()) }
    var showPhases by rememberSaveable { mutableStateOf(false) }
    var phases by rememberSaveable {
        mutableStateOf(ArrayList(program?.completionCycle?.phases.orEmpty().flatMap { listOf(it.through.toString(), it.name, it.guidance) }))
    }
    val cyclePhases = phases.chunked(3).mapNotNull { row ->
        positiveInt(row[0])?.takeIf { row[1].isNotBlank() }?.let { CyclePhase(it, row[1].trim(), row[2].trim()) }
    }
    val phasesValid = cyclePhases.size * 3 == phases.size && cyclePhases.zipWithNext().all { (a, b) -> a.through < b.through }
    val weeks = positiveInt(duration)
    val isValid = title.isNotBlank() && dateOrNull(anchor) != null && (duration.isBlank() || weeks != null) && (fixed ||
        (if (legacy) positiveInt(interval) != null else times != null && phasesValid &&
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
        Field(anchor, "Дата начала", { anchor = it }, error = dateOrNull(anchor) == null)
        Field(duration, "Длительность, недель", { duration = it }, keyboard = KeyboardType.Number,
            error = duration.isNotBlank() && weeks == null)
        if (!fixed) {
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
                TextButton(onClick = { showPhases = !showPhases }) {
                    Text(if (showPhases) "Скрыть фазы" else "Фазы цикла · ${phases.size / 3}")
                }
                if (showPhases) {
                    phases.chunked(3).forEachIndexed { index, values ->
                        CardBlock {
                            fun change(field: Int, value: String) {
                                phases = ArrayList(phases).apply { set(index * 3 + field, value) }
                            }
                            Field(values[0], "До занятия №", { change(0, it) }, keyboard = KeyboardType.Number)
                            Field(values[1], "Название фазы", { change(1, it) })
                            Field(values[2], "Указания", { change(2, it) }, singleLine = false)
                            TextButton(onClick = { phases = ArrayList(phases.filterIndexed { i, _ -> i / 3 != index }) }) { Text("Убрать фазу") }
                        }
                    }
                    if (!phasesValid) InlineStatus("Укажите название и возрастающий номер последнего занятия каждой фазы.", true)
                    OutlinedButton(onClick = { phases = ArrayList(phases + listOf("", "", "")) }) { Text("Добавить фазу") }
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
                visitsPerWeek = requireNotNull(times), visitDays = preferred, targetSessions = sessions, phases = cyclePhases)
            save(initial.copy(
                title = title.trim(),
                description = description.trim(),
                caution = caution.trim(),
                enabled = enabled,
                cycle = !fixed,
                anchor = dateOrNull(anchor)?.toString() ?: LocalDate.now().toString(),
                interval = positiveInt(interval) ?: 2,
                participantIds = people,
                completionCycle = completion,
                durationWeeks = weeks,
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
        Field(notes, "Примечания к дню", { notes = it }, singleLine = false)
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
    var edits by rememberPersonalSets()
    var reset by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    val baseline = plan.individual[id]?.takeIf { id !in reset } ?: plan.sets
    val initial = remember(id, baseline) { baseline.map { PlanSetDraft(newId(), it) } }
    val rows = edits[id] ?: initial
    val timed = plan.exercise.tracksDuration
    val valid = edits.values.all { it.plansOrNull(timed) != null }
    EditorSheet(stringResource(R.string.personal_load), close, footer = {
        PlanEditorActions(close, stringResource(R.string.save), valid, "save_personal_plan") {
            save(plan.copy(individual = (plan.individual - reset.toSet()) + edits.mapValues { requireNotNull(it.value.plansOrNull(timed)) }))
        }
    }) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            profiles.forEach { profile -> FilterChip(id == profile.id, { id = profile.id }, label = { Text(profile.name) }) }
        }
        key(id) {
            PlanSets(rows, plan.exercise, SetPlan(repsMin = plan.exercise.repsMin, repsMax = plan.exercise.repsMax,
                weightKnown = !plan.exercise.tracksDuration, repsKnown = !plan.exercise.tracksDuration)) {
                edits = edits + (id to it)
                reset = ArrayList(reset - id)
            }
        }
        if (!valid && rows.plansOrNull(timed) != null) InlineStatus("Проверьте незаполненные поля у других участников.", true)
        if (id in plan.individual && id !in reset || id in edits) TextButton(onClick = {
            edits = edits - id
            reset = ArrayList(reset + id)
        }) { Text(stringResource(R.string.reset_individual)) }
    }
}

@Composable
private fun weekday(day: DayOfWeek): String {
    val labels = listOf(stringResource(R.string.mon), stringResource(R.string.tue), stringResource(R.string.wed), stringResource(R.string.thu), stringResource(R.string.fri), stringResource(R.string.sat), stringResource(R.string.sun))
    return labels[day.ordinal]
}
