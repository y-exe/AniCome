package net.yexe.anicome

import org.junit.Assert.*
import org.junit.Test

class AcquisitionFailureTest {
    @Test fun errorsDistinguishMissingAccessNetworkRateLimitUnavailableAndStorage() {
        assertEquals(AcquisitionFailure.NOT_FOUND, acquisitionFailure(OfficialMatchUnavailable("none")))
        assertEquals(AcquisitionFailure.ACCESS, acquisitionFailure(ApiHttpException(401)))
        assertEquals(AcquisitionFailure.ACCESS, acquisitionFailure(ApiHttpException(403)))
        assertEquals(AcquisitionFailure.RATE_LIMIT, acquisitionFailure(ApiHttpException(429)))
        assertEquals(AcquisitionFailure.UNAVAILABLE, acquisitionFailure(ApiHttpException(404)))
        assertEquals(AcquisitionFailure.NETWORK, acquisitionFailure(java.net.SocketTimeoutException("timeout")))
        assertEquals(AcquisitionFailure.STORAGE, acquisitionFailure(java.io.IOException("ENOSPC")))
        assertEquals(AcquisitionFailure.STORAGE, acquisitionFailure(IllegalStateException("保存容量の上限に達しました")))
        assertEquals(AcquisitionFailure.SYNC, acquisitionFailure(MatchUnavailable("同期に必要な情報がありません")))
        assertEquals(AcquisitionFailure.UPCOMING, acquisitionFailure(OfficialMatchUnavailable("none"), java.time.Year.now().value + 1))
        assertEquals(AcquisitionFailure.NOT_FOUND, acquisitionFailure(OfficialMatchUnavailable("none"), java.time.Year.now().value))
    }
    @Test fun visibleErrorLabelsKeepTheTypedCauseAcrossTheUiBoundary() {
        AcquisitionFailure.entries.forEach { failure -> assertEquals(failure, acquisitionFailure("${failure.label}: details")) }
    }
    @Test fun persistedFailuresKeepTheOriginalMessageAndRecommendedDestination() {
        val output = acquisitionFailureOutput(ApiHttpException(403))
        assertEquals("ACCESS", output.getString("failureKind"))
        assertTrue(output.getString("message")!!.contains("403"))
        assertEquals("コメント取得", AcquisitionFailure.valueOf(output.getString("failureKind")!!).settingsPage)
        assertEquals("ネットワーク", acquisitionFailure("取得失敗（HTTP 406）").settingsPage)
        assertTrue(acquisitionFailure("動画が見つかりません").manual)
        assertEquals(AcquisitionFailure.NOT_FOUND, acquisitionFailure("動画 so403 が見つかりません"))
        assertEquals(AcquisitionFailure.NOT_FOUND, acquisitionFailure("動画 so429 が見つかりません"))
    }
}
