package com.nabla.notes.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nabla.notes.model.AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages persistent app settings using DataStore<Preferences>.
 *
 * Settings:
 *  - folderPath: relative OneDrive folder path (e.g. "Documents/notes")
 *  - folderId:   cached OneDrive item ID for the folder
 */
@Singleton
class SettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    companion object {
        private val KEY_FOLDER_PATH = stringPreferencesKey("folder_path")
        private val KEY_FOLDER_ID = stringPreferencesKey("folder_id")
        private val KEY_LAST_FOLDER_STACK = stringPreferencesKey("last_folder_stack")
        private val KEY_EDITOR_FONT_SIZE = floatPreferencesKey("editor_font_size")

        const val DEFAULT_EDITOR_FONT_SIZE = 14f
    }

    /** Observe current settings as a Flow. */
    val settings: Flow<AppSettings> = dataStore.data.map { prefs ->
        AppSettings(
            folderPath = prefs[KEY_FOLDER_PATH] ?: "",
            folderId = prefs[KEY_FOLDER_ID] ?: "root"
        )
    }

    /** Save updated settings. */
    suspend fun saveSettings(settings: AppSettings) {
        dataStore.edit { prefs ->
            prefs[KEY_FOLDER_PATH] = settings.folderPath
            prefs[KEY_FOLDER_ID] = settings.folderId
        }
    }

    /**
     * Persist the current folder navigation stack.
     * Each entry encoded as "id::name", entries joined by "|".
     * Empty list clears the stored stack (back at root).
     */
    suspend fun saveLastFolderStack(stack: List<Pair<String, String>>) {
        dataStore.edit { prefs ->
            prefs[KEY_LAST_FOLDER_STACK] = stack.joinToString("|") { (id, name) ->
                "${id}::${name}"
            }
        }
    }

    /**
     * Load the last saved folder stack.
     * Returns an empty list if nothing was saved or if the stored data cannot be parsed.
     */
    suspend fun loadLastFolderStack(): List<Pair<String, String>> {
        return try {
            val raw = dataStore.data.map { it[KEY_LAST_FOLDER_STACK] ?: "" }.first()
            if (raw.isBlank()) emptyList()
            else raw.split("|").mapNotNull { entry ->
                val parts = entry.split("::")
                if (parts.size == 2) Pair(parts[0], parts[1]) else null
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Raw-text editor font size in sp, persisted across sessions. */
    val editorFontSize: Flow<Float> = dataStore.data.map { prefs ->
        prefs[KEY_EDITOR_FONT_SIZE] ?: DEFAULT_EDITOR_FONT_SIZE
    }

    suspend fun setEditorFontSize(sizeSp: Float) {
        dataStore.edit { prefs -> prefs[KEY_EDITOR_FONT_SIZE] = sizeSp }
    }
}
