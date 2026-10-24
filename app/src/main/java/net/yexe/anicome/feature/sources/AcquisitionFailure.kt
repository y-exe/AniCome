package net.yexe.anicome

enum class AcquisitionFailure(val label: String, val explanation: String, val settingsPage: String? = null, val manual: Boolean = false) {
    NOT_FOUND("動画が見つかりません", "作品名・期・話数を確認するか、公式チャンネルを指定してください。", manual = true),
    UPCOMING("公開前の可能性があります", "作品情報の公開年が現在より先です。配信開始日を確認してください。", manual = true),
    ACCESS("認証・アクセス制限", "ログイン状態と動画の公開条件を確認してください。", "コメント取得"),
    NETWORK("通信エラー", "接続先・プロキシ・タイムアウト設定を確認して再試行してください。", "ネットワーク"),
    TRAFFIC_LIMIT("通信量の上限に達しました", "期間の切り替わりを待つか、ネットワーク設定で上限を変更してください。保存済みコメントはそのまま使えます。", "ネットワーク"),
    RATE_LIMIT("アクセスが集中しています", "時間を置いてから再試行してください。"),
    UNAVAILABLE("動画を利用できません", "削除・非公開や配信終了の可能性があります。別の動画を確認してください。", manual = true),
    STORAGE("保存容量が不足しています", "不要なデータを削除するか、保存容量の上限を変更してください。", "ダウンロード"),
    SYNC("同期情報が不足しています", "取得元の同期状態や時刻補正を確認してください。", "取得元"),
    UNKNOWN("取得できませんでした", "詳しいエラーを確認して再試行してください。", manual = true)
}
fun acquisitionFailure(error: Throwable, year: Int? = null): AcquisitionFailure {
    val status = (error as? ApiHttpException)?.status
    return when {
        error is TrafficLimitReached -> AcquisitionFailure.TRAFFIC_LIMIT
        status in setOf(401, 403) -> AcquisitionFailure.ACCESS
        status == 429 -> AcquisitionFailure.RATE_LIMIT
        status in setOf(404, 410) -> AcquisitionFailure.UNAVAILABLE
        error is MatchUnavailable && error.message.orEmpty().contains("同期") -> AcquisitionFailure.SYNC
        error is MatchUnavailable && year != null && year > java.time.Year.now().value -> AcquisitionFailure.UPCOMING
        error is MatchUnavailable -> AcquisitionFailure.NOT_FOUND
        Regex("空き容量|保存容量|容量の上限|ENOSPC|No space left|disk full|保存データが大き", RegexOption.IGNORE_CASE).containsMatchIn(error.message.orEmpty()) -> AcquisitionFailure.STORAGE
        error is java.io.IOException -> AcquisitionFailure.NETWORK
        else -> acquisitionFailure(error.message.orEmpty())
    }
}
fun acquisitionFailure(message: String): AcquisitionFailure = AcquisitionFailure.entries.firstOrNull { message.startsWith(it.label + ":") } ?: when {
    message.contains("通信量の上限") -> AcquisitionFailure.TRAFFIC_LIMIT
    Regex("(?:HTTP[^0-9]*|^)(?:401|403)\\b", RegexOption.IGNORE_CASE).containsMatchIn(message) || message.contains("ログイン") -> AcquisitionFailure.ACCESS
    Regex("(?:HTTP[^0-9]*|^)429\\b", RegexOption.IGNORE_CASE).containsMatchIn(message) -> AcquisitionFailure.RATE_LIMIT
    Regex("(?:HTTP[^0-9]*|^)(?:404|410)\\b", RegexOption.IGNORE_CASE).containsMatchIn(message) -> AcquisitionFailure.UNAVAILABLE
    message.contains("容量") || message.contains("空き") -> AcquisitionFailure.STORAGE
    message.contains("同期") -> AcquisitionFailure.SYNC
    listOf("通信", "timeout", "Unable", "offline", "HTTP", "接続").any { message.contains(it, true) } -> AcquisitionFailure.NETWORK
    listOf("見つかりません", "ありません", "特定できません", "一致しません").any(message::contains) -> AcquisitionFailure.NOT_FOUND
    else -> AcquisitionFailure.UNKNOWN
}
fun acquisitionFailureOutput(error: Throwable, year: Int? = null) = androidx.work.workDataOf("message" to error.message.orEmpty().take(300), "failureKind" to acquisitionFailure(error, year).name)
