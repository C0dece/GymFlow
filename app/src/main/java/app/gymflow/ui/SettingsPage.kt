@file:OptIn(ExperimentalLayoutApi::class)

package app.gymflow.ui

import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import app.gymflow.GymModel
import app.gymflow.R
import app.gymflow.data.*
import app.gymflow.domain.*

@Composable
fun PreferencesPage(library: Library, prefs: Preferences, status: SyncStatus, model: GymModel, progression: () -> Unit, chooseFolder: () -> Unit) {
    var catalog by rememberSaveable { mutableStateOf(false) }
    var addPerson by rememberSaveable { mutableStateOf(false) }
    var profileId by rememberSaveable { mutableStateOf<String?>(null) }
    var defaults by rememberSaveable { mutableStateOf(false) }
    var resolve by rememberSaveable { mutableStateOf<String?>(null) }
    var switchFolder by rememberSaveable { mutableStateOf(false) }
    var backups by rememberSaveable { mutableStateOf(false) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) model.export(uri, backups)
    }
    BackHandler(catalog) { catalog = false }
    if (catalog) {
        Catalog(library, model) { catalog = false }
        return
    }
    val conflicts = library.records.filter { it.conflict != null }
    val pending = library.records.count { it.pending }
    val path = if (prefs.folder.isBlank()) null else runCatching {
        DocumentsContract.getTreeDocumentId(prefs.folder.toUri()).substringAfter(':')
    }.getOrDefault(prefs.folder)
    val docs = remember(library.documents) { library.documents.associateBy { it.id } }

    ScreenColumn(Modifier.testTag("settings")) {
        CardBlock {
            SectionTitle(stringResource(R.string.markdown_folder))
            Muted(path ?: stringResource(R.string.local_mode))
            if (pending > 0) InlineStatus(stringResource(R.string.pending_count, pending))
            if (status.checking) LinearProgressIndicator(Modifier.fillMaxWidth())
            status.issues.take(5).forEach { message ->
                Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { if (path == null) chooseFolder() else switchFolder = true }, modifier = Modifier.testTag("settings_choose_folder")) {
                    Text(if (path == null) stringResource(R.string.select_folder) else stringResource(R.string.switch_folder))
                }
                if (path != null) TextButton(onClick = model::refresh) { Text(stringResource(R.string.refresh)) }
            }
            conflicts.forEach { row ->
                val name = when (val doc = docs[row.id]) {
                    is Session -> doc.title
                    is Program -> doc.title
                    is Profile -> doc.name
                    is Exercise -> doc.name
                    is ProgressionPolicy -> stringResource(R.string.progression)
                    is ProgressionReview -> stringResource(R.string.progression)
                    null -> row.id
                }
                HorizontalDivider()
                Text(name, style = MaterialTheme.typography.titleMedium)
                Muted(row.conflict.orEmpty())
                TextButton(onClick = { resolve = row.id }) { Text(stringResource(R.string.resolve)) }
            }
        }
        CardBlock {
            SectionTitle(stringResource(R.string.profiles))
            if (library.profiles.isEmpty()) Muted(stringResource(R.string.profile_intro))
            library.profiles.forEach { profile ->
                TextButton(onClick = { profileId = profile.id }, modifier = Modifier.testTag("profile_${profile.id}")) {
                    val color = profile.color
                    if (color != null) {
                        Marker(Color(color))
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(profile.name + if (profile.archived) " · " + stringResource(R.string.archived_profile) else "")
                }
            }
            OutlinedButton(onClick = { addPerson = true }, modifier = Modifier.testTag("settings_add_person")) { Text(stringResource(R.string.add_person)) }
        }
        CardBlock {
            SectionTitle(stringResource(R.string.training))
            TextButton(onClick = progression, modifier = Modifier.testTag("settings_progression")) { Text(stringResource(R.string.progression)) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Switch(prefs.rotation, { model.settings(prefs.copy(rotation = it)) }, modifier = Modifier.testTag("auto_rotation"))
                Text(stringResource(R.string.auto_switch))
            }
            Text(stringResource(R.string.load_units))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                UnitWeight.entries.forEach { option ->
                    FilterChip(prefs.unit == option, { model.settings(prefs.copy(unit = option)) }, label = { Text(unitText(option)) })
                }
            }
            TextButton(onClick = { defaults = true }) { Text(stringResource(R.string.defaults)) }
            TextButton(onClick = { catalog = true }) { Text(stringResource(R.string.catalog)) }
        }
        CardBlock {
            SectionTitle(stringResource(R.string.theme))
            val names = listOf(stringResource(R.string.dark_theme), stringResource(R.string.light_theme), stringResource(R.string.system_theme))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Theme.entries.forEach { option ->
                    FilterChip(prefs.theme == option, { model.settings(prefs.copy(theme = option)) },
                        modifier = Modifier.testTag("theme_${option.name.lowercase()}"), label = { Text(names[option.ordinal]) })
                }
            }
        }
        CardBlock {
            SectionTitle(stringResource(R.string.backups))
            Muted(stringResource(R.string.export_intro))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { backups = false; export.launch("GymFlow.zip") }) { Text(stringResource(R.string.export_markdown)) }
                OutlinedButton(onClick = { backups = true; export.launch("GymFlow-backup.zip") }) { Text(stringResource(R.string.create_backup)) }
            }
        }
    }

    if (addPerson) PersonEditor(library, null, { addPerson = false }) { profile, _ ->
        model.save(profile)
        addPerson = false
    }
    library.profiles.find { it.id == profileId }?.let { profile ->
        key(profile.id, profile.color) {
            ProfileEditor(profile, profile.id == prefs.athlete, { profileId = null }) { updated, primary ->
                model.save(updated)
                if (primary) model.settings(prefs.copy(athlete = updated.id))
                else if (prefs.athlete == updated.id) model.settings(prefs.copy(athlete = ""))
                profileId = null
            }
        }
    }
    if (defaults) DefaultEditor(prefs, { defaults = false }) { model.settings(it); defaults = false }
    if (switchFolder) AlertDialog(
        onDismissRequest = { switchFolder = false },
        title = { Text(stringResource(R.string.switch_folder)) },
        text = { Text(stringResource(R.string.switch_folder_help)) },
        confirmButton = { Button(onClick = { switchFolder = false; chooseFolder() }) { Text(stringResource(R.string.select_folder)) } },
        dismissButton = { TextButton(onClick = { switchFolder = false }) { Text(stringResource(R.string.cancel)) } },
    )
    resolve?.let { id ->
        var useLocal by rememberSaveable(id) { mutableStateOf<Boolean?>(null) }
        val cancel = { resolve = null }
        EditorSheet(stringResource(R.string.resolve), cancel) {
            Muted(stringResource(R.string.conflict_explanation))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(useLocal == true, { useLocal = true }, label = { Text(stringResource(R.string.local_version)) })
                FilterChip(useLocal == false, { useLocal = false }, label = { Text(stringResource(R.string.folder_version)) })
            }
            Button(onClick = { useLocal?.let { model.resolve(id, it) }; cancel() },
                enabled = useLocal != null, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.apply)) }
        }
    }
}

