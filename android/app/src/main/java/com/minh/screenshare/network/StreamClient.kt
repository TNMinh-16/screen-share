package com.minh.screenshare.network

import android.util.Log
import com.minh.screenshare.config.VideoConfig
import com.minh.screenshare.protocol.PacketWriter
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Lớp StreamClient: Quản lý kết nối TCP Client tới máy tính Windows.
 * - Luồng gửi tách biệt với luồng mã hóa (không chặn encoder).
 * - Sử dụng hàng đợi có giới hạn dung lượng để kiểm soát độ trễ.
 * - Khi hàng đợi đầy (nghẽn mạng): xóa sạch hàng đợi, bỏ qua các khung thường
 *   cho đến khi có Keyframe mới, và yêu cầu MediaCodec sinh Keyframe ngay.
 * - Khi nhận Keyframe mà hàng đợi đầy: giữ lại gói cấu hình (Loại 0) đi ngay trước Keyframe đó.
 */
class StreamClient(
    private val host: String,
    private val port: Int,
    private val onError: (Throwable) -> Unit,
    private val onRequestSyncFrame: () -> Unit
) {
    companion object {
        private const val TAG = "ScreenShare"
        private const val CONNECT_TIMEOUT_MS = 5000
    }

    private var socket: Socket? = null
    private var outputStream: OutputStream? = null

    // Hàng đợi chứa các gói tin đã đóng gói chờ gửi
    private val packetQueue = ArrayBlockingQueue<ByteArray>(VideoConfig.QUEUE_CAPACITY)

    private val isRunning = AtomicBoolean(false)
    private var senderThread: Thread? = null

    // Cờ báo hiệu đang đợi Keyframe sau khi bị drop gói do đầy queue
    @Volatile
    private var waitingForKeyframe = false

    // Lưu lại gói cấu hình gần nhất để luôn có thể ghép trước Keyframe khi hàng đợi bị xóa
    @Volatile
    private var lastConfigPacket: ByteArray? = null

    // Thống kê cho việc ghi log định kỳ
    val totalBytesSent = AtomicLong(0L)
    val totalFramesSent = AtomicLong(0L)

    val currentQueueSize: Int
        get() = packetQueue.size

    /**
     * Kết nối TCP tới máy chủ (chạy ở luồng nền).
     * Bật TCP_NODELAY và khởi động luồng gửi.
     */
    fun connect() {
        val newSocket = Socket()
        // Đặt timeout kết nối 5 giây
        newSocket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        // Bật TCP_NODELAY để loại bỏ độ trễ của thuật toán Nagle
        newSocket.tcpNoDelay = true

        socket = newSocket
        outputStream = BufferedOutputStream(newSocket.getOutputStream(), 64 * 1024)

        isRunning.set(true)
        waitingForKeyframe = false

        // Khởi động luồng gửi chuyên dụng
        senderThread = Thread({ sendLoop() }, "StreamSenderThread").apply {
            start()
        }

        // Vừa kết nối xong -> yêu cầu MediaCodec gửi ngay Keyframe đầu tiên
        onRequestSyncFrame()
        Log.i(TAG, "Đã kết nối TCP thành công tới $host:$port (TCP_NODELAY=true)")
    }

    /**
     * Vòng lặp gửi dữ liệu qua Socket trên luồng riêng.
     */
    private fun sendLoop() {
        try {
            val out = outputStream ?: return
            while (isRunning.get()) {
                val packet = packetQueue.poll(100, TimeUnit.MILLISECONDS) ?: continue

                out.write(packet)
                out.flush()

                totalBytesSent.addAndGet(packet.size.toLong())
                totalFramesSent.incrementAndGet()
            }
        } catch (e: Exception) {
            if (isRunning.get()) {
                Log.e(TAG, "Lỗi trong luồng gửi dữ liệu: ${e.message}")
                isRunning.set(false)
                onError(e)
            }
        }
    }

    /**
     * Đẩy một gói tin vào hàng đợi gửi.
     * @param packet Mảng byte gói tin hoàn chỉnh
     * @param packetType Loại gói tin (0: Config, 1: Keyframe, 2: Delta)
     */
    fun enqueuePacket(packet: ByteArray, packetType: Byte) {
        if (!isRunning.get()) return

        // 1. Gói Cấu hình (Loại 0):
        if (packetType == PacketWriter.TYPE_CONFIG) {
            lastConfigPacket = packet
            // Ưu tiên nạp gói cấu hình; nếu queue đầy thì lấy bớt gói cũ ra để nhường chỗ
            if (!packetQueue.offer(packet)) {
                packetQueue.poll()
                packetQueue.offer(packet)
            }
            return
        }

        // 2. Gói Keyframe (Loại 1):
        if (packetType == PacketWriter.TYPE_KEYFRAME) {
            if (waitingForKeyframe) {
                Log.i(TAG, "Đã nhận được Keyframe sau khi nghẽn mạng. Phục hồi truyền bình thường.")
                waitingForKeyframe = false
            }

            // Thử đưa vào queue, nếu đầy:
            // XÓA HẾT NHƯNG PHẢI GIỮ LẠI GÓI CẤU HÌNH (LOẠI 0) ĐI NGAY TRƯỚC KEYFRAME!
            if (!packetQueue.offer(packet)) {
                Log.w(TAG, "Queue đầy khi nhận Keyframe. Xóa queue và xếp lại [Config] -> [Keyframe].")
                packetQueue.clear()
                // Xếp lại gói cấu hình trước nếu có
                lastConfigPacket?.let { cfg ->
                    packetQueue.offer(cfg)
                }
                // Xếp gói Keyframe ngay sau
                packetQueue.offer(packet)
            }
            return
        }

        // 3. Khung hình thường (Loại 2):
        // Nếu đang trong trạng thái chờ Keyframe do vừa drop mạng -> bỏ qua khung thường này
        if (waitingForKeyframe) {
            return
        }

        // Đẩy vào hàng đợi
        val success = packetQueue.offer(packet)
        if (!success) {
            // HÀNG ĐỢI ĐÃ ĐẦY (MẠNG BỊ CHẬM):
            // Quy tắc: KHÔNG bỏ lẻ một gói giữa chuỗi (sẽ làm vỡ hình).
            // Thay vào đó: Xóa sạch hàng đợi, đặt cờ chờ Keyframe, và yêu cầu MediaCodec phát Keyframe mới!
            Log.w(TAG, "Hàng đợi gửi đầy (${VideoConfig.QUEUE_CAPACITY} gói). Xóa queue và yêu cầu Keyframe mới!")
            packetQueue.clear()
            waitingForKeyframe = true
            onRequestSyncFrame()
        }
    }

    /**
     * Dọn dẹp và đóng socket an toàn.
     */
    fun disconnect() {
        isRunning.set(false)
        try {
            senderThread?.interrupt()
            senderThread = null
        } catch (_: Exception) {}

        packetQueue.clear()

        try {
            outputStream?.close()
        } catch (_: Exception) {}
        outputStream = null

        try {
            socket?.close()
        } catch (_: Exception) {}
        socket = null

        Log.i(TAG, "Đã đóng kết nối StreamClient.")
    }
}
