package com.nabla.notes.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-memory [DataStore]<[Preferences]> for unit tests.
 *
 * Real (file-backed) DataStore hits a longstanding, still-unresolved upstream bug
 * on Windows where the atomic tmp-file rename in FileStorage intermittently throws
 * "Unable to rename ... This likely means that there are multiple instances of
 * DataStore for this file" even with exactly one instance and one file per test
 * (https://issuetracker.google.com/issues/173522155). Now in Android hit the same
 * thing and worked around it the same way: skip the file entirely for tests.
 *
 * Only the two DataStore members SettingsRepository actually calls are implemented.
 */
class InMemoryPreferencesDataStore : DataStore<Preferences> {
    private val state = MutableStateFlow<Preferences>(emptyPreferences())
    private val mutex = Mutex()

    override val data = state

    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
        mutex.withLock {
            val updated = transform(state.value)
            state.value = updated
            updated
        }
}
