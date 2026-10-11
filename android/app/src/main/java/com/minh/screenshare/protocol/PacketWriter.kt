package com.minh.screenshare.protocol

import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Lớp PacketWriter thuần Kotlin (không phụ thuộc Android framework).
 * Đảm nhiệm việc đóng gói dữ liệu H.264 theo đúng định dạng giao thức:
 *
 * (4 byte: Độ dài Payload Big-Endian) (1 byte: Loại gói) (Payload Data)
 *
 * LƯU Ý QUAN TRỌNG:
 * - 4 byte độ dài CHỈ TÍNH phần Payload data, KHÔNG bao gồm 1 byte loại gói.
 * - Loại 0 (Config): Payload = (4 byte Width) + (4 byte Height) + (Dữ liệu SPS/PPS Annex-B)
 * - Loại 1 (Keyframe): Payload = Dữ liệu NAL unit của I-frame
 * - Loại 2 (Delta frame): Payload = Dữ liệu NAL unit của P-frame
 */
object PacketWriter {
    const val TYPE_CONFIG: Byte = 0
    const val TYPE_KEYFRAME: Byte = 1
    const val TYPE_DELTA: Byte = 2

    /**
     * Tạo mảng byte cho gói Cấu hình (Loại 0).
     * @param width Chiều rộng khung hình video (pixel)
     * @param height Chiều cao khung hình video (pixel)
     * @param spsPps Dữ liệu NAL units SPS và PPS (Annex-B có start code 00 00 00 01)
     * @return Mảng byte hoàn chỉnh sẵn sàng gửi qua Socket
     */
    fun createConfigPacket(width: Int, height: Int, spsPps: ByteArray): ByteArray {
        val payloadLength = 4 + 4 + spsPps.size
        val totalLength = 4 + 1 + payloadLength

        val buffer = ByteBuffer.allocate(totalLength).order(ByteOrder.BIG_ENDIAN)
        // 1. 4 byte độ dài payload
        buffer.putInt(payloadLength)
        // 2. 1 byte loại gói tin (0)
        buffer.put(TYPE_CONFIG)
        // 3. 4 byte width
        buffer.putInt(width)
        // 4. 4 byte height
        buffer.putInt(height)
        // 5. SPS/PPS data
        buffer.put(spsPps)

        return buffer.array()
    }

    /**
     * Tạo mảng byte cho gói Khung hình (Loại 1: Keyframe, hoặc Loại 2: Delta frame).
     * @param type TYPE_KEYFRAME (1) hoặc TYPE_DELTA (2)
     * @param data Mảng chứa dữ liệu khung hình từ MediaCodec
     * @param offset Vị trí bắt đầu trong mảng data
     * @param size Độ dài dữ liệu cần lấy
     * @return Mảng byte hoàn chỉnh sẵn sàng gửi qua Socket
     */
    fun createFramePacket(type: Byte, data: ByteArray, offset: Int = 0, size: Int = data.size): ByteArray {
        val payloadLength = size
        val totalLength = 4 + 1 + payloadLength

        val buffer = ByteBuffer.allocate(totalLength).order(ByteOrder.BIG_ENDIAN)
        // 1. 4 byte độ dài payload
        buffer.putInt(payloadLength)
        // 2. 1 byte loại gói tin (1 hoặc 2)
        buffer.put(type)
        // 3. Dữ liệu khung hình H.264
        buffer.put(data, offset, size)

        return buffer.array()
    }

    /**
     * Ghi trực tiếp gói Cấu hình vào OutputStream (hỗ trợ ghi trực tiếp vào socket stream).
     */
    fun writeConfigPacket(output: OutputStream, width: Int, height: Int, spsPps: ByteArray) {
        val packet = createConfigPacket(width, height, spsPps)
        output.write(packet)
    }

    /**
     * Ghi trực tiếp gói Khung hình vào OutputStream (hạn chế cấp phát mảng thừa).
     */
    fun writeFramePacket(output: OutputStream, type: Byte, data: ByteArray, offset: Int, size: Int) {
        // Ghi header 5 byte
        val header = ByteBuffer.allocate(5).order(ByteOrder.BIG_ENDIAN)
        header.putInt(size)
        header.put(type)
        output.write(header.array())

        // Ghi payload
        output.write(data, offset, size)
    }
}
