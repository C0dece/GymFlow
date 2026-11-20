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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gymflow.GymModel
import app.gymflow.R
import app.gymflow.data.Library
import app.gymflow.data.Preferences
import app.gymflow.domain.*
import kotlinx.coroutines.launch

private enum class Sheet { ADD, REPLACE, PERSON, SET, METRICS, EDIT_METRICS, FINISH, FINISH_PERSON, ASSIGN, DAY, VOLUME, VARIATION, ADVICE, GUIDANCE, NOTES }

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
    var sheet by rememberSaveable { mutableStateOf<Sheet?>(null) }
    var menu by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var setError by rememberSaveable { mutableStateOf<String?>(null) }
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
    fun record(exerciseId: String, result: SetEntry, rotate: Boolean, expected: SetEntry) {
        var next: ParticipantWorkout? = null
        var detached = false
        model.session(session.id, onSaved = {
            close()
            if (onScreen && !detached) next?.let { chooseCurrent(it.profile.id, it.nextExerciseId()) }
        }, onError = { setError = it }) { current ->
            val activeWorkout = current.workout(personId)
            if (activeWorkout?.id != chosen.id || !activeWorkout.canEdit) {
                detached = true
                current
            } else {
                val exerciseEntry = activeWorkout.exercises.find { it.id == exerciseId }
                val originalSet = exerciseEntry?.athletes?.single()?.sets?.find { it.id == result.id }
                check(originalSet == expected) { "Подход уже изменился. Откройте его повторно." }
                val base = if (result.plan != originalSet.plan || result.status == SetStatus.CANCELLED) {
                    current.editWorkoutById(chosen.id) { it.withOriginalPlan() }
                } else current
                val updated = base.editAthlete(exerciseId, personId) { row ->
                    row.copy(sets = row.sets.map { if (it.id == result.id) result else it })
                }
                val rest = exerciseEntry.plan.restSeconds?.takeIf { exerciseEntry.plan.autoRest &&
                    result.status == SetStatus.COMPLETED && originalSet.status == SetStatus.PLANNED }
                val located = updated.editWorkout(personId) {
                    it.copy(currentExerciseId = exerciseId, restUntil = rest?.let { seconds ->
                        java.time.Instant.now().plusSeconds(seconds.toLong()).toString()
                    } ?: it.restUntil)
                }
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
            Text(chosen.templateTitle ?: session.title, maxLines = 2, style = MaterialTheme.typography.titleMedium,
                overflow = TextOverflow.Ellipsis)
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
                    Action("Заметки к тренировке", "workout_notes") { open(Sheet.NOTES) }
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
                    if (chosen.templateNotes.isNotBlank() || chosen.trainingBlocks.any { it.enabled && it.guidance.isNotBlank() }) {
                        Action("Указания к тренировке") { open(Sheet.GUIDANCE) }
                    }
                    if (editable) Action("Объём тренировки", "training_volume") { open(Sheet.VOLUME) }
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
                    Action("Заметки к тренировке", "workout_notes") { open(Sheet.NOTES) }
                    if (program != null && program.days.isNotEmpty()) {
                        Action(stringResource(R.string.switch_day), "menu_switch_day") { open(Sheet.DAY) }
                    }
                    Action(stringResource(R.string.read_metrics)) { open(Sheet.METRICS) }
                    if (canCorrect) Action("Изменить показатели и примечания") { open(Sheet.EDIT_METRICS) }
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
    val context: @Composable (ExerciseEntry?, Int) -> Unit = { current, page ->
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (!editable) Muted(workoutStatus(chosen.status))
            if (reviewing) Muted(profile.name)
            if (canCorrect && correcting) Muted(stringResource(R.string.correct_results))
            if (current != null) {
                Text(stringResource(R.string.exercise_progress, page + 1, exercises.size),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
            }
            chosen.trainingBlocks.find { it.id == current?.plan?.blockId }?.let { Muted(it.name) }
            if (editable) RestTimer(chosen.restUntil, current?.plan?.restSeconds) { seconds ->
                edit { it.copy(restUntil = seconds?.let { value -> java.time.Instant.now().plusSeconds(value.toLong()).toString() }) }
            }
        }
    }
    LinearProgressIndicator(progress = { if (total == 0) 0f else completed.toFloat() / total },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp).height(4.dp))
    HorizontalPager(
        state = pager, userScrollEnabled = sheet == null && !menu && !more && exercises.size > 1,
        key = { exercises.getOrNull(it)?.id ?: "empty" },
        modifier = Modifier.weight(1f).testTag("exercise_pager"),
    ) { page ->
        val current = exercises.getOrNull(page)
        if (current == null) {
            ScreenColumn {
                if (!editable) context(null, page)
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
                header = { context(current, page) },
                actions = {
                    if (page == pager.currentPage) {
                        Box {
                            FilledTonalIconButton(onClick = { more = true }, modifier = Modifier.testTag("exercise_menu")) {
                                Symbol(R.drawable.ic_more, description = stringResource(R.string.exercise_actions))
                            }
                            DropdownMenu(more, { more = false }) {
                                Action(stringResource(R.string.replace_exercise)) { open(Sheet.REPLACE) }
                                if ((library.exercises.find { it.id == current.exercise.id } ?: current.exercise).movement?.familyId != null) {
                                    Action("Вариации семейства", "exercise_variations") { open(Sheet.VARIATION) }
                                }
                                Action("Проверить нагрузку", "training_advice") { open(Sheet.ADVICE) }
                                chosen.trainingBlocks.find { it.id == current.plan.blockId && it.requirement != PlanBlock.REQUIRED }?.let { block ->
                                    Action("Пропустить блок", "skip_training_block") {
                                        more = false
                                        edit { it.skipBlock(block.id) }
                                    }
                                }
                                Action("Убрать из текущего плана", "discard_exercise") {
                                    more = false
                                    edit { it.discardExercise(current.id) }
                                }
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
                    if (set.status != SetStatus.COMPLETED && (!set.proposed.weightKnown || !set.proposed.repsKnown || !current.exercise.movement?.measures.isNullOrEmpty() || current.exercise.cardio != null)) {
                        editing = set.id
                        open(Sheet.SET)
                    } else record(current.id, set.toggle(), rotate = true, expected = set)
                },
                edit = { set -> editing = set.id; open(Sheet.SET) },
                rir = { value ->
                    if (canCorrect && correcting) model.correctRir(session.id, chosen.id, current.id, row.rir,
                        value.takeUnless { row.rir == value })
                    else model.session(session.id) {
                        it.editAthlete(current.id, personId) { a -> a.copy(rir = value.takeUnless { a.rir == value }) }
                    }
                },
                addSet = { if (canCorrect && correcting) {
                    val plan = row.sets.lastOrNull()?.plan ?: SetPlan(prefs.initialWeight, prefs.minReps, prefs.maxReps)
                    extraSet = SetEntry(plan = plan.copy(warmup = false, role = SetRole.WORK))
                    editing = extraSet?.id
                    open(Sheet.SET)
                } else model.session(session.id) {
                    it.editWorkoutById(chosen.id, ParticipantWorkout::withOriginalPlan).editAthlete(current.id, personId) { a ->
                        val plan = a.sets.lastOrNull()?.plan ?: SetPlan(prefs.initialWeight, prefs.minReps, prefs.maxReps)
                        a.copy(sets = a.sets + SetEntry(plan = plan.copy(warmup = false, role = SetRole.WORK)))
                    }
                } },
            )
        }
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedIconButton(onClick = { coroutine.launch { pager.animateScrollToPage(pager.currentPage - 1) } },
                enabled = pager.currentPage > 0, modifier = Modifier.size(50.dp).testTag("previous_exercise")) {
                Symbol(R.drawable.ic_back, description = stringResource(R.string.back))
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
        Sheet.NOTES -> WorkoutNotesSheet(session, library, model, close)
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
                    else value.copy(workouts = value.workouts.map { row ->
                        if (row.canEdit && row.profile.id in selection) row.withOriginalPlan().addExercise(plan) else row
                    })
                }
            }
        }
        Sheet.PERSON -> PersonEditor(library, session, close, options = {
            TemplatePicker(library.programs, template) { template = it }
        }) { added, permanent ->
            val selectedTemplate = template
            model.addWorkoutParticipant(session.id, added, selectedTemplate, permanent) {
                close()
                choose(added.id, null)
            }
        }
        Sheet.ASSIGN -> EditorSheet(stringResource(R.string.assign_program), close) {
            TemplatePicker(library.programs, template) { template = it }
            Button(onClick = { val plan = template; model.session(session.id, onSaved = close) {
                it.editWorkout(personId) { w -> if (w.exercises.isEmpty()) w.withTemplate(plan) else w }
            } }, enabled = template != null, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.save)) }
        }
        Sheet.SET -> if (entry != null) {
            val original = remember(chosen.id, entry.id, editing) { athlete?.sets?.find { it.id == editing } }
            val set = extraSet ?: original
            val history = canCorrect && correcting
            if (set != null) SetEditor(set, entry.exercise, prefs, close, newResult = extraSet != null, history = history,
                orderActions = {
                    if (editable && original != null && athlete != null && !athlete.skipped) {
                        val index = athlete.sets.indexOfFirst { it.id == original.id }
                        fun changeOrder(offset: Int, copy: Boolean = false) {
                            model.session(session.id, onSaved = close, onError = { setError = it }) { current ->
                                val workout = current.workout(personId)
                                check(workout?.id == chosen.id && workout.canEdit) { "Тренировка уже изменилась." }
                                current.editWorkoutById(chosen.id, ParticipantWorkout::withOriginalPlan).editAthlete(entry.id, personId) { row ->
                                    val position = row.sets.indexOfFirst { it.id == original.id }
                                    check(row.sets.getOrNull(position) == original) { "Подход уже изменился." }
                                    check(copy || position + offset in row.sets.indices) { "Порядок подходов изменился." }
                                    row.copy(sets = row.sets.toMutableList().apply {
                                        if (copy) add(position + 1, SetEntry(plan = original.plan))
                                        else add(position + offset, removeAt(position))
                                    })
                                }
                            }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { changeOrder(-1) }, enabled = index > 0) { Text("Выше") }
                            TextButton(onClick = { changeOrder(1) }, enabled = index in 0 until athlete.sets.lastIndex) { Text("Ниже") }
                            TextButton(onClick = { changeOrder(0, copy = true) }, enabled = index >= 0) { Text("Копировать план") }
                        }
                    }
                }) { result ->
                if (canCorrect && correcting) model.correctResult(session.id, chosen.id, entry.id, original, result,
                    onError = { setError = it }, onSaved = close)
                else if (original != null) record(entry.id, result, rotate = true, expected = original)
            }
        }
        Sheet.GUIDANCE -> EditorSheet("Указания к тренировке", close) {
            if (chosen.templateNotes.isNotBlank()) Text(chosen.templateNotes)
            chosen.trainingBlocks.filter { it.enabled && it.guidance.isNotBlank() }.forEach { block ->
                SectionTitle(block.name)
                Muted(block.requirement.label)
                Text(block.guidance)
            }
        }
        Sheet.EDIT_METRICS -> if (canCorrect) {
            val original = remember(session.id, chosen.id) { session to chosen }
            MetricsEditor(original.first, profile, close, initial = original.second.metrics) { metrics, notes ->
                model.correctMetrics(library.scope, original.first, original.second, metrics, notes, { setError = it }, close)
            }
        }
        Sheet.METRICS -> if (active) {
            MetricsEditor(session, profile, close) { metrics, notes ->
                model.session(session.id, onSaved = close) { it.copy(notes = notes).editWorkout(personId) { w -> w.copy(metrics = metrics) } }
            }
        } else MetricsSummary(session, profile, close, chosen.metrics)
        Sheet.VOLUME -> VolumeSheet(chosen, close) { mode ->
            model.session(session.id, onSaved = close) { it.editWorkoutById(chosen.id) { row -> applyVolume(row, mode) } }
        }
        Sheet.VARIATION -> if (entry != null) VariationSheet(entry, chosen, library, close) { plan ->
            model.session(session.id, onSaved = close) { it.editWorkoutById(chosen.id) { row -> row.replaceExercise(entry.id, plan) } }
        }
        Sheet.ADVICE -> if (entry != null) AdviceSheet(entry, chosen, library, close, edit = {
            editing = it.id
            open(Sheet.SET)
        }, finish = {
            model.session(session.id, onSaved = close) { it.editWorkoutById(chosen.id) { row -> row.discardExercise(entry.id) } }
        }, variations = { open(Sheet.VARIATION) })
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
                it.editWorkout(personId) { w -> w.copy(status = WorkoutStatus.COMPLETED, restUntil = null) }
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
    setError?.let { error ->
        AlertDialog(onDismissRequest = { setError = null }, title = { Text("Сохранение не завершено") }, text = { Text(error) },
            confirmButton = { TextButton(onClick = { setError = null }) { Text("Понятно") } })
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
                Column(Modifier.widthIn(max = 240.dp).padding(horizontal = 12.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(item.profile.name, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
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
