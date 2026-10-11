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

    const val NAL_TYPE_SPS = 7
    const val NAL_TYPE_PPS = 8

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

    /**
     * Tìm vị trí header NAL unit có loại mong muốn (targetType: 7 cho SPS, 8 cho PPS).
     * Hỗ trợ cả start code 4 byte (00 00 00 01) và 3 byte (00 00 01).
     * Loại NAL được xác định bằng: (nalHeaderByte.toInt() and 0x1F).
     * @return Vị trí index của byte NAL header nếu tìm thấy, hoặc -1 nếu không có.
     */
    fun findNalUnitType(data: ByteArray, targetType: Int, startIndex: Int = 0): Int {
        for (i in startIndex..data.size - 4) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
                // Kiểm tra start code 4 byte: 00 00 00 01 [NAL_HEADER]
                if (i + 4 < data.size && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                    val nalType = data[i + 4].toInt() and 0x1F
                    if (nalType == targetType) return i + 4
                }
                // Kiểm tra start code 3 byte: 00 00 01 [NAL_HEADER]
                else if (data[i + 2] == 1.toByte()) {
                    val nalType = data[i + 3].toInt() and 0x1F
                    if (nalType == targetType) return i + 3
                }
            }
        }
        return -1
    }

    /**
     * Kiểm tra tính hợp lệ của mảng byte SPS/PPS:
     * - Chấp nhận start code 3 byte (00 00 01) hoặc 4 byte (00 00 00 01).
     * - Bắt đầu bằng NAL SPS (loại 7).
     * - Phía sau có chứa NAL PPS (loại 8).
     * - Kiểm tra loại NAL bằng (byte and 0x1F): SPS = 7, PPS = 8 (không so sánh cứng 0x67 / 0x68).
     */
    fun isValidSpsPps(data: ByteArray): Boolean {
        val spsHeaderIndex = when {
            data.size >= 5 && data[0] == 0.toByte() && data[1] == 0.toByte() && data[2] == 0.toByte() && data[3] == 1.toByte() -> 4
            data.size >= 4 && data[0] == 0.toByte() && data[1] == 0.toByte() && data[2] == 1.toByte() -> 3
            else -> return false
        }

        val spsType = data[spsHeaderIndex].toInt() and 0x1F
        if (spsType != NAL_TYPE_SPS) return false

        return findNalUnitType(data, NAL_TYPE_PPS, startIndex = spsHeaderIndex + 1) != -1
    }

    /**
     * Trích xuất và ghép csd-0 (SPS) và csd-1 (PPS) từ ByteBuffer.
     * Sử dụng duplicate() để không ảnh hưởng đến position / limit của buffer gốc.
     * Trả về mảng byte ghép SPS rồi PPS nguyên vẹn nếu hợp lệ, ngược lại trả về null.
     */
    fun combineCsdBuffers(csd0: ByteBuffer?, csd1: ByteBuffer?): ByteArray? {
        if (csd0 == null || csd1 == null) return null

        val dup0 = csd0.duplicate()
        val len0 = dup0.remaining()
        if (len0 <= 0) return null
        val bytes0 = ByteArray(len0)
        dup0.get(bytes0)

        val dup1 = csd1.duplicate()
        val len1 = dup1.remaining()
        if (len1 <= 0) return null
        val bytes1 = ByteArray(len1)
        dup1.get(bytes1)

        val combined = bytes0 + bytes1
        return if (isValidSpsPps(combined)) combined else null
    }

    /**
     * Tìm vị trí xuất hiện đầu tiên của chuỗi byte con 'target' trong mảng 'source'.
     */
    fun indexOfSequence(source: ByteArray, target: ByteArray, startIndex: Int = 0): Int {
        if (target.isEmpty() || source.size < target.size + startIndex) return -1
        for (i in startIndex..(source.size - target.size)) {
            var matched = true
            for (j in target.indices) {
                if (source[i + j] != target[j]) {
                    matched = false
                    break
                }
            }
            if (matched) return i
        }
        return -1
    }
}
