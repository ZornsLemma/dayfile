package app.zornslemma.dayfile.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.appStateDataStore by preferencesDataStore(name = "app_state")

/**
 * Holds app-level UI state that must survive process death reliably (unlike SavedStateHandle). This
 * is intentionally separate from SettingsRepository, which is for user-configurable preferences.
 * The selected logical date, the manual protection override and the history filter are not user
 * settings; they are transient app state that we nevertheless want to persist durably (including
 * across swipe-away from overview).
 *
 * The background timestamp is deliberately NOT here. It is session signal, not durable user data,
 * and its lifetime is tied to the task rather than to the process: it lives in a SavedStateHandle
 * (see ResetViewModel) so that it is discarded exactly when the user removes the task from the
 * overview screen. Persisting it here - in a DataStore that outlives the task - is what made
 * "swiped away while foregrounded" and "swiped away while backgrounded" behave differently.
 */
class AppStateRepository(context: Context) {
    private val dataStore = context.applicationContext.appStateDataStore
    private val selectedDateKey = stringPreferencesKey("selected_logical_date")
    private val protectionOverrideKey = booleanPreferencesKey("protection_override")
    private val historyFilterCategoryKey = longPreferencesKey("history_filter_category")
    private val historyFilterIncludeDeletedKey =
        booleanPreferencesKey("history_filter_include_deleted")

    // Manual protection override: null means "no manual override - use the date-based default"
    // (historical dates default to protected, the current date to unprotected). Like every other
    // piece of state here it lives in DataStore, so it is shared by all repository instances and
    // survives process death. It is dropped by every date movement (see setSelectedDate), so its
    // natural lifetime is the selection's lifetime, not that of an indefinite setting.
    val protectionOverride: Flow<Boolean?> =
        dataStore.data.map { prefs -> prefs[protectionOverrideKey] }

    val selectedDate: Flow<LocalDate?> =
        dataStore.data.map { prefs -> prefs[selectedDateKey]?.let { LocalDate.parse(it) } }

    val historyFilterCategory: Flow<Long?> =
        dataStore.data.map { prefs -> prefs[historyFilterCategoryKey]?.let { it.toLong() } }

    val historyFilterIncludeDeleted: Flow<Boolean> =
        dataStore.data.map { prefs -> prefs[historyFilterIncludeDeletedKey] ?: false }

    /**
     * Moves the selected date. This is the single funnel for every date movement - user navigation,
     * the date picker, the 10-minute background auto-reset (when the day actually changes),
     * first-install initialisation - and it always drops any manual protection override in the same
     * atomic write, so the new selection arrives with its default protection state (SPEC,
     * "Protection": moving to another date always resets to the default protection state for that
     * date). A future caller that needs to move the date while *keeping* the override must not
     * silently bypass this function: the pairing is deliberate and pinned by tests.
     */
    suspend fun setSelectedDate(date: LocalDate) {
        dataStore.edit { prefs ->
            prefs.remove(protectionOverrideKey)
            prefs[selectedDateKey] = date.format(DateTimeFormatter.ISO_LOCAL_DATE)
        }
    }

    suspend fun setProtectionOverride(isProtected: Boolean?) {
        dataStore.edit { prefs ->
            if (isProtected == null) {
                prefs.remove(protectionOverrideKey)
            } else {
                prefs[protectionOverrideKey] = isProtected
            }
        }
    }

    suspend fun setHistoryFilterCategory(categoryId: Long?) {
        dataStore.edit { prefs ->
            if (categoryId == null) {
                prefs.remove(historyFilterCategoryKey)
            } else {
                prefs[historyFilterCategoryKey] = categoryId
            }
        }
    }

    suspend fun setHistoryFilterIncludeDeleted(include: Boolean) {
        dataStore.edit { prefs -> prefs[historyFilterIncludeDeletedKey] = include }
    }

    suspend fun clearHistoryFilter() {
        dataStore.edit { prefs ->
            prefs.remove(historyFilterCategoryKey)
            prefs.remove(historyFilterIncludeDeletedKey)
        }
    }
}
