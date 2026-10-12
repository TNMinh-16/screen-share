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

/**
 * GestureController: Quản lý cử chỉ chạm, kéo (Live Drag), cuộn (Scroll), phóng to (Pinch) và phím hệ thống (Nav).
 * - Chạy tuần tự trên một HandlerThread chuyên biệt để đảm bảo thread-safety.
 * - Mọi khối code trong HandlerThread được bọc try/catch Throwable, không bao giờ để ngoại lệ thoát ra ngoài.
 * - Live Drag có generation ID: callback của thế hệ cũ không bao giờ làm hỏng nét chạm mới.
 * - Scroll: Gộp các nấc trong 30-50 ms, không dispatch đè khi cử chỉ trước chưa kết thúc.
 * - Tọa độ: Dùng GesturePointHelper đảm bảo tọa độ luôn nằm trong [0, w-1] và [0, h-1], không bao giờ âm.
 */
class GestureController(
    private val getScreenMetrics: () -> Pair<Int, Int>
) {
    companion object {
        private const val TAG = "ScreenShareControl"
        private const val SCROLL_DEBOUNCE_MS = 40L // Gộp các nấc cuộn trong 40ms (30-50ms)
    }

    private val handlerThread = HandlerThread("GestureControllerThread").apply { start() }
    private val handler = Handler(handlerThread.looper)

    // Mã thế hệ (generation id) cho Live Drag
    private var currentDragGeneration = 0L

    // Trạng thái cho Live Drag
    private var isFingerDown = false
    private var activeStroke: GestureDescription.StrokeDescription? = null
    private var lastDispatchedPoint: PointF? = null
    private var lastDispatchedTime: Long = 0L
    private var isStrokeDispatching = false

    private var pendingMovePoint: PointF? = null
    private var pendingUpPoint: PointF? = null

    // Trạng thái cho Scroll (Gộp các nấc con lăn)
    private var isScrollDispatching = false
    private var accumulatedScrollDx = 0
    private var accumulatedScrollDy = 0
    private var lastScrollNormX = 0
    private var lastScrollNormY = 0
    private val scrollRunnable = Runnable {
        try {
            dispatchPendingScroll()
        } catch (t: Throwable) {
            Log.w(TAG, "Lỗi khi dispatch pending scroll: ${t.message}", t)
            isScrollDispatching = false
        }
    }

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
            try {
                val (w, h) = getScreenMetrics()
                val (px, py) = ControlPacketParser.normalizedToScreenPixels(normX, normY, w, h)
                val clampedX = px.coerceIn(0f, maxOf(0f, (w - 1).toFloat()))
                val clampedY = py.coerceIn(0f, maxOf(0f, (h - 1).toFloat()))
                val point = PointF(clampedX, clampedY)

                resetWatchdog()

                // Bắt đầu một thế hệ cử chỉ mới
                currentDragGeneration++
                val generation = currentDragGeneration

                if (ControlConfig.LIVE_DRAG_ENABLED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    cancelOngoingGesturesInternal(incrementGen = false)

                    isFingerDown = true
                    lastDispatchedPoint = point
                    lastDispatchedTime = SystemClock.uptimeMillis()
                    pendingMovePoint = null
                    pendingUpPoint = null

                    val path = Path().apply {
                        moveTo(clampedX, clampedY)
                        lineTo(clampedX, clampedY)
                    }

                    val stroke = GestureDescription.StrokeDescription(
                        path, 0, ControlConfig.MIN_STROKE_DURATION_MS, true
                    )
                    activeStroke = stroke
                    dispatchSegment(stroke, generation)
                } else {
                    fallbackPoints.clear()
                    fallbackPoints.add(point)
                    fallbackDownTime = SystemClock.uptimeMillis()
                    isFingerDown = true
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Lỗi trong handleTouchDown: ${t.message}", t)
                cancelOngoingGesturesInternal()
            }
        }
    }

    fun handleTouchMove(normX: Int, normY: Int) {
        handler.post {
            try {
                if (!isFingerDown) return@post
                val (w, h) = getScreenMetrics()
                val (px, py) = ControlPacketParser.normalizedToScreenPixels(normX, normY, w, h)
                val clampedX = px.coerceIn(0f, maxOf(0f, (w - 1).toFloat()))
                val clampedY = py.coerceIn(0f, maxOf(0f, (h - 1).toFloat()))
                val point = PointF(clampedX, clampedY)

                resetWatchdog()

                if (ControlConfig.LIVE_DRAG_ENABLED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    pendingMovePoint = point
                    if (!isStrokeDispatching && activeStroke != null && lastDispatchedPoint != null) {
                        dispatchNextMoveSegment(currentDragGeneration)
                    }
                } else {
                    fallbackPoints.add(point)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Lỗi trong handleTouchMove: ${t.message}", t)
                cancelOngoingGesturesInternal()
            }
        }
    }

    fun handleTouchUp(normX: Int, normY: Int) {
        handler.post {
            try {
                if (!isFingerDown) return@post
                val (w, h) = getScreenMetrics()
                val (px, py) = ControlPacketParser.normalizedToScreenPixels(normX, normY, w, h)
                val clampedX = px.coerceIn(0f, maxOf(0f, (w - 1).toFloat()))
                val clampedY = py.coerceIn(0f, maxOf(0f, (h - 1).toFloat()))
                val point = PointF(clampedX, clampedY)

                resetWatchdog()

                if (ControlConfig.LIVE_DRAG_ENABLED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    pendingUpPoint = point
                    if (!isStrokeDispatching && activeStroke != null && lastDispatchedPoint != null) {
                        dispatchFinalUpSegment(currentDragGeneration)
                    }
                } else {
                    fallbackPoints.add(point)
                    dispatchFallbackGesture()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Lỗi trong handleTouchUp: ${t.message}", t)
                cancelOngoingGesturesInternal()
            }
        }
    }

    private fun dispatchSegment(stroke: GestureDescription.StrokeDescription, generation: Long) {
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
                    try {
                        if (generation != currentDragGeneration) {
                            Log.d(TAG, "Bỏ qua onCompleted thế hệ cũ: $generation (hiện tại: $currentDragGeneration)")
                            return@post
                        }
                        isStrokeDispatching = false
                        onSegmentCompleted(generation)
                    } catch (t: Throwable) {
                        Log.w(TAG, "Lỗi trong onCompleted: ${t.message}", t)
                        cancelOngoingGesturesInternal()
                    }
                }
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                handler.post {
                    try {
                        if (generation != currentDragGeneration) {
                            Log.d(TAG, "Bỏ qua onCancelled thế hệ cũ: $generation (hiện tại: $currentDragGeneration)")
                            return@post
                        }
                        Log.d(TAG, "Đoạn cử chỉ live drag bị hệ thống hủy (onCancelled).")
                        cancelOngoingGesturesInternal()
                    } catch (t: Throwable) {
                        Log.w(TAG, "Lỗi trong onCancelled: ${t.message}", t)
                        cancelOngoingGesturesInternal()
                    }
                }
            }
        }, handler)
    }

    private fun onSegmentCompleted(generation: Long) {
        if (generation != currentDragGeneration) return
        if (pendingUpPoint != null) {
            dispatchFinalUpSegment(generation)
        } else if (pendingMovePoint != null) {
            dispatchNextMoveSegment(generation)
        }
    }

    private fun dispatchNextMoveSegment(generation: Long) {
        if (generation != currentDragGeneration) return
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
            dispatchSegment(nextStroke, generation)
        }
    }

    private fun dispatchFinalUpSegment(generation: Long) {
        if (generation != currentDragGeneration) return
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
            dispatchSegment(finalStroke, generation)
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
            try {
                // Cộng dồn dx, dy
                accumulatedScrollDx += dx.toInt()
                accumulatedScrollDy += dy.toInt()
                lastScrollNormX = normX
                lastScrollNormY = normY

                // Nếu đang dispatch cử chỉ cuộn trước -> chờ callback onCompleted xong sẽ tự dispatch tiếp
                if (isScrollDispatching) {
                    return@post
                }

                // Gộp các nấc con lăn trong 30-50 ms
                handler.removeCallbacks(scrollRunnable)
                handler.postDelayed(scrollRunnable, SCROLL_DEBOUNCE_MS)
            } catch (t: Throwable) {
                Log.w(TAG, "Lỗi trong handleScroll: ${t.message}", t)
                isScrollDispatching = false
            }
        }
    }

    private fun dispatchPendingScroll() {
        if (isScrollDispatching) return
        val dx = accumulatedScrollDx.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        val dy = accumulatedScrollDy.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()

        if (dx == 0.toShort() && dy == 0.toShort()) return

        accumulatedScrollDx = 0
        accumulatedScrollDy = 0

        val service = RemoteControlAccessibilityService.instance ?: return
        val (w, h) = getScreenMetrics()
        val (cx, cy) = ControlPacketParser.normalizedToScreenPixels(lastScrollNormX, lastScrollNormY, w, h)

        val (start, end) = GesturePointHelper.calculateScrollPoints(cx, cy, w, h, dx, dy)

        val path = Path().apply {
            moveTo(start.x, start.y)
            lineTo(end.x, end.y)
        }

        val stroke = GestureDescription.StrokeDescription(path, 0, ControlConfig.SCROLL_DURATION_MS)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        isScrollDispatching = true
        service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                handler.post {
                    try {
                        isScrollDispatching = false
                        if (accumulatedScrollDx != 0 || accumulatedScrollDy != 0) {
                            dispatchPendingScroll()
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "Lỗi trong onCompleted scroll: ${t.message}", t)
                        isScrollDispatching = false
                    }
                }
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                handler.post {
                    try {
                        isScrollDispatching = false
                        if (accumulatedScrollDx != 0 || accumulatedScrollDy != 0) {
                            dispatchPendingScroll()
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "Lỗi trong onCancelled scroll: ${t.message}", t)
                        isScrollDispatching = false
                    }
                }
            }
        }, handler)
    }

    fun handlePinch(normX: Int, normY: Int, direction: Byte) {
        handler.post {
            try {
                val service = RemoteControlAccessibilityService.instance ?: return@post
                val (w, h) = getScreenMetrics()
                val (cx, cy) = ControlPacketParser.normalizedToScreenPixels(normX, normY, w, h)

                val pinch = GesturePointHelper.calculatePinchPoints(cx, cy, w, h, direction)

                val path1 = Path().apply {
                    moveTo(pinch.f1Start.x, pinch.f1Start.y)
                    lineTo(pinch.f1End.x, pinch.f1End.y)
                }
                val path2 = Path().apply {
                    moveTo(pinch.f2Start.x, pinch.f2Start.y)
                    lineTo(pinch.f2End.x, pinch.f2End.y)
                }

                val stroke1 = GestureDescription.StrokeDescription(path1, 0, ControlConfig.PINCH_DURATION_MS)
                val stroke2 = GestureDescription.StrokeDescription(path2, 0, ControlConfig.PINCH_DURATION_MS)

                val gesture = GestureDescription.Builder()
                    .addStroke(stroke1)
                    .addStroke(stroke2)
                    .build()

                service.dispatchGesture(gesture, null, handler)
            } catch (t: Throwable) {
                Log.w(TAG, "Lỗi trong handlePinch: ${t.message}", t)
            }
        }
    }

    fun handleNav(navCode: Byte) {
        handler.post {
            try {
                val service = RemoteControlAccessibilityService.instance ?: return@post
                when (navCode) {
                    ControlCommand.NAV_BACK -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    ControlCommand.NAV_HOME -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                    ControlCommand.NAV_RECENTS -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Lỗi trong handleNav: ${t.message}", t)
            }
        }
    }

    fun cancelOngoingGestures() {
        handler.post {
            try {
                cancelOngoingGesturesInternal(incrementGen = true)
            } catch (t: Throwable) {
                Log.w(TAG, "Lỗi trong cancelOngoingGestures: ${t.message}", t)
            }
        }
    }

    private fun cancelOngoingGesturesInternal(incrementGen: Boolean = true) {
        if (incrementGen) {
            currentDragGeneration++
        }
        if (isFingerDown && activeStroke != null && lastDispatchedPoint != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
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
        handler.removeCallbacks(scrollRunnable)
        handlerThread.quitSafely()
    }
}
