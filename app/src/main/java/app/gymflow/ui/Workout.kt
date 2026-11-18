@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package app.gymflow.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.gymflow.GymModel
import app.gymflow.R
import app.gymflow.data.Library
import app.gymflow.data.Preferences
import app.gymflow.domain.*
import kotlinx.coroutines.launch

private enum class Sheet { ADD, REPLACE, PERSON, SET, METRICS, FINISH, FINISH_PERSON, ASSIGN, DAY }

@Composable
fun Workout(
    session: Session, library: Library, prefs: Preferences, model: GymModel, initialProfile: String?, reviewId: String?,
    showDays: Boolean, leave: () -> Unit, repeat: () -> Unit, openDay: (String, String) -> Unit, reviewDay: (String, String) -> Unit,
) {
    var selected by rememberSaveable(session.id) {
        mutableStateOf(initialProfile ?: session.defaultWorkout.profile.id)
    }
    var openDaysOnEntry by rememberSaveable(session.id) { mutableStateOf(showDays) }
    var pinnedId by rememberSaveable(session.id) { mutableStateOf<String?>(null) }
    val cursors = remember(session.id) { mutableStateMapOf<String, String>() }
    val chosen = session.allWorkouts.find { it.id == reviewId } ?: session.workout(selected) ?: session.workouts.first()
    val profileId = chosen.profile.id
    Column(Modifier.fillMaxSize().statusBarsPadding().testTag("workout")) {
        key(chosen.id) {
            val initial = chosen.exercises.indexOfFirst { it.id == cursors[chosen.id] }.takeIf { it >= 0 } ?: chosen.currentIndex
            val pager = rememberPagerState(initialPage = initial) { chosen.exercises.size.coerceAtLeast(1) }
            ParticipantScreen(session, chosen, pager, library, prefs, model, pinnedId == profileId,
                { pinnedId = if (pinnedId == profileId) null else profileId }, leave, repeat, reviewId != null, openDay, reviewDay,
                openDaysOnEntry, { openDaysOnEntry = false },
                choose = { id, exerciseId ->
                    chosen.exercises.getOrNull(pager.settledPage)?.let { cursors[chosen.id] = it.id }
                    if (exerciseId != null) session.workout(id)?.let { cursors[it.id] = exerciseId }
                    if (id != profileId) pinnedId = null
                    selected = id
                },
                rememberCursor = { id -> cursors[chosen.id] = id },
            )
        }
    }
}

