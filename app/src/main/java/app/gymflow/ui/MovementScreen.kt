@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package app.gymflow.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.gymflow.GymModel
import app.gymflow.R
import app.gymflow.data.Library
import app.gymflow.domain.*
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun MovementScreen(library: Library, preferredProfile: String, model: GymModel, back: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var profileId by rememberSaveable { mutableStateOf(preferredProfile) }
    var familyId by rememberSaveable { mutableStateOf<String?>(null) }
    var editFamily by remember { mutableStateOf<Boolean?>(null) }
    var exerciseId by rememberSaveable { mutableStateOf<String?>(null) }
    var editExercise by remember { mutableStateOf(false) }
    var variation by remember { mutableStateOf(false) }
    var editTrack by remember { mutableStateOf(false) }
    var skillId by rememberSaveable { mutableStateOf<String?>(null) }
    val profiles = library.availableProfiles
    val person = profiles.find { it.id == profileId } ?: profiles.firstOrNull()
    val family = library.families.find { it.id == familyId }
    val skill = library.skills.find { it.id == skillId.takeIf { tab == 2 } }
    val exercise = library.exercises.find { it.id == exerciseId }
    val tracks = remember(person?.id, library.movementTracks) {
        library.movementTracks.filter { it.profileId == person?.id }.associateBy { it.exerciseId }
    }
    val track = tracks[exerciseId]
    val history by produceState(emptyList<MovementResult>(), person?.id, exercise, library.sessions) {
        value = withContext(Dispatchers.Default) {
            if (person == null || exercise == null) emptyList() else movementResults(library.sessions, person.id, exercise)
        }
    }
    val members = remember(library.exercises, family?.id) {
        library.exercises.filter { family != null && it.movement?.familyId == family.id && !it.archived }
    }
    val totals by produceState(emptyMap<String, Int>(), person?.id, members, library.sessions) {
        value = withContext(Dispatchers.Default) {
            if (person == null || members.isEmpty()) emptyMap() else {
                val since = LocalDate.now().minusDays(29).toString()
                val rows = library.sessions.filter { it.date >= since }.flatMap { it.completedWorkouts(person.id) }
                members.associate { item ->
                    item.id to rows.sumOf { workSets(it, setOf(item.id), setOf(SetStatus.COMPLETED)) }
                }
            }
        }
    }
    fun leave() {
        when {
            exerciseId != null -> exerciseId = null
            familyId != null -> familyId = null
            skill != null -> skillId = null
            else -> back()
        }
    }

    BackHandler(exerciseId != null || familyId != null || skill != null) { leave() }
    Scaffold(topBar = {
        TopAppBar(title = { BarTitle(exercise?.name ?: family?.name ?: skill?.name ?: "Движения") },
            navigationIcon = { IconButton(onClick = ::leave, modifier = Modifier.testTag("movement_back")) { Symbol(R.drawable.ic_back, description = stringResource(R.string.back)) } })
    }) { padding ->
        ScreenColumn(Modifier.padding(padding).consumeWindowInsets(padding).testTag("movement_screen")) {
            if (person != null && profiles.size > 1) Choice(person, profiles, { it.name }, { profileId = it.id }, "movement_profile")
            when {
                exercise != null -> {
                    val details = exercise.movement
                    val descriptor = listOfNotNull(details?.variation?.label, details?.quality?.label,
                        details?.equipment?.takeIf { it.isNotBlank() }, details?.gym?.takeIf { it.isNotBlank() })
                    if (descriptor.isNotEmpty()) Muted(descriptor.joinToString(" · "))
                    val parent = library.exercises.find { it.id == details?.parentId }
                    if (parent != null) Muted("Исходное упражнение: " + parent.name)
                    if (details?.limitations?.isNotBlank() == true) Muted(details.limitations)
                    if (exercise.notes.isNotBlank()) Muted(exercise.notes)
                    track?.limitations?.takeIf { it.isNotBlank() }?.let { Muted(it) }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { editExercise = true }) { Text("Изменить") }
                        if (details?.familyId != null) OutlinedButton(onClick = { variation = true }) { Text("Создать вариацию") }
                        if (person != null) Button(onClick = { editTrack = true }, modifier = Modifier.testTag("movement_track")) {
                            Text(track?.role?.label ?: "Настроить развитие")
                        }
                    }
                    track?.goal?.let { goal ->
                        val proof = history.find { it.supports(goal) }
                        CardBlock {
                            SectionTitle("Цель")
                            Muted(goalText(goal))
                            if (proof != null) {
                                Text("Подтверждено ${shortDate(proof.date)} · ${number(proof.value)}" + if (proof.isLowerBound) "+" else "")
                                if (track.role in setOf(MovementRole.ACTIVE, MovementRole.RELEARN)) {
                                    Muted("Можно продолжить развитие, перевести в поддержание или выбрать новую вариацию.")
                                    OutlinedButton(onClick = { model.saveMovementTrack(track.copy(role = MovementRole.MAINTENANCE)) {} }) {
                                        Text("Перевести в поддержание")
                                    }
                                    details?.familyId?.let { id ->
                                        TextButton(onClick = { familyId = id; exerciseId = null }) { Text("Другие вариации") }
                                    }
                                }
                            } else Muted("Пока нет подтверждённого результата для этой цели.")
                            if (proof != null && history.firstOrNull { it.kind == goal.measure }?.let { !it.supports(goal) && !it.isLowerBound } == true) {
                                Muted("Последняя запись пока не подтверждает критерий. При необходимости повторите проверку.")
                            }
                            val latest = library.sessions.firstNotNullOfOrNull { it.completedWorkouts(track.profileId).firstOrNull() }?.metrics
                            nextMeasurement(track, history, latest?.highFatigue == true || latest?.discomfort == true)?.let { suggestion ->
                                Text("Предложение · ${goal.measure.label} · ${number(suggestion.suggested)}")
                                Muted("Последняя проверка ${shortDate(suggestion.date)}: ${number(suggestion.current)}. Применён заданный шаг.")
                            }
                        }
                    }
                    SectionTitle("Результаты движения")
                    if (history.isEmpty()) Muted("Записанных результатов пока нет.")
                    history.groupBy { it.kind }.forEach { (metric, facts) ->
                        CardBlock {
                            Text(metric.label)
                            val last = facts.first()
                            Muted("${shortDate(last.date)} · ${number(last.value)}" + if (last.isLowerBound) "+" else "")
                            if (facts.any { it.isLowerBound }) Muted("RIR 4+ показан на графике по нижней границе.")
                            Trend(metric.label, facts.asReversed().map { it.date to it.value })
                        }
                    }
                }
                family != null -> {
                    if (family.notes.isNotBlank()) Muted(family.notes)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { editFamily = true }) { Text("Изменить семейство") }
                        TextButton(onClick = { editExercise = true }) { Text("Новое упражнение") }
                    }
                    CardBlock {
                        Text("Рабочие подходы за 30 дней · ${totals.values.sum()}")
                        val muscles = members.flatMap { item -> item.muscle.split(',').map(String::trim).filter(String::isNotBlank)
                            .distinct().map { it to (totals[item.id] ?: 0) } }
                            .groupingBy { it.first }.fold(0) { total, (_, count) -> total + count }
                        if (muscles.isNotEmpty()) Muted("По мышцам приблизительно · " +
                            muscles.entries.joinToString(" · ") { "${it.key} ${it.value}" })
                    }
                    members.sortedWith(compareBy<Exercise> { tracks[it.id]?.role?.ordinal ?: 9 }.thenBy { it.name }).forEach { item ->
                        CardBlock(onClick = { exerciseId = item.id }) {
                            Text(item.name)
                            Muted(listOfNotNull(tracks[item.id]?.role?.label, item.movement?.variation?.label,
                                "${totals[item.id] ?: 0} рабочих подходов").joinToString(" · "))
                        }
                    }
                }
                else -> {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Семейства", "Каталог", "Навыки").forEachIndexed { i, label ->
                            FilterChip(tab == i, { tab = i }, label = { Text(label) }, modifier = Modifier.testTag("movement_tab_$i"))
                        }
                    }
                    when (tab) {
                        0 -> {
                            Button(onClick = { editFamily = false }, modifier = Modifier.testTag("new_family")) { Text("Создать семейство") }
                            if (library.families.isEmpty()) {
                                Muted("Объедините упражнения с общим движением. У каждой вариации своя история.")
                            }
                            library.families.forEach { item ->
                                CardBlock(onClick = { familyId = item.id }) { Text(item.name) }
                            }
                        }
                        1 -> CatalogPanel(library, person?.id, tracks, { exerciseId = it }, { editExercise = true })
                        2 -> SkillsPanel(library, person, model, skill) { skillId = it }
                    }
                }
            }
        }
    }

    editFamily?.let { existing ->
        val old = family.takeIf { existing }
        var name by remember { mutableStateOf(old?.name.orEmpty()) }
        var notes by remember { mutableStateOf(old?.notes.orEmpty()) }
        EditorSheet("Семейство движения", { editFamily = null }) {
            Field(name, "Название", { name = it }, Modifier.testTag("family_name"))
            Field(notes, "Заметка", { notes = it }, singleLine = false)
            Button(onClick = {
                model.save(old?.copy(name = name.trim(), notes = notes.trim()) ?: MovementFamily(name = name.trim(), notes = notes.trim()))
                editFamily = null
            }, enabled = name.isNotBlank() && library.families.none { it.id != old?.id && it.name.equals(name.trim(), true) },
                modifier = Modifier.testTag("save_family")) { Text("Сохранить") }
        }
    }
    if (editExercise || variation) {
        MovementExerciseEditor(library, exercise.takeUnless { variation }, exercise.takeIf { variation }, family,
            { editExercise = false; variation = false }) { value ->
            model.saveMovement(value) { editExercise = false; variation = false; exerciseId = value.id }
        }
    }
    if (editTrack && person != null && exercise != null) {
        TrackEditor(track ?: MovementTrack(person.id, exercise.id), { editTrack = false }) {
            model.saveMovementTrack(it) { editTrack = false }
        }
    }
}

