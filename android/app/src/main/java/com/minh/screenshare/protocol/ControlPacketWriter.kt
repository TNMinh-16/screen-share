package com.minh.screenshare.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Lớp ControlPacketWriter thuần Kotlin:
 * Đóng gói gói trạng thái điều khiển (Loại 3: CONTROL_STATE) từ điện thoại gửi lên PC:
 * [4 byte: Độ dài Payload = 1] [1 byte: Loại gói = 3] [1 byte: Cờ trạng thái]
 */
object ControlPacketWriter {

    /**
     * Tạo mảng byte cho gói CONTROL_STATE (Loại 3).
     * @param controlEnabled true nếu công tắc cho phép điều khiển trong app đang BẬT
     * @param accessibilityConnected true nếu dịch vụ Trợ năng Accessibility đang KẾT NỐI
     * @return Mảng byte hoàn chỉnh sẵn sàng gửi qua Socket
     */
    fun createControlStatePacket(controlEnabled: Boolean, accessibilityConnected: Boolean): ByteArray {
        var flags: Byte = 0
        if (controlEnabled) {
            flags = (flags.toInt() or ControlCommand.FLAG_CONTROL_ENABLED.toInt()).toByte()
        }
        if (accessibilityConnected) {
            flags = (flags.toInt() or ControlCommand.FLAG_ACCESSIBILITY_CONNECTED.toInt()).toByte()
        }

        val totalLength = 4 + 1 + 1 // 6 bytes
        val buffer = ByteBuffer.allocate(totalLength).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(1) // Payload length = 1
        buffer.put(ControlCommand.TYPE_CONTROL_STATE)
        buffer.put(flags)

        return buffer.array()
    }
}
