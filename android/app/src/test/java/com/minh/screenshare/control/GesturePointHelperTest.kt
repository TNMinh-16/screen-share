package com.minh.screenshare.control

import org.junit.Assert.assertTrue
import org.junit.Test

class GesturePointHelperTest {

    private val screenWidth = 720
    private val screenHeight = 1280
    private val maxX = (screenWidth - 1).toFloat()
    private val maxY = (screenHeight - 1).toFloat()

    private fun assertWithinBounds(name: String, pt: Point2D) {
        assertTrue(
            "$name.x (${pt.x}) phải nằm trong [0, $maxX]",
            pt.x in 0f..maxX
        )
        assertTrue(
            "$name.y (${pt.y}) phải nằm trong [0, $maxY]",
            pt.y in 0f..maxY
        )
    }

    @Test
    fun testScrollAtFourCornersAndFourEdges() {
        // Danh sách kiểm tra: 4 góc và 4 mép màn hình cùng trường hợp tọa độ ngoài biên
        val testPositions = listOf(
            Pair(0f, 0f),                 // Góc trên - trái
            Pair(maxX, 0f),               // Góc trên - phải
            Pair(0f, maxY),               // Góc dưới - trái
            Pair(maxX, maxY),             // Góc dưới - phải
            Pair(maxX / 2f, 0f),          // Mép trên
            Pair(maxX / 2f, maxY),        // Mép dưới
            Pair(0f, maxY / 2f),          // Mép trái
            Pair(maxX, maxY / 2f),        // Mép phải
            Pair(-100f, -100f),           // Ngoài biên âm
            Pair(maxX + 200f, maxY + 200f)// Ngoài biên dương
        )

        val scrollDeltas = listOf(
            Pair(0.toShort(), (-120).toShort()), // Cuộn xuống -> ngón vuốt LÊN
            Pair(0.toShort(), 120.toShort()),    // Cuộn lên -> ngón vuốt XUỐNG
            Pair((-120).toShort(), 0.toShort()), // Cuộn phải -> ngón vuốt TRÁI
            Pair(120.toShort(), 0.toShort()),    // Cuộn trái -> ngón vuốt PHẢI
            Pair(0.toShort(), (-480).toShort()), // Cuộn rất mạnh
            Pair(0.toShort(), 480.toShort())     // Cuộn rất mạnh
        )

        for ((cx, cy) in testPositions) {
            for ((dx, dy) in scrollDeltas) {
                val (start, end) = GesturePointHelper.calculateScrollPoints(
                    cx, cy, screenWidth, screenHeight, dx, dy
                )
                assertWithinBounds("Scroll start at ($cx, $cy, dx=$dx, dy=$dy)", start)
                assertWithinBounds("Scroll end at ($cx, $cy, dx=$dx, dy=$dy)", end)

                // Kiểm tra quãng vuốt không bằng 0
                val dist = Math.hypot((end.x - start.x).toDouble(), (end.y - start.y).toDouble())
                assertTrue("Khoảng cách vuốt phải > 0 tại ($cx, $cy)", dist > 0)
            }
        }
    }

    @Test
    fun testPinchAtFourCornersAndFourEdges() {
        val testPositions = listOf(
            Pair(0f, 0f),                 // Góc trên - trái
            Pair(maxX, 0f),               // Góc trên - phải
            Pair(0f, maxY),               // Góc dưới - trái
            Pair(maxX, maxY),             // Góc dưới - phải
            Pair(maxX / 2f, 0f),          // Mép trên
            Pair(maxX / 2f, maxY),        // Mép dưới
            Pair(0f, maxY / 2f),          // Mép trái
            Pair(maxX, maxY / 2f),        // Mép phải
            Pair(-50f, -50f),             // Ngoài biên âm
            Pair(maxX + 100f, maxY + 100f)// Ngoài biên dương
        )

        val directions = listOf(1.toByte(), (-1).toByte())

        for ((cx, cy) in testPositions) {
            for (dir in directions) {
                val pinch = GesturePointHelper.calculatePinchPoints(
                    cx, cy, screenWidth, screenHeight, dir
                )

                assertWithinBounds("Pinch f1Start at ($cx, $cy, dir=$dir)", pinch.f1Start)
                assertWithinBounds("Pinch f1End at ($cx, $cy, dir=$dir)", pinch.f1End)
                assertWithinBounds("Pinch f2Start at ($cx, $cy, dir=$dir)", pinch.f2Start)
                assertWithinBounds("Pinch f2End at ($cx, $cy, dir=$dir)", pinch.f2End)

                // Kiểm tra 2 ngón không trùng điểm xuất phát
                assertTrue(
                    "Hai ngón tay phải cách nhau tại ($cx, $cy)",
                    pinch.f2Start.x > pinch.f1Start.x
                )
            }
        }
    }
}
