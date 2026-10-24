package net.yexe.anicome

import androidx.datastore.preferences.core.*
import org.json.JSONObject

data class CommentDisplayProfile(val size: Float = 15f, val speed: Float = .65f, val opacity: Float = .75f, val fps: Int = 60,
    val font: String = "default", val color: String = "original", val position: String = "center",
    val amount: Int = 5, val renderMode: String = "balanced", val customY: Float = 1f, val countMode: Boolean = true, val countLimit: Int = 7000) {
    fun json() = JSONObject().put("size", size).put("speed", speed).put("opacity", opacity).put("fps", fps).put("font", font)
        .put("color", color).put("position", position).put("amount", amount)
        .put("countMode", countMode).put("countLimit", countLimit).put("customY", customY).put("renderMode", renderMode).toString()
    fun writePosition(p: MutablePreferences) {
        p[stringPreferencesKey("overlayPosition")] = position
        p[floatPreferencesKey("overlayCustomY")] = customY
    }
    fun publishPosition() {
        Session.overlayPosition.value = position
        Session.customPosition.value = customY
    }
    fun write(p: MutablePreferences) {
        p[floatPreferencesKey("overlayCustomY")] = customY
        p[floatPreferencesKey("commentSize")] = size; p[floatPreferencesKey("speedScale")] = speed; p[floatPreferencesKey("opacity")] = opacity
        p[intPreferencesKey("fps")] = fps; p[stringPreferencesKey("fontStyle")] = font; p[stringPreferencesKey("colorStyle")] = color
        p[stringPreferencesKey("overlayPosition")] = position; p.remove(booleanPreferencesKey("flowOnly"))
        p[booleanPreferencesKey("commentCountMode")] = countMode; p[intPreferencesKey("commentCountLimit")] = countLimit;
        p[intPreferencesKey("commentAmount")] = amount; p.remove(booleanPreferencesKey("hideOnOwnApp")); p[stringPreferencesKey("renderMode")] = renderMode
    }
    fun publish(app: AppCoordinator) {
        Session.customPosition.value = customY; Session.size.value = size; Session.speedScale.value = speed; Session.opacity.value = opacity; Session.fps.value = fps
        Session.fontStyle.value = font; Session.colorStyle.value = color; Session.overlayPosition.value = position
        app.commentCountMode.value = countMode; app.commentCountLimit.value = countLimit;
        Session.commentAmount.value = amount; app.commentAmount.value = amount; Session.renderMode.value = renderMode
    }
    companion object {
        fun read(p: Preferences): CommentDisplayProfile {
            val s = SettingsSnapshot(p)
            return CommentDisplayProfile(s.getFloat("commentSize", 15f), s.getFloat("speedScale", .65f), s.getFloat("opacity", .75f), s.getInt("fps", 60),
                s.getString("fontStyle", "default")!!, s.getString("colorStyle", "original")!!, s.getString("overlayPosition", "center")!!,
                s.getInt("commentAmount", 5), s.getString("renderMode", "balanced")!!, s.getFloat("overlayCustomY", 1f).coerceIn(0f,1f), s.getBoolean("commentCountMode", true), s.getInt("commentCountLimit", 7000).coerceAtLeast(1))
        }
        fun parse(text: String): CommentDisplayProfile? = runCatching {
            val j = JSONObject(text)
            CommentDisplayProfile(j.optDouble("size", 15.0).toFloat().coerceIn(5f,25f), j.optDouble("speed", .65).toFloat().coerceIn(.25f,1.5f),
                j.optDouble("opacity", .75).toFloat().coerceIn(.2f,1f), j.optInt("fps",60).coerceIn(15,144), j.optString("font","default"), j.optString("color","original"),
                j.optString("position","center").takeIf { it in setOf("top","center","bottom","custom") } ?: "center", j.optInt("amount",5).coerceAtLeast(1),
                j.optString("renderMode","balanced").takeIf { it in setOf("quality","balanced","battery") } ?: "balanced", j.optDouble("customY", 1.0).takeIf { it.isFinite() }?.toFloat()?.coerceIn(0f,1f) ?: 1f, j.optBoolean("countMode", false), j.optInt("countLimit", 5000).coerceAtLeast(1))
        }.getOrNull()
    }
}
