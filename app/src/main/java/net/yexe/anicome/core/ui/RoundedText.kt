package net.yexe.anicome

import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.style.*

val ChineseRoundedFont = FontFamily(Font(R.font.chiron_round, FontWeight.Medium))
val KoreanRoundedFont = FontFamily(Font(R.font.jua, FontWeight.Normal))
val LocalTitleMaxLines = staticCompositionLocalOf { Int.MAX_VALUE }
val JapaneseRoundedFont = FontFamily(Font(R.font.kosugi_maru, FontWeight.Normal))
fun isJapaneseGlyph(char: Char): Boolean = char.code in 0x3000..0x30ff || char.code in 0x3400..0x9fff || char.code in 0xf900..0xfaff || char.code in 0xff01..0xff60
fun roundedText(text: AnnotatedString, cjkFont: FontFamily = JapaneseRoundedFont): AnnotatedString = buildAnnotatedString {
    append(text)
    var korean = 0
    while (korean < text.length) {
        fun hangul(char: Char) = char.code in 0x1100..0x11ff || char.code in 0x3130..0x318f || char.code in 0xac00..0xd7af
        if (!hangul(text[korean])) { korean++; continue }
        val start = korean
        while (korean < text.length && hangul(text[korean])) korean++
        addStyle(SpanStyle(fontFamily = KoreanRoundedFont, fontWeight = FontWeight.Normal), start, korean)
    }
    var index = 0
    while (index < text.length) {
        if (!isJapaneseGlyph(text[index])) { index++; continue }
        val start = index
        while (index < text.length && isJapaneseGlyph(text[index])) index++
        addStyle(SpanStyle(fontFamily = cjkFont, fontWeight = if (cjkFont == ChineseRoundedFont) FontWeight.Medium else FontWeight.SemiBold), start, index)
    }
}
object RoundedInput : VisualTransformation {
    override fun filter(text: AnnotatedString) = TransformedText(roundedText(text), OffsetMapping.Identity)
}
@Composable fun rememberRoundedInput(): VisualTransformation {
    val chinese = LocalUiLanguage.current == "zh"
    return remember(chinese) { object : VisualTransformation {
        override fun filter(text: AnnotatedString) = TransformedText(roundedText(text, if (chinese) ChineseRoundedFont else JapaneseRoundedFont), OffsetMapping.Identity)
    } }
}
@Composable fun AppText(text: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified, textAlign: TextAlign? = null, overflow: TextOverflow = if (LocalTitleMaxLines.current == 1) TextOverflow.Ellipsis else TextOverflow.Clip, maxLines: Int = LocalTitleMaxLines.current, style: TextStyle = LocalTextStyle.current, localize: Boolean = true, uiContext: String? = null, cjkFont: FontFamily? = null) {
    val display = if (localize) uiText(text, uiContext) else text
    val chinese = localize && LocalUiLanguage.current == "zh"
    val font = cjkFont ?: if (chinese) ChineseRoundedFont else JapaneseRoundedFont
    val annotated = remember(display, font) { roundedText(AnnotatedString(display), font) }
    androidx.compose.material3.Text(annotated, modifier, color = color, textAlign = textAlign, overflow = overflow, maxLines = maxLines, style = style)
}
