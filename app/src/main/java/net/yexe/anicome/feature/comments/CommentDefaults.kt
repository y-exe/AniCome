package net.yexe.anicome

val DefaultCommentSources = setOf(PlaybackSource.OFFICIAL, PlaybackSource.DANIME)
fun defaultCommentPosition(service: StreamingService) = when (service) {
    StreamingService.ABEMA, StreamingService.TVER -> "top"
    else -> "center"
}
fun defaultServiceProfile(service: StreamingService) = CommentDisplayProfile(position = defaultCommentPosition(service))
