package com.teapotlab.rumahku.capture

import android.graphics.ImageFormat
import android.media.Image

/**
 * Converts an ARCore camera [Image] (YUV_420_888) into an NV21 byte array,
 * which Android's YuvImage can then compress to JPEG.
 *
 * Why this exists: cameras deliver frames as YUV planes, not RGB. The three
 * planes (Y = brightness, U/V = color) can have arbitrary row/pixel strides
 * and padding, so we can't just concatenate them — we walk each plane honoring
 * its strides. NV21 layout is: all Y bytes, then interleaved V,U,V,U…
 *
 * This is the well-known reference conversion; it handles the crop rect and
 * non-1 pixel strides that real devices use.
 */
object YuvUtil {

    fun toNv21(image: Image): ByteArray {
        require(image.format == ImageFormat.YUV_420_888) {
            "expected YUV_420_888, got ${image.format}"
        }

        val crop = image.cropRect
        val width = crop.width()
        val height = crop.height()
        val planes = image.planes

        val data = ByteArray(width * height * ImageFormat.getBitsPerPixel(ImageFormat.YUV_420_888) / 8)
        val rowData = ByteArray(planes[0].rowStride)

        var channelOffset = 0
        var outputStride = 1

        for (i in 0 until 3) {
            when (i) {
                0 -> { channelOffset = 0; outputStride = 1 }             // Y
                1 -> { channelOffset = width * height + 1; outputStride = 2 } // U → odd bytes
                2 -> { channelOffset = width * height; outputStride = 2 }     // V → even bytes
            }

            val buffer = planes[i].buffer
            val rowStride = planes[i].rowStride
            val pixelStride = planes[i].pixelStride

            // Chroma planes are half-resolution in both dimensions.
            val shift = if (i == 0) 0 else 1
            val w = width shr shift
            val h = height shr shift

            buffer.position(rowStride * (crop.top shr shift) + pixelStride * (crop.left shr shift))

            for (row in 0 until h) {
                val length: Int
                if (pixelStride == 1 && outputStride == 1) {
                    // Tightly packed row — bulk copy.
                    length = w
                    buffer.get(data, channelOffset, length)
                    channelOffset += length
                } else {
                    // Strided — copy the row then pick out every pixelStride-th byte.
                    length = (w - 1) * pixelStride + 1
                    buffer.get(rowData, 0, length)
                    for (col in 0 until w) {
                        data[channelOffset] = rowData[col * pixelStride]
                        channelOffset += outputStride
                    }
                }
                // Advance to the next row, skipping any trailing padding.
                if (row < h - 1) {
                    buffer.position(buffer.position() + rowStride - length)
                }
            }
        }
        return data
    }
}
