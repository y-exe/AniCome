package net.yexe.anicome

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SourceFailurePropagationTest {
    @get:Rule val folder = TemporaryFolder()
    @Test fun accessFailuresAreNotReportedAsMissingEpisodes() = runTest {
        val settings = SettingsRepository(MemoryPreferences(), backgroundScope)
        val api = object : JsonApiService {
            override suspend fun get(url: String, headers: Map<String, String>): retrofit2.Response<okhttp3.ResponseBody> = error("Unexpected request")
            override suspend fun post(url: String, headers: Map<String, String>, body: okhttp3.RequestBody): retrofit2.Response<okhttp3.ResponseBody> = error("Unexpected request")
        }
        val repository = PlaybackSourceRepository(CommentRepository(CommentStore(folder.newFolder())), ApiClient(api), settings,
            videoSearch = { throw ApiHttpException(403) })
        try {
            repository.obtain(Episode("作品", 1), 1400, "", 1, {}, { _, _, _ -> }, requestedSources = setOf(PlaybackSource.DANIME))
            fail("Access failure expected")
        } catch (e: ApiHttpException) { assertEquals(403, e.status); assertEquals(AcquisitionFailure.ACCESS, acquisitionFailure(e)) }
    }
}
