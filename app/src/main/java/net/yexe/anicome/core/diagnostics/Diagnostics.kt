package net.yexe.anicome

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

object Diagnostics {
    val receiver = MutableStateFlow("通知リスナー未接続")
    val metadata = MutableStateFlow("再生情報未取得")
    val log = MutableStateFlow<List<String>>(emptyList())
    val renderer = MutableStateFlow("表示サービス停止中")
    val renderedAt = MutableStateFlow<Long?>(null)
    val renderedObservedAt = MutableStateFlow<Long?>(null)
    val viewport = MutableStateFlow("")
    fun trace(message: String) { log.update { (it + message).takeLast(12) } }
}
