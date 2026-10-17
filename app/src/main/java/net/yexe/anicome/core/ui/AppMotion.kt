@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.animation.ExperimentalSharedTransitionApi::class)
package net.yexe.anicome

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset

@Composable fun detailMotion(pop: Boolean): ContentTransform {
    if (LocalAdvancedSettings.current.reducedMotion) return ContentTransform(EnterTransition.None, ExitTransition.None, sizeTransform = null)
    val motion = MaterialTheme.motionScheme
    val position = remember(motion) { motion.fastSpatialSpec<IntOffset>() }
    val scale = remember(motion) { motion.fastSpatialSpec<Float>() }
    val alpha = remember(motion) { motion.fastEffectsSpec<Float>() }
    return if (pop) {
        (slideInHorizontally(position) { -it / 3 } + scaleIn(scale, initialScale = .98f)) togetherWith
            (slideOutHorizontally(position) { it } + scaleOut(scale, targetScale = .97f))
    } else {
        (slideInHorizontally(position) { it } + fadeIn(alpha)) togetherWith
            (slideOutHorizontally(position) { -it / 3 } + fadeOut(alpha))
    }
}
@Composable fun tabMotion(forward: Boolean): ContentTransform {
    if (LocalAdvancedSettings.current.reducedMotion) return ContentTransform(EnterTransition.None, ExitTransition.None, sizeTransform = null)
    val motion = MaterialTheme.motionScheme
    val position = remember(motion) { motion.fastSpatialSpec<IntOffset>() }
    val alpha = remember(motion) { motion.fastEffectsSpec<Float>() }
    val direction = if (forward) 1 else -1
    return (slideInHorizontally(position) { (it * .2f * direction).toInt() } + fadeIn(alpha)) togetherWith
        (slideOutHorizontally(position) { (-it * .2f * direction).toInt() } + fadeOut(alpha))
}
fun Modifier.pressMotion(interactions: MutableInteractionSource): Modifier = composed {
    val reduce = LocalAdvancedSettings.current.reducedMotion
    val pressed by interactions.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && !reduce) .97f else 1f, MaterialTheme.motionScheme.fastSpatialSpec(), label = "pressScale")
    graphicsLayer { scaleX = scale; scaleY = scale }
}
fun Modifier.motionClickable(enabled: Boolean = true, role: Role? = Role.Button, onClick: () -> Unit): Modifier = composed {
    val interactions = remember { MutableInteractionSource() }
    pressMotion(interactions).clickable(interactions, ripple(), enabled = enabled, role = role, onClick = onClick)
}

data class ArtworkTransition(val shared: SharedTransitionScope, val visibility: AnimatedVisibilityScope)
val LocalArtworkTransition = staticCompositionLocalOf<ArtworkTransition?> { null }
@Composable fun Modifier.sharedArtwork(key: String): Modifier {
    if (LocalAdvancedSettings.current.reducedMotion) return this
    val transition = LocalArtworkTransition.current ?: return this
    val motion = MaterialTheme.motionScheme
    return with(transition.shared) {
        this@sharedArtwork.sharedElement(rememberSharedContentState("cover:$key"), transition.visibility,
            boundsTransform = { _, _ -> motion.fastSpatialSpec() })
    }
}
