package app.gymflow.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import app.gymflow.domain.UnitWeight
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

private val recoveryNotice = MutableStateFlow(false)
private val Context.settingsStore by preferencesDataStore(
    "gymflow_settings",
    corruptionHandler = ReplaceFileCorruptionHandler {
        Log.w("GymFlow", "settings_corruption defaults_restored")
        recoveryNotice.value = true
        emptyPreferences()
    },
)

enum class Theme { DARK, LIGHT, SYSTEM }

data class Preferences(
    val folder: String = "",
    val athlete: String = "",
    val theme: Theme = Theme.DARK,
    val rotation: Boolean = true,
    val unit: UnitWeight = UnitWeight.KG,
    val step: Double = 0.5,
    val initialSets: Int = 3,
    val minReps: Int = 8,
    val maxReps: Int = 12,
    val initialWeight: Double = 0.0,
    val vaultId: String = "",
) {
    val scope get() = vaultId.ifBlank { folder }
}

class Settings(private val context: Context) {
    val recovered = recoveryNotice.asStateFlow()
    fun acknowledgeRecovery() { recoveryNotice.value = false }

    private val folder = stringPreferencesKey("folder")
    private val vault = stringPreferencesKey("vault_id")
    private val athlete = stringPreferencesKey("athlete")
    private val theme = stringPreferencesKey("theme")
    private val rotation = booleanPreferencesKey("rotation")
    private val unit = stringPreferencesKey("unit")
    private val step = doublePreferencesKey("step")
    private val sets = intPreferencesKey("sets")
    private val min = intPreferencesKey("min")
    private val max = intPreferencesKey("max")
    private val weight = doublePreferencesKey("weight")

    val values = context.settingsStore.data.map { row ->
        val low = (row.asMap()[min] as? Int)?.takeIf { it in 1..999 } ?: 8
        val high = (row.asMap()[max] as? Int)?.takeIf { it in low..999 } ?: maxOf(low, 12)
        Preferences(
            folder = (row.asMap()[folder] as? String).orEmpty(),
            vaultId = (row.asMap()[vault] as? String).orEmpty(),
            athlete = (row.asMap()[athlete] as? String).orEmpty(),
            theme = Theme.entries.find { it.name == row.asMap()[theme] } ?: Theme.DARK,
            rotation = row.asMap()[rotation] as? Boolean ?: true,
            unit = UnitWeight.entries.find { it.name == row.asMap()[unit] } ?: UnitWeight.KG,
            step = (row.asMap()[step] as? Double)?.takeIf { it.isFinite() && it > 0 } ?: 0.5,
            initialSets = (row.asMap()[sets] as? Int)?.takeIf { it in 1..30 } ?: 3,
            minReps = low,
            maxReps = high,
            initialWeight = (row.asMap()[weight] as? Double)?.takeIf { it.isFinite() && it >= 0 } ?: 0.0,
        )
    }

    suspend fun update(prefs: Preferences) {
        context.settingsStore.edit { row ->
            row[athlete] = prefs.athlete
            row[theme] = prefs.theme.name
            row[rotation] = prefs.rotation
            row[unit] = prefs.unit.name
            row[step] = prefs.step
            row[sets] = prefs.initialSets
            row[min] = prefs.minReps
            row[max] = prefs.maxReps
            row[weight] = prefs.initialWeight
        }
    }

    suspend fun selectFolder(value: String, vaultId: String) {
        context.settingsStore.edit { row ->
            row[folder] = value
            row[vault] = vaultId
        }
    }
}