@Composable
private fun CatalogPanel(library: Library, profileId: String?, tracks: Map<String, MovementTrack>, select: (String) -> Unit, add: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var limit by rememberSaveable { mutableIntStateOf(25) }
    var equipment by rememberSaveable { mutableStateOf("") }
    var gym by rememberSaveable { mutableStateOf("") }
    var limitation by rememberSaveable { mutableStateOf("") }
    var family by rememberSaveable { mutableStateOf<String?>(null) }
    var load by rememberSaveable { mutableStateOf<Load?>(null) }
    var quality by rememberSaveable { mutableStateOf<Quality?>(null) }
    var role by rememberSaveable { mutableStateOf<MovementRole?>(null) }
    var difficulty by rememberSaveable { mutableStateOf<Int?>(null) }
    var progression by rememberSaveable { mutableStateOf(false) }
    var mastery by rememberSaveable { mutableIntStateOf(0) }
    val established by produceState<Set<String>?>(null, mastery, library, tracks, profileId) {
        value = if (mastery == 0 || profileId == null) null else withContext(Dispatchers.Default) {
            library.exercises.mapNotNull { exercise ->
                val goal = tracks[exercise.id]?.goal ?: return@mapNotNull null
                exercise.id.takeIf { movementResults(library.sessions, profileId, exercise).any { result -> result.supports(goal) } }
            }.toSet()
        }
    }
    val loadLabels = Load.entries.map { loadText(it) }
    val policyIds = remember(library.policies) { library.policies.mapNotNull { it.scope.exerciseId }.toSet() }

    Button(onClick = add, modifier = Modifier.testTag("movement_new_exercise")) { Text("Новое упражнение") }
    Field(query, "Название или мышцы", { query = it }, Modifier.testTag("movement_catalog_search"))
    TextButton(onClick = { expanded = !expanded }) { Text("Фильтры каталога") }
    if (expanded) {
        Field(equipment, "Оборудование", { equipment = it })
        Field(gym, "Зал", { gym = it })
        Field(limitation, "Мои ограничения (исключить совпадения)", { limitation = it })
        Choice(library.families.find { it.id == family }, listOf(null) + library.families, { it?.name ?: "Все семейства" }, { family = it?.id }, "filter_family")
        Choice(load, listOf(null) + Load.entries, { it?.let { value -> loadLabels[value.ordinal] } ?: "Все типы нагрузки" }, { load = it }, "filter_load")
        Choice(quality, listOf(null) + Quality.entries, { it?.label ?: "Любая цель" }, { quality = it }, "filter_quality")
        Choice(role, listOf(null) + MovementRole.entries, { it?.label ?: "Любой статус" }, { role = it }, "filter_movement_role")
        Choice(difficulty, listOf(null) + (1..5), { it?.let { "Сложность $it" } ?: "Любая сложность" }, { difficulty = it }, "filter_difficulty")
        FilterChip(progression, { progression = !progression }, label = { Text("Есть настройки прогрессии") })
        if (profileId != null) {
            val labels = listOf("Любое освоение", "Цель подтверждена", "Цель пока не подтверждена")
            Choice(mastery, labels.indices.toList(), { labels[it] }, { mastery = it }, "filter_mastery")
        }
    }
    val matches = library.exercises.filter { item ->
        val details = item.movement
        val track = tracks[item.id]
        !item.archived && (item.name.contains(query.trim(), true) || item.muscle.contains(query.trim(), true)) &&
            (family == null || details?.familyId == family) && (load == null || item.load == load) &&
            (quality == null || details?.quality == quality) && (difficulty == null || details?.difficulty == difficulty) &&
            (role == null || track?.role == role) && (!progression || track?.goal != null || item.id in policyIds) &&
            (mastery == 0 || established?.let { (item.id in it) == (mastery == 1) } == true) &&
            details?.equipment.orEmpty().contains(equipment.trim(), true) && details?.gym.orEmpty().contains(gym.trim(), true) &&
            (limitation.isBlank() || !(details?.limitations.orEmpty() + " " + track?.limitations.orEmpty()).contains(limitation.trim(), true))
    }
    if (matches.isEmpty()) Muted("Упражнения не найдены.")
    matches.take(limit).forEach { item ->
        CardBlock(onClick = { select(item.id) }) {
            Text(item.name)
            if (item.muscle.isNotBlank()) Muted(item.muscle)
        }
    }
    if (matches.size > limit) TextButton(onClick = { limit += 25 }) { Text("Показать ещё") }
}

internal fun goalText(goal: MovementGoal): String =
    "${goal.measure.label} · ${if (goal.atLeast) "не меньше" else "не больше"} ${number(goal.target)}"
