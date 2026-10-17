package net.yexe.anicome

import org.junit.Assert.*
import org.junit.Test

class NgWordsTest {
    @Test fun trimsBlankLinesAndMergesDuplicateWords() {
        assertEquals(listOf("ネタバレ", "広告"), normalizeNgWords(" ネタバレ \r\n\n広告\nネタバレ\n"))
    }
    @Test fun exportedWordsCanBeImportedAgain() {
        val words = listOf("こんにちは", "spoiler", "ネタバレ")
        assertEquals(words, normalizeNgWords(words.joinToString("\n")))
    }
}
