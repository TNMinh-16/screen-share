package com.minh.screenshare.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Unit Test kiểm tra tính chính xác của PacketWriter từng byte:
 * - Thứ tự byte Big-Endian
 * - 4 byte độ dài payload (không tính 1 byte loại gói)
 * - 1 byte loại gói tin (0: Config, 1: Keyframe, 2: Delta)
 * - Ghép csd-0 (SPS) và csd-1 (PPS), kiểm tra tính toàn vẹn và thứ tự
 * - Kiểm tra tính hợp lệ của mảng byte SPS/PPS
 */
class PacketWriterTest {

    @Test
    fun testConfigPacketStructureAndEndianness() {
        val width = 1280
        val height = 720
        val fakeSpsPps = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0x00, 0x1E)

        val packet = PacketWriter.createConfigPacket(width, height, fakeSpsPps)

        // Tổng độ dài mong đợi: 4 (len) + 1 (type) + 4 (w) + 4 (h) + 8 (sps) = 21 bytes
        val expectedPayloadLen = 4 + 4 + fakeSpsPps.size
        assertEquals(4 + 1 + expectedPayloadLen, packet.size)

        val buffer = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN)

        // 1. Kiểm tra 4 byte độ dài payload (Big-Endian)
        val readPayloadLen = buffer.int
        assertEquals(expectedPayloadLen, readPayloadLen)

        // 2. Kiểm tra 1 byte loại gói (0)
        val readType = buffer.get()
        assertEquals(PacketWriter.TYPE_CONFIG, readType)

        // 3. Kiểm tra 4 byte width (Big-Endian)
        val readWidth = buffer.int
        assertEquals(width, readWidth)

        // 4. Kiểm tra 4 byte height (Big-Endian)
        val readHeight = buffer.int
        assertEquals(height, readHeight)

        // 5. Kiểm tra SPS/PPS bytes
        val readSpsPps = ByteArray(fakeSpsPps.size)
        buffer.get(readSpsPps)
        assertArrayEquals(fakeSpsPps, readSpsPps)
    }

    @Test
    fun testKeyframePacketStructure() {
        val fakeFrameData = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x65, 0x11, 0x22, 0x33, 0x44)

        val packet = PacketWriter.createFramePacket(PacketWriter.TYPE_KEYFRAME, fakeFrameData)

        // Tổng độ dài: 4 + 1 + fakeFrameData.size = 14 bytes
        assertEquals(4 + 1 + fakeFrameData.size, packet.size)

        val buffer = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN)

        // 1. Kiểm tra độ dài payload
        val readLen = buffer.int
        assertEquals(fakeFrameData.size, readLen)

        // 2. Kiểm tra loại gói
        val readType = buffer.get()
        assertEquals(PacketWriter.TYPE_KEYFRAME, readType)

        // 3. Kiểm tra payload
        val readData = ByteArray(fakeFrameData.size)
        buffer.get(readData)
        assertArrayEquals(fakeFrameData, readData)
    }

    @Test
    fun testDeltaFramePacketWithOffset() {
        val largeBuffer = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0x00, 0x00, 0x01, 0x41, 0x99.toByte())
        val offset = 2
        val size = 4 // 00 00 01 41

        val packet = PacketWriter.createFramePacket(PacketWriter.TYPE_DELTA, largeBuffer, offset, size)

        assertEquals(4 + 1 + size, packet.size)

        val buffer = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN)
        assertEquals(size, buffer.int)
        assertEquals(PacketWriter.TYPE_DELTA, buffer.get())

        val readPayload = ByteArray(size)
        buffer.get(readPayload)
        val expectedPayload = byteArrayOf(0x00, 0x00, 0x01, 0x41)
        assertArrayEquals(expectedPayload, readPayload)
    }

    @Test
    fun testOutputStreamMatchesByteArray() {
        val width = 640
        val height = 360
        val spsPps = byteArrayOf(1, 2, 3, 4)

        val arrayPacket = PacketWriter.createConfigPacket(width, height, spsPps)

        val bos = ByteArrayOutputStream()
        PacketWriter.writeConfigPacket(bos, width, height, spsPps)

        assertArrayEquals(arrayPacket, bos.toByteArray())
    }

    @Test
    fun testCombineCsdBuffersValidOrderAndContent() {
        // Giả lập csd-0 (SPS) bắt đầu bằng 00 00 00 01 67
        val spsBytes = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0xC0.toByte(), 0x1E, 0xD9.toByte())
        // Giả lập csd-1 (PPS) bắt đầu bằng 00 00 00 01 68
        val ppsBytes = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x68, 0xCE.toByte(), 0x3C, 0x80.toByte())

        val csd0 = ByteBuffer.wrap(spsBytes)
        val csd1 = ByteBuffer.wrap(ppsBytes)

        val initialPos0 = csd0.position()
        val initialPos1 = csd1.position()

        val combined = PacketWriter.combineCsdBuffers(csd0, csd1)
        assertNotNull(combined)

        // 1. Kiểm tra không làm thay đổi con trỏ đọc (position) của buffer gốc
        assertEquals(initialPos0, csd0.position())
        assertEquals(initialPos1, csd1.position())

        // 2. Kiểm tra độ dài ghép đúng bằng tổng độ dài
        assertEquals(spsBytes.size + ppsBytes.size, combined!!.size)

        // 3. Kiểm tra SPS ở đầu, PPS ở ngay sau
        val expected = spsBytes + ppsBytes
        assertArrayEquals(expected, combined)

        // 4. Kiểm tra hàm isValidSpsPps nhận diện hợp lệ
        assertTrue(PacketWriter.isValidSpsPps(combined))
    }

    @Test
    fun testCombineCsdBuffersThreeByteAndFourByteStartCodes() {
        // csd-0 (SPS) dùng start code 3 byte (00 00 01) và NAL header 0x27 (nal_ref_idc=1, type=7)
        val sps3Bytes = byteArrayOf(0x00, 0x00, 0x01, 0x27, 0x42, 0x00, 0x1E)
        // csd-1 (PPS) dùng start code 4 byte (00 00 00 01) và NAL header 0x28 (nal_ref_idc=1, type=8)
        val pps4Bytes = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x28, 0xCE.toByte(), 0x3C)

        val combined = PacketWriter.combineCsdBuffers(ByteBuffer.wrap(sps3Bytes), ByteBuffer.wrap(pps4Bytes))
        assertNotNull(combined)
        assertArrayEquals(sps3Bytes + pps4Bytes, combined)
        assertTrue(PacketWriter.isValidSpsPps(combined!!))

        // Ngược lại: csd-0 4 byte và csd-1 3 byte
        val sps4Bytes = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x47, 0x42, 0x00)
        val pps3Bytes = byteArrayOf(0x00, 0x00, 0x01, 0x48, 0xCE.toByte(), 0x3C)
        val combined2 = PacketWriter.combineCsdBuffers(ByteBuffer.wrap(sps4Bytes), ByteBuffer.wrap(pps3Bytes))
        assertNotNull(combined2)
        assertTrue(PacketWriter.isValidSpsPps(combined2!!))
    }

    @Test
    fun testIsValidSpsPpsVariousNalHeadersAndStartCodes() {
        // 1. Cả 2 đều dùng start code 3 byte: 00 00 01 [SPS=0x67] + 00 00 01 [PPS=0x68]
        val both3Byte = byteArrayOf(0x00, 0x00, 0x01, 0x67, 0x11, 0x22, 0x00, 0x00, 0x01, 0x68, 0x33)
        assertTrue(PacketWriter.isValidSpsPps(both3Byte))

        // 2. Cả 2 đều dùng start code 4 byte: 00 00 00 01 [SPS=0x67] + 00 00 00 01 [PPS=0x68]
        val both4Byte = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x67, 0x11, 0x00, 0x00, 0x00, 0x01, 0x68, 0x22)
        assertTrue(PacketWriter.isValidSpsPps(both4Byte))

        // 3. Các giá trị NAL header khác nhau nhưng cùng NAL type:
        // SPS type 7: 0x07 (ref_idc=0), 0x27 (ref_idc=1), 0x47 (ref_idc=2), 0x67 (ref_idc=3)
        // PPS type 8: 0x08 (ref_idc=0), 0x28 (ref_idc=1), 0x48 (ref_idc=2), 0x68 (ref_idc=3)
        val refIdcCases = listOf(
            Pair(0x07.toByte(), 0x08.toByte()),
            Pair(0x27.toByte(), 0x28.toByte()),
            Pair(0x47.toByte(), 0x48.toByte()),
            Pair(0x67.toByte(), 0x68.toByte())
        )
        for ((spsHeader, ppsHeader) in refIdcCases) {
            val data4Byte = byteArrayOf(
                0x00, 0x00, 0x00, 0x01, spsHeader, 0x11,
                0x00, 0x00, 0x00, 0x01, ppsHeader, 0x22
            )
            assertTrue("Thử nghiệm với SPS header=0x${Integer.toHexString(spsHeader.toInt())}, PPS header=0x${Integer.toHexString(ppsHeader.toInt())}",
                PacketWriter.isValidSpsPps(data4Byte))

            val data3Byte = byteArrayOf(
                0x00, 0x00, 0x01, spsHeader, 0x11,
                0x00, 0x00, 0x01, ppsHeader, 0x22
            )
            assertTrue(PacketWriter.isValidSpsPps(data3Byte))
        }
    }

    @Test
    fun testCombineCsdBuffersInvalidData() {
        // Thiếu csd0 hoặc csd1
        assertNull(PacketWriter.combineCsdBuffers(null, ByteBuffer.wrap(byteArrayOf(1, 2))))
        assertNull(PacketWriter.combineCsdBuffers(ByteBuffer.wrap(byteArrayOf(1, 2)), null))

        // csd0 không phải SPS (0x65 = IDR slice, type 5)
        val invalidSps = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x65, 0x11)
        val validPps = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x68, 0x22)
        assertNull(PacketWriter.combineCsdBuffers(ByteBuffer.wrap(invalidSps), ByteBuffer.wrap(validPps)))

        // Thiếu PPS (chỉ có IDR slice 0x65 ở buffer 2)
        val validSps = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0x00)
        val invalidPps = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x65, 0x22)
        assertNull(PacketWriter.combineCsdBuffers(ByteBuffer.wrap(validSps), ByteBuffer.wrap(invalidPps)))

        // Bắt đầu bằng PPS trước SPS
        val ppsFirst = byteArrayOf(
            0x00, 0x00, 0x00, 0x01, 0x68, 0x11,
            0x00, 0x00, 0x00, 0x01, 0x67, 0x22
        )
        assertFalse(PacketWriter.isValidSpsPps(ppsFirst))

        // Dữ liệu ngẫu nhiên hoặc quá ngắn
        assertFalse(PacketWriter.isValidSpsPps(byteArrayOf(0, 0, 1)))
        assertFalse(PacketWriter.isValidSpsPps(byteArrayOf(1, 2, 3, 4, 5, 6)))
    }
}
