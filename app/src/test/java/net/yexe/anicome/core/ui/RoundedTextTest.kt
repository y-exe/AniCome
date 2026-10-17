package net.yexe.anicome

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import org.junit.Test
import org.junit.Assert.*

class RoundedTextTest {
    @Test fun appliesJapaneseFontOnlyToJapaneseRuns() {
        val source = AnnotatedString("NGワード AniCome 144fps 日本語")
        val result = roundedText(source)
        assertEquals(source.text, result.text)
        assertEquals(listOf("ワード", "日本語"), result.spanStyles.map { source.text.substring(it.start, it.end) })
        assertTrue(result.spanStyles.all { it.item.fontFamily == JapaneseRoundedFont && it.item.fontWeight == FontWeight.SemiBold })
    }
    @Test fun appliesRoundedChineseAndKoreanFontsWithoutChangingLatin() {
        val chinese = roundedText(AnnotatedString("NG屏蔽词"), ChineseRoundedFont)
        assertEquals("屏蔽词", chinese.text.substring(chinese.spanStyles.single().start, chinese.spanStyles.single().end))
        assertEquals(ChineseRoundedFont, chinese.spanStyles.single().item.fontFamily)
        val korean = roundedText(AnnotatedString("NG차단 단어"))
        assertTrue(korean.spanStyles.all { it.item.fontFamily == KoreanRoundedFont })
        assertTrue(korean.spanStyles.all { it.start >= 2 })
    }
    @Test fun preservesSelectionOffsetsAndLatinText() {
        val source = AnnotatedString("NGワード")
        val result = RoundedInput.filter(source)
        assertEquals(source.text, result.text.text)
        assertEquals(4, result.offsetMapping.originalToTransformed(4))
        assertEquals(4, result.offsetMapping.transformedToOriginal(4))
        assertTrue(roundedText(AnnotatedString("AniCome 144fps")).spanStyles.isEmpty())
    }
}
