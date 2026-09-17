package com.nabla.notes.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nabla.voice.TranscriptEntry
import com.nabla.voice.TranscriptStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Dictation settings and transcript persistence, on the same DataStore<Preferences> instance
 * [SettingsRepository] uses — a separate class rather than folding into that one, since its
 * own doc comment scopes it to folder browsing state, not dictation.
 *
 * Also this app's [TranscriptStore] implementation — the Hilt @Binds for it lives in
 * [com.nabla.notes.di.VoiceModule]. Transcript entries are serialized the same tab-delimited
 * way chato's ChatoGatewayRepository used, not JSON — no library on the classpath here parses
 * tab-free text faster than it'd take to pull one in for three fields.
 *
 * typedNotes (2026-09-16 device-testing feedback) is deliberately just a plain string, not a
 * structured/interleaved-with-transcript type — the Notes tab is "just a box", independent of
 * the transcript, sent alongside it as its own field once summarization exists (P5) rather than
 * merged into one stream beforehand. An earlier round tried unifying spoken+typed into one
 * unified list (NoteLine) and that was the wrong direction per this feedback — removed.
 */
@Singleton
class DictationRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : TranscriptStore {

    companion object {
        private val KEY_AZURE_SPEECH_KEY = stringPreferencesKey("dictation_azure_speech_key")
        private val KEY_AZURE_SPEECH_REGION = stringPreferencesKey("dictation_azure_speech_region")
        private val KEY_CONTEXT_NOTES = stringPreferencesKey("dictation_context_notes")
        private val KEY_TYPED_NOTES = stringPreferencesKey("dictation_typed_notes")
        private val KEY_TRANSCRIPT = stringPreferencesKey("dictation_transcript")
        private const val DEFAULT_AZURE_REGION = "eastus"
    }

    suspend fun azureSpeechKey(): String = pref(KEY_AZURE_SPEECH_KEY, "")
    suspend fun azureSpeechRegion(): String = pref(KEY_AZURE_SPEECH_REGION, DEFAULT_AZURE_REGION)

    suspend fun saveAzureSettings(key: String, region: String) {
        dataStore.edit { prefs ->
            prefs[KEY_AZURE_SPEECH_KEY] = key.trim()
            prefs[KEY_AZURE_SPEECH_REGION] = region.trim().ifBlank { DEFAULT_AZURE_REGION }
        }
    }

    suspend fun contextNotes(): String = pref(KEY_CONTEXT_NOTES, "")
    suspend fun saveContextNotes(notes: String) {
        dataStore.edit { it[KEY_CONTEXT_NOTES] = notes }
    }

    suspend fun typedNotes(): String = pref(KEY_TYPED_NOTES, "")
    suspend fun saveTypedNotes(text: String) {
        dataStore.edit { it[KEY_TYPED_NOTES] = text }
    }

    /** Clears everything except Azure settings — those are config, not session state. */
    suspend fun clearSession() {
        dataStore.edit { prefs ->
            prefs.remove(KEY_CONTEXT_NOTES)
            prefs.remove(KEY_TYPED_NOTES)
            prefs.remove(KEY_TRANSCRIPT)
        }
    }

    override suspend fun load(): List<TranscriptEntry> {
        val raw = pref(KEY_TRANSCRIPT, "")
        if (raw.isBlank()) return emptyList()
        return raw.lines().mapNotNull { line ->
            val parts = line.split("\t", limit = 3)
            if (parts.size == 3) TranscriptEntry(parts[0], parts[1], parts[2]) else null
        }
    }

    override suspend fun save(entries: List<TranscriptEntry>) {
        val raw = entries.joinToString("\n") { "${it.timestamp}\t${it.speakerId}\t${it.text}" }
        dataStore.edit { it[KEY_TRANSCRIPT] = raw }
    }

    private suspend fun pref(key: Preferences.Key<String>, default: String?): String =
        dataStore.data.map { it[key] ?: default ?: "" }.first()
}
