package net.yexe.anicome

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.crossfade

@Composable fun AnimeArtwork(
    info: AnimeInfo?, modifier: Modifier = Modifier, contentDescription: String? = null,
    episode: Int? = null, imageOverride: String? = null, imageError: () -> Unit = {}
) {
    val artworkUrl = imageOverride?.takeIf(String::isNotBlank) ?: info?.imageUrl
    Surface(modifier.clip(RoundedCornerShape(6.dp)), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        if (artworkUrl.isNullOrBlank()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { AppIcon(Icons.Filled.Movie, contentDescription, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            Box(Modifier.fillMaxSize()) {
                AsyncImage(model = coil3.request.ImageRequest.Builder(LocalContext.current).data(artworkUrl).crossfade(true).build(), contentDescription = contentDescription, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(), onError = { imageError() })
                if (episode != null) Surface(color = Color.Black.copy(alpha = .56f), shape = RoundedCornerShape(topEnd = 12.dp), modifier = Modifier.align(Alignment.BottomStart)) {
                    AppText("第${episode}話", Modifier.padding(horizontal = 8.dp, vertical = 4.dp), color = Color.White, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable fun EpisodeArtwork(sourceId: String, thumbnailUrl: String, modifier: Modifier, description: String? = null) {
    val load = LocalVideoArtworkLoad.current
    val id = niconicoVideoId(sourceId)
    val resolved = id?.let { LocalVideoArtwork.current[it] }
    androidx.compose.runtime.LaunchedEffect(id, thumbnailUrl) { if (id != null && thumbnailUrl.isBlank()) load(id, false) }
    androidx.compose.runtime.key(resolved) {
        AnimeArtwork(null, modifier, description, imageOverride = resolved?.takeIf(String::isNotBlank) ?: thumbnailUrl,
            imageError = { if (id != null) load(id, true) })
    }
}

@Composable fun CatalogArtwork(info: AnimeInfo, modifier: Modifier, description: String? = null) {
    val supplement = if (info.imageUrl.isBlank()) rememberArtwork(info.title) else null
    AnimeArtwork(info, modifier, description, imageOverride = info.imageUrl.takeIf(String::isNotBlank) ?: supplement?.imageUrl)
}
