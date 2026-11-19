@file:OptIn(ExperimentalLayoutApi::class)

package app.gymflow.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.gymflow.R
import app.gymflow.data.Library
import app.gymflow.data.Preferences
import app.gymflow.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

@Composable
fun History(library: Library, prefs: Preferences, open: (String) -> Unit, repeat: (String) -> Unit, review: (String, String) -> Unit) {
    val completed = remember(library.sessions) {
        library.sessions.filter { it.status == SessionStatus.COMPLETED || it.allWorkouts.any { w -> w.status == WorkoutStatus.COMPLETED } }
    }
    val people = remember(completed, library.profiles) { (library.profiles + completed.flatMap { it.participants }).distinctBy { it.id } }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var exerciseId by rememberSaveable { mutableStateOf<String?>(null) }
    var stats by rememberSaveable { mutableStateOf(false) }
    val person = people.find { it.id == selected } ?: people.find { it.id == prefs.athlete } ?: people.firstOrNull()
    val entries = remember(completed, person?.id) {
        person?.let { p -> completed.flatMap { session -> session.completedWorkouts(p.id).map { session to it } } }.orEmpty()
    }
    val filtered = remember(entries) { entries.map { it.first }.distinctBy { it.id } }
    val measures by produceState(emptyList<Performance>(), filtered, person?.id) {
        value = withContext(Dispatchers.Default) { person?.let { performance(filtered, it.id) }.orEmpty() }
    }
    val exerciseList = remember(measures) { measures.asReversed().map { it.exercise }.distinctBy { it.id } }
    val exercise = exerciseList.find { it.id == exerciseId } ?: exerciseList.firstOrNull()
    val series = remember(measures, exercise) { measures.filter { exercise != null && it.exercise.comparableWith(exercise) } }
    val body = remember(entries) {
        entries.mapNotNull { (session, row) -> row.metrics.bodyWeightKg?.let { session.date to it } }.reversed()
    }
    val recent = remember(entries) {
        val since = LocalDate.now().minusDays(29).toString()
        entries.count { it.first.date >= since }
    }
    LazyColumn(
        Modifier.fillMaxSize().testTag("history"),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(if (stats) stringResource(R.string.progress) else stringResource(R.string.diary), style = MaterialTheme.typography.headlineLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!stats, { stats = false }, label = { Text(stringResource(R.string.history)) })
                FilterChip(stats, { stats = true }, label = { Text(stringResource(R.string.progress)) }, modifier = Modifier.testTag("progress_tab"))
            }
        }
        if (people.size > 1) {
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    people.forEach { p -> FilterChip(person?.id == p.id, { selected = p.id; exerciseId = null }, label = { Text(p.name) }) }
                }
            }
        }
        if (filtered.isEmpty()) {
            item {
                CardBlock {
                    SectionTitle(stringResource(R.string.empty_history))
                    Muted(stringResource(R.string.history_intro))
                }
            }
        } else if (stats) {
            item {
                CardBlock {
                    SectionTitle(recent.toString())
                    Muted(stringResource(R.string.frequency))
                }
            }
            if (body.isNotEmpty()) item { Trend(stringResource(R.string.body_mass), body) }
            if (exercise != null) {
                item {
                    SectionTitle(stringResource(R.string.exercise_history))
                    ExerciseFilter(exercise, exerciseList, library) { exerciseId = it }
                }
                val exact = series.filter { it.exercise.load == exercise.load }
                val measured = exact.filter { it.actuals.any { actual -> actual.weightKnown } }
                if (exercise.cardio != null) {
                    item { Muted(exercise.cardio.label) }
                    val aerobic = exact.flatMap { row -> row.actuals.flatMap { actual ->
                        actual.measurements.entries.map { (kind, value) -> kind to (row.date to value) }
                    } }.groupBy({ it.first }, { it.second })
                    aerobic.forEach { (kind, points) -> item { Trend(kind.label, points) } }
                } else if (exact.isNotEmpty()) {
                    if (measured.isNotEmpty()) item { Trend(stringResource(R.string.working_weight), measured.map { it.date to it.maxKg }) }
                    item {
                        CardBlock {
                            if (measured.isNotEmpty()) {
                                SectionTitle(stringResource(R.string.record))
                                Text(stringResource(R.string.kg_number, number(measured.maxOf { it.maxKg })), style = MaterialTheme.typography.headlineMedium)
                            }
                            val latest = exact.last()
                            Muted(stringResource(R.string.working_reps, latest.reps))
                            val volume = latest.volumeKg
                            if (volume != null) Muted(stringResource(R.string.volume, number(volume)))
                        }
                    }
                    val rir = exact.mapNotNull { row -> row.rir?.let { row.date to it.toDouble() } }
                    if (rir.isNotEmpty()) item { Trend(stringResource(R.string.rir_chart), rir) }
                }
            }
        } else {
            items(entries, key = { it.second.id }) { (item, workout) ->
                val parked = workout in item.otherWorkouts
                CardBlock(Modifier.testTag("history_session"), onClick = {
                    if (parked) review(item.id, workout.id) else open(item.id)
                }) {
                    Muted(shortDate(item.date))
                    SectionTitle(workout.templateTitle ?: item.title)
                    Muted(stringResource(R.string.workout_summary, workout.exercises.size, workout.completed))
                    if (item.workoutNotes.isNotEmpty()) Muted("Заметок: ${item.workoutNotes.size}")
                    if (item.notes.isNotBlank()) Text(item.notes, maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    if (!parked && item.status == SessionStatus.COMPLETED) {
                        TextButton(onClick = { repeat(item.id) }, modifier = Modifier.testTag("repeat_history")) {
                            Text(stringResource(R.string.repeat_workout))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExerciseFilter(current: Exercise, entries: List<Exercise>, library: Library, pick: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val labels = remember(library.exercises) { library.exercises.associate { it.id to it.name } }
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text(labels[current.id] ?: current.name) }
        DropdownMenu(expanded, { expanded = false }) {
            entries.forEach { item ->
                DropdownMenuItem(text = { Text(labels[item.id] ?: item.name) }, onClick = { expanded = false; pick(item.id) })
            }
        }
    }
}

@Composable
internal fun Trend(title: String, points: List<Pair<String, Double>>) {
    val samples = points.takeLast(24)
    val first = samples.first()
    val last = samples.last()
    val primary = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val description = stringResource(R.string.chart_summary, title, number(last.second))
    CardBlock {
        SectionTitle(title)
        Text(number(last.second), style = MaterialTheme.typography.headlineMedium)
        if (samples.size > 1) {
            Canvas(Modifier.fillMaxWidth().height(94.dp).semantics { contentDescription = description }) {
                val top = 8.dp.toPx()
                val bottom = size.height - top
                repeat(3) { i ->
                    val y = top + (bottom - top) * i / 2f
                    drawLine(grid, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                }
                val low = samples.minOf { it.second }
                val range = samples.maxOf { it.second } - low
                val coords = samples.mapIndexed { i, (_, y) ->
                    val trend = if (range == 0.0) 0.5f else ((y - low) / range).toFloat()
                    val inset = 5.dp.toPx()
                    Offset(inset + (size.width - inset * 2) * i / (samples.size - 1), bottom - (bottom - top) * trend)
                }
                coords.zipWithNext().forEach { (a, b) -> drawLine(primary, a, b, 2.dp.toPx()) }
                coords.forEach { drawCircle(primary, 3.dp.toPx(), it) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Muted(shortDate(first.first))
                Muted(shortDate(last.first))
            }
        } else Muted(shortDate(last.first))
    }
}
