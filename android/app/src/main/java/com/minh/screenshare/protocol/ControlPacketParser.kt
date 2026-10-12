package com.minh.screenshare.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Lớp ControlPacketParser thuần Kotlin:
 * - Phân tích cú pháp mảng byte thành các đối tượng ControlCommand.
 * - Kiểm tra chặt chẽ độ dài, kẹp (clamp) tọa độ trong phạm vi 0..65535.
 * - Tuyệt đối không bao giờ ném ngoại lệ hay làm crash ứng dụng khi nhận gói sai.
 */
object ControlPacketParser {

    /**
     * Chuyển đổi dữ liệu nhị phân thành ControlCommand.
     * @param type Byte loại lệnh
     * @param payload Mảng byte nội dung lệnh
     * @return ControlCommand tương ứng hoặc null nếu gói không hợp lệ/không nhận diện được
     */
    fun parseCommand(type: Byte, payload: ByteArray): ControlCommand? {
        // Kiểm tra an toàn: kích thước gói không được vượt quá 64 KB
        if (payload.size > ControlCommand.MAX_PAYLOAD_SIZE) {
            return null
        }

        return try {
            val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)

            when (type) {
                ControlCommand.CMD_TOUCH_DOWN -> {
                    if (payload.size < 4) return null
                    val x = buffer.short.toInt() and 0xFFFF
                    val y = buffer.short.toInt() and 0xFFFF
                    ControlCommand.TouchDown(clampCoord(x), clampCoord(y))
                }

                ControlCommand.CMD_TOUCH_MOVE -> {
                    if (payload.size < 4) return null
                    val x = buffer.short.toInt() and 0xFFFF
                    val y = buffer.short.toInt() and 0xFFFF
                    ControlCommand.TouchMove(clampCoord(x), clampCoord(y))
                }

                ControlCommand.CMD_TOUCH_UP -> {
                    if (payload.size < 4) return null
                    val x = buffer.short.toInt() and 0xFFFF
                    val y = buffer.short.toInt() and 0xFFFF
                    ControlCommand.TouchUp(clampCoord(x), clampCoord(y))
                }

                ControlCommand.CMD_SCROLL -> {
                    if (payload.size < 8) return null
                    val x = buffer.short.toInt() and 0xFFFF
                    val y = buffer.short.toInt() and 0xFFFF
                    val dx = buffer.short
                    val dy = buffer.short
                    ControlCommand.Scroll(clampCoord(x), clampCoord(y), dx, dy)
                }

                ControlCommand.CMD_PINCH -> {
                    if (payload.size < 5) return null
                    val x = buffer.short.toInt() and 0xFFFF
                    val y = buffer.short.toInt() and 0xFFFF
                    val dir = buffer.get()
                    ControlCommand.Pinch(clampCoord(x), clampCoord(y), if (dir >= 0) 1 else -1)
                }

                ControlCommand.CMD_TEXT -> {
                    if (payload.isEmpty()) return null
                    // Giải mã chuỗi UTF-8 an toàn
                    val text = String(payload, Charsets.UTF_8)
                    ControlCommand.Text(text)
                }

                ControlCommand.CMD_KEY -> {
                    if (payload.isEmpty()) return null
                    val keyCode = payload[0]
                    if (keyCode in ControlCommand.KEY_BACKSPACE..ControlCommand.KEY_SELECT_ALL) {
                        ControlCommand.Key(keyCode)
                    } else null
                }

                ControlCommand.CMD_NAV -> {
                    if (payload.isEmpty()) return null
                    val navCode = payload[0]
                    if (navCode in ControlCommand.NAV_BACK..ControlCommand.NAV_RECENTS) {
                        ControlCommand.Nav(navCode)
                    } else null
                }

                else -> null // Bỏ qua an toàn các loại gói không nhận ra
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Kẹp tọa độ chuẩn hóa trong phạm vi 0..65535.
     */
    fun clampCoord(value: Int): Int = maxOf(0, minOf(65535, value))

    /**
     * Chuyển tọa độ chuẩn hóa (0..65535) sang tọa độ pixel màn hình thực tế.
     * @param normX Tọa độ X chuẩn hóa (0..65535)
     * @param normY Tọa độ Y chuẩn hóa (0..65535)
     * @param screenWidth Chiều rộng màn hình thực tế (pixel)
     * @param screenHeight Chiều cao màn hình thực tế (pixel)
     * @return Cặp tọa độ Float (pixelX, pixelY) đã được kẹp trong phạm vi màn hình
     */
    fun normalizedToScreenPixels(
        normX: Int, normY: Int,
        screenWidth: Int, screenHeight: Int
    ): Pair<Float, Float> {
        if (screenWidth <= 0 || screenHeight <= 0) return Pair(0f, 0f)

        val clampedX = clampCoord(normX)
        val clampedY = clampCoord(normY)

        val px = (clampedX.toFloat() / 65535f) * screenWidth
        val py = (clampedY.toFloat() / 65535f) * screenHeight

        val finalX = maxOf(0f, minOf((screenWidth - 1).toFloat(), px))
        val finalY = maxOf(0f, minOf((screenHeight - 1).toFloat(), py))

        return Pair(finalX, finalY)
    }
}
