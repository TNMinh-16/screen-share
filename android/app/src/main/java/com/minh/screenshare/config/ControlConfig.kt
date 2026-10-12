package com.minh.screenshare.config

/**
 * Các hằng số cấu hình cho tính năng điều khiển từ xa qua Accessibility Service.
 */
object ControlConfig {
    // Chế độ kéo thời gian thực (Live drag) dùng continueStroke (API 26+)
    // Nếu thiết bị gặp vấn đề driver có thể đổi thành false để chuyển sang chế độ dự phòng
    const val LIVE_DRAG_ENABLED = true

    // Thời lượng nét cử chỉ live drag (16ms - 80ms)
    const val MIN_STROKE_DURATION_MS = 16L
    const val MAX_STROKE_DURATION_MS = 80L

    // Watchdog: Tự động hủy/kết thúc nét chạm nếu không có sự kiện mới sau 15 giây
    const val GESTURE_WATCHDOG_TIMEOUT_MS = 15_000L

    // Cấu hình SCROLL: Tỉ lệ chiều cao màn hình vuốt mỗi nấc con lăn (12% mỗi nấc, max 60%)
    const val SCROLL_STEP_RATIO = 0.12f
    const val SCROLL_MAX_RATIO = 0.60f
    const val SCROLL_DURATION_MS = 150L

    // Cấu hình PINCH: Khoảng cách bung/thu ngón tay và thời lượng
    const val PINCH_SPAN_RATIO = 0.25f
    const val PINCH_DURATION_MS = 250L
}
