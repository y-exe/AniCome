package net.yexe.anicome

internal fun alignQuantizedPlaybackTime(displayed: Long, estimated: Long?): Long =
    estimated?.takeIf { it >= displayed && it - displayed < 1000 } ?: displayed
