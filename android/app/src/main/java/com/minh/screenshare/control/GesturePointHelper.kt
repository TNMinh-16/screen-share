package com.minh.screenshare.control

import com.minh.screenshare.config.ControlConfig
import kotlin.math.abs

data class Point2D(val x: Float, val y: Float)

data class PinchPoints(
    val f1Start: Point2D,
    val f1End: Point2D,
    val f2Start: Point2D,
    val f2End: Point2D
)

/**
 * GesturePointHelper: Hàm thuần Kotlin tính toán tọa độ cử chỉ Cuộn (Scroll) và Phóng to (Pinch).
 * Đảm bảo:
 * 1. Kẹp mọi tọa độ đầu ra trong phạm vi [0, w-1] và [0, h-1], không bao giờ bị âm hoặc vượt bounds.
 * 2. Khi con trỏ gần mép hoặc ở 4 góc:
 *    - Với scroll: Tự động dịch điểm bắt đầu để toàn bộ quãng vuốt nằm trọn trong màn hình.
 *    - Với pinch: Tự động dịch tâm vào trong để cả 2 ngón tay đều nằm trọn trong màn hình.
 */
object GesturePointHelper {

    /**
     * Tính toán điểm bắt đầu và kết thúc của cử chỉ cuộn màn hình.
     */
    fun calculateScrollPoints(
        cx: Float,
        cy: Float,
        screenWidth: Int,
        screenHeight: Int,
        dx: Short,
        dy: Short
    ): Pair<Point2D, Point2D> {
        val minX = 0f
        val maxX = maxOf(0f, (screenWidth - 1).toFloat())
        val minY = 0f
        val maxY = maxOf(0f, (screenHeight - 1).toFloat())

        val curX = cx.coerceIn(minX, maxX)
        val curY = cy.coerceIn(minY, maxY)

        val isHorizontal = abs(dx.toInt()) > abs(dy.toInt())
        val delta = if (isHorizontal) dx.toInt() else dy.toInt()
        val steps = abs(delta) / 120.0f
        val baseDimension = if (isHorizontal) screenWidth else screenHeight
        val maxRatio = ControlConfig.SCROLL_MAX_RATIO
        val stepRatio = ControlConfig.SCROLL_STEP_RATIO
        val ratio = maxOf(0.05f, minOf(maxRatio, steps * stepRatio))
        val rawDistance = baseDimension * ratio
        val distance = if (isHorizontal) rawDistance.coerceIn(1f, maxX) else rawDistance.coerceIn(1f, maxY)

        return if (isHorizontal) {
            val (startX, endX) = if (dx < 0) {
                // Cuộn sang phải -> ngón vuốt sang TRÁI (x giảm)
                // Cần endX >= minX -> startX >= minX + distance
                val sX = maxOf(curX, minX + distance).coerceIn(minX, maxX)
                val eX = (sX - distance).coerceIn(minX, maxX)
                Pair(sX, eX)
            } else {
                // Cuộn sang trái -> ngón vuốt sang PHẢI (x tăng)
                // Cần endX <= maxX -> startX <= maxX - distance
                val sX = minOf(curX, maxX - distance).coerceIn(minX, maxX)
                val eX = (sX + distance).coerceIn(minX, maxX)
                Pair(sX, eX)
            }
            Pair(Point2D(startX, curY), Point2D(endX, curY))
        } else {
            val (startY, endY) = if (dy < 0) {
                // Con lăn xuống -> nội dung cuộn xuống -> ngón vuốt LÊN (y giảm)
                // Cần endY >= minY -> startY >= minY + distance
                val sY = maxOf(curY, minY + distance).coerceIn(minY, maxY)
                val eY = (sY - distance).coerceIn(minY, maxY)
                Pair(sY, eY)
            } else {
                // Con lăn lên -> nội dung cuộn lên -> ngón vuốt XUỐNG (y tăng)
                // Cần endY <= maxY -> startY <= maxY - distance
                val sY = minOf(curY, maxY - distance).coerceIn(minY, maxY)
                val eY = (sY + distance).coerceIn(minY, maxY)
                Pair(sY, eY)
            }
            Pair(Point2D(curX, startY), Point2D(curX, endY))
        }
    }

    /**
     * Tính toán điểm bắt đầu và kết thúc của 2 ngón tay cho cử chỉ Pinch Zoom.
     */
    fun calculatePinchPoints(
        cx: Float,
        cy: Float,
        screenWidth: Int,
        screenHeight: Int,
        direction: Byte
    ): PinchPoints {
        val minX = 0f
        val maxX = maxOf(0f, (screenWidth - 1).toFloat())
        val minY = 0f
        val maxY = maxOf(0f, (screenHeight - 1).toFloat())

        val rawSpan = minOf(screenWidth, screenHeight) * ControlConfig.PINCH_SPAN_RATIO
        val span = if (maxX > 0f) minOf(rawSpan, maxX / 2f).coerceAtLeast(1f) else 1f

        // Dịch tâm vào trong để cả 2 ngón đều nằm trong [minX, maxX]
        val clampedCenterX = cx.coerceIn(minX + span, maxX - span)
        val clampedCenterY = cy.coerceIn(minY, maxY)

        val (f1StartX, f1EndX, f2StartX, f2EndX) = if (direction > 0) {
            // Phóng to (Zoom In): 2 ngón bung ra xa tâm
            val f1S = clampedCenterX - span * 0.3f
            val f1E = clampedCenterX - span
            val f2S = clampedCenterX + span * 0.3f
            val f2E = clampedCenterX + span
            listOf(f1S, f1E, f2S, f2E)
        } else {
            // Thu nhỏ (Zoom Out): 2 ngón khép lại vào tâm
            val f1S = clampedCenterX - span
            val f1E = clampedCenterX - span * 0.3f
            val f2S = clampedCenterX + span
            val f2E = clampedCenterX + span * 0.3f
            listOf(f1S, f1E, f2S, f2E)
        }

        return PinchPoints(
            f1Start = Point2D(f1StartX.coerceIn(minX, maxX), clampedCenterY),
            f1End = Point2D(f1EndX.coerceIn(minX, maxX), clampedCenterY),
            f2Start = Point2D(f2StartX.coerceIn(minX, maxX), clampedCenterY),
            f2End = Point2D(f2EndX.coerceIn(minX, maxX), clampedCenterY)
        )
    }
}
