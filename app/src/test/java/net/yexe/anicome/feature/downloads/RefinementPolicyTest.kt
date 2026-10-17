package net.yexe.anicome

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import org.junit.Test
import org.junit.Assert.*

class RefinementPolicyTest {
    @Test fun fallsBackAfterEmptyAndFailedSearch() = runBlocking {
        var calls = 0
        val result = searchWithFallback(listOf({ calls++; emptyList() }, { calls++; error("通信エラー") }, { calls++; listOf("ご注文はうさぎですか") }))
        assertEquals(listOf("ご注文はうさぎですか"), result)
        assertEquals(3, calls)
    }
    @Test fun stopsAfterTheFirstNonEmptySearch() = runBlocking {
        assertEquals(listOf("作品"), searchWithFallback(listOf({ listOf("作品") }, { error("呼び出してはいけない") })))
    }
    @Test fun successfulEmptySearchIsDistinctFromNetworkFailure() = runBlocking {
        assertTrue(searchWithFallback<String>(listOf({ emptyList() }, { error("通信エラー") })).isEmpty())
    }
    @Test(expected = IllegalStateException::class) fun reportsAllProviderFailures() = runBlocking {
        searchWithFallback<String>(listOf({ error("通信エラー") }, { error("通信エラー") }))
        Unit
    }
    @Test(expected = CancellationException::class) fun propagatesCancelledSearch() = runBlocking {
        searchWithFallback<String>(listOf({ throw CancellationException() }, { error("呼び出してはいけない") }))
        Unit
    }
    @Test fun snapsPercentageAndSizeValuesToRequestedIntervals() {
        assertEquals(.55f, snapSliderValue(.537f, .2f..1f, 15), .0001f)
        assertEquals(.7f, snapSliderValue(.712f, .25f..1f, 14), .0001f)
        assertEquals(18f, snapSliderValue(17.6f, 5f..25f, 19), .0001f)
        assertEquals(500f, snapSliderValue(630f, -15000f..15000f, 59), .0001f)
        assertEquals(144f, snapSliderValue(155f, 15f..144f, 128), .0001f)
    }
    @Test fun extractsOnlyTheIdFromSupportedNiconicoSessions() {
        assertEquals("12345", extractNiconicoUserId("user_session_12345_example"))
        assertNull(extractNiconicoUserId("unsupported_example"))
        assertNull(extractNiconicoUserId(null))
    }
}
