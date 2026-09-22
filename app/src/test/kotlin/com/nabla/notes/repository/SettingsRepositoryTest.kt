package com.nabla.notes.repository

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for SettingsRepository folder stack persistence.
 *
 * Uses [InMemoryPreferencesDataStore] rather than a real file-backed DataStore:
 * the latter hits a longstanding upstream Windows bug on every write (see that
 * class's doc comment) that has nothing to do with SettingsRepository's own logic.
 */
class SettingsRepositoryTest {

    private fun buildRepo(): SettingsRepository = SettingsRepository(InMemoryPreferencesDataStore())

    @Test
    fun `saveLastFolderStack and loadLastFolderStack round-trip single entry`() = runTest {
        val repo = buildRepo()
        val stack = listOf(Pair("id1", "Projects"))
        repo.saveLastFolderStack(stack)
        assertEquals(stack, repo.loadLastFolderStack())
    }

    @Test
    fun `saveLastFolderStack and loadLastFolderStack round-trip multiple entries`() = runTest {
        val repo = buildRepo()
        val stack = listOf(Pair("abc123", "Projects"), Pair("def456", "2026"))
        repo.saveLastFolderStack(stack)
        assertEquals(stack, repo.loadLastFolderStack())
    }

    @Test
    fun `loadLastFolderStack returns empty list when nothing saved`() = runTest {
        val repo = buildRepo()
        assertTrue(repo.loadLastFolderStack().isEmpty())
    }

    @Test
    fun `saveLastFolderStack with empty list clears stored value`() = runTest {
        val repo = buildRepo()
        repo.saveLastFolderStack(listOf(Pair("id1", "Folder")))
        repo.saveLastFolderStack(emptyList())
        assertTrue(repo.loadLastFolderStack().isEmpty())
    }

    @Test
    fun `folder names with spaces survive round-trip`() = runTest {
        val repo = buildRepo()
        val stack = listOf(Pair("id1", "My Notes"), Pair("id2", "Work Projects"))
        repo.saveLastFolderStack(stack)
        assertEquals(stack, repo.loadLastFolderStack())
    }
}
