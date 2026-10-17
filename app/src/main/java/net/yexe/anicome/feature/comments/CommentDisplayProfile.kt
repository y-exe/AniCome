package net.yexe.anicome

import androidx.datastore.preferences.core.*
import org.json.JSONObject

data class CommentDisplayProfile(val size: Float = 14f, val speed: Float = .7f, val opacity: Float = .75f, val fps: Int = 60,
    val font: String = "default", val color: String = "original", val position: String = "center",
    val amount: Int = 5, val renderMode: String = "balanced", val customY: Float = 1f) {
    fun json() = JSONObject().put("size", size).put("speed", speed).put("opacity", opacity).put("fps", fps).put("font", font)
        .put("color", color).put("position", position).put("amount", amount)
        .put("customY", customY).put("renderMode", renderMode).toString()
    fun write(p: MutablePreferences) {
        p[floatPreferencesKey("overlayCustomY")] = customY
        p[floatPreferencesKey("commentSize")] = size; p[floatPreferencesKey("speedScale")] = speed; p[floatPreferencesKey("opacity")] = opacity
        p[intPreferencesKey("fps")] = fps; p[stringPreferencesKey("fontStyle")] = font; p[stringPreferencesKey("colorStyle")] = color
        p[stringPreferencesKey("overlayPosition")] = position; p.remove(booleanPreferencesKey("flowOnly"))
        p[intPreferencesKey("commentAmount")] = amount; p.remove(booleanPreferencesKey("hideOnOwnApp")); p[stringPreferencesKey("renderMode")] = renderMode
    }
    fun publish(app: AppCoordinator) {
        Session.customPosition.value = customY; Session.size.value = size; Session.speedScale.value = speed; Session.opacity.value = opacity; Session.fps.value = fps
        Session.fontStyle.value = font; Session.colorStyle.value = color; Session.overlayPosition.value = position
        Session.commentAmount.value = amount; app.commentAmount.value = amount; Session.renderMode.value = renderMode
    }
    companion object {
        fun read(p: Preferences): CommentDisplayProfile {
            val s = SettingsSnapshot(p)
            return CommentDisplayProfile(s.getFloat("commentSize", 14f), s.getFloat("speedScale", .7f), s.getFloat("opacity", .75f), s.getInt("fps", 60),
                s.getString("fontStyle", "default")!!, s.getString("colorStyle", "original")!!, s.getString("overlayPosition", "center")!!,
                s.getInt("commentAmount", 5), s.getString("renderMode", "balanced")!!, s.getFloat("overlayCustomY", 1f).coerceIn(0f,1f))
        }
        fun parse(text: String): CommentDisplayProfile? = runCatching {
            val j = JSONObject(text)
            CommentDisplayProfile(j.optDouble("size", 14.0).toFloat().coerceIn(5f,25f), j.optDouble("speed", .7).toFloat().coerceIn(.25f,1.5f),
                j.optDouble("opacity", .75).toFloat().coerceIn(.2f,1f), j.optInt("fps",60).coerceIn(15,144), j.optString("font","default"), j.optString("color","original"),
                j.optString("position","center").takeIf { it in setOf("top","center","bottom","custom") } ?: "center", j.optInt("amount",5).coerceIn(1,20),
                j.optString("renderMode","balanced").takeIf { it in setOf("quality","balanced","battery") } ?: "balanced", j.optDouble("customY", 1.0).takeIf { it.isFinite() }?.toFloat()?.coerceIn(0f,1f) ?: 1f)
        }.getOrNull()
    }
}
