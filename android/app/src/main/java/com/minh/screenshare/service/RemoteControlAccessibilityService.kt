package com.minh.screenshare.service

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Dịch vụ Trợ năng RemoteControlAccessibilityService:
 * - Tiếp nhận cử chỉ dispatchGesture (API 24+) và các thao tác toàn cục performGlobalAction.
 * - Truy cập cây View (AccessibilityNodeInfo) để gõ văn bản và chỉnh sửa con trỏ.
 * - Quản lý singleton instance an toàn luồng và thông báo khi trạng thái kết nối thay đổi.
 */
class RemoteControlAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ScreenShareControl"

        @Volatile
        var instance: RemoteControlAccessibilityService? = null
            private set

        val isConnected: Boolean
            get() = instance != null

        // Listener thông báo khi dịch vụ Trợ năng được bật hoặc tắt trong cài đặt hệ thống
        @Volatile
        var onConnectionStateListener: ((Boolean) -> Unit)? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "RemoteControlAccessibilityService ĐÃ KẾT NỐI!")
        onConnectionStateListener?.invoke(true)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Chỉ lắng nghe sự kiện khi cần thiết, không can thiệp nội dung
    }

    override fun onInterrupt() {
        Log.w(TAG, "RemoteControlAccessibilityService bị ngắt quãng.")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        Log.i(TAG, "RemoteControlAccessibilityService ĐÃ NGẮT KẾT NỐI (onUnbind)!")
        onConnectionStateListener?.invoke(false)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.i(TAG, "RemoteControlAccessibilityService ĐÃ HỦY (onDestroy)!")
        onConnectionStateListener?.invoke(false)
    }
}
