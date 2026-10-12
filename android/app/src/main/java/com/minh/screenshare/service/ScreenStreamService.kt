package com.minh.screenshare.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.minh.screenshare.R
import com.minh.screenshare.config.VideoConfig
import com.minh.screenshare.encoder.ScreenEncoder
import com.minh.screenshare.network.StreamClient
import com.minh.screenshare.state.AppState
import com.minh.screenshare.state.StreamState
import com.minh.screenshare.ui.MainActivity
import com.minh.screenshare.control.GestureController
import com.minh.screenshare.control.TextController
import com.minh.screenshare.protocol.ControlCommand
import com.minh.screenshare.service.RemoteControlAccessibilityService
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground Service điều phối toàn bộ phiên truyền màn hình.
 * Tuân thủ nghiêm ngặt các quy tắc vòng đời của Android:
 * 1. Bật Notification và gọi startForeground(..., mediaProjection) TRƯỚC khi gọi getMediaProjection().
 * 2. Đăng ký MediaProjection.Callback TRƯỚC khi tạo VirtualDisplay.
 * 3. Kết nối Socket TCP TRƯỚC khi chạy MediaCodec để gói SPS/PPS đầu tiên đi được ngay.
 * 4. Lắng nghe xoay màn hình và resize trên chính VirtualDisplay hiện có.
 * 5. Ghi log thống kê định kỳ mỗi 2 giây vào Logcat (Tag: ScreenShare).
 * 6. Dọn dẹp tài nguyên an toàn theo đúng thứ tự.
 */
class ScreenStreamService : Service() {

    companion object {
        private const val TAG = "ScreenShare"

        const val ACTION_START = "com.minh.screenshare.ACTION_START"
        const val ACTION_STOP = "com.minh.screenshare.ACTION_STOP"
        const val ACTION_SET_CONTROL = "com.minh.screenshare.ACTION_SET_CONTROL"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_DATA = "extra_data"
        const val EXTRA_HOST = "extra_host"
        const val EXTRA_PORT = "extra_port"
        const val EXTRA_CONTROL_ENABLED = "extra_control_enabled"
    }

    private var mediaProjection: MediaProjection? = null
    private var streamClient: StreamClient? = null
    private var screenEncoder: ScreenEncoder? = null
    private var gestureController: GestureController? = null
    private var textController: TextController? = null

    private var displayManager: DisplayManager? = null
    private var displayListener: DisplayManager.DisplayListener? = null

    private val isStreaming = AtomicBoolean(false)
    private var isControlEnabled = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private var currentWidth = 0
    private var currentHeight = 0
    private var currentDpi = 0

    // Biến cho việc ghi log thống kê định kỳ
    private var lastLogTime = 0L
    private var lastFramesSent = 0L
    private var lastBytesSent = 0L
    private val statsRunnable = object : Runnable {
        override fun run() {
            if (isStreaming.get()) {
                logStatistics()
                mainHandler.postDelayed(this, 2000L) // Lặp lại mỗi 2 giây
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                @Suppress("DEPRECATION")
                val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)
                val host = intent.getStringExtra(EXTRA_HOST) ?: "127.0.0.1"
                val port = intent.getIntExtra(EXTRA_PORT, VideoConfig.DEFAULT_PORT)

                if (resultCode != 0 && data != null) {
                    startStreaming(resultCode, data, host, port)
                } else {
                    // Gọi startForeground() trước khi stopSelf() để tránh crash trên Android 8 đến 11
                    createNotificationChannel()
                    val notification = buildNotification()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        startForeground(
                            VideoConfig.NOTIFICATION_ID,
                            notification,
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                        )
                    } else {
                        startForeground(VideoConfig.NOTIFICATION_ID, notification)
                    }

                    AppState.updateState(StreamState.Error("Dữ liệu cấp quyền MediaProjection không hợp lệ!"))
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
            ACTION_STOP -> {
                stopStreaming()
                stopSelf()
            }
            ACTION_SET_CONTROL -> {
                val enabled = intent.getBooleanExtra(EXTRA_CONTROL_ENABLED, false)
                setControlEnabledInternal(enabled)
            }
        }
        return START_NOT_STICKY
    }

