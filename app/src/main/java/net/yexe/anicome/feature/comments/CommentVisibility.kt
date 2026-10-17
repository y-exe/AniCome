package net.yexe.anicome

fun renderableComment(comment: Comment, ng: List<String>): Boolean =
    ng.none { comment.body.contains(it, ignoreCase = true) }
