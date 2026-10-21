package net.yexe.anicome

import org.json.JSONObject

internal object NiconicoWatch {
    suspend fun load(id: String, session: String?, client: ApiClient = ApiAccess.client): JSONObject {
        val url = "https://www.nicovideo.jp/watch/$id"
        val payload = try { client.requestText("$url?responseType=json&nvpcClientVersion=1", cookie = session) }
        catch (error: ApiHttpException) {
            if (error.status != 400) throw error
            client.requestText(url, cookie = session)
        }
        return parse(payload)
    }

    fun parse(payload: String): JSONObject {
        val json = if (payload.trimStart().startsWith("{")) JSONObject(payload) else {
            val content = Regex("<meta\\b(?:[^>\"']|\"[^\"]*\"|'[^']*')*>", RegexOption.IGNORE_CASE).findAll(payload).firstNotNullOfOrNull { tag ->
                val attributes = Regex("([\\w-]+)\\s*=\\s*([\"'])(.*?)\\2", RegexOption.DOT_MATCHES_ALL)
                    .findAll(tag.value).associate { it.groupValues[1].lowercase() to it.groupValues[3] }
                attributes["content"]?.takeIf { attributes["name"] == "server-response" }
            } ?: error("動画のコメント情報を確認できません")
            JSONObject(decodeAttribute(content))
        }
        val response = json.getJSONObject("data").getJSONObject("response")
        val modern = response.optJSONObject("\$watchV4")?.getJSONObject("data") ?: return response
        val owner = modern.optJSONObject("metadata")?.optJSONObject("jsonLd")?.optJSONObject("owner")
        if (owner != null) when (owner.optString("type")) {
            "channel" -> modern.put("channel", owner)
            "user" -> modern.put("owner", owner)
        }
        return modern
    }

    private fun decodeAttribute(value: String): String = Regex("&(#x[0-9a-fA-F]+|#\\d+|quot|apos|amp|lt|gt);").replace(value) { match ->
        when (val entity = match.groupValues[1]) {
            "quot" -> "\""
            "apos" -> "'"
            "amp" -> "&"
            "lt" -> "<"
            "gt" -> ">"
            else -> {
                val code = if (entity.startsWith("#x")) entity.drop(2).toIntOrNull(16) else entity.drop(1).toIntOrNull()
                code?.takeIf { Character.isValidCodePoint(it) && it !in 0xD800..0xDFFF }?.let { String(Character.toChars(it)) } ?: match.value
            }
        }
    }
}
