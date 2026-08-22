package com.deepseek.chat.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.File
import java.util.UUID

object Media {

    fun dir(ctx: Context): File = File(ctx.filesDir, "attachments").apply { mkdirs() }

    /** Copies a picked image/video into app storage. Returns the file. */
    fun copyIn(ctx: Context, uri: Uri, mime: String?): File {
        val ext = when {
            mime?.startsWith("video") == true -> ".mp4"
            mime?.contains("png") == true -> ".png"
            mime?.contains("webp") == true -> ".webp"
            else -> ".jpg"
        }
        val f = File(dir(ctx), UUID.randomUUID().toString() + ext)
        ctx.contentResolver.openInputStream(uri)?.use { ins ->
            f.outputStream().use { outs -> ins.copyTo(outs) }
        } ?: throw IllegalStateException("Cannot open picked file")
        return f
    }

    /** Extracts `count` frames from a video, downscaled to <=1024px JPEGs. */
    fun videoFrames(ctx: Context, videoFile: File, count: Int = 4): List<File> {
        val mmr = MediaMetadataRetriever()
        val out = mutableListOf<File>()
        try {
            mmr.setDataSource(videoFile.absolutePath)
            val durMs = mmr.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            if (durMs <= 0) return out
            val fractions = floatArrayOf(0.10f, 0.40f, 0.60f, 0.90f)
            for (i in 0 until minOf(count, fractions.size)) {
                val bmp = mmr.getFrameAtTime((durMs * 1000 * fractions[i]).toLong(),
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: continue
                val scaled = downscale(bmp)
                val f = File(dir(ctx), UUID.randomUUID().toString() + ".jpg")
                f.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                if (scaled !== bmp) scaled.recycle()
                out.add(f)
            }
        } catch (_: Exception) {
        } finally {
            try { mmr.release() } catch (_: Exception) {}
        }
        return out
    }

    /** Downscale an image file in place to max 1024px side (JPEG). */
    fun downscaleImage(file: File, maxSide: Int = 1024) {
        try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, opts)
            if (opts.outWidth <= maxSide && opts.outHeight <= maxSide &&
                file.extension.equals("jpg", true)) return
            val bmp = BitmapFactory.decodeFile(file.absolutePath) ?: return
            val scaled = downscale(bmp, maxSide)
            file.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            if (scaled !== bmp) scaled.recycle()
        } catch (_: Exception) {}
    }

    private fun downscale(bmp: Bitmap, maxSide: Int = 1024): Bitmap {
        val w = bmp.width; val h = bmp.height
        val m = maxOf(w, h)
        if (m <= maxSide) return bmp
        val scale = maxSide.toFloat() / m
        return Bitmap.createScaledBitmap(bmp,
            (w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1), true)
    }

    private const val TAG = "Media"
}
