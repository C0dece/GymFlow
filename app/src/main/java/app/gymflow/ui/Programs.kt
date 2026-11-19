@file:OptIn(ExperimentalLayoutApi::class)

package app.gymflow.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
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
    var currentId by rememberSaveable { mutableStateOf<String?>(null) }
    var currentDay by rememberSaveable { mutableStateOf<String?>(null) }
    var editProgram by rememberSaveable { mutableStateOf(false) }
    var editDay by rememberSaveable { mutableStateOf(false) }
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
            Button(onClick = { editProgram = true }, modifier = Modifier.fillMaxWidth().testTag("new_program")) {
                Symbol(R.drawable.ic_add)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.new_program))
            }
            if (library.programs.none { it.id == foundationProgramId }) {
                OutlinedButton(onClick = model::addFoundationProgram, modifier = Modifier.testTag("add_foundation_program")) {
                    Text("Добавить «Силовой фундамент 8»")
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
                    Text(item.title, style = MaterialTheme.typography.titleLarge)
                    Muted(stringResource(R.string.days_count, item.days.size))
                    InlineStatus(if (item.enabled) stringResource(R.string.schedule_on) else stringResource(R.string.schedule_off))
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
        } else {
            TextButton(onClick = ::back) { Symbol(R.drawable.ic_back); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.back)) }
            Text(day?.title ?: program.title, style = MaterialTheme.typography.headlineLarge)
            if (program.caution.isNotBlank()) InlineStatus(program.caution, warning = true)
            if (day == null) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { editProgram = true }) { Text(stringResource(R.string.schedule_and_people)) }
                    Button(onClick = { editDay = true }) { Text(stringResource(R.string.add_day)) }
                }
                program.days.forEachIndexed { index, item ->
                    CardBlock {
                        TextButton(onClick = { currentDay = item.id }) { Text(item.title, style = MaterialTheme.typography.titleLarge) }
                        Muted(if (program.cycle) stringResource(R.string.cycle_day, index + 1) else weekday(item.weekday))
                        Muted(stringResource(R.string.exercise_count, item.exercises.size))
                        if (program.cycle && program.days.size > 1) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (index > 0) TextButton(onClick = { model.moveDay(program.id, item.id, -1) }) {
                                    Text(stringResource(R.string.move_up))
                                }
                                if (index < program.days.lastIndex) TextButton(onClick = { model.moveDay(program.id, item.id, 1) }) {
                                    Text(stringResource(R.string.move_down))
                                }
                            }
                        }
                    }
                }
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { preview(day.id) }) { Text(stringResource(R.string.view_plan)) }
                    OutlinedButton(onClick = { editDay = true }) { Text(stringResource(R.string.edit_day)) }
                    Button(onClick = { editPlan = -1 }, modifier = Modifier.testTag("program_add_exercise")) {
                        Text(stringResource(R.string.add_exercise))
                    }
                }
                OutlinedButton(onClick = { blockId = "new" }, modifier = Modifier.testTag("add_training_block")) { Text("Добавить блок") }
                day.blocks.forEachIndexed { index, block ->
                    CardBlock {
                        Text(block.name)
                        Muted(block.requirement.label + if (block.enabled) "" else " · Отключён")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { blockId = block.id }) { Text("Изменить блок") }
                            if (index > 0) TextButton(onClick = {
                                model.changeDay(program.id, day.id) { current ->
                                    current.copy(blocks = current.blocks.toMutableList().apply { add(index - 1, removeAt(index)) })
                                }
                            }) { Text(stringResource(R.string.move_up)) }
                        }
                    }
                }
                day.exercises.forEachIndexed { index, plan ->
                    CardBlock {
                        Text(library.exercises.find { it.id == plan.exercise.id }?.name ?: plan.exercise.name,
                            style = MaterialTheme.typography.titleMedium)
                        day.blocks.find { it.id == plan.blockId }?.let { Muted(it.name) }
                        val sample = plan.sets.firstOrNull { it.purpose == SetRole.WORK } ?: plan.sets.firstOrNull()
                        if (sample != null) {
                            Muted(stringResource(R.string.plan_details, plan.sets.size,
                                if (sample.maxEffort) stringResource(R.string.max_effort) else if (sample.repsKnown) repRange(sample.repsMin, sample.repsMax) else "?",
                                if (sample.weightKnown) number(sample.weight) else "?", unitText(plan.exercise.unit)))
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
                            TextButton(onClick = {
                                model.changeDay(program.id, day.id) { it.copy(exercises = it.exercises.filterIndexed { i, _ -> i != index }) }
                            }) { Text(stringResource(R.string.remove)) }
                        }
                    }
                }
            }
        }
    }

    if (editProgram) {
        ProgramEditor(program, library.profiles, { editProgram = false }) {
            currentId = it.id
            model.save(it)
            editProgram = false
        }
    }
    if (editDay && program != null) {
        DayEditor(day, program.cycle, { editDay = false }) { updated ->
            model.edit(program.id) { doc ->
                val p = doc as Program
                p.copy(days = if (day == null) p.days + updated else p.days.map { if (it.id == day.id) updated else it })
            }
            currentDay = updated.id
            editDay = false
        }
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
    var fixed by rememberSaveable { mutableStateOf(program?.cycle != true) }
    var enabled by rememberSaveable { mutableStateOf(program?.enabled ?: true) }
    var anchor by rememberSaveable { mutableStateOf(program?.anchor ?: LocalDate.now().toString()) }
    var interval by rememberSaveable { mutableStateOf((program?.interval ?: 2).toString()) }
    var legacy by rememberSaveable { mutableStateOf(program?.cycle == true && program.completionCycle == null) }
    var visits by rememberSaveable { mutableStateOf((program?.completionCycle?.visitsPerWeek ?: 3).toString()) }
    var target by rememberSaveable { mutableStateOf(program?.completionCycle?.targetSessions?.toString().orEmpty()) }
    var preferred by remember { mutableStateOf(program?.completionCycle?.visitDays.orEmpty()) }
    var caution by rememberSaveable { mutableStateOf(program?.caution.orEmpty()) }
    val times = visits.toIntOrNull()?.takeIf { it in 1..7 }
    val sessions = positiveInt(target)
    var people by remember { mutableStateOf(program?.participantIds.orEmpty()) }
    val isValid = title.isNotBlank() && (fixed || dateOrNull(anchor) != null &&
        (if (legacy) positiveInt(interval) != null else times != null &&
            (preferred.isEmpty() || preferred.size == times) && (target.isBlank() || sessions != null)))

    EditorSheet(if (program == null) stringResource(R.string.new_program) else stringResource(R.string.schedule_and_people), close) {
        Field(title, stringResource(R.string.program_name), { title = it }, Modifier.fillMaxWidth().testTag("program_name"))
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
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Switch(enabled, { enabled = it })
            Text(stringResource(R.string.schedule_on))
        }
        Field(caution, "Ограничения программы", { caution = it }, singleLine = false)
        if (profiles.isNotEmpty()) {
            Text(stringResource(R.string.participants), style = MaterialTheme.typography.titleMedium)
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
            save(initial.copy(title = title.trim(), enabled = enabled, cycle = !fixed,
                anchor = dateOrNull(anchor)?.toString() ?: LocalDate.now().toString(),
                interval = positiveInt(interval) ?: 2, participantIds = people, completionCycle = completion, caution = caution.trim()))
        }, enabled = isValid, modifier = Modifier.fillMaxWidth().testTag("save_program")) { Text(stringResource(R.string.save)) }
    }
}

@Composable
private fun DayEditor(day: ProgramDay?, cycle: Boolean, close: () -> Unit, save: (ProgramDay) -> Unit) {
    var title by rememberSaveable { mutableStateOf(day?.title.orEmpty()) }
    var date by rememberSaveable { mutableStateOf(day?.weekday ?: LocalDate.now().dayOfWeek) }
    EditorSheet(if (day == null) stringResource(R.string.add_day) else stringResource(R.string.edit_day), close) {
        Field(title, stringResource(R.string.day_name), { title = it }, Modifier.fillMaxWidth().testTag("day_name"))
        if (!cycle) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DayOfWeek.entries.forEach { option -> FilterChip(date == option, { date = option }, label = { Text(weekday(option)) }) }
            }
        }
        Button(onClick = { save((day ?: ProgramDay(title = title.trim())).copy(title = title.trim(), weekday = date)) },
            enabled = title.isNotBlank(), modifier = Modifier.fillMaxWidth().testTag("save_day")) { Text(stringResource(R.string.save)) }
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
