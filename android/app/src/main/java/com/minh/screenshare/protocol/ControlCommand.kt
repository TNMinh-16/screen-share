package com.minh.screenshare.protocol

/**
 * Định nghĩa các lệnh điều khiển từ PC xuống Android và trạng thái điều khiển.
 */
sealed class ControlCommand {
    data class TouchDown(val x: Int, val y: Int) : ControlCommand()
    data class TouchMove(val x: Int, val y: Int) : ControlCommand()
    data class TouchUp(val x: Int, val y: Int) : ControlCommand()
    data class Scroll(val x: Int, val y: Int, val dx: Short, val dy: Short) : ControlCommand()
    data class Pinch(val x: Int, val y: Int, val direction: Byte) : ControlCommand()
    data class Text(val text: String) : ControlCommand()
    data class Key(val keyCode: Byte) : ControlCommand()
    data class Nav(val navCode: Byte) : ControlCommand()

    companion object {
        // Mã lệnh điều khiển (PC -> Android)
        const val CMD_TOUCH_DOWN: Byte = 0x10   // 16
        const val CMD_TOUCH_MOVE: Byte = 0x11   // 17
        const val CMD_TOUCH_UP: Byte   = 0x12   // 18
        const val CMD_SCROLL: Byte     = 0x13   // 19
        const val CMD_PINCH: Byte      = 0x14   // 20
        const val CMD_TEXT: Byte       = 0x15   // 21
        const val CMD_KEY: Byte        = 0x16   // 22
        const val CMD_NAV: Byte        = 0x17   // 23

        // Mã phím chức năng (KEY)
        const val KEY_BACKSPACE: Byte   = 1
        const val KEY_DELETE: Byte      = 2
        const val KEY_LEFT: Byte        = 3
        const val KEY_RIGHT: Byte       = 4
        const val KEY_HOME_CURSOR: Byte = 5
        const val KEY_END_CURSOR: Byte  = 6
        const val KEY_ENTER: Byte       = 7
        const val KEY_SELECT_ALL: Byte  = 8

        // Mã điều hướng (NAV)
        const val NAV_BACK: Byte    = 1
        const val NAV_HOME: Byte    = 2
        const val NAV_RECENTS: Byte = 3

        // Gói trạng thái điều khiển (Android -> PC)
        const val TYPE_CONTROL_STATE: Byte = 3
        const val FLAG_CONTROL_ENABLED: Byte = 0x01
        const val FLAG_ACCESSIBILITY_CONNECTED: Byte = 0x02

        // Giới hạn an toàn payload tối đa (64 KB)
        const val MAX_PAYLOAD_SIZE = 64 * 1024
    }
}
