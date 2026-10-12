package com.minh.screenshare.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit Test kiểm tra TextEditorHelper cho tiếng Việt, Emoji, vùng chọn và di chuyển con trỏ.
 */
class TextEditorHelperTest {

    @Test
    fun testInsertText() {
        val s0 = TextState("Hello", 5, 5)
        val s1 = TextEditorHelper.insertText(s0, " Việt Nam")
        assertEquals("Hello Việt Nam", s1.text)
        assertEquals(14, s1.selectionStart)
        assertEquals(14, s1.selectionEnd)

        // Chèn thay thế vùng chọn
        val s2 = TextState("Xin chào bạn!", 9, 12) // Chọn "bạn"
        val s3 = TextEditorHelper.insertText(s2, "Minh")
        assertEquals("Xin chào Minh!", s3.text)
        assertEquals(13, s3.selectionStart)
    }

    @Test
    fun testDeleteBackwardStandardAndVietnamese() {
        // Xóa ký tự thường
        val s0 = TextState("ABC", 3, 3)
        val s1 = TextEditorHelper.deleteBackward(s0)
        assertEquals("AB", s1.text)
        assertEquals(2, s1.selectionStart)

        // Xóa ký tự tiếng Việt có dấu
        val s2 = TextState("Tiếng Việt", 10, 10)
        val s3 = TextEditorHelper.deleteBackward(s2)
        assertEquals("Tiếng Việ", s3.text)
        assertEquals(9, s3.selectionStart)

        // Xóa vùng chọn
        val s4 = TextState("Hello World", 5, 11) // Chọn " World"
        val s5 = TextEditorHelper.deleteBackward(s4)
        assertEquals("Hello", s5.text)
        assertEquals(5, s5.selectionStart)
    }

    @Test
    fun testDeleteBackwardEmojiSurrogatePair() {
        // Chuỗi có emoji: "Hi 👋" ("👋" có 2 code units trong UTF-16)
        val textWithEmoji = "Hi \uD83D\uDC4B"
        val s0 = TextState(textWithEmoji, textWithEmoji.length, textWithEmoji.length)
        val s1 = TextEditorHelper.deleteBackward(s0)
        // Phải xóa nguyên vẹn cả 2 code units của emoji, không để lại ký tự rác
        assertEquals("Hi ", s1.text)
        assertEquals(3, s1.selectionStart)
    }

    @Test
    fun testDeleteForward() {
        val s0 = TextState("ABC", 1, 1) // Con trỏ giữa A và B
        val s1 = TextEditorHelper.deleteForward(s0)
        assertEquals("AC", s1.text)
        assertEquals(1, s1.selectionStart)

        // Xóa forward với Emoji
        val s2 = TextState("A\uD83D\uDC4BB", 1, 1) // Con trỏ trước emoji
        val s3 = TextEditorHelper.deleteForward(s2)
        assertEquals("AB", s3.text)
        assertEquals(1, s3.selectionStart)
    }

    @Test
    fun testCursorMovement() {
        val text = "A\uD83D\uDC4BB" // "A👋B"
        val s0 = TextState(text, 0, 0)

        // Sang phải 1 bước -> sau chữ A
        val s1 = TextEditorHelper.moveCursorRight(s0)
        assertEquals(1, s1.selectionStart)

        // Sang phải bước 2 -> nhảy qua toàn bộ Emoji (2 code units)
        val s2 = TextEditorHelper.moveCursorRight(s1)
        assertEquals(3, s2.selectionStart)

        // Sang trái 1 bước -> nhảy lùi qua toàn bộ Emoji
        val s3 = TextEditorHelper.moveCursorLeft(s2)
        assertEquals(1, s3.selectionStart)

        // Home và End
        val sHome = TextEditorHelper.moveCursorHome(s2)
        assertEquals(0, sHome.selectionStart)
        val sEnd = TextEditorHelper.moveCursorEnd(sHome)
        assertEquals(text.length, sEnd.selectionStart)

        // Select All
        val sAll = TextEditorHelper.selectAll(sEnd)
        assertEquals(0, sAll.selectionStart)
        assertEquals(text.length, sAll.selectionEnd)
        assertTrue(sAll.hasSelection)
    }
}
