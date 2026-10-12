package com.minh.screenshare.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Unit Test kiểm tra tính chính xác của ControlPacketParser, ControlPacketWriter và chuyển đổi tọa độ.
 */
class ControlProtocolTest {

    @Test
    fun testParseTouchCommands() {
        // 1. TOUCH_DOWN
        val downBuffer = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
        downBuffer.putShort(1234.toShort())
        downBuffer.putShort(5678.toShort())
        val cmdDown = ControlPacketParser.parseCommand(ControlCommand.CMD_TOUCH_DOWN, downBuffer.array())
        assertTrue(cmdDown is ControlCommand.TouchDown)
        val td = cmdDown as ControlCommand.TouchDown
        assertEquals(1234, td.x)
        assertEquals(5678, td.y)

        // 2. TOUCH_MOVE
        val moveBuffer = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
        moveBuffer.putShort(65535.toShort())
        moveBuffer.putShort(0.toShort())
        val cmdMove = ControlPacketParser.parseCommand(ControlCommand.CMD_TOUCH_MOVE, moveBuffer.array())
        assertTrue(cmdMove is ControlCommand.TouchMove)
        val tm = cmdMove as ControlCommand.TouchMove
        assertEquals(65535, tm.x)
        assertEquals(0, tm.y)

        // 3. TOUCH_UP
        val upBuffer = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
        upBuffer.putShort(32000.toShort())
        upBuffer.putShort(16000.toShort())
        val cmdUp = ControlPacketParser.parseCommand(ControlCommand.CMD_TOUCH_UP, upBuffer.array())
        assertTrue(cmdUp is ControlCommand.TouchUp)
        val tu = cmdUp as ControlCommand.TouchUp
        assertEquals(32000, tu.x)
        assertEquals(16000, tu.y)
    }

    @Test
    fun testParseScrollAndPinch() {
        // SCROLL
        val scrollBuf = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
        scrollBuf.putShort(100.toShort())
        scrollBuf.putShort(200.toShort())
        scrollBuf.putShort((-120).toShort())
        scrollBuf.putShort(240.toShort())
        val cmdScroll = ControlPacketParser.parseCommand(ControlCommand.CMD_SCROLL, scrollBuf.array())
        assertTrue(cmdScroll is ControlCommand.Scroll)
        val sc = cmdScroll as ControlCommand.Scroll
        assertEquals(100, sc.x)
        assertEquals(200, sc.y)
        assertEquals((-120).toShort(), sc.dx)
        assertEquals(240.toShort(), sc.dy)

        // PINCH
        val pinchBuf = ByteBuffer.allocate(5).order(ByteOrder.BIG_ENDIAN)
        pinchBuf.putShort(500.toShort())
        pinchBuf.putShort(600.toShort())
        pinchBuf.put(1.toByte())
        val cmdPinch = ControlPacketParser.parseCommand(ControlCommand.CMD_PINCH, pinchBuf.array())
        assertTrue(cmdPinch is ControlCommand.Pinch)
        val pi = cmdPinch as ControlCommand.Pinch
        assertEquals(500, pi.x)
        assertEquals(600, pi.y)
        assertEquals(1.toByte(), pi.direction)
    }

    @Test
    fun testParseTextAndKeyAndNav() {
        // TEXT (Tiếng Việt có dấu và Emoji)
        val textStr = "Xin chào Việt Nam 🇻🇳!"
        val textBytes = textStr.toByteArray(Charsets.UTF_8)
        val cmdText = ControlPacketParser.parseCommand(ControlCommand.CMD_TEXT, textBytes)
        assertTrue(cmdText is ControlCommand.Text)
        assertEquals(textStr, (cmdText as ControlCommand.Text).text)

        // KEY
        val cmdKey = ControlPacketParser.parseCommand(ControlCommand.CMD_KEY, byteArrayOf(ControlCommand.KEY_BACKSPACE))
        assertTrue(cmdKey is ControlCommand.Key)
        assertEquals(ControlCommand.KEY_BACKSPACE, (cmdKey as ControlCommand.Key).keyCode)

        // NAV
        val cmdNav = ControlPacketParser.parseCommand(ControlCommand.CMD_NAV, byteArrayOf(ControlCommand.NAV_HOME))
        assertTrue(cmdNav is ControlCommand.Nav)
        assertEquals(ControlCommand.NAV_HOME, (cmdNav as ControlCommand.Nav).navCode)
    }

