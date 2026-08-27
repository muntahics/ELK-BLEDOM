package com.example.elkbledom.screen

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import androidx.annotation.RequiresApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

data class ScreenColor(val r: Int, val g: Int, val b: Int)

object ScreenAnalyzer {

    private const val CAPTURE_WIDTH = 160
    private const val FRAME_INTERVAL_MS = 50L   // 20 FPS cap

    @RequiresApi(29)
    fun stream(mediaProjection: MediaProjection, context: Context): Flow<ScreenColor> = callbackFlow {
        val metrics = context.resources.displayMetrics
        val captureHeight = (CAPTURE_WIDTH * metrics.heightPixels.toFloat() / metrics.widthPixels).toInt()

        val handlerThread = HandlerThread("elk-screen-capture").also { it.start() }
        val handler = Handler(handlerThread.looper)

        val reader = ImageReader.newInstance(CAPTURE_WIDTH, captureHeight, PixelFormat.RGBA_8888, 2)

        var lastFrameMs = 0L

        reader.setOnImageAvailableListener({ r ->
            val now = System.currentTimeMillis()
            if (now - lastFrameMs < FRAME_INTERVAL_MS) {
                r.acquireLatestImage()?.close()
                return@setOnImageAvailableListener
            }
            lastFrameMs = now

            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val plane = image.planes[0]
                val buffer = plane.buffer
                val rowStride = plane.rowStride
                val pixelStride = plane.pixelStride
                val w = image.width
                val h = image.height
                val limit = buffer.limit()

                var sumR = 0.0
                var sumG = 0.0
                var sumB = 0.0
                var sumW = 0.0

                // Sample every 4th pixel in both dimensions; weight by saturation×value
                // so colourful pixels dominate over grey/white backgrounds.
                var py = 0
                while (py < h) {
                    var px = 0
                    while (px < w) {
                        val off = py * rowStride + px * pixelStride
                        if (off + 3 >= limit) { px += 4; continue }
                        val rv = buffer[off].toInt() and 0xFF
                        val gv = buffer[off + 1].toInt() and 0xFF
                        val bv = buffer[off + 2].toInt() and 0xFF

                        val maxC = maxOf(rv, gv, bv)
                        val minC = minOf(rv, gv, bv)
                        val sat = if (maxC == 0) 0f else (maxC - minC) / maxC.toFloat()
                        val v = maxC / 255f
                        val weight = (sat * v + 0.1).toDouble()

                        sumR += rv * weight
                        sumG += gv * weight
                        sumB += bv * weight
                        sumW += weight
                        px += 4
                    }
                    py += 4
                }

                if (sumW > 0) {
                    trySend(ScreenColor(
                        r = (sumR / sumW).toInt().coerceIn(0, 255),
                        g = (sumG / sumW).toInt().coerceIn(0, 255),
                        b = (sumB / sumW).toInt().coerceIn(0, 255),
                    ))
                }
            } finally {
                image.close()
            }
        }, handler)

        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                channel.close()
            }
        }
        mediaProjection.registerCallback(callback, handler)

        val display = mediaProjection.createVirtualDisplay(
            "elk-screen",
            CAPTURE_WIDTH,
            captureHeight,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            handler,
        )

        awaitClose {
            mediaProjection.unregisterCallback(callback)
            display.release()
            reader.close()
            handlerThread.quit()
        }
    }
}