package net.yexe.anicome

import androidx.datastore.preferences.core.*
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class SetupFlowTest {
    @Before fun setup() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun cleanup() { Dispatchers.resetMain() }
    @Test fun permissionsAndLoginAreRequiredAndGuideFinishesOnlyOnItsLastPage() = runTest {
        val settings = SettingsRepository(MemoryPreferences(), backgroundScope)
        val model = SetupViewModel(settings, SavedStateHandle())
        try {
            assertTrue(model.state.value.loaded)
            model.next(false, false, false); assertEquals(1, model.state.value.step)
            model.next(false, false, false); assertEquals(1, model.state.value.step)
            model.next(true, false, false); assertEquals(2, model.state.value.step)
            model.next(true, false, false); assertEquals(2, model.state.value.step)
            model.next(true, true, false); assertEquals(3, model.state.value.step)
            model.next(true, true, false); assertEquals(3, model.state.value.step)
            model.next(true, true, true); assertEquals(4, model.state.value.step)
            assertFalse(model.state.value.finished)
            model.next(true, true, true); assertEquals(6, model.state.value.step)
            assertFalse(model.state.value.finished)
            model.next(true, true, true); assertTrue(model.state.value.finished)
        } finally { model.viewModelScope.cancel() }
    }
    @Test fun helpAppearsOnlyAfterReturningWithoutPermissionAndRestoresAcrossRecreation() = runTest {
        val settings = SettingsRepository(MemoryPreferences(), backgroundScope)
        val saved = SavedStateHandle()
        val model = SetupViewModel(settings, saved)
        try {
            for (permission in listOf(1, 2)) {
                model.help(null); model.requested(permission)
                model.resumed(false, false); assertNull(model.state.value.help)
                model.background(); model.resumed(false, false)
                assertEquals(permission, model.state.value.help)
                val restored = SetupViewModel(settings, saved)
                assertEquals(permission, restored.state.value.help)
                restored.viewModelScope.cancel()
                model.help(null); model.requested(permission); model.background(); model.resumed(true, true)
                assertNull(model.state.value.help)
            }
        } finally { model.viewModelScope.cancel() }
    }
    @Test fun existingInstallationsKeepTheirSetupAndInterruptedFirstRunKeepsTheGuide() = runTest {
        val prefs = MemoryPreferences()
        prefs.updateData { it.toMutablePreferences().apply { this[booleanPreferencesKey("setupCompleted")] = true } }
        val settings = SettingsRepository(prefs, backgroundScope)
        val existing = SetupViewModel(settings, SavedStateHandle())
        assertTrue(existing.state.value.finished)
        existing.resumed(false, true); assertEquals(1, existing.state.value.step)
        existing.viewModelScope.cancel()
        prefs.updateData { it.toMutablePreferences().apply { this[booleanPreferencesKey("setupFlowStarted")] = true } }
        val interrupted = SetupViewModel(settings, SavedStateHandle(mapOf("step" to 4)))
        assertFalse(interrupted.state.value.finished); assertEquals(4, interrupted.state.value.step)
        interrupted.viewModelScope.cancel()
    }
}
