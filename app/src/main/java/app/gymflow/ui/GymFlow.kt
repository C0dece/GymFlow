@file:OptIn(ExperimentalMaterial3Api::class)

package app.gymflow.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import app.gymflow.GymModel
import app.gymflow.R
import app.gymflow.data.*
import app.gymflow.domain.*
import java.time.LocalDate

private enum class Page(val route: String, val icon: Int, val label: Int) {
    HOME("home", R.drawable.ic_today, R.string.home),
    PROGRAM("program", R.drawable.ic_program, R.string.program),
    HISTORY("history", R.drawable.ic_history, R.string.history),
    SETTINGS("settings", R.drawable.ic_settings, R.string.settings),
}

private const val workoutRoute = "workout/{id}?profile={profile}&review={review}&days={days}"

@Composable
fun GymFlow(model: GymModel, applySystemBars: (Boolean) -> Unit) {
    val library by model.library.collectAsStateWithLifecycle()
    val preferences by model.preferences.collectAsStateWithLifecycle()
    val dark = preferences.theme == Theme.DARK || (preferences.theme == Theme.SYSTEM && isSystemInDarkTheme())
    SideEffect { applySystemBars(dark) }
    val status by model.sync.collectAsStateWithLifecycle()
    val message by model.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val workout = entry?.destination?.route in setOf(workoutRoute, "plan/{id}", "progression")
    val selected = Page.entries.find { it.route == entry?.destination?.route } ?: Page.HOME
    val selectFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) model.connect(uri)
    }
    val open: (String) -> Unit = { nav.navigate("workout/$it") { launchSingleTop = true } }
    val reviewDay: (String, String) -> Unit = { session, row -> nav.navigate("workout/$session?review=$row") }
    val preview: (String?) -> Unit = { id -> nav.navigate("plan/${id ?: "new"}") { launchSingleTop = true } }
    val repeat: (String) -> Unit = { preview("repeat.$it") }
    val progression = { nav.navigate("progression") { launchSingleTop = true }; Unit }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            if (model.message.value == it) model.message.value = null
        }
    }
    GymTheme(preferences.theme) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                if (!workout) {
                    TopAppBar(
                        title = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                if (selected == Page.HOME) {
                                    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                                        Symbol(R.drawable.ic_dumbbell, modifier = Modifier.padding(7.dp).size(23.dp))
                                    }
                                }
                                Text(stringResource(if (selected == Page.HOME) R.string.app_name else selected.label),
                                    style = MaterialTheme.typography.titleLarge)
                            }
                        },
                        actions = {
                            if (selected == Page.HOME && preferences.folder.isNotBlank()) {
                                TextButton(onClick = model::refresh) { Text(stringResource(R.string.refresh)) }
                            }
                        },
                    )
                }
            },
            bottomBar = {
                if (!workout) {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Page.entries.forEach { page ->
                            NavigationBarItem(
                                modifier = Modifier.testTag("nav_${page.route}"),
                                selected = page == selected,
                                onClick = {
                                    nav.navigate(page.route) {
                                        popUpTo("home") { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = { Symbol(page.icon) },
                                label = { Text(stringResource(page.label), style = MaterialTheme.typography.labelSmall, maxLines = 2) },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            NavHost(nav, startDestination = "home", modifier = Modifier.padding(padding).consumeWindowInsets(padding)) {
                composable("home") {
                    Home(library, preferences, status, { selectFolder.launch(null) }, open, preview, repeat, progression,
                        openDays = { id -> nav.navigate("workout/$id?days=true") { launchSingleTop = true } },
                        changePlannedDay = model::changePlannedDay,
                    ) { id ->
                        model.start(id, open = open)
                    }
                }
                composable("program") { Programs(library, preferences, model, preview) }
                composable("history") { History(library, preferences, open, repeat, reviewDay) }
                composable("settings") { PreferencesPage(library, preferences, status, model, progression) { selectFolder.launch(null) } }
                composable("progression") { ProgressionScreen(library, model, { nav.popBackStack() }, preview) }
                composable("plan/{id}", listOf(navArgument("id") { type = NavType.StringType })) { route ->
                    if (library.loaded) {
                        val id = route.arguments?.getString("id")
                        val plan = library.sessions.find { it.id == id && it.status == SessionStatus.PLANNED }
                        val day = library.programs.asSequence().flatMap { it.days.asSequence() }.find { it.id == id }
                        val sourceId = id?.takeIf { it.startsWith("repeat.") }?.removePrefix("repeat.")
                        val source = library.sessions.find { it.id == sourceId && it.status == SessionStatus.COMPLETED }
                        if (id == "new" || plan != null || day != null || source != null) {
                            PlanPreview(plan, day, library, preferences, source, model, { nav.popBackStack() }) {
                                nav.popBackStack()
                                open(it)
                            }
                        } else ScreenColumn(Modifier.statusBarsPadding()) {
                            SectionTitle(stringResource(R.string.session_not_available))
                            Button(onClick = { nav.popBackStack() }) { Text(stringResource(R.string.back)) }
                        }
                    }
                }
                composable(workoutRoute, listOf(
                    navArgument("id") { type = NavType.StringType },
                    navArgument("profile") { type = NavType.StringType; nullable = true },
                    navArgument("review") { type = NavType.StringType; nullable = true },
                    navArgument("days") { type = NavType.BoolType; defaultValue = false },
                )) { route ->
                    val id = route.arguments?.getString("id")
                    val session = library.sessions.firstOrNull { it.id == id }
                    if (session != null) Workout(session, library, preferences, model,
                        initialProfile = route.arguments?.getString("profile"), reviewId = route.arguments?.getString("review"),
                        showDays = route.arguments?.getBoolean("days") == true,
                        leave = { if (nav.currentBackStackEntry == route) nav.popBackStack() }, repeat = { repeat(session.id) },
                        openDay = { target, person ->
                            if (nav.currentBackStackEntry == route) nav.navigate("workout/$target?profile=$person") {
                                if (route.arguments?.getString("review") == null) popUpTo(route.destination.id) { inclusive = true }
                            }
                        }, reviewDay = reviewDay,
                    )
                    else if (!library.loaded) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    else ScreenColumn(Modifier.statusBarsPadding()) {
                        SectionTitle(stringResource(R.string.session_not_available))
                        Button(onClick = { nav.popBackStack() }) { Text(stringResource(R.string.back)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Home(
    library: Library,
    preferences: Preferences,
    status: SyncStatus,
    chooseFolder: () -> Unit,
    open: (String) -> Unit,
    preview: (String?) -> Unit,
    repeat: (String) -> Unit,
    progression: () -> Unit,
    openDays: (String) -> Unit,
    changePlannedDay: (Session, String, String, LocalDate?, () -> Unit) -> Unit,
    start: (String?) -> Unit,
) {
    if (!library.loaded || library.scope != preferences.folder) {
        if (status.issues.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else ScreenColumn {
            SectionTitle(stringResource(R.string.settings_unavailable))
            status.issues.forEach { Muted(it) }
            Button(onClick = chooseFolder) { Text(stringResource(R.string.select_folder)) }
        }
        return
    }
    val today = LocalDate.now()
    val active = library.active()
    val finished = library.sessions.filter { it.status == SessionStatus.COMPLETED }
    val planned = library.sessions.filter { it.status == SessionStatus.PLANNED }.sortedBy { it.date }
    val next = planned.firstOrNull()
    val plannedMember = next?.let { it.workout(preferences.athlete) ?: it.defaultWorkout }
    val plannedProgram = if (next != null && plannedMember != null) workoutProgram(library.programs, next, plannedMember) else null
    val effectiveProgram = if (active != null) workoutProgram(library.programs, active, active.defaultWorkout)
        else (plannedProgram ?: (if (next != null && plannedMember != null) {
            val tid = next.templateOf(plannedMember)
            library.programs.firstOrNull { p -> p.days.any { it.id == tid } }
        } else null) ?: library.programs.firstOrNull { it.enabled && it.days.isNotEmpty() } ?: library.programs.firstOrNull { it.days.isNotEmpty() })
    val canSwitchDay = effectiveProgram != null && effectiveProgram.days.isNotEmpty()
    var choosePlannedDay by remember(next?.id) { mutableStateOf(false) }
    val upcoming = remember(library.sessions, library.programs, today, preferences.athlete) {
        val slots = library.sessions.filter { it.programId != null }.groupBy { it.date to it.programId }
        (1L..14L).flatMap { offset ->
            val date = today.plusDays(offset)
            library.programs.flatMap { program ->
                val saved = slots[date.toString() to program.id].orEmpty()
                val occupied = saved.mapTo(hashSetOf()) { it.programDayId }
                val templates = plannedOn(program, date).filter { it.exercises.isNotEmpty() && it.id !in occupied }
                    .map { it.title }
                val explicit = saved.filter { it.status == SessionStatus.PLANNED }.map { session ->
                    (session.workout(preferences.athlete) ?: session.defaultWorkout).templateTitle ?: session.title
                }
                (explicit + templates).map { date.toString() to it }
            }
        }.take(4)
    }
    if (choosePlannedDay && active == null && effectiveProgram != null) {
        val effectiveSession = next ?: scheduledSession(effectiveProgram, effectiveProgram.days.first(), today, listOf(library.profiles.find { it.id == preferences.athlete } ?: library.profiles.firstOrNull() ?: Profile(id = preferences.athlete, name = "")))
        val effectiveMember = effectiveSession.workout(preferences.athlete) ?: effectiveSession.defaultWorkout
        PlannedDaySheet(effectiveProgram, effectiveSession, effectiveMember, library.sessions, close = { choosePlannedDay = false }) { day, swap ->
            changePlannedDay(effectiveSession, effectiveMember.profile.id, day, swap) { choosePlannedDay = false }
        }
    }
    val conflicts = library.records.count { it.conflict != null }
    val pending = library.records.count { it.pending }

    ScreenColumn(Modifier.testTag("home")) {
        Muted(fullDate(today))
        Text(stringResource(R.string.home_heading), style = MaterialTheme.typography.headlineLarge)
        Surface(color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Symbol(R.drawable.ic_dumbbell, modifier = Modifier.size(22.dp))
                    Text(when {
                        active != null -> stringResource(R.string.active_status)
                        next != null -> stringResource(R.string.planned_status)
                        else -> stringResource(R.string.training)
                    }, style = MaterialTheme.typography.labelLarge)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(active?.let { if (it.workouts.size == 1) it.defaultWorkout.templateTitle ?: it.title else it.title }
                        ?: plannedMember?.templateTitle ?: next?.title ?: stringResource(R.string.free_session),
                        modifier = Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
                    if (canSwitchDay) {
                        IconButton(
                            onClick = {
                                if (active != null) openDays(active.id)
                                else choosePlannedDay = true
                            },
                            modifier = Modifier.testTag("home_switch_day")
                        ) {
                            Symbol(R.drawable.ic_program, description = stringResource(R.string.switch_day))
                        }
                    }
                }
                if (active != null) {
                    val complete = active.workouts.sumOf { it.completed }
                    val total = complete + active.workouts.sumOf { it.remaining }
                    Text(stringResource(R.string.sets_progress, complete, total))
                } else if (next != null) {
                    Text(shortDate(next.date))
                } else {
                    Text(stringResource(R.string.first_training))
                }
                Button(
                    onClick = {
                        when {
                            active != null -> open(active.id)
                            next != null -> preview(next.id)
                            else -> start(null)
                        }
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("start_training"),
                ) {
                    Text(if (active == null) stringResource(R.string.start_workout) else stringResource(R.string.continue_training))
                    Spacer(Modifier.width(6.dp))
                    Symbol(R.drawable.ic_forward, modifier = Modifier.size(19.dp))
                }
                OutlinedButton(onClick = { preview(next?.id) }, modifier = Modifier.fillMaxWidth().testTag("view_plan")) {
                    Text(stringResource(R.string.view_plan))
                }
                if (active != null) {
                    TextButton(onClick = { start(null) }, modifier = Modifier.testTag("new_active")) { Text(stringResource(R.string.new_session)) }
                }
                if (active == null && next != null) {
                    if (canSwitchDay) OutlinedButton(
                        onClick = { choosePlannedDay = true },
                        modifier = Modifier.fillMaxWidth().testTag("planned_switch_day"),
                    ) { Text(stringResource(R.string.switch_day)) }
                    TextButton(onClick = { start(null) }) { Text(stringResource(R.string.free_session)) }
                }
            }
        }

        OutlinedButton(onClick = progression, modifier = Modifier.fillMaxWidth().testTag("open_progression")) {
            Text(stringResource(R.string.progression))
        }
        if (library.unreadable > 0) InlineStatus(stringResource(R.string.unreadable_documents, library.unreadable), warning = true)
        if (library.activeSessions.size > 1) {
            SectionTitle(stringResource(R.string.active_sessions))
            library.activeSessions.drop(1).forEach { item ->
                CardBlock(onClick = { open(item.id) }) {
                    Text(item.title, style = MaterialTheme.typography.titleMedium)
                    Muted(item.participants.joinToString { it.name })
                }
            }
        }

        if (preferences.folder.isBlank()) {
            CardBlock {
                SectionTitle(stringResource(R.string.markdown_folder))
                Muted(stringResource(R.string.folder_intro))
                OutlinedButton(onClick = chooseFolder, modifier = Modifier.testTag("choose_folder")) {
                    Text(stringResource(R.string.select_folder))
                }
                if (pending > 0) Muted(stringResource(R.string.local_pending, pending))
            }
        } else {
            InlineStatus(when {
                conflicts > 0 -> stringResource(R.string.conflict_count, conflicts)
                status.checking -> stringResource(R.string.checking_folder)
                status.issues.isNotEmpty() -> stringResource(R.string.folder_error)
                pending > 0 -> stringResource(R.string.pending_count, pending)
                else -> stringResource(R.string.folder_ready)
            }, warning = conflicts > 0 || status.issues.isNotEmpty())
        }

        SectionTitle(stringResource(R.string.overview))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CardBlock(Modifier.weight(1f)) {
                Text(finished.size.toString(), style = MaterialTheme.typography.headlineMedium)
                Muted(stringResource(R.string.completed_workouts))
            }
            CardBlock(Modifier.weight(1f)) {
                Text(library.exercises.count { !it.archived }.toString(), style = MaterialTheme.typography.headlineMedium)
                Muted(stringResource(R.string.exercises))
            }
        }
        finished.firstOrNull()?.let {
            CardBlock {
                Muted(stringResource(R.string.last_workout))
                Text(it.title, style = MaterialTheme.typography.titleMedium)
                Muted(shortDate(it.date))
                TextButton(onClick = { repeat(it.id) }) { Text(stringResource(R.string.repeat_workout)) }
            }
        }
        if (upcoming.isNotEmpty() || planned.size > 1) {
            SectionTitle(stringResource(R.string.upcoming))
            (planned.drop(1).map { it.date to it.title } + upcoming).distinct().sortedBy { it.first }.take(5).forEach { (date, title) ->
                CardBlock {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Muted(shortDate(date))
                }
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 390)
@Composable
private fun HomePreview() {
    GymTheme(Theme.DARK) { Surface { Home(Library(loaded = true), Preferences(), SyncStatus(), {}, {}, {}, {}, {}, {}, { _, _, _, _, _ -> }, {}) } }
}
