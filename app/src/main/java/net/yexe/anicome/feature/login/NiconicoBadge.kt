package net.yexe.anicome

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

@Composable internal fun NiconicoBadge(modifier: Modifier = Modifier) {
    Surface(modifier.size(56.dp), shape = ReferenceClover, color = MaterialTheme.colorScheme.secondaryContainer) {
        Box(contentAlignment = Alignment.Center) { coil3.compose.AsyncImage("https://resource.video.nimg.jp/web/images/favicon_uni/144.png?re", uiText("ニコニコ"), Modifier.size(34.dp), colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(MaterialTheme.colorScheme.onSecondaryContainer)) }
    }
}