@Composable
private fun ProfileEditor(profile: Profile, primary: Boolean, close: () -> Unit, save: (Profile, Boolean) -> Unit) {
    var name by rememberSaveable { mutableStateOf(profile.name) }
    var color by rememberSaveable { mutableStateOf(profile.color) }
    var compact by rememberSaveable { mutableStateOf(profile.compact) }
    var own by rememberSaveable { mutableStateOf(primary) }
    var archived by rememberSaveable { mutableStateOf(profile.archived) }
    val colors = listOf(null, 0xFF87C16E.toInt(), 0xFF7FB2EF.toInt(), 0xFFCF91EA.toInt(), 0xFFECB46D.toInt())
    EditorSheet(stringResource(R.string.change_profile), close) {
        Field(name, stringResource(R.string.name), { name = it })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            colors.forEachIndexed { index, choice ->
                FilterChip(choice == color, { color = choice }, modifier = Modifier.testTag("profile_color_$index"), label = {
                    if (choice == null) Text(stringResource(R.string.no_color))
                    else Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Marker(Color(choice))
                        Text(index.toString())
                    }
                })
            }
        }
        FilterChip(compact, { compact = !compact }, label = { Text(stringResource(R.string.compact_sets)) })
        FilterChip(archived, { archived = !archived }, label = { Text(stringResource(R.string.archive_profile)) })
        FilterChip(own && !archived, { own = !own }, enabled = !archived, label = { Text(stringResource(R.string.primary_profile)) })
        Button(onClick = { save(profile.copy(name = name.trim(), color = color, compact = compact, archived = archived), own && !archived) },
            enabled = name.isNotBlank(), modifier = Modifier.fillMaxWidth().testTag("save_profile")) { Text(stringResource(R.string.save)) }
    }
}

