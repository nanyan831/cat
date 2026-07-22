package com.example.catlifepet.floating

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.util.zip.InflaterInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PetAssetContractTest {
    @Test
    fun allPetPngsUseTransparent512CanvasAndSafeBounds() {
        val assets = assetDirectory().listFiles { file ->
            file.name.startsWith("cat_") && file.extension == "png"
        }.orEmpty().sortedBy(File::getName)

        assertTrue("Expected the complete pet asset set", assets.size >= 90)
        assets.forEach { file ->
            val image = decodeRgbaPng(file)
            assertEquals("Unexpected width: ${file.name}", 512, image.width)
            assertEquals("Unexpected height: ${file.name}", 512, image.height)
            val bounds = opaqueBounds(image)
            assertTrue("Artwork touches left edge: ${file.name}", bounds.left >= SAFE_MARGIN_PX)
            assertTrue("Artwork touches top edge: ${file.name}", bounds.top >= SAFE_MARGIN_PX)
            assertTrue("Artwork touches right edge: ${file.name}", bounds.right <= 512 - SAFE_MARGIN_PX)
            assertTrue("Artwork touches bottom edge: ${file.name}", bounds.bottom <= 512 - SAFE_MARGIN_PX)
        }
    }

    @Test
    fun framesWithinEachAnimationKeepTheirBottomAnchorStable() {
        ANIMATED_PREFIXES.forEach { prefix ->
            val bottoms = assetDirectory().listFiles { file ->
                file.name.matches(Regex("${prefix}_\\d{2}\\.png"))
            }.orEmpty().map { file -> opaqueBounds(decodeRgbaPng(file)).bottom }
            assertTrue("Missing animation frames for $prefix", bottoms.size >= 4)
            assertTrue(
                "Bottom anchor drifts in $prefix: $bottoms",
                bottoms.max() - bottoms.min() <= MAX_ANCHOR_DRIFT_PX
            )
        }
    }

    private fun assetDirectory() = File("src/main/res/drawable-nodpi")

    private fun decodeRgbaPng(file: File): RgbaImage {
        val input = DataInputStream(file.inputStream().buffered())
        val signature = ByteArray(8).also(input::readFully)
        require(signature.contentEquals(PNG_SIGNATURE)) { "Invalid PNG: ${file.name}" }
        var width = 0
        var height = 0
        val compressed = ByteArrayOutputStream()
        while (true) {
            val length = input.readInt()
            val type = ByteArray(4).also(input::readFully).decodeToString()
            val data = ByteArray(length).also(input::readFully)
            input.readInt()
            when (type) {
                "IHDR" -> DataInputStream(ByteArrayInputStream(data)).use { header ->
                    width = header.readInt()
                    height = header.readInt()
                    require(header.readUnsignedByte() == 8) { "Only 8-bit PNG is supported" }
                    require(header.readUnsignedByte() == 6) { "Only RGBA PNG is supported" }
                    header.skipBytes(2)
                    require(header.readUnsignedByte() == 0) { "Interlaced PNG is not supported" }
                }
                "IDAT" -> compressed.write(data)
                "IEND" -> break
            }
        }

        val scanlines = InflaterInputStream(ByteArrayInputStream(compressed.toByteArray())).readBytes()
        val stride = width * BYTES_PER_PIXEL
        require(scanlines.size == height * (stride + 1)) { "Unexpected PNG data length: ${file.name}" }
        val pixels = ByteArray(width * height * BYTES_PER_PIXEL)
        var sourceOffset = 0
        for (y in 0 until height) {
            val filter = scanlines[sourceOffset++].toInt() and 0xFF
            val rowOffset = y * stride
            for (x in 0 until stride) {
                val raw = scanlines[sourceOffset++].toInt() and 0xFF
                val left = if (x >= BYTES_PER_PIXEL) pixels[rowOffset + x - BYTES_PER_PIXEL].u8() else 0
                val up = if (y > 0) pixels[rowOffset + x - stride].u8() else 0
                val upperLeft = if (y > 0 && x >= BYTES_PER_PIXEL) {
                    pixels[rowOffset + x - stride - BYTES_PER_PIXEL].u8()
                } else {
                    0
                }
                val value = when (filter) {
                    0 -> raw
                    1 -> raw + left
                    2 -> raw + up
                    3 -> raw + (left + up) / 2
                    4 -> raw + paeth(left, up, upperLeft)
                    else -> error("Unsupported PNG filter $filter in ${file.name}")
                }
                pixels[rowOffset + x] = value.toByte()
            }
        }
        return RgbaImage(width, height, pixels)
    }

    private fun opaqueBounds(image: RgbaImage): Bounds {
        var left = image.width
        var top = image.height
        var right = -1
        var bottom = -1
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val alpha = image.pixels[(y * image.width + x) * BYTES_PER_PIXEL + 3].u8()
                if (alpha == 0) continue
                left = minOf(left, x)
                top = minOf(top, y)
                right = maxOf(right, x + 1)
                bottom = maxOf(bottom, y + 1)
            }
        }
        require(right >= 0) { "Empty pet asset" }
        return Bounds(left, top, right, bottom)
    }

    private fun paeth(left: Int, up: Int, upperLeft: Int): Int {
        val prediction = left + up - upperLeft
        val leftDistance = kotlin.math.abs(prediction - left)
        val upDistance = kotlin.math.abs(prediction - up)
        val upperLeftDistance = kotlin.math.abs(prediction - upperLeft)
        return when {
            leftDistance <= upDistance && leftDistance <= upperLeftDistance -> left
            upDistance <= upperLeftDistance -> up
            else -> upperLeft
        }
    }

    private fun Byte.u8(): Int = toInt() and 0xFF

    private data class RgbaImage(val width: Int, val height: Int, val pixels: ByteArray)
    private data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

    private companion object {
        const val BYTES_PER_PIXEL = 4
        const val SAFE_MARGIN_PX = 8
        const val MAX_ANCHOR_DRIFT_PX = 8
        val PNG_SIGNATURE = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
        val ANIMATED_PREFIXES = listOf(
            "cat_idle", "cat_happy", "cat_eating", "cat_drinking",
            "cat_sleeping", "cat_stretching", "cat_dragging", "cat_blinking",
            "cat_yawning", "cat_licking", "cat_curious", "cat_cuddle"
        )
    }
}
