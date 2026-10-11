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
    fun testCombineCsdBuffersInvalidData() {
        // Thiếu csd0 hoặc csd1
        assertNull(PacketWriter.combineCsdBuffers(null, ByteBuffer.wrap(byteArrayOf(1, 2))))
        assertNull(PacketWriter.combineCsdBuffers(ByteBuffer.wrap(byteArrayOf(1, 2)), null))

        // csd0 không bắt đầu bằng 00 00 00 01 67
        val invalidSps = byteArrayOf(0x00, 0x00, 0x01, 0x65, 0x11) // Không phải SPS
        val validPps = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x68, 0x22)
        assertNull(PacketWriter.combineCsdBuffers(ByteBuffer.wrap(invalidSps), ByteBuffer.wrap(validPps)))

        // Thiếu PPS 00 00 00 01 68
        val validSps = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0x00)
        val invalidPps = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x65, 0x22) // IDR slice, không phải PPS
        assertNull(PacketWriter.combineCsdBuffers(ByteBuffer.wrap(validSps), ByteBuffer.wrap(invalidPps)))
    }
}
