package de.plainplayer.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * One downscaled JPEG per album in app-private storage. Embedded pictures can be several MB;
 * only a ~512 px copy is kept, so neither memory nor the database grows with cover size.
 */
class CoverStore(context: Context) {
    private val dir = File(context.filesDir, "covers")

    /** Decodes [encoded] (JPEG/PNG/WebP), downscales it and saves it. Returns the file path or null. */
    fun save(albumKey: String, encoded: ByteArray): String? {
        return try {
            val bitmap = decodeScaled(encoded, MAX_EDGE_PX) ?: return null
            dir.mkdirs()
            val target = File(dir, fileName(albumKey))
            val tmp = File(dir, target.name + ".tmp")
            FileOutputStream(tmp).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            bitmap.recycle()
            if (tmp.renameTo(target)) target.absolutePath else { tmp.delete(); null }
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    /** Deletes cover files that no album refers to any more. */
    fun retainOnly(paths: Collection<String>) {
        val keep = paths.map { File(it).name }.toHashSet()
        dir.listFiles()?.forEach { if (it.name !in keep) it.delete() }
    }

    companion object {
        const val MAX_EDGE_PX = 512

        private fun fileName(albumKey: String): String {
            val digest = MessageDigest.getInstance("SHA-1").digest(albumKey.toByteArray())
            return digest.joinToString("") { "%02x".format(it) } + ".jpg"
        }

        private fun decodeScaled(bytes: ByteArray, maxEdge: Int): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxEdge) }
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
            val longest = maxOf(decoded.width, decoded.height)
            if (longest <= maxEdge) return decoded
            val scale = maxEdge.toFloat() / longest
            val scaled = Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * scale).toInt().coerceAtLeast(1),
                (decoded.height * scale).toInt().coerceAtLeast(1),
                true
            )
            if (scaled !== decoded) decoded.recycle()
            return scaled
        }

        internal fun sampleSize(width: Int, height: Int, maxEdge: Int): Int {
            var sample = 1
            while (maxOf(width, height) / (sample * 2) >= maxEdge) sample *= 2
            return sample
        }
    }
}

/** Decoded cover bitmaps for the UI, bounded by memory size. Decoding happens off the main thread. */
object CoverBitmaps {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun cached(path: String, maxPx: Int): Bitmap? = cache.get("$maxPx|$path")

    /** Blocking; call from a background dispatcher. */
    fun load(path: String, maxPx: Int): Bitmap? {
        val key = "$maxPx|$path"
        cache.get(key)?.let { return it }
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0) return null
            val options = BitmapFactory.Options().apply {
                inSampleSize = CoverStore.sampleSize(bounds.outWidth, bounds.outHeight, maxPx)
            }
            BitmapFactory.decodeFile(path, options)?.also { cache.put(key, it) }
        } catch (e: Exception) {
            null
        }
    }

    fun clear() = cache.evictAll()
}
