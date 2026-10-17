package net.yexe.anicome

import android.provider.Settings
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import kotlinx.coroutines.delay

internal fun startupLetterAlpha(timeMs: Float, index: Int, stem: Boolean = false): Float =
    ((timeMs - 360f - index * 50f - if (stem) 55f else 0f) / 200f).coerceIn(0f, 1f)

@Composable internal fun StartupBrand(onFinished: () -> Unit) {
    val context = LocalContext.current
    val scale = remember(context) { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }
    val instant = LocalAdvancedSettings.current.reducedMotion || scale == 0f || LocalInspectionMode.current
    val finish by rememberUpdatedState(onFinished)
    var artworkReady by remember { mutableStateOf(instant) }
    val motion = remember { Animatable(if (instant) 1f else 0f) }
    LaunchedEffect(artworkReady, instant) {
        if (instant) { motion.snapTo(1f); finish() }
        else if (artworkReady) {
            motion.animateTo(1f, tween(1400, easing = LinearEasing))
            delay(180)
            finish()
        }
    }
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val word = "AniCome"
    val color = MaterialTheme.colorScheme.onBackground
    val desiredStyle = MaterialTheme.typography.displayLarge.copy(fontSize = 48.sp, lineHeight = 56.sp, fontWeight = FontWeight.SemiBold)
    val slideEasing = remember { CubicBezierEasing(.3f, 0f, 0f, 1f) }
    Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.Center) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
            val width = maxWidth.coerceAtMost(304.dp)
            val logoSize = 192.dp.coerceAtMost(width)
            val finalLogo = 62.dp
            val measured = measurer.measure(word, desiredStyle, softWrap = false)
            val allowedText = with(density) { (width - finalLogo).toPx() }
            val factor = (allowedText / measured.size.width.coerceAtLeast(1)).coerceIn(.1f, 1f)
            val style = desiredStyle.copy(fontSize = desiredStyle.fontSize * factor, lineHeight = desiredStyle.lineHeight * factor)
            val layout = measurer.measure(word, style, softWrap = false)
            val finalLogoPx = with(density) { finalLogo.toPx() }
            val logoScale = finalLogo / logoSize
            val shift = -layout.size.width / 2f
            Box(Modifier.width(width).height(224.dp).clipToBounds(), contentAlignment = Alignment.Center) {
                BrandLogo(Modifier.size(logoSize).graphicsLayer {
                    val time = motion.value * 900f
                    val shrink = FastOutSlowInEasing.transform((time / 300f).coerceIn(0f, 1f))
                    val slide = slideEasing.transform(((time - 300f) / 550f).coerceIn(0f, 1f))
                    scaleX = 1f + (logoScale - 1f) * shrink
                    scaleY = scaleX
                    translationX = shift * slide
                }, animated = !instant, animationSpeed = 1.9f, onDrawn = { artworkReady = true })
                Canvas(Modifier.size(with(density) { layout.size.width.toDp() }, with(density) { layout.size.height.toDp() })
                    .graphicsLayer {
                        val slide = slideEasing.transform(((motion.value * 900f - 300f) / 550f).coerceIn(0f, 1f))
                        translationX = (finalLogoPx + layout.size.width) / 2f + shift * slide
                    }.semantics { text = AnnotatedString(word) }) {
                    val time = motion.value * 900f
                    for (index in word.indices) {
                        val left = if (index == 0) 0f else layout.getHorizontalPosition(index, true)
                        val right = if (index == word.lastIndex) size.width else layout.getHorizontalPosition(index + 1, true)
                        val alpha = startupLetterAlpha(time, index)
                        if (word[index] == 'i') {
                            val split = layout.firstBaseline - style.fontSize.toPx() * .56f
                            clipRect(left, 0f, right, split) { drawText(layout, color = color.copy(alpha = alpha)) }
                            clipRect(left, split, right, size.height) { drawText(layout, color = color.copy(alpha = startupLetterAlpha(time, index, stem = true))) }
                        } else clipRect(left, 0f, right, size.height) { drawText(layout, color = color.copy(alpha = alpha)) }
                    }
                }
            }
        }
    }
}