    /**
     * Bắt đầu toàn bộ luồng truyền màn hình.
     */
    private fun startStreaming(resultCode: Int, data: Intent, host: String, port: Int) {
        if (isStreaming.get()) return

        Log.i(TAG, "Bắt đầu khởi động phiên truyền màn hình tới $host:$port")
        AppState.updateState(StreamState.Connecting)

        // BƯỚC 1: Tạo NotificationChannel và gọi startForeground() NGAY LẬP TỨC
        createNotificationChannel()
        val notification = buildNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                VideoConfig.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(VideoConfig.NOTIFICATION_ID, notification)
        }

        // BƯỚC 2: RỒI MỚI gọi getMediaProjection() (bắt buộc trên Android 10/14)
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = projectionManager.getMediaProjection(resultCode, data)
        if (projection == null) {
            AppState.updateState(StreamState.Error("Không lấy được MediaProjection từ hệ thống!"))
            stopStreaming()
            stopSelf()
            return
        }
        mediaProjection = projection

        // BƯỚC 3: Đăng ký MediaProjection.Callback TRƯỚC khi tạo VirtualDisplay (bắt buộc từ Android 14)
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "MediaProjection đã bị hệ thống dừng.")
                stopStreaming()
                stopSelf()
            }
        }, mainHandler)

        // Lấy thông số màn hình thực tế
        calculateScreenMetrics()

        // Khởi tạo bộ điều khiển cử chỉ và nhập liệu
        gestureController = GestureController {
            getRealScreenMetrics()
        }
        textController = TextController(this)

        // BƯỚC 4: Kết nối Socket TCP ở luồng nền (timeout 5s) TRƯỚC KHI chạy MediaCodec
        Thread {
            try {
                val client = StreamClient(
                    host = host,
                    port = port,
                    onError = { error ->
                        Log.e(TAG, "Lỗi kết nối Socket: ${error.message}")
                        mainHandler.post {
                            AppState.updateState(StreamState.Error("Mất kết nối máy tính: ${error.message}"))
                            stopStreaming()
                            stopSelf()
                        }
                    },
                    onRequestSyncFrame = {
                        screenEncoder?.requestSyncFrame()
                    },
                    onControlCommand = { cmd ->
                        if (!isControlEnabled || !RemoteControlAccessibilityService.isConnected) {
                            return@StreamClient
                        }
                        try {
                            when (cmd) {
                                is ControlCommand.TouchDown -> gestureController?.handleTouchDown(cmd.x, cmd.y)
                                is ControlCommand.TouchMove -> gestureController?.handleTouchMove(cmd.x, cmd.y)
                                is ControlCommand.TouchUp -> gestureController?.handleTouchUp(cmd.x, cmd.y)
                                is ControlCommand.Scroll -> gestureController?.handleScroll(cmd.x, cmd.y, cmd.dx, cmd.dy)
                                is ControlCommand.Pinch -> gestureController?.handlePinch(cmd.x, cmd.y, cmd.direction)
                                is ControlCommand.Nav -> gestureController?.handleNav(cmd.navCode)
                                is ControlCommand.Text -> textController?.handleText(cmd.text)
                                is ControlCommand.Key -> textController?.handleKey(cmd.keyCode)
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Lỗi khi xử lý lệnh điều khiển: ${e.message}")
                        }
                    }
                )

                // Thực hiện kết nối tới PC
                client.connect()
                streamClient = client

                // Gửi trạng thái điều khiển ban đầu cho PC
                client.sendControlState(isControlEnabled, RemoteControlAccessibilityService.isConnected)

                // Đăng ký lắng nghe thay đổi trạng thái dịch vụ Trợ năng
                RemoteControlAccessibilityService.onConnectionStateListener = { connected ->
                    AppState.setAccessibilityConnected(connected)
                    if (isStreaming.get()) {
                        streamClient?.sendControlState(isControlEnabled, connected)
                        if (!connected) {
                            gestureController?.cancelOngoingGestures()
                        }
                    }
                }

                // BƯỚC 5: Khởi động ScreenEncoder đưa Surface vào VirtualDisplay
                mainHandler.post {
                    try {
                        val encoder = ScreenEncoder(
                            mediaProjection = projection,
                            width = currentWidth,
                            height = currentHeight,
                            densityDpi = currentDpi,
                            streamClient = client,
                            onError = { error ->
                                Log.e(TAG, "Lỗi trong ScreenEncoder: ${error.message}")
                                mainHandler.post {
                                    AppState.updateState(StreamState.Error("Lỗi bộ mã hóa video: ${error.message}"))
                                    stopStreaming()
                                    stopSelf()
                                }
                            }
                        )
                        encoder.start()
                        screenEncoder = encoder

                        // Đăng ký lắng nghe xoay màn hình
                        registerDisplayListener()

                        isStreaming.set(true)
                        AppState.updateState(StreamState.Streaming(host, port))

                        // Bắt đầu timer log định kỳ
                        lastLogTime = System.currentTimeMillis()
                        lastFramesSent = 0L
                        lastBytesSent = 0L
                        mainHandler.postDelayed(statsRunnable, 2000L)

                        Log.i(TAG, ">>> PHIÊN TRUYỀN MÀN HÌNH ĐÃ KHỞI CHẠY THÀNH CÔNG! <<<")
                    } catch (e: Exception) {
                        Log.e(TAG, "Lỗi khởi động ScreenEncoder: ${e.message}")
                        AppState.updateState(StreamState.Error("Lỗi khởi động MediaCodec: ${e.message}"))
                        stopStreaming()
                        stopSelf()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Không thể kết nối tới $host:$port: ${e.message}")
                mainHandler.post {
                    AppState.updateState(StreamState.Error("Không kết nối được PC ($host:$port). Hãy kiểm tra IP và Firewall!"))
                    stopStreaming()
                    stopSelf()
                }
            }
        }.start()
    }

    /**
     * Tính toán kích thước và mật độ màn hình gốc, co giãn theo VideoConfig.MAX_LONG_EDGE.
     */
    private fun calculateScreenMetrics() {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()

        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)

        val rawWidth = metrics.widthPixels
        val rawHeight = metrics.heightPixels
        val (scaledW, scaledH) = VideoConfig.calculateScaledResolution(rawWidth, rawHeight)

        currentWidth = scaledW
        currentHeight = scaledH
        currentDpi = metrics.densityDpi
        Log.i(TAG, "Màn hình gốc: ${rawWidth}x${rawHeight} -> Kích thước truyền: ${currentWidth}x${currentHeight}, DPI: $currentDpi")
    }

    /**
     * Lắng nghe sự kiện xoay màn hình để cập nhật kích thước truyền.
     */
    private fun registerDisplayListener() {
        displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        displayListener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
            override fun onDisplayChanged(displayId: Int) {
                if (displayId == Display.DEFAULT_DISPLAY && isStreaming.get()) {
                    handleOrientationChanged()
                }
            }
        }
        displayManager?.registerDisplayListener(displayListener, mainHandler)
    }

    /**
     * Xử lý khi màn hình xoay hướng:
     * Tính lại kích thước, reconfigure encoder và VirtualDisplay.
     */
    private fun handleOrientationChanged() {
        val oldW = currentWidth
        val oldH = currentHeight
        calculateScreenMetrics()

        if (oldW != currentWidth || oldH != currentHeight) {
            Log.i(TAG, "Phát hiện xoay màn hình: ${oldW}x${oldH} -> ${currentWidth}x${currentHeight}")
            screenEncoder?.reconfigure(currentWidth, currentHeight, currentDpi)
        }
    }

    /**
     * Ghi log thống kê (Logcat tag "ScreenShare") mỗi 2 giây.
     */
    private fun logStatistics() {
        val client = streamClient ?: return
        val now = System.currentTimeMillis()
        val durationSec = (now - lastLogTime) / 1000.0

        if (durationSec > 0) {
            val totalFrames = client.totalFramesSent.get()
            val totalBytes = client.totalBytesSent.get()

            val deltaFrames = totalFrames - lastFramesSent
            val deltaBytes = totalBytes - lastBytesSent

            val fps = deltaFrames / durationSec
            val bitrateKbps = (deltaBytes * 8 / durationSec) / 1000.0

            Log.i(
                TAG,
                String.format(
                    "[THỐNG KÊ] Gửi: %d khung | FPS: %.1f | Bitrate: %.1f kbps | Hàng đợi: %d gói",
                    totalFrames,
                    fps,
                    bitrateKbps,
                    client.currentQueueSize
                )
            )

            lastLogTime = now
            lastFramesSent = totalFrames
            lastBytesSent = totalBytes
        }
    }

    /**
     * Dừng phiên truyền và dọn dẹp tài nguyên theo đúng thứ tự an toàn:
     * VirtualDisplay -> Encoder -> MediaProjection -> StreamClient -> stopForeground.
     * An toàn khi gọi nhiều lần.
     */
    private fun stopStreaming() {
        if (!isStreaming.getAndSet(false) && mediaProjection == null && streamClient == null) {
            return
        }

        Log.i(TAG, "Đang dọn dẹp và dừng phiên truyền màn hình...")
        mainHandler.removeCallbacks(statsRunnable)

        // Hủy lắng nghe xoay màn hình
        displayListener?.let {
            displayManager?.unregisterDisplayListener(it)
            displayListener = null
        }

        // 1. Dọn dẹp bộ điều khiển điều khiển từ xa
        RemoteControlAccessibilityService.onConnectionStateListener = null
        try {
            gestureController?.cancelOngoingGestures()
            gestureController?.destroy()
            gestureController = null
            textController = null
        } catch (e: Exception) {
            Log.w(TAG, "Lỗi giải phóng gestureController: ${e.message}")
        }

        // Tự động tắt công tắc điều khiển khi dừng phiên truyền
        isControlEnabled = false
        AppState.setControlEnabled(false)

        // 2. Giải phóng ScreenEncoder & VirtualDisplay
        try {
            screenEncoder?.release()
            screenEncoder = null
        } catch (e: Exception) {
            Log.w(TAG, "Lỗi giải phóng encoder: ${e.message}")
        }

        // 3. Dừng MediaProjection
        try {
            mediaProjection?.stop()
            mediaProjection = null
        } catch (e: Exception) {
            Log.w(TAG, "Lỗi dừng mediaProjection: ${e.message}")
        }

        // 4. Đóng Socket Client
        try {
            streamClient?.disconnect()
            streamClient = null
        } catch (e: Exception) {
            Log.w(TAG, "Lỗi đóng socket: ${e.message}")
        }

        // 5. Dừng Foreground
        stopForeground(STOP_FOREGROUND_REMOVE)

        if (AppState.streamState.value !is StreamState.Error) {
            AppState.updateState(StreamState.Idle)
        }
        Log.i(TAG, "Đã dọn dẹp hoàn tất phiên truyền màn hình.")
    }

    /**
     * Bật hoặc tắt trạng thái cho phép điều khiển từ máy tính.
     */
    private fun setControlEnabledInternal(enabled: Boolean) {
        isControlEnabled = enabled
        AppState.setControlEnabled(enabled)
        Log.i(TAG, "Cập nhật công tắc điều khiển từ xa: $enabled")

        if (isStreaming.get()) {
            updateNotification()
            streamClient?.sendControlState(isControlEnabled, RemoteControlAccessibilityService.isConnected)
            if (!enabled) {
                gestureController?.cancelOngoingGestures()
            }
        }
    }

    /**
     * Cập nhật nội dung Notification khi trạng thái điều khiển đổi.
     */
    private fun updateNotification() {
        if (isStreaming.get()) {
            val manager = getSystemService(NotificationManager::class.java)
            manager?.notify(VideoConfig.NOTIFICATION_ID, buildNotification())
        }
    }

    /**
     * Lấy kích thước thực tế của màn hình (real metrics) tại thời điểm nhận lệnh.
     */
    private fun getRealScreenMetrics(): Pair<Int, Int> {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        return Pair(metrics.widthPixels, metrics.heightPixels)
    }

    override fun onDestroy() {
        stopStreaming()
        super.onDestroy()
    }

    /**
     * Tạo NotificationChannel cho Android 8.0+
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                VideoConfig.CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Hiển thị thông báo khi đang phát màn hình"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    /**
     * Tạo Notification với nút hành động "Dừng".
     * Khi đang bật điều khiển: hiển thị "Đang cho phép điều khiển từ máy tính".
     */
    private fun buildNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val pendingOpenApp = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, ScreenStreamService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStop = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val contentText = if (isControlEnabled) {
            getString(R.string.notification_control_active)
        } else {
            getString(R.string.notification_text)
        }

        return NotificationCompat.Builder(this, VideoConfig.CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pendingOpenApp)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.notification_action_stop),
                pendingStop
            )
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
