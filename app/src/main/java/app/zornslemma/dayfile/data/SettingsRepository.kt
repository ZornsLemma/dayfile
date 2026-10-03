package app.zornslemma.dayfile.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.time.LocalTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

open class SettingsRepository(context: Context) {
    private val dataStore = context.dataStore
    private val dayStartMinutesKey = intPreferencesKey("day_start_minutes")
    private val csvBomKey = booleanPreferencesKey("csv_export_bom")
    private val historyRetentionDaysKey = intPreferencesKey("history_retention_days")
    // This isn't really a "setting" but I think it's OK for it to live here.
    private val defaultCategoriesCreatedKey = booleanPreferencesKey("default_categories_created")

    open val dayStartTime: Flow<LocalTime> =
        dataStore.data.map { prefs ->
            val minutes = prefs[dayStartMinutesKey] ?: DEFAULT_DAY_START_MINUTES
            LocalTime.of(minutes / 60, minutes % 60)
        }

    val csvBomEnabled: Flow<Boolean> =
        dataStore.data.map { prefs -> prefs[csvBomKey] ?: DEFAULT_CSV_BOM }

    open val historyRetentionDays: Flow<Int> =
        dataStore.data.map { prefs ->
            prefs[historyRetentionDaysKey] ?: DEFAULT_HISTORY_RETENTION_DAYS
        }

    suspend fun setDayStartTime(time: LocalTime) {
        val minutes = time.hour * 60 + time.minute
        dataStore.edit { prefs -> prefs[dayStartMinutesKey] = minutes }
    }

    suspend fun setCsvBomEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[csvBomKey] = enabled }
    }

    suspend fun setHistoryRetentionDays(days: Int) {
        dataStore.edit { prefs -> prefs[historyRetentionDaysKey] = days }
    }

    suspend fun setDefaultCategoriesCreated() {
        dataStore.edit { prefs -> prefs[defaultCategoriesCreatedKey] = true }
    }

    suspend fun wereDefaultCategoriesCreated(): Boolean {
        return dataStore.data.first()[defaultCategoriesCreatedKey] ?: false
    }

    companion object {
        private const val DEFAULT_DAY_START_MINUTES = 4 * 60 // 04:00
        const val DEFAULT_CSV_BOM = false
        const val DEFAULT_HISTORY_RETENTION_DAYS = 7

        // Derived from DEFAULT_DAY_START_MINUTES so the two cannot drift. Exposed for callers
        // needing the shipped default as a LocalTime (e.g. StateFlow placeholder initial values);
        // tests deliberately do NOT use this, pinning their own literal 04:00 so they stay
        // meaningful if the production default ever changes.
        val DEFAULT_DAY_START: LocalTime =
            LocalTime.of(DEFAULT_DAY_START_MINUTES / 60, DEFAULT_DAY_START_MINUTES % 60)
    }
}
