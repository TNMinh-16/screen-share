package com.minh.screenshare.control

/**
 * Trạng thái văn bản gồm nội dung chuỗi và vị trí con trỏ / vùng chọn.
 */
data class TextState(
    val text: String,
    val selectionStart: Int,
    val selectionEnd: Int
) {
    init {
        require(selectionStart in 0..text.length) { "selectionStart $selectionStart nằm ngoài [0, ${text.length}]" }
        require(selectionEnd in 0..text.length) { "selectionEnd $selectionEnd nằm ngoài [0, ${text.length}]" }
    }

    val selMin: Int get() = minOf(selectionStart, selectionEnd)
    val selMax: Int get() = maxOf(selectionStart, selectionEnd)
    val hasSelection: Boolean get() = selectionStart != selectionEnd
}

/**
 * Trợ thủ chỉnh sửa văn bản thuần Kotlin (hỗ trợ tiếng Việt có dấu, Emoji surrogate pairs, vùng chọn).
 */
object TextEditorHelper {

    /**
     * Chèn chuỗi văn bản tại vị trí con trỏ hoặc thay thế vùng chọn hiện tại.
     */
    fun insertText(state: TextState, toInsert: String): TextState {
        val min = state.selMin
        val max = state.selMax
        val newText = state.text.substring(0, min) + toInsert + state.text.substring(max)
        val newPos = min + toInsert.length
        return TextState(newText, newPos, newPos)
    }

    /**
     * Xóa lùi (Backspace):
     * - Nếu có vùng chọn -> xóa toàn bộ vùng chọn.
     * - Nếu là con trỏ -> xóa 1 ký tự phía trước (hoặc 2 code units nếu là emoji/surrogate pair).
     */
    fun deleteBackward(state: TextState): TextState {
        if (state.hasSelection) {
            val min = state.selMin
            val max = state.selMax
            val newText = state.text.substring(0, min) + state.text.substring(max)
            return TextState(newText, min, min)
        }

        val pos = state.selectionStart
        if (pos <= 0) return state

        // Kiểm tra cặp ký tự Unicode Surrogate (ví dụ Emoji)
        val charsToDelete = if (pos >= 2 && Character.isSurrogatePair(state.text[pos - 2], state.text[pos - 1])) {
            2
        } else {
            1
        }

        val start = pos - charsToDelete
        val newText = state.text.substring(0, start) + state.text.substring(pos)
        return TextState(newText, start, start)
    }

    /**
     * Xóa tiến (Delete):
     * - Nếu có vùng chọn -> xóa toàn bộ vùng chọn.
     * - Nếu là con trỏ -> xóa 1 ký tự phía sau (hoặc 2 code units nếu là emoji/surrogate pair).
     */
    fun deleteForward(state: TextState): TextState {
        if (state.hasSelection) {
            val min = state.selMin
            val max = state.selMax
            val newText = state.text.substring(0, min) + state.text.substring(max)
            return TextState(newText, min, min)
        }

        val pos = state.selectionStart
        if (pos >= state.text.length) return state

        val charsToDelete = if (pos + 1 < state.text.length && Character.isSurrogatePair(state.text[pos], state.text[pos + 1])) {
            2
        } else {
            1
        }

        val end = pos + charsToDelete
        val newText = state.text.substring(0, pos) + state.text.substring(end)
        return TextState(newText, pos, pos)
    }

    /**
     * Di chuyển con trỏ sang trái (Left Arrow).
     */
    fun moveCursorLeft(state: TextState): TextState {
        if (state.hasSelection) {
            val min = state.selMin
            return TextState(state.text, min, min)
        }

        val pos = state.selectionStart
        if (pos <= 0) return state

        val step = if (pos >= 2 && Character.isSurrogatePair(state.text[pos - 2], state.text[pos - 1])) 2 else 1
        val newPos = pos - step
        return TextState(state.text, newPos, newPos)
    }

    /**
     * Di chuyển con trỏ sang phải (Right Arrow).
     */
    fun moveCursorRight(state: TextState): TextState {
        if (state.hasSelection) {
            val max = state.selMax
            return TextState(state.text, max, max)
        }

        val pos = state.selectionStart
        if (pos >= state.text.length) return state

        val step = if (pos + 1 < state.text.length && Character.isSurrogatePair(state.text[pos], state.text[pos + 1])) 2 else 1
        val newPos = pos + step
        return TextState(state.text, newPos, newPos)
    }

    /**
     * Di chuyển con trỏ về đầu (Home).
     */
    fun moveCursorHome(state: TextState): TextState {
        return TextState(state.text, 0, 0)
    }

    /**
     * Di chuyển con trỏ về cuối (End).
     */
    fun moveCursorEnd(state: TextState): TextState {
        val end = state.text.length
        return TextState(state.text, end, end)
    }

    /**
     * Chọn tất cả văn bản (Select All).
     */
    fun selectAll(state: TextState): TextState {
        return TextState(state.text, 0, state.text.length)
    }
}