@Composable
private fun DefaultEditor(prefs: Preferences, close: () -> Unit, save: (Preferences) -> Unit) {
    var step by rememberSaveable { mutableStateOf(number(prefs.step)) }
    var sets by rememberSaveable { mutableStateOf(prefs.initialSets.toString()) }
    var low by rememberSaveable { mutableStateOf(prefs.minReps.toString()) }
    var high by rememberSaveable { mutableStateOf(prefs.maxReps.toString()) }
    var weight by rememberSaveable { mutableStateOf(number(prefs.initialWeight)) }
    val increment = decimal(step)?.takeIf { it > 0.0 }
    val count = sets.toIntOrNull()?.takeIf { it in 1..30 }
    val min = positiveInt(low)
    val max = positiveInt(high)
    val initial = decimal(weight)
    EditorSheet(stringResource(R.string.defaults), close) {
        Field(step, stringResource(R.string.weight_step, unitText(prefs.unit)), { step = it }, keyboard = KeyboardType.Decimal)
        Field(weight, stringResource(R.string.weight_unit, unitText(prefs.unit)), { weight = it }, keyboard = KeyboardType.Decimal)
        Field(sets, stringResource(R.string.set_count), { sets = it }, keyboard = KeyboardType.Number)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field(low, stringResource(R.string.min_reps), { low = it }, Modifier.weight(1f), KeyboardType.Number)
            Field(high, stringResource(R.string.max_reps), { high = it }, Modifier.weight(1f), KeyboardType.Number)
        }
        Button(onClick = {
            if (increment != null && count != null && min != null && max != null && initial != null) {
                save(prefs.copy(step = increment, initialSets = count, minReps = min, maxReps = max, initialWeight = initial))
            }
        }, enabled = increment != null && count != null && min != null && max != null && min <= max && initial != null,
            modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.save)) }
    }
}

@Composable
private fun Catalog(library: Library, model: GymModel, close: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var archived by rememberSaveable { mutableStateOf(false) }
    var id by rememberSaveable { mutableStateOf<String?>(null) }
    val entries = remember(query, archived, library.exercises) {
        library.exercises.filter { it.archived == archived && it.name.contains(query.trim(), true) }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            TextButton(onClick = close) { Symbol(R.drawable.ic_back); Text(stringResource(R.string.back)) }
            SectionTitle(stringResource(R.string.catalog))
            Spacer(Modifier.height(8.dp))
            Field(query, stringResource(R.string.search), { query = it })
            FilterChip(archived, { archived = !archived }, label = { Text(stringResource(R.string.archive)) })
        }
        items(entries, key = { it.id }) { entry ->
            CardBlock(onClick = { id = entry.id }) {
                Text(entry.name, style = MaterialTheme.typography.titleMedium)
                Muted("${loadText(entry.load)} · ${unitText(entry.unit)}")
            }
        }
        if (entries.isEmpty()) item { Muted(stringResource(R.string.empty_catalog)) }
    }
    library.exercises.find { it.id == id }?.let { entry ->
        var name by rememberSaveable(id) { mutableStateOf(entry.name) }
        var muscle by rememberSaveable(id) { mutableStateOf(entry.muscle) }
        var notes by rememberSaveable(id) { mutableStateOf(entry.notes) }
        var favorite by rememberSaveable(id) { mutableStateOf(entry.favorite) }
        var archive by rememberSaveable(id) { mutableStateOf(entry.archived) }
        EditorSheet(stringResource(R.string.edit_exercise), { id = null }) {
            Field(name, stringResource(R.string.exercise_name), { name = it })
            Field(muscle, stringResource(R.string.muscle), { muscle = it })
            Field(notes, stringResource(R.string.note), { notes = it }, singleLine = false)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(favorite, { favorite = !favorite }, label = { Text(stringResource(R.string.favorites)) })
                FilterChip(archive, { archive = !archive }, label = { Text(stringResource(R.string.archive)) })
            }
            Button(onClick = {
                model.save(entry.copy(name = name.trim(), muscle = muscle.trim(), notes = notes, archived = archive, favorite = favorite))
                id = null
            }, enabled = name.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.save)) }
        }
    }
}
