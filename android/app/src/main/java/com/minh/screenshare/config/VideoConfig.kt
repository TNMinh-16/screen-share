package com.minh.screenshare.config

import kotlin.math.roundToInt

/**
 * Các hằng số cấu hình mã hóa và truyền video H.264.
 * Đặt tập trung tại đây để dễ dàng tinh chỉnh hiệu năng và độ trễ.
 */
object VideoConfig {
    // Cạnh dài tối đa của màn hình khi truyền (px), giữ nguyên tỉ lệ
    const val MAX_LONG_EDGE = 1280

    // Tốc độ khung hình (FPS)
    const val FRAME_RATE = 30

    // Bitrate mục tiêu: 5 Mbps (5,000,000 bps)
    const val BIT_RATE = 5_000_000

    // Chu kỳ khung hình chính (I-frame): 1 giây
    const val I_FRAME_INTERVAL_SECONDS = 1

    // Khoảng thời gian phát lại khung hình cũ khi màn hình đứng yên (100 ms = 100,000 micro-giây)
    const val REPEAT_PREVIOUS_FRAME_INTERVAL_US = 100_000L

    // Dung lượng tối đa của hàng đợi gói tin chờ gửi qua mạng (tránh đầy RAM khi nghẽn mạng)
    const val QUEUE_CAPACITY = 60

    // Cổng TCP mặc định
    const val DEFAULT_PORT = 8554

    // Cấu hình Notification
    const val CHANNEL_ID = "screen_share_stream_channel"
    const val NOTIFICATION_ID = 1001

    // SharedPreferences keys
    const val PREFS_NAME = "screen_share_prefs"
    const val PREF_KEY_IP = "pref_server_ip"
    const val PREF_KEY_PORT = "pref_server_port"

    /**
     * Tính toán kích thước co giãn theo tỉ lệ màn hình gốc:
     * - Cạnh dài nhất không vượt quá maxLongEdge.
     * - Chiều rộng và chiều cao luôn chia hết cho 2 (yêu cầu của H.264 codec).
     */
    fun calculateScaledResolution(
        screenWidth: Int,
        screenHeight: Int,
        maxLongEdge: Int = MAX_LONG_EDGE
    ): Pair<Int, Int> {
        val longEdge = maxOf(screenWidth, screenHeight)
        val scale = if (longEdge > maxLongEdge) maxLongEdge.toFloat() / longEdge else 1.0f

        var targetWidth = (screenWidth * scale).roundToInt()
        var targetHeight = (screenHeight * scale).roundToInt()

        // Đảm bảo chia hết cho 2
        if (targetWidth % 2 != 0) targetWidth -= 1
        if (targetHeight % 2 != 0) targetHeight -= 1

        // Đảm bảo không nhỏ hơn 16 px
        targetWidth = maxOf(targetWidth, 16)
        targetHeight = maxOf(targetHeight, 16)

        return Pair(targetWidth, targetHeight)
    }
}
