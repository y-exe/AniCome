package net.yexe.anicome

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class NiconicoProfile(val id: String, val name: String, val icon: String)
class NiconicoProfileRepository(private val api: ApiClient, scope: CoroutineScope) {
    private val mutable = MutableStateFlow<NiconicoProfile?>(null)
    val state = mutable.asStateFlow()
    private val retry = MutableStateFlow(0)
    fun refresh() { retry.update { it + 1 } }
    init { scope.launch {
        combine(NiconicoLogin.profileId, retry) { id, _ -> id }.collectLatest { id ->
            mutable.value = null
            if (id != null) try {
                val user = api.request("https://nvapi.nicovideo.jp/v1/users/$id").getJSONObject("data").getJSONObject("user")
                val icons = user.optJSONObject("icons")
                mutable.value = NiconicoProfile(id, user.optString("nickname"), icons?.optString("large").orEmpty().ifBlank { icons?.optString("small").orEmpty() })
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.value = NiconicoProfile(id, "", "") }
        }
    } }
}
