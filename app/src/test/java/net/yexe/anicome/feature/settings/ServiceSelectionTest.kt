package net.yexe.anicome

import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ServiceSelectionTest {
    @Test fun supportedServicesAreGroupedWithoutHuluOrFod() {
        assertFalse(StreamingService.HULU in StreamingService.displayOrder)
        assertFalse(StreamingService.FOD in StreamingService.displayOrder)
        assertTrue(StreamingService.standardServices.none { it.requiresAccessibility })
        assertTrue(StreamingService.accessibilityServices.all { it.requiresAccessibility })
        assertEquals(9, StreamingService.displayOrder.distinct().size)
    }

    @Test fun permissionAndFirstUseExplanationAreRequiredBeforeSelection() = runTest {
        val permission = BandaiTitleRepository()
        val settings = SettingsRepository(MemoryPreferences(), backgroundScope)
        val model = ServiceSelectionViewModel(permission, settings)
        assertTrue(model.choose(StreamingService.NETFLIX))
        assertFalse(model.choose(StreamingService.PRIME_VIDEO))
        model.waitForPermission()
        assertNull(model.confirm())
        assertNotNull(model.request.value)
        permission.setEnabled(true)
        assertEquals(StreamingService.PRIME_VIDEO, model.confirm())
        runCurrent()
        assertTrue(model.choose(StreamingService.PRIME_VIDEO))
        assertFalse(model.choose(StreamingService.D_ANIME))
        model.cancel()
        assertFalse(model.choose(StreamingService.D_ANIME))
        assertEquals(StreamingService.D_ANIME, model.confirm())
        runCurrent()
        permission.setEnabled(false)
        assertFalse(model.choose(StreamingService.PRIME_VIDEO))
        assertNull(model.confirm())
        assertFalse(model.choose(StreamingService.HULU))
        assertFalse(model.choose(StreamingService.FOD))
    }
}