    @Test
    fun testParseInvalidOrTruncatedPackets() {
        // Gói thiếu byte
        assertNull(ControlPacketParser.parseCommand(ControlCommand.CMD_TOUCH_DOWN, byteArrayOf(1, 2)))
        assertNull(ControlPacketParser.parseCommand(ControlCommand.CMD_SCROLL, byteArrayOf(1, 2, 3, 4)))
        assertNull(ControlPacketParser.parseCommand(ControlCommand.CMD_TEXT, byteArrayOf()))
        assertNull(ControlPacketParser.parseCommand(ControlCommand.CMD_KEY, byteArrayOf(99))) // Mã key không hợp lệ
        assertNull(ControlPacketParser.parseCommand(ControlCommand.CMD_NAV, byteArrayOf(0)))  // Mã nav không hợp lệ

        // Loại gói không xác định
        assertNull(ControlPacketParser.parseCommand(0x99.toByte(), byteArrayOf(1, 2, 3)))

        // Gói vượt quá 64 KB
        val hugePayload = ByteArray(65 * 1024)
        assertNull(ControlPacketParser.parseCommand(ControlCommand.CMD_TEXT, hugePayload))
    }

    @Test
    fun testNormalizedToScreenPixels() {
        val screenW = 720
        val screenH = 1600

        // Tọa độ góc trên trái (0, 0) -> (0f, 0f)
        val (p0x, p0y) = ControlPacketParser.normalizedToScreenPixels(0, 0, screenW, screenH)
        assertEquals(0f, p0x, 0.001f)
        assertEquals(0f, p0y, 0.001f)

        // Tọa độ chính giữa (32767, 32767) -> (~360f, ~800f)
        val (pmx, pmy) = ControlPacketParser.normalizedToScreenPixels(32767, 32767, screenW, screenH)
        assertEquals(360f, pmx, 1.0f)
        assertEquals(800f, pmy, 1.0f)

        // Tọa độ góc dưới phải (65535, 65535) -> (719f, 1599f)
        val (p1x, p1y) = ControlPacketParser.normalizedToScreenPixels(65535, 65535, screenW, screenH)
        assertEquals(719f, p1x, 0.5f)
        assertEquals(1599f, p1y, 0.5f)
    }

    @Test
    fun testControlStatePacketStructure() {
        // Cả 2 đều BẬT: flags = 0x01 | 0x02 = 3
        val pktBoth = ControlPacketWriter.createControlStatePacket(controlEnabled = true, accessibilityConnected = true)
        assertEquals(6, pktBoth.size)
        val bufBoth = ByteBuffer.wrap(pktBoth).order(ByteOrder.BIG_ENDIAN)
        assertEquals(1, bufBoth.int) // payload len
        assertEquals(ControlCommand.TYPE_CONTROL_STATE, bufBoth.get())
        assertEquals(3.toByte(), bufBoth.get()) // flags

        // Chỉ enabled: flags = 1
        val pktEnabledOnly = ControlPacketWriter.createControlStatePacket(controlEnabled = true, accessibilityConnected = false)
        val bufEnabledOnly = ByteBuffer.wrap(pktEnabledOnly).order(ByteOrder.BIG_ENDIAN)
        bufEnabledOnly.int
        bufEnabledOnly.get()
        assertEquals(1.toByte(), bufEnabledOnly.get())

        // Cả 2 đều TẮT: flags = 0
        val pktOff = ControlPacketWriter.createControlStatePacket(controlEnabled = false, accessibilityConnected = false)
        val bufOff = ByteBuffer.wrap(pktOff).order(ByteOrder.BIG_ENDIAN)
        bufOff.int
        bufOff.get()
        assertEquals(0.toByte(), bufOff.get())
    }
}
