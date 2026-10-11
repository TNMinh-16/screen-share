package com.minh.screenshare.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Unit Test kiểm tra tính chính xác của PacketWriter từng byte:
 * - Thứ tự byte Big-Endian
 * - 4 byte độ dài payload (không tính 1 byte loại gói)
 * - 1 byte loại gói tin (0: Config, 1: Keyframe, 2: Delta)
 * - Nội dung payload (Width, Height, SPS/PPS, NAL data)
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
}
