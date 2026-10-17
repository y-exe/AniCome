package net.yexe.anicome

import android.webkit.CookieManager

 
object NiconicoLogin {
    @Volatile private var rejected = false
    @Volatile private var confirmedProfile: Pair<String, String>? = null
    val profileId = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    fun sessionCookie(): String? = runCatching {
        CookieManager.getInstance().getCookie("https://www.nicovideo.jp")
            ?.split(';')
            ?.map(String::trim)
            ?.firstOrNull { it.startsWith("user_session=") }
            ?.takeIf { it.length > "user_session=".length }
    }.getOrNull()

    fun userId(): String? {
        val session = sessionCookie() ?: return null
        return confirmedProfile?.takeIf { it.first == session }?.second ?: extractNiconicoUserId(session.substringAfter("user_session="))
    }
    fun recordViewer(session: String?, response: org.json.JSONObject) {
        val id = niconicoViewerId(response) ?: return
        if (session == null || session != sessionCookie() || rejected) return
        confirmedProfile = session to id
        profileId.value = id
    }
    fun publishProfile() { profileId.value = if (isLinked()) userId() else null }


    fun isLinked(): Boolean = !rejected && sessionCookie() != null

    fun markLinked() { rejected = false; publishProfile() }

    fun markRejected() { rejected = true; confirmedProfile = null; profileId.value = null }

    fun unlink() {
        confirmedProfile = null
        profileId.value = null
        runCatching {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        }
        rejected = false
    }
}

fun extractNiconicoUserId(value: String?): String? = value?.let { Regex("^user_session_([1-9][0-9]*)_").find(it)?.groupValues?.get(1) }

fun niconicoViewerId(response: org.json.JSONObject): String? {
    val viewer = response.optJSONObject("viewer") ?: return null
    if (!viewer.optBoolean("isLoggedIn")) return null
    return viewer.optString("userId").ifBlank { viewer.optString("id") }.takeIf { it.matches(Regex("[1-9][0-9]*")) }
}
