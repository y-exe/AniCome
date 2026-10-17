package net.yexe.anicome

import java.net.URI
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class BandaiScreenTitle(val series: String, val episode: String)

fun bandaiVideoIdentity(mediaId: String?, mediaUri: String?): String {
    val value = mediaId?.trim()?.takeIf { it.isNotBlank() }?.let { "id:$it" }
        ?: runCatching {
            val uri = URI(mediaUri.orEmpty())
            if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.rawPath.isNullOrBlank()) null
            else "uri:${uri.host.lowercase()}${uri.rawPath}"
        }.getOrNull() ?: return ""
    return "bandai:" + MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

class BandaiTitleRepository(private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val mutableIdentity = MutableStateFlow("")
    val identity = mutableIdentity.asStateFlow()
    private val mutableTitle = MutableStateFlow<BandaiScreenTitle?>(null)
    val title = mutableTitle.asStateFlow()
    private val mutableEnabled = MutableStateFlow(false)
    val enabled = mutableEnabled.asStateFlow()
    private val known = linkedMapOf<String, BandaiScreenTitle>()
    private var changedAt = 0L
    private var lastBoundIdentity = ""
    private var lastBoundTitle: BandaiScreenTitle? = null

    fun mediaChanged(identity: String) {
        if (mutableIdentity.value == identity) return
        mutableIdentity.value = identity
        changedAt = now()
        mutableTitle.value = if (mutableEnabled.value) known[identity] else null
    }

    fun setEnabled(enabled: Boolean) {
        mutableEnabled.value = enabled
        if (!enabled) {
            mutableTitle.value = null
            known.clear()
            lastBoundTitle = null
            lastBoundIdentity = ""
        }
    }

    fun observeScreen(series: String, episode: String) {
        val identity = mutableIdentity.value
        if (!mutableEnabled.value || identity.isBlank() || now() - changedAt < 1000) return
        val incoming = BandaiScreenTitle(series.trim(), episode.trim())
        if (incoming.series.isBlank() || EpisodeMatcher.parse(incoming.series, incoming.episode) == null) return
        val existing = known[identity]
        if (existing != null) return
        if (lastBoundIdentity != identity && incoming == lastBoundTitle) return
        known[identity] = incoming
        while (known.size > 32) known.remove(known.keys.first())
        lastBoundIdentity = identity
        lastBoundTitle = incoming
        mutableTitle.value = incoming
    }
}
