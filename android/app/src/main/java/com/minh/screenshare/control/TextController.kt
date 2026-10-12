package com.minh.screenshare.control

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.minh.screenshare.protocol.ControlCommand
import com.minh.screenshare.service.RemoteControlAccessibilityService

/**
 * TextController: Quản lý thao tác gõ văn bản và phím chức năng trên ô đang focus.
 * - Tìm ô đang focus qua AccessibilityNodeInfo.findFocus(FOCUS_INPUT).
 * - TEXT: Ưu tiên Clipboard ACTION_PASTE, dự phòng ACTION_SET_TEXT.
 * - KEY: Backspace, Delete, Left, Right, Home, End, Enter, Select All.
 * - TUYỆT ĐỐI không ghi nội dung văn bản vào log (chỉ log độ dài).
 */
class TextController(private val context: Context) {

    companion object {
        private const val TAG = "ScreenShareControl"
    }

    /**
     * Chèn chuỗi văn bản vào ô nhập liệu đang focus.
     */
    @Suppress("DEPRECATION")
    fun handleText(text: String) {
        if (text.isEmpty()) return
        Log.d(TAG, "Tiếp nhận lệnh TEXT (độ dài: ${text.length} ký tự).")

        val service = RemoteControlAccessibilityService.instance ?: return
        val root = service.rootInActiveWindow ?: return

        var focusNode: AccessibilityNodeInfo? = null
        try {
            focusNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focusNode == null) {
                Log.d(TAG, "Không tìm thấy ô nhập liệu đang focus.")
                return
            }

            // Cách 1: Ưu tiên dùng Clipboard ACTION_PASTE (hỗ trợ tiếng Việt và giữ chuẩn con trỏ)
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            if (clipboard != null) {
                try {
                    val clip = ClipData.newPlainText("ScreenShareInput", text)
                    clipboard.setPrimaryClip(clip)
                    val pasted = focusNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                    if (pasted) {
                        return
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "ACTION_PASTE không thành công: ${e.message}. Thử ACTION_SET_TEXT.")
                }
            }

            // Cách 2: Dự phòng dùng ACTION_SET_TEXT + ACTION_SET_SELECTION
            val currentText = getCurrentTextFromNode(focusNode)
            val (selStart, selEnd) = getSelectionFromNode(focusNode, currentText.length)

            val state = TextState(currentText, selStart, selEnd)
            val newState = TextEditorHelper.insertText(state, text)

            applyTextAndSelection(focusNode, newState)
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi khi xử lý handleText: ${e.message}")
        } finally {
            try {
                focusNode?.recycle()
                root.recycle()
            } catch (_: Exception) {}
        }
    }

    /**
     * Xử lý phím chức năng (Backspace, Delete, mũi tên, Home, End, Enter, Select All).
     */
    @Suppress("DEPRECATION")
    fun handleKey(keyCode: Byte) {
        Log.d(TAG, "Tiếp nhận lệnh KEY (mã: $keyCode).")

        val service = RemoteControlAccessibilityService.instance ?: return
        val root = service.rootInActiveWindow ?: return

        var focusNode: AccessibilityNodeInfo? = null
        try {
            focusNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focusNode == null) {
                Log.d(TAG, "Không tìm thấy ô nhập liệu đang focus để xử lý phím.")
                return
            }

            val currentText = getCurrentTextFromNode(focusNode)
            val (selStart, selEnd) = getSelectionFromNode(focusNode, currentText.length)
            val state = TextState(currentText, selStart, selEnd)

            when (keyCode) {
                ControlCommand.KEY_BACKSPACE -> {
                    val newState = TextEditorHelper.deleteBackward(state)
                    applyTextAndSelection(focusNode, newState)
                }

                ControlCommand.KEY_DELETE -> {
                    val newState = TextEditorHelper.deleteForward(state)
                    applyTextAndSelection(focusNode, newState)
                }

                ControlCommand.KEY_LEFT -> {
                    val newState = TextEditorHelper.moveCursorLeft(state)
                    applySelectionOnly(focusNode, newState.selectionStart, newState.selectionEnd)
                }

                ControlCommand.KEY_RIGHT -> {
                    val newState = TextEditorHelper.moveCursorRight(state)
                    applySelectionOnly(focusNode, newState.selectionStart, newState.selectionEnd)
                }

                ControlCommand.KEY_HOME_CURSOR -> {
                    val newState = TextEditorHelper.moveCursorHome(state)
                    applySelectionOnly(focusNode, newState.selectionStart, newState.selectionEnd)
                }

                ControlCommand.KEY_END_CURSOR -> {
                    val newState = TextEditorHelper.moveCursorEnd(state)
                    applySelectionOnly(focusNode, newState.selectionStart, newState.selectionEnd)
                }

                ControlCommand.KEY_SELECT_ALL -> {
                    val newState = TextEditorHelper.selectAll(state)
                    applySelectionOnly(focusNode, newState.selectionStart, newState.selectionEnd)
                }

                ControlCommand.KEY_ENTER -> {
                    // Chèn ký tự xuống dòng cho ô nhập liệu
                    val newState = TextEditorHelper.insertText(state, "\n")
                    applyTextAndSelection(focusNode, newState)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi khi xử lý handleKey: ${e.message}")
        } finally {
            try {
                focusNode?.recycle()
                root.recycle()
            } catch (_: Exception) {}
        }
    }

    private fun getCurrentTextFromNode(node: AccessibilityNodeInfo): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && node.isShowingHintText) {
            return ""
        }
        return node.text?.toString() ?: ""
    }

    private fun getSelectionFromNode(node: AccessibilityNodeInfo, textLength: Int): Pair<Int, Int> {
        val start = node.textSelectionStart.let { if (it in 0..textLength) it else textLength }
        val end = node.textSelectionEnd.let { if (it in 0..textLength) it else textLength }
        return Pair(start, end)
    }

    private fun applyTextAndSelection(node: AccessibilityNodeInfo, state: TextState) {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, state.text)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        applySelectionOnly(node, state.selectionStart, state.selectionEnd)
    }

    private fun applySelectionOnly(node: AccessibilityNodeInfo, start: Int, end: Int) {
        val selArgs = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selArgs)
    }
}
