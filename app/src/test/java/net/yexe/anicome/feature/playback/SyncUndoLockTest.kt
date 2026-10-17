package net.yexe.anicome

import android.content.ContextWrapper
import androidx.datastore.preferences.core.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SyncUndoLockTest {
    @Test fun floatingUndoDoesNotConsumeThePendingOperationWhileLocked() = runTest {
        val context = ContextWrapper(null)
        val settings = SettingsRepository(MemoryPreferences().apply { updateData { it.toMutablePreferences().apply { this[stringPreferencesKey("lockMode")] = "pin" } } }, backgroundScope)
        val corrections = SyncAdjustmentRepository(backgroundScope)
        val locked = FloatingPanelRepository(context, settings, AppCoordinator(settings = settings, scope = backgroundScope), AppLockRepository(settings, backgroundScope),
            AdvancedSettingsRepository(settings, backgroundScope), DownloadRepository(context, settings, ReservationTransport()), backgroundScope, corrections = corrections)
        Session.offset.value = 0
        try {
            runCurrent(); corrections.global(1000); runCurrent()
            val id = corrections.undo.value!!.id
            locked.undoCorrection(id); runCurrent()
            assertEquals(1000L, Session.offset.value); assertEquals(id, corrections.undo.value!!.id)
            val unlockedSettings = SettingsRepository(MemoryPreferences(), backgroundScope)
            val unlocked = FloatingPanelRepository(context, unlockedSettings, AppCoordinator(settings = unlockedSettings, scope = backgroundScope), AppLockRepository(unlockedSettings, backgroundScope),
                AdvancedSettingsRepository(unlockedSettings, backgroundScope), DownloadRepository(context, unlockedSettings, ReservationTransport()), backgroundScope, corrections = corrections)
            runCurrent(); unlocked.undoCorrection(id)
            assertEquals(0L, Session.offset.value)
        } finally { Session.offset.value = 0 }
    }
}
