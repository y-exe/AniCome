package net.yexe.anicome

fun commentSyncExplanation(sync: CommentSync): String = when (sync) {
    CommentSync.NATIVE -> "本編と同じ時刻を使います。手動補正は別に適用します。"
    CommentSync.OFFSET -> "開始位置を本編に合わせています。途中でずれる場合は手動で調整してください。"
    CommentSync.CUT -> "コメントの反応からCM区間を推定して補正しています。ずれがあれば手動で調整してください。"
    CommentSync.START_ONLY -> "開始位置だけ補正しています。途中のCMによるずれは未補正です。"
    CommentSync.UNKNOWN -> "同期の手がかりが足りません。表示する前に時刻を確認してください。"
    CommentSync.DISABLED -> "自動補正はオフです。必要に応じて手動で時刻を合わせてください。"
}
