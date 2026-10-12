package com.minh.screenshare.control

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PointF
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import com.minh.screenshare.config.ControlConfig
import com.minh.screenshare.protocol.ControlCommand
import com.minh.screenshare.protocol.ControlPacketParser
import com.minh.screenshare.service.RemoteControlAccessibilityService
import kotlin.math.abs

/**
 * GestureController: Quản lý cử chỉ chạm, kéo (Live Drag), cuộn (Scroll), phóng to (Pinch) và phím hệ thống (Nav).
 * - Chạy tuần tự trên một HandlerThread chuyên biệt để đảm bảo thread-safety.
 * - Chế độ LIVE (API 26): Dùng continuous strokes với willContinue=true/false, tự động gộp (coalesce) các MOVE.
 * - Chế độ dự phòng: Ghi lại đường đi và phát một cử chỉ duy nhất khi nhả chuột.
 * - Cơ chế Watchdog 15 giây tự động hủy/kết thúc cử chỉ khi mất kết nối.
 */
class GestureController(
    private val getScreenMetrics: () -> Pair<Int, Int>
) {
    companion object {
        private const val TAG = "ScreenShareControl"
    }

    private val handlerThread = HandlerThread("GestureControllerThread").apply { start() }
    private val handler = Handler(handlerThread.looper)

    // Trạng thái cho Live Drag
    private var isFingerDown = false
    private var activeStroke: GestureDescription.StrokeDescription? = null
    private var lastDispatchedPoint: PointF? = null
    private var lastDispatchedTime: Long = 0L
    private var isStrokeDispatching = false

    private var pendingMovePoint: PointF? = null
    private var pendingUpPoint: PointF? = null

    // Trạng thái cho chế độ dự phòng (Fallback)
    private val fallbackPoints = mutableListOf<PointF>()
    private var fallbackDownTime = 0L

    // Watchdog kết thúc nét nếu quá 15s không có sự kiện mới
    private val watchdogRunnable = Runnable {
        Log.w(TAG, "Watchdog kích hoạt: Hủy nét chạm do quá 15 giây không có thao tác.")
        cancelOngoingGestures()
    }

    fun handleTouchDown(normX: Int, normY: Int) {
        handler.post {
            val (w, h) = getScreenMetrics()
            val (px, py) = ControlPacketParser.normalizedToScreenPixels(normX, normY, w, h)
            val point = PointF(px, py)

            resetWatchdog()

            if (ControlConfig.LIVE_DRAG_ENABLED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Hủy nét cũ nếu có
                cancelOngoingGesturesInternal()

                isFingerDown = true
                lastDispatchedPoint = point
                lastDispatchedTime = SystemClock.uptimeMillis()
                pendingMovePoint = null
                pendingUpPoint = null

                val path = Path().apply {
                    moveTo(px, py)
                    lineTo(px, py)
                }

                val stroke = GestureDescription.StrokeDescription(
                    path, 0, ControlConfig.MIN_STROKE_DURATION_MS, true
                )
                activeStroke = stroke
                dispatchSegment(stroke)
            } else {
                // Chế độ dự phòng
                fallbackPoints.clear()
                fallbackPoints.add(point)
                fallbackDownTime = SystemClock.uptimeMillis()
                isFingerDown = true
            }
        }
    }

    fun handleTouchMove(normX: Int, normY: Int) {
        handler.post {
            if (!isFingerDown) return@post
            val (w, h) = getScreenMetrics()
            val (px, py) = ControlPacketParser.normalizedToScreenPixels(normX, normY, w, h)
            val point = PointF(px, py)

            resetWatchdog()

            if (ControlConfig.LIVE_DRAG_ENABLED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Tự động gộp: chỉ lưu điểm MOVE mới nhất
                pendingMovePoint = point

                // Nếu không có đoạn nét nào đang chờ callback onCompleted -> dispatch ngay đoạn tiếp theo
                if (!isStrokeDispatching && activeStroke != null && lastDispatchedPoint != null) {
                    dispatchNextMoveSegment()
                }
            } else {
                fallbackPoints.add(point)
            }
        }
    }

    fun handleTouchUp(normX: Int, normY: Int) {
        handler.post {
            if (!isFingerDown) return@post
            val (w, h) = getScreenMetrics()
            val (px, py) = ControlPacketParser.normalizedToScreenPixels(normX, normY, w, h)
            val point = PointF(px, py)

            resetWatchdog()

            if (ControlConfig.LIVE_DRAG_ENABLED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                pendingUpPoint = point
                if (!isStrokeDispatching && activeStroke != null && lastDispatchedPoint != null) {
                    dispatchFinalUpSegment()
                }
            } else {
                fallbackPoints.add(point)
                dispatchFallbackGesture()
            }
        }
    }

    private fun dispatchSegment(stroke: GestureDescription.StrokeDescription) {
        val service = RemoteControlAccessibilityService.instance
        if (service == null) {
            cancelOngoingGesturesInternal()
            return
        }

        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        isStrokeDispatching = true

        service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                handler.post {
                    isStrokeDispatching = false
                    onSegmentCompleted()
                }
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                handler.post {
                    Log.d(TAG, "Đoạn cử chỉ live drag bị hệ thống hủy (onCancelled).")
                    cancelOngoingGesturesInternal()
                }
            }
        }, handler)
    }

    private fun onSegmentCompleted() {
        if (pendingUpPoint != null) {
            dispatchFinalUpSegment()
        } else if (pendingMovePoint != null) {
            dispatchNextMoveSegment()
        }
        // Nếu không có MOVE hay UP -> Giữ nguyên ngón tay đang chạm (hỗ trợ Long Press)
    }

    private fun dispatchNextMoveSegment() {
        val target = pendingMovePoint ?: return
        pendingMovePoint = null
        val prev = lastDispatchedPoint ?: return
        val currentStroke = activeStroke ?: return

        val now = SystemClock.uptimeMillis()
        val elapsed = now - lastDispatchedTime
        val duration = maxOf(ControlConfig.MIN_STROKE_DURATION_MS, minOf(ControlConfig.MAX_STROKE_DURATION_MS, elapsed))

        val path = Path().apply {
            moveTo(prev.x, prev.y)
            lineTo(target.x, target.y)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nextStroke = currentStroke.continueStroke(path, 0, duration, true)
            activeStroke = nextStroke
            lastDispatchedPoint = target
            lastDispatchedTime = now
            dispatchSegment(nextStroke)
        }
    }

    private fun dispatchFinalUpSegment() {
        val target = pendingUpPoint ?: return
        pendingUpPoint = null
        val prev = lastDispatchedPoint ?: return
        val currentStroke = activeStroke ?: return

        val now = SystemClock.uptimeMillis()
        val elapsed = now - lastDispatchedTime
        val duration = maxOf(ControlConfig.MIN_STROKE_DURATION_MS, minOf(ControlConfig.MAX_STROKE_DURATION_MS, elapsed))

        val path = Path().apply {
            moveTo(prev.x, prev.y)
            lineTo(target.x, target.y)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val finalStroke = currentStroke.continueStroke(path, 0, duration, false)
            dispatchSegment(finalStroke)
        }

        // Hoàn tất chuỗi cử chỉ
        isFingerDown = false
        activeStroke = null
        lastDispatchedPoint = null
        handler.removeCallbacks(watchdogRunnable)
    }

    private fun dispatchFallbackGesture() {
        if (fallbackPoints.isEmpty()) {
            isFingerDown = false
            return
        }

        val service = RemoteControlAccessibilityService.instance
        if (service == null) {
            fallbackPoints.clear()
            isFingerDown = false
            return
        }

        val path = Path()
        val first = fallbackPoints.first()
        path.moveTo(first.x, first.y)
        for (i in 1 until fallbackPoints.size) {
            val pt = fallbackPoints[i]
            path.lineTo(pt.x, pt.y)
        }

        val elapsed = SystemClock.uptimeMillis() - fallbackDownTime
        val duration = maxOf(50L, minOf(GestureDescription.getMaxGestureDuration(), elapsed))

        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        service.dispatchGesture(gesture, null, handler)

        fallbackPoints.clear()
        isFingerDown = false
        handler.removeCallbacks(watchdogRunnable)
    }

    fun handleScroll(normX: Int, normY: Int, dx: Short, dy: Short) {
        handler.post {
            val service = RemoteControlAccessibilityService.instance ?: return@post
            val (w, h) = getScreenMetrics()
            val (cx, cy) = ControlPacketParser.normalizedToScreenPixels(normX, normY, w, h)

            val isHorizontal = abs(dx.toInt()) > abs(dy.toInt())
            val delta = if (isHorizontal) dx.toInt() else dy.toInt()
            val steps = abs(delta) / 120.0f
            val baseDimension = if (isHorizontal) w else h
            val ratio = maxOf(0.05f, minOf(ControlConfig.SCROLL_MAX_RATIO, steps * ControlConfig.SCROLL_STEP_RATIO))
            val distance = baseDimension * ratio

            // Cuộn theo hướng: giá trị âm là cuộn xuống/phải -> ngón tay vuốt ngược lại
            val (startX, endX) = if (isHorizontal) {
                Pair(cx, if (dx < 0) cx - distance else cx + distance)
            } else {
                Pair(cx, cx)
            }

            val (startY, endY) = if (!isHorizontal) {
                Pair(cy, if (dy < 0) cy - distance else cy + distance)
            } else {
                Pair(cy, cy)
            }

            val path = Path().apply {
                moveTo(startX, startY)
                lineTo(endX, endY)
            }

            val stroke = GestureDescription.StrokeDescription(path, 0, ControlConfig.SCROLL_DURATION_MS)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            service.dispatchGesture(gesture, null, handler)
        }
    }

    fun handlePinch(normX: Int, normY: Int, direction: Byte) {
        handler.post {
            val service = RemoteControlAccessibilityService.instance ?: return@post
            val (w, h) = getScreenMetrics()
            val (cx, cy) = ControlPacketParser.normalizedToScreenPixels(normX, normY, w, h)

            val span = minOf(w, h) * ControlConfig.PINCH_SPAN_RATIO

            // direction > 0: Phóng to (Zoom In) -> 2 ngón bung ra xa tâm
            // direction < 0: Thu nhỏ (Zoom Out) -> 2 ngón khép lại vào tâm
            val (f1Start, f1End) = if (direction > 0) {
                Pair(PointF(cx - span * 0.3f, cy), PointF(cx - span, cy))
            } else {
                Pair(PointF(cx - span, cy), PointF(cx - span * 0.3f, cy))
            }

            val (f2Start, f2End) = if (direction > 0) {
                Pair(PointF(cx + span * 0.3f, cy), PointF(cx + span, cy))
            } else {
                Pair(PointF(cx + span, cy), PointF(cx + span * 0.3f, cy))
            }

            val path1 = Path().apply {
                moveTo(f1Start.x, f1Start.y)
                lineTo(f1End.x, f1End.y)
            }
            val path2 = Path().apply {
                moveTo(f2Start.x, f2Start.y)
                lineTo(f2End.x, f2End.y)
            }

            val stroke1 = GestureDescription.StrokeDescription(path1, 0, ControlConfig.PINCH_DURATION_MS)
            val stroke2 = GestureDescription.StrokeDescription(path2, 0, ControlConfig.PINCH_DURATION_MS)

            val gesture = GestureDescription.Builder()
                .addStroke(stroke1)
                .addStroke(stroke2)
                .build()

            service.dispatchGesture(gesture, null, handler)
        }
    }

    fun handleNav(navCode: Byte) {
        handler.post {
            val service = RemoteControlAccessibilityService.instance ?: return@post
            when (navCode) {
                ControlCommand.NAV_BACK -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                ControlCommand.NAV_HOME -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                ControlCommand.NAV_RECENTS -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
            }
        }
    }

    fun cancelOngoingGestures() {
        handler.post {
            cancelOngoingGesturesInternal()
        }
    }

    private fun cancelOngoingGesturesInternal() {
        if (isFingerDown && activeStroke != null && lastDispatchedPoint != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                // Kết thúc nét bằng continueStroke với willContinue = false
                val path = Path().apply {
                    moveTo(lastDispatchedPoint!!.x, lastDispatchedPoint!!.y)
                    lineTo(lastDispatchedPoint!!.x, lastDispatchedPoint!!.y)
                }
                val endStroke = activeStroke!!.continueStroke(path, 0, ControlConfig.MIN_STROKE_DURATION_MS, false)
                val gesture = GestureDescription.Builder().addStroke(endStroke).build()
                RemoteControlAccessibilityService.instance?.dispatchGesture(gesture, null, handler)
            } catch (_: Exception) {}
        }

        isFingerDown = false
        activeStroke = null
        lastDispatchedPoint = null
        isStrokeDispatching = false
        pendingMovePoint = null
        pendingUpPoint = null
        fallbackPoints.clear()
        handler.removeCallbacks(watchdogRunnable)
    }

    private fun resetWatchdog() {
        handler.removeCallbacks(watchdogRunnable)
        handler.postDelayed(watchdogRunnable, ControlConfig.GESTURE_WATCHDOG_TIMEOUT_MS)
    }

    fun destroy() {
        cancelOngoingGestures()
        handlerThread.quitSafely()
    }
}
