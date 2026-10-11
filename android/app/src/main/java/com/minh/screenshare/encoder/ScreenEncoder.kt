package com.minh.screenshare.encoder

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Bundle
import android.util.Log
import android.view.Surface
import com.minh.screenshare.config.VideoConfig
import com.minh.screenshare.network.StreamClient
import com.minh.screenshare.protocol.PacketWriter
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lớp ScreenEncoder: Quản lý MediaCodec phần cứng và VirtualDisplay.
 * - Mã hóa H.264 thời gian thực từ Surface của VirtualDisplay.
 * - Xử lý fallback cấu hình MediaCodec cho các chip MediaTek (như Oppo A15s).
 * - Luồng drain riêng biệt đọc buffer xuất ra từ encoder và đẩy vào StreamClient.
 * - Hỗ trợ đổi kích thước khi xoay màn hình trên chính VirtualDisplay hiện có.
 */
class ScreenEncoder(
    private val mediaProjection: MediaProjection,
    private var width: Int,
    private var height: Int,
    private var densityDpi: Int,
    private val streamClient: StreamClient
) {
    companion object {
        private const val TAG = "ScreenShare"
        private const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val TIMEOUT_US = 10_000L // 10ms timeout khi dequeue buffer
    }

    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var virtualDisplay: VirtualDisplay? = null

    private val isRunning = AtomicBoolean(false)
    private var drainThread: Thread? = null

    // Lưu trữ SPS/PPS nhận được từ buffer có cờ BUFFER_FLAG_CODEC_CONFIG
    @Volatile
    private var cachedSpsPps: ByteArray? = null

    /**
     * Bắt đầu phiên mã hóa:
     * 1. Khởi tạo và cấu hình MediaCodec.
     * 2. Lấy input Surface.
     * 3. Tạo VirtualDisplay đưa thẳng vào Surface.
     * 4. Bắt đầu luồng đọc dữ liệu đầu ra.
     */
    fun start() {
        if (isRunning.get()) return
        Log.i(TAG, "Khởi động ScreenEncoder với kích thước: ${width}x${height}, DPI: $densityDpi")

        initCodecAndSurface()

        // Tạo VirtualDisplay đưa thẳng vào Input Surface của MediaCodec (Zero-copy qua GPU)
        virtualDisplay = mediaProjection.createVirtualDisplay(
            "ScreenShareVirtualDisplay",
            width,
            height,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            inputSurface,
            null,
            null
        )

        isRunning.set(true)
        drainThread = Thread({ drainLoop() }, "EncoderDrainThread").apply {
            start()
        }
    }

    /**
     * Khởi tạo MediaCodec với cơ chế Fallback nếu chip không hỗ trợ một số tham số nâng cao.
     */
    private fun initCodecAndSurface() {
        // Thử lần 1: Bộ tham số tối ưu chuẩn
        val format = createFullFormat(width, height)
        val newCodec = MediaCodec.createEncoderByType(MIME_TYPE)

        try {
            newCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            Log.w(TAG, "configure() với format đầy đủ bị lỗi (${e.message}). Thử lại với cấu hình tối giản (Fallback MediaTek)...")
            // Thử lần 2: Làm tròn kích thước xuống bội số của 16 và dùng format tối giản
            val safeWidth = (width / 16) * 16
            val safeHeight = (height / 16) * 16
            width = safeWidth
            height = safeHeight

            val fallbackFormat = createMinimalFormat(safeWidth, safeHeight)
            newCodec.configure(fallbackFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }

        inputSurface = newCodec.createInputSurface()
        newCodec.start()
        codec = newCodec
        Log.i(TAG, "MediaCodec H.264 đã khởi động thành công (${width}x${height}).")
    }

    /**
     * Tạo MediaFormat đầy đủ tính năng tối ưu cho streaming độ trễ thấp.
     */
    private fun createFullFormat(w: Int, h: Int): MediaFormat {
        val format = MediaFormat.createVideoFormat(MIME_TYPE, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, VideoConfig.BIT_RATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, VideoConfig.FRAME_RATE)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, VideoConfig.I_FRAME_INTERVAL_SECONDS)

            // CBR: Bitrate cố định
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            // Profile Baseline: Tương thích cao, không có B-frame
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)

            // Độ trễ thời gian thực (KEY_PRIORITY = 0)
            setInteger(MediaFormat.KEY_PRIORITY, 0)

            // Tự động lặp lại khung hình khi màn hình tĩnh để tránh đứng hình
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, VideoConfig.REPEAT_PREVIOUS_FRAME_INTERVAL_US)
        }
        return format
    }

    /**
     * Bộ cấu hình tối giản nhất cho các dòng máy chip cũ hoặc hạn chế về driver.
     */
    private fun createMinimalFormat(w: Int, h: Int): MediaFormat {
        return MediaFormat.createVideoFormat(MIME_TYPE, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, VideoConfig.BIT_RATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, VideoConfig.FRAME_RATE)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, VideoConfig.I_FRAME_INTERVAL_SECONDS)
        }
    }

    /**
     * Vòng lặp lấy output buffer từ MediaCodec và đóng gói gửi qua StreamClient.
     */
    private fun drainLoop() {
        val bufferInfo = MediaCodec.BufferInfo()

        while (isRunning.get()) {
            val currentCodec = codec ?: break

            val outputBufferId = try {
                currentCodec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            } catch (e: Exception) {
                if (!isRunning.get()) break
                Log.e(TAG, "Lỗi dequeueOutputBuffer: ${e.message}")
                break
            }

            if (outputBufferId >= 0) {
                val outputBuffer = currentCodec.getOutputBuffer(outputBufferId)

                if (outputBuffer != null && bufferInfo.size > 0) {
                    outputBuffer.position(bufferInfo.offset)
                    outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                    val bufferData = ByteArray(bufferInfo.size)
                    outputBuffer.get(bufferData)

                    // 1. Nếu là buffer chứa thông tin cấu hình Codec (SPS/PPS):
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        Log.i(TAG, "Nhận BUFFER_FLAG_CODEC_CONFIG (${bufferData.size} bytes). Lưu lại SPS/PPS.")
                        cachedSpsPps = bufferData
                        // Gửi ngay gói cấu hình đầu tiên cho máy tính
                        val configPacket = PacketWriter.createConfigPacket(width, height, bufferData)
                        streamClient.enqueuePacket(configPacket, PacketWriter.TYPE_CONFIG)
                    }
                    // 2. Nếu là Keyframe (I-frame):
                    else if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) {
                        val sps = cachedSpsPps
                        // Quy tắc: Luôn gửi gói Loại 0 (Config) ngay trước mỗi Keyframe
                        if (sps != null) {
                            val configPacket = PacketWriter.createConfigPacket(width, height, sps)
                            streamClient.enqueuePacket(configPacket, PacketWriter.TYPE_CONFIG)
                        }

                        // Gửi gói Loại 1 (Keyframe)
                        val keyframePacket = PacketWriter.createFramePacket(
                            PacketWriter.TYPE_KEYFRAME,
                            bufferData,
                            0,
                            bufferData.size
                        )
                        streamClient.enqueuePacket(keyframePacket, PacketWriter.TYPE_KEYFRAME)
                    }
                    // 3. Khung hình thường (P-frame / Delta frame):
                    else {
                        val deltaPacket = PacketWriter.createFramePacket(
                            PacketWriter.TYPE_DELTA,
                            bufferData,
                            0,
                            bufferData.size
                        )
                        streamClient.enqueuePacket(deltaPacket, PacketWriter.TYPE_DELTA)
                    }
                }

                try {
                    currentCodec.releaseOutputBuffer(outputBufferId, false)
                } catch (_: Exception) {}
            } else if (outputBufferId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val newFormat = currentCodec.outputFormat
                Log.i(TAG, "Định dạng output của MediaCodec thay đổi: $newFormat")
                // Trích xuất SPS/PPS từ format nếu có
                val csd0 = newFormat.getByteBuffer("csd-0")
                val csd1 = newFormat.getByteBuffer("csd-1")
                if (csd0 != null && csd1 != null) {
                    val spsPps = ByteArray(csd0.remaining() + csd1.remaining())
                    csd0.get(spsPps, 0, csd0.remaining())
                    csd1.get(spsPps, csd0.remaining(), csd1.remaining())
                    cachedSpsPps = spsPps
                    val configPacket = PacketWriter.createConfigPacket(width, height, spsPps)
                    streamClient.enqueuePacket(configPacket, PacketWriter.TYPE_CONFIG)
                }
            }
        }
    }

    /**
     * Yêu cầu MediaCodec sinh Keyframe ngay lập tức.
     */
    fun requestSyncFrame() {
        try {
            val currentCodec = codec
            if (currentCodec != null && isRunning.get()) {
                val params = Bundle().apply {
                    putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                }
                currentCodec.setParameters(params)
                Log.d(TAG, "Đã gửi yêu cầu PARAMETER_KEY_REQUEST_SYNC_FRAME tới MediaCodec.")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Không thể yêu cầu sync frame: ${e.message}")
        }
    }

    /**
     * Xử lý xoay màn hình (Orientation Change):
     * - Dừng luồng drain và giải phóng MediaCodec cũ.
     * - Khởi tạo MediaCodec mới với kích thước mới.
     * - Gọi resize() và setSurface() trên CHÍNH VirtualDisplay hiện tại
     *   (Android 14 cấm tạo VirtualDisplay thứ 2 từ cùng MediaProjection).
     */
    fun reconfigure(newWidth: Int, newHeight: Int, newDpi: Int) {
        if (!isRunning.get()) return
        Log.i(TAG, "Bắt đầu cập nhật kích thước xoay màn hình: ${newWidth}x${newHeight}")

        this.width = newWidth
        this.height = newHeight
        this.densityDpi = newDpi

        // Dừng luồng drain cũ
        isRunning.set(false)
        try {
            drainThread?.join(500)
        } catch (_: Exception) {}

        // Dọn dẹp codec cũ
        try {
            codec?.stop()
            codec?.release()
        } catch (_: Exception) {}
        codec = null

        try {
            inputSurface?.release()
        } catch (_: Exception) {}
        inputSurface = null

        // Khởi tạo codec và Surface mới với kích thước mới
        initCodecAndSurface()

        // Cập nhật lại chính VirtualDisplay hiện có
        virtualDisplay?.let { vd ->
            vd.resize(newWidth, newHeight, newDpi)
            vd.surface = inputSurface
        }

        // Bắt đầu lại luồng drain
        isRunning.set(true)
        drainThread = Thread({ drainLoop() }, "EncoderDrainThread").apply {
            start()
        }

        // Yêu cầu Keyframe mới với kích thước mới
        requestSyncFrame()
        Log.i(TAG, "Đã reconfigure thành công VirtualDisplay sang ${newWidth}x${newHeight}.")
    }

    /**
     * Dọn dẹp toàn bộ tài nguyên an toàn khi dừng phiên truyền.
     * An toàn khi gọi nhiều lần.
     */
    fun release() {
        isRunning.set(false)
        try {
            drainThread?.interrupt()
            drainThread = null
        } catch (_: Exception) {}

        try {
            virtualDisplay?.release()
            virtualDisplay = null
        } catch (_: Exception) {}

        try {
            codec?.stop()
        } catch (_: Exception) {}

        try {
            codec?.release()
            codec = null
        } catch (_: Exception) {}

        try {
            inputSurface?.release()
            inputSurface = null
        } catch (_: Exception) {}

        cachedSpsPps = null
        Log.i(TAG, "Đã giải phóng hoàn toàn tài nguyên ScreenEncoder.")
    }
}
