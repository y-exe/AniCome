package net.yexe.anicome

import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.airbnb.lottie.compose.*
import kotlinx.coroutines.flow.first

@Composable internal fun BrandLogo(modifier: Modifier = Modifier, animated: Boolean = false, white: Boolean = false, cropped: Boolean = false, animationSpeed: Float = 1.5f, onDrawn: () -> Unit = {}, onFinished: () -> Unit = {}) {
    val finish by rememberUpdatedState(onFinished)
    val drawn by rememberUpdatedState(onDrawn)
    val resource = when { white && cropped -> R.drawable.app_logo_white_inline; cropped -> R.drawable.app_logo_inline; white -> R.drawable.app_logo_white; else -> R.drawable.app_logo }
    val context = LocalContext.current
    val inspection = LocalInspectionMode.current
    val durationScale = remember(context) { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f).coerceAtLeast(0f) }
    val reduced = LocalAdvancedSettings.current.reducedMotion || durationScale == 0f
    var finished by rememberSaveable { mutableStateOf(false) }
    if (!animated || white || reduced || inspection || finished) {
        LaunchedEffect(Unit) { drawn(); finish() }
        Image(painterResource(resource), "AniCome", modifier)
        return
    }
    val result = rememberLottieComposition(LottieCompositionSpec.Asset("logo-animation.json"))
    val composition by result
    LaunchedEffect(result.isFailure) { if (result.isFailure) { drawn(); finish() } }
    val animation = rememberLottieAnimatable()
    LaunchedEffect(composition) {
        if (composition != null) { snapshotFlow { animation.progress }.first { it >= .72f }; drawn() }
    }
    LaunchedEffect(composition) {
        composition?.let {
            animation.animate(it, iterations = 1, speed = animationSpeed / durationScale)
            finished = true
            finish()
        }
    }
    Box(modifier.semantics { contentDescription = "AniCome" }) {
        if (composition == null) Image(painterResource(resource), null, Modifier.matchParentSize())
        else LottieAnimation(composition, progress = { animation.progress }, modifier = Modifier.matchParentSize())
    }
}

@Composable internal fun BrandWordmark(compact: Boolean = false) {
    androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)) {
        BrandLogo(Modifier.size(if (compact) 24.dp else 40.dp), cropped = true)
        AppText("AniCome", style = if (compact) androidx.compose.material3.MaterialTheme.typography.titleLarge else androidx.compose.material3.MaterialTheme.typography.displaySmall, maxLines = 1)
    }
}