@Composable
private fun ColumnScope.ParticipantScreen(
    session: Session, chosen: ParticipantWorkout, pager: PagerState, library: Library, prefs: Preferences,
    model: GymModel, pinned: Boolean, togglePin: () -> Unit, leave: () -> Unit, repeat: () -> Unit,
    reviewing: Boolean, openDay: (String, String) -> Unit, reviewDay: (String, String) -> Unit,
    openDaysOnEntry: Boolean, daysOpened: () -> Unit,
    choose: (String, String?) -> Unit, rememberCursor: (String) -> Unit,
) {
    val coroutine = rememberCoroutineScope()
    var onScreen by remember { mutableStateOf(true) }
    DisposableEffect(Unit) { onDispose { onScreen = false } }
    val latestSession by rememberUpdatedState(session)
    val chooseCurrent by rememberUpdatedState(choose)
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    var menu by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<String?>(null) }
    var extraSet by remember { mutableStateOf<SetEntry?>(null) }
    var correcting by rememberSaveable { mutableStateOf(reviewing) }
    var moveToLast by remember { mutableStateOf<Int?>(null) }
    var targets by remember { mutableStateOf(setOf(chosen.profile.id)) }
    var template by remember { mutableStateOf<WorkoutTemplate?>(null) }
    val personId = chosen.profile.id
    val profile = library.profiles.find { it.id == personId } ?: chosen.profile
    val exercises = chosen.exercises
    val entry = exercises.getOrNull(pager.currentPage)
    val athlete = entry?.athletes?.single()
    val active = session.status == SessionStatus.ACTIVE && !reviewing
    val editable = active && chosen.canEdit
    val canCorrect = session.completedWorkouts(personId).any { it.id == chosen.id }
    val completed = chosen.completed
    val total = completed + chosen.remaining
    val program = workoutProgram(library.programs, session, chosen)
    val day = program?.days?.find { it.id == chosen.templateId }
    val close = { sheet = null; extraSet = null }

    fun open(next: Sheet) {
        menu = false
        more = false
        if (next == Sheet.ADD) targets = setOf(personId)
        if (next == Sheet.PERSON || next == Sheet.ASSIGN) template = null
        sheet = next
    }
    LaunchedEffect(openDaysOnEntry) {
        if (openDaysOnEntry) {
            open(Sheet.DAY)
            daysOpened()
        }
    }
    fun edit(change: (ParticipantWorkout) -> ParticipantWorkout) = model.session(session.id) {
        it.editWorkoutById(chosen.id, change)
    }
    fun status(value: WorkoutStatus) = edit { it.copy(status = value) }
    fun record(exerciseId: String, result: SetEntry, rotate: Boolean) {
        var next: ParticipantWorkout? = null
        var detached = false
        model.session(session.id, onSaved = {
            close()
            if (onScreen && !detached) next?.let { chooseCurrent(it.profile.id, it.nextExerciseId()) }
        }) { current ->
            val activeWorkout = current.workout(personId)
            if (activeWorkout?.id != chosen.id || !activeWorkout.canEdit) {
                detached = true
                current
            } else {
                val updated = current.editAthlete(exerciseId, personId) { row ->
                    row.copy(sets = row.sets.map { if (it.id == result.id) result else it })
                }
                val located = updated.editWorkout(personId) { it.copy(currentExerciseId = exerciseId) }
                if (rotate && prefs.rotation && !pinned && result.status == SetStatus.COMPLETED) {
                    next = nextWorkout(located, personId)
                    next?.let { target ->
                        located.editWorkout(target.profile.id) { it.copy(currentExerciseId = target.nextExerciseId()) }
                    } ?: located
                } else located
            }
        }
    }

    LaunchedEffect(pager.settledPage, exercises.getOrNull(pager.settledPage)?.id) {
        val id = exercises.getOrNull(pager.settledPage)?.id ?: return@LaunchedEffect
        rememberCursor(id)
        if (editable && pager.settledPage != chosen.currentIndex) {
            model.session(session.id) { value ->
                value.editWorkoutById(chosen.id) { row ->
                    if (row.exercises.any { it.id == id }) row.copy(currentExerciseId = id) else row
                }
            }
        }
    }
    LaunchedEffect(exercises.size, moveToLast) {
        val expected = moveToLast
        if (expected != null && exercises.size >= expected) {
            pager.animateScrollToPage(exercises.lastIndex)
            moveToLast = null
        }
    }

    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(chosen.templateTitle ?: session.title, maxLines = 2, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f, fill = false))
                if (program != null && program.days.isNotEmpty()) {
                    IconButton(onClick = { open(Sheet.DAY) }, modifier = Modifier.size(36.dp).testTag("title_switch_day")) {
                        Symbol(R.drawable.ic_program, description = stringResource(R.string.switch_day), modifier = Modifier.size(20.dp))
                    }
                }
            }
        },
        navigationIcon = { IconButton(onClick = leave) { Symbol(R.drawable.ic_back, description = stringResource(R.string.back)) } },
        actions = {
            if (program != null && program.days.isNotEmpty()) {
                IconButton(onClick = { open(Sheet.DAY) }, modifier = Modifier.testTag("switch_day")) {
                    Symbol(R.drawable.ic_program, description = stringResource(R.string.switch_day))
                }
            }
            if (active) {
                if (editable) IconButton(onClick = { open(Sheet.ADD) }, modifier = Modifier.testTag("quick_add")) {
                    Symbol(R.drawable.ic_add, description = stringResource(R.string.add_exercise))
                }
                IconButton(onClick = { menu = true }, modifier = Modifier.testTag("workout_menu")) {
                    Symbol(R.drawable.ic_more, description = stringResource(R.string.workout_actions))
                }
                DropdownMenu(menu, { menu = false }) {
                    if (program != null && program.days.isNotEmpty()) {
                        Action(stringResource(R.string.switch_day), "menu_switch_day") { open(Sheet.DAY) }
                    }
                    Action(stringResource(R.string.add_person), "add_person") { open(Sheet.PERSON) }
                    if (editable && chosen.completed == 0 && exercises.isEmpty()) {
                        Action(stringResource(R.string.assign_program), "assign_program") { open(Sheet.ASSIGN) }
                    }
                    if (session.workouts.size > 1) {
                        Action(stringResource(if (pinned) R.string.unpin_person else R.string.pin_person), "pin_person") {
                            menu = false
                            togglePin()
                        }
                    }
                    Action(stringResource(R.string.workout_details)) { open(Sheet.METRICS) }
                    if (canCorrect) Action(stringResource(R.string.correct_results), "correct_results") { menu = false; correcting = true }
                    if (editable) {
                        Action(stringResource(R.string.finish_person), "finish_person") { open(Sheet.FINISH_PERSON) }
                        Action(stringResource(R.string.pause_person)) { menu = false; status(WorkoutStatus.PAUSED) }
                        Action(stringResource(R.string.skip_person_workout)) { menu = false; status(WorkoutStatus.SKIPPED) }
                        if (session.workouts.size > 1) Action(stringResource(R.string.detach_person)) { menu = false; status(WorkoutStatus.CANCELLED) }
                    }
                    if (chosen.status in setOf(WorkoutStatus.PAUSED, WorkoutStatus.SKIPPED, WorkoutStatus.CANCELLED)) {
                        Action(stringResource(R.string.resume_person)) { menu = false; status(WorkoutStatus.ACTIVE) }
                    }
                    if (editable && exercises.isNotEmpty()) {
                        Action(stringResource(R.string.save_personal_plan)) {
                            menu = false
                            model.savePersonalPlan(chosen, program, day, session.title)
                        }
                    }
                    Action(stringResource(R.string.finish_group), "finish_group") { open(Sheet.FINISH) }
                }
            } else {
                IconButton(onClick = { menu = true }) { Symbol(R.drawable.ic_more, description = stringResource(R.string.workout_actions)) }
                DropdownMenu(menu, { menu = false }) {
                    if (program != null && program.days.isNotEmpty()) {
                        Action(stringResource(R.string.switch_day), "menu_switch_day") { open(Sheet.DAY) }
                    }
                    Action(stringResource(R.string.read_metrics)) { open(Sheet.METRICS) }
                    if (canCorrect && !correcting) Action(stringResource(R.string.correct_results), "correct_results") { menu = false; correcting = true }
                    if (!reviewing && session.status == SessionStatus.COMPLETED) Action(stringResource(R.string.repeat_workout), "repeat_workout") {
                        menu = false
                        repeat()
                    }
                }
            }
        },
        windowInsets = WindowInsets(0, 0, 0, 0),
    )
    if (!reviewing && session.workouts.size > 1) {
        ParticipantTabs(session.workouts, personId, pager.currentPage, pinned) { choose(it, null) }
    }
    Column(Modifier.padding(horizontal = 18.dp).padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        if (!editable) Muted(workoutStatus(chosen.status))
        if (reviewing) Muted(profile.name)
        if (canCorrect && correcting) Muted(stringResource(R.string.correct_results))
        if (entry != null) {
            Text(stringResource(R.string.exercise_progress, pager.currentPage + 1, exercises.size),
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        }
        LinearProgressIndicator(progress = { if (total == 0) 0f else completed.toFloat() / total }, modifier = Modifier.fillMaxWidth().height(5.dp))
    }
    HorizontalPager(
        state = pager, userScrollEnabled = sheet == null && !menu && !more && exercises.size > 1,
        key = { exercises.getOrNull(it)?.id ?: "empty" },
        modifier = Modifier.weight(1f).testTag("exercise_pager"),
    ) { page ->
        val current = exercises.getOrNull(page)
        if (current == null) {
            ScreenColumn {
                Text(stringResource(R.string.empty_workout), style = MaterialTheme.typography.headlineLarge)
                Muted(stringResource(R.string.empty_workout_body))
                if (editable) {
                    Button(onClick = { open(Sheet.ADD) }, modifier = Modifier.fillMaxWidth().testTag("add_exercise")) {
                        Symbol(R.drawable.ic_add)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.add_exercise))
                    }
                    if (library.programs.any { it.days.isNotEmpty() }) {
                        OutlinedButton(onClick = { open(Sheet.ASSIGN) }) { Text(stringResource(R.string.assign_program)) }
                    }
                }
            }
        } else {
            val row = current.athletes.single()
            val focus = row.sets.getOrNull(row.next)?.id.takeIf { editable }
            ExerciseCard(current, latestSession, profile, row, library, editable, focus, correcting = canCorrect && correcting,
                actions = {
                    if (page == pager.currentPage) {
                        Box {
                            FilledTonalIconButton(onClick = { more = true }, modifier = Modifier.testTag("exercise_menu")) {
                                Symbol(R.drawable.ic_more, description = stringResource(R.string.exercise_actions))
                            }
                            DropdownMenu(more, { more = false }) {
                                Action(stringResource(R.string.replace_exercise)) { open(Sheet.REPLACE) }
                                Action(stringResource(if (row.skipped) R.string.restore_exercise else R.string.skip_exercise), "skip_exercise") {
                                    more = false
                                    edit { w -> w.editExercise(current.id) { e -> e.copy(athletes = listOf(e.athletes.single().let { it.copy(skipped = !it.skipped) })) } }
                                }
                                if (page > 0) Action(stringResource(R.string.move_earlier)) { more = false; edit { it.moveExercise(current.id, -1) } }
                                if (page < exercises.lastIndex) {
                                    Action(stringResource(R.string.move_later)) { more = false; edit { it.moveExercise(current.id, 1) } }
                                    Action(stringResource(R.string.postpone)) { more = false; edit { it.moveExercise(current.id, exercises.size) } }
                                }
                            }
                        }
                    }
                },
                toggle = { set ->
                    if (set.status != SetStatus.COMPLETED && (!set.proposed.weightKnown || !set.proposed.repsKnown)) {
                        editing = set.id
                        open(Sheet.SET)
                    } else record(current.id, set.toggle(), rotate = true)
                },
                edit = { set -> editing = set.id; open(Sheet.SET) },
                rir = { value -> model.session(session.id) {
                    it.editAthlete(current.id, personId) { a -> a.copy(rir = value.takeUnless { a.rir == value }) }
                } },
                addSet = { if (canCorrect && correcting) {
                    val plan = row.sets.lastOrNull()?.plan ?: SetPlan(prefs.initialWeight, prefs.minReps, prefs.maxReps)
                    extraSet = SetEntry(plan = plan.copy(warmup = false))
                    editing = extraSet?.id
                    open(Sheet.SET)
                } else model.session(session.id) {
                    it.editAthlete(current.id, personId) { a ->
                        val plan = a.sets.lastOrNull()?.plan ?: SetPlan(prefs.initialWeight, prefs.minReps, prefs.maxReps)
                        a.copy(sets = a.sets + SetEntry(plan = plan.copy(warmup = false)))
                    }
                } },
            )
        }
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { coroutine.launch { pager.animateScrollToPage(pager.currentPage - 1) } },
                enabled = pager.currentPage > 0, modifier = Modifier.weight(1f).heightIn(min = 50.dp).testTag("previous_exercise")) {
                Text(stringResource(R.string.back))
            }
            val hasNext = pager.currentPage < exercises.lastIndex
            Button(onClick = {
                when {
                    hasNext -> coroutine.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                    editable -> open(if (session.workouts.size == 1) Sheet.FINISH else Sheet.FINISH_PERSON)
                    active -> open(Sheet.FINISH)
                    else -> leave()
                }
            }, modifier = Modifier.weight(1f).heightIn(min = 50.dp).testTag(if (hasNext) "next_exercise" else "finish_workout")) {
                Text(stringResource(when {
                    hasNext -> R.string.next
                    editable && session.workouts.size > 1 -> R.string.finish_person
                    active -> R.string.finish
                    else -> R.string.close
                }))
            }
        }
    }

    when (sheet) {
        Sheet.ADD, Sheet.REPLACE -> {
            val replace = sheet == Sheet.REPLACE
            ExerciseEditor(
                stringResource(if (replace) R.string.replace_exercise else R.string.add_exercise), library, prefs, close = close,
                options = {
                    val available = session.workouts.filter { it.canEdit }
                    if (!replace && available.size > 1) {
                        Muted(stringResource(R.string.add_for_people))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            available.forEach { member ->
                                val id = member.profile.id
                                FilterChip(id in targets, {
                                    if (id !in targets) targets = targets + id
                                    else if (targets.size > 1) targets = targets - id
                                }, label = { Text(member.profile.name) })
                            }
                        }
                    }
                },
            ) { plan, created ->
                val oldSize = exercises.size
                val selection = targets
                model.session(session.id, listOfNotNull(created), onSaved = {
                    if (!replace && personId in selection && oldSize > 0) moveToLast = oldSize + 1
                    close()
                }) { value ->
                    if (replace && entry != null) value.editWorkout(personId) { it.replaceExercise(entry.id, plan) }
                    else value.addExercise(plan, value.workouts.filter { it.canEdit && it.profile.id in selection }.map { it.profile.id }.toSet())
                }
            }
        }
        Sheet.PERSON -> PersonEditor(library, session, close, options = {
            TemplatePicker(library.programs, template) { template = it }
        }) { added, permanent ->
            val selectedTemplate = template
            model.session(session.id, if (permanent) listOf(added) else emptyList(), onSaved = {
                close()
                choose(added.id, null)
            }) { it.addParticipant(added, selectedTemplate) }
        }
        Sheet.ASSIGN -> EditorSheet(stringResource(R.string.assign_program), close) {
            TemplatePicker(library.programs, template) { template = it }
            Button(onClick = { val plan = template; model.session(session.id, onSaved = close) {
                it.editWorkout(personId) { w -> if (w.exercises.isEmpty()) w.withTemplate(plan) else w }
            } }, enabled = template != null, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.save)) }
        }
        Sheet.SET -> if (entry != null) {
            val original = athlete?.sets?.find { it.id == editing }
            val set = extraSet ?: original
            if (set != null) SetEditor(set, entry.exercise, prefs, close, newResult = extraSet != null) { result ->
                if (canCorrect && correcting) model.correctResult(session.id, chosen.id, entry.id, original, result, onSaved = close)
                else record(entry.id, result, rotate = true)
            }
        }
        Sheet.METRICS -> if (active) {
            MetricsEditor(session, profile, close) { metrics, notes ->
                model.session(session.id, onSaved = close) { it.copy(notes = notes).editWorkout(personId) { w -> w.copy(metrics = metrics) } }
            }
        } else MetricsSummary(session, profile, close, chosen.metrics)
        Sheet.DAY -> if (program != null) {
            val options = remember(library.sessions, session, personId) { workoutDays(library.sessions, session, personId) }
            WorkoutDaySheet(program, profile, chosen, options, close, start = { dayId ->
                model.switchDay(session.id, personId, dayId) { id ->
                    if (onScreen) {
                        close()
                        if (!active || id != session.id) openDay(id, personId)
                    }
                }
            }, review = { item -> close(); reviewDay(item.session.id, item.workout.id) })
        }
        Sheet.FINISH_PERSON -> AlertDialog(
            onDismissRequest = close,
            title = { Text(stringResource(R.string.finish_person)) },
            text = { Text(stringResource(R.string.individual_finish_message, profile.name, chosen.remaining)) },
            confirmButton = { Button(onClick = { model.session(session.id, onSaved = close) {
                it.editWorkout(personId) { w -> w.copy(status = WorkoutStatus.COMPLETED) }
            } }, modifier = Modifier.testTag("confirm_finish_person")) { Text(stringResource(R.string.finish)) } },
            dismissButton = { TextButton(onClick = close) { Text(stringResource(R.string.cancel)) } },
        )
        Sheet.FINISH -> AlertDialog(
            onDismissRequest = close, title = { Text(stringResource(R.string.finish_prompt)) },
            text = { Text(stringResource(R.string.group_finish_message, session.workouts.sumOf { it.completed })) },
            confirmButton = { Button(onClick = { model.session(session.id, onSaved = { close(); leave() }) { it.finish() } },
                modifier = Modifier.testTag("confirm_finish")) { Text(stringResource(R.string.finish)) } },
            dismissButton = { TextButton(onClick = close) { Text(stringResource(R.string.cancel)) } },
        )
        null -> Unit
    }
}

@Composable
private fun ParticipantTabs(workouts: List<ParticipantWorkout>, selected: String, page: Int, pinned: Boolean, choose: (String) -> Unit) {
    PrimaryScrollableTabRow(
        selectedTabIndex = workouts.indexOfFirst { it.profile.id == selected }.coerceAtLeast(0), edgePadding = 0.dp,
        containerColor = MaterialTheme.colorScheme.surface, modifier = Modifier.testTag("participants_tabs"),
    ) {
        workouts.forEachIndexed { index, item ->
            val current = item.profile.id == selected
            val position = if (item.exercises.isEmpty()) 0 else if (current) page + 1 else item.currentIndex + 1
            Tab(selected = current, onClick = { choose(item.profile.id) }, modifier = Modifier.testTag("participant_$index")) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(item.profile.name, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                    Muted(if (item.canEdit) stringResource(R.string.participant_progress, position, item.exercises.size, item.remaining)
                        else workoutStatus(item.status))
                    if (current && pinned) Muted(stringResource(R.string.person_pinned))
                }
            }
        }
    }
}

@Composable
private fun Action(label: String, tag: String? = null, click: () -> Unit) {
    DropdownMenuItem(text = { Text(label) }, onClick = click, modifier = if (tag == null) Modifier else Modifier.testTag(tag))
}
