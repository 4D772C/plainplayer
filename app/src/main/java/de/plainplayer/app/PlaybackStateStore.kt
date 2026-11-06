package de.plainplayer.app

import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import org.json.JSONArray
import java.io.File
import java.util.concurrent.Executors

/**
 * Remembers what was playing.
 *
 * - Queue: only the track keys (paths relative to the music folder), written in the background and
 *   only when the queue changes. Titles, file links and covers are rebuilt from the library database
 *   on restore, so even a 20,000-track queue stays around 1 MB and always uses the current file links.
 * - Index, current track key, position, shuffle, repeat: SharedPreferences, written often.
 */
class PlaybackStateStore(context: Context) {

    data class Saved(
        val items: List<MediaItem>,
        val index: Int,
        val positionMs: Long,
        val shuffle: Boolean,
        val repeatMode: Int,
    )

    private val appContext = context.applicationContext
    private val queueFile = File(appContext.filesDir, "playback_queue_keys.json")
    /** Format of 1.2.0 (full metadata per item). Read once for migration, then deleted. */
    private val legacyQueueFile = File(appContext.filesDir, "playback_queue.json")
    private val prefs = appContext.getSharedPreferences("plainplayer_playback", Context.MODE_PRIVATE)
    private val io = Executors.newSingleThreadExecutor()

    fun saveQueue(trackKeys: List<String>) {
        io.execute {
            try {
                val json = JSONArray()
                trackKeys.forEach { json.put(it) }
                val tmp = File(queueFile.parentFile, queueFile.name + ".tmp")
                tmp.writeText(json.toString())
                if (!tmp.renameTo(queueFile)) tmp.delete()
                legacyQueueFile.delete()
            } catch (e: Exception) {
                Log.w(TAG, "Could not save queue", e)
            }
        }
    }

    fun savePosition(index: Int, currentKey: String?, positionMs: Long, shuffle: Boolean, repeatMode: Int) {
        prefs.edit()
            .putInt(KEY_INDEX, index)
            .putString(KEY_CURRENT, currentKey)
            .putLong(KEY_POSITION, positionMs.coerceAtLeast(0L))
            .putBoolean(KEY_SHUFFLE, shuffle)
            .putInt(KEY_REPEAT, repeatMode)
            .apply()
    }

    /**
     * Blocking – call from a background thread. Returns null if nothing was saved or none of the saved
     * tracks are in the library any more. Tracks that disappeared are left out.
     */
    fun load(): Saved? = try {
        val keys = readKeys()
        if (keys.isNullOrEmpty()) {
            null
        } else {
            val library = LibraryCache.get(appContext).load()
            val byKey = library.tracks.associateBy { it.key }

            // Keep the queue order; remember where each saved position ended up.
            val tracks = ArrayList<LocalTrack>(keys.size)
            val newIndexOf = IntArray(keys.size) { -1 }
            keys.forEachIndexed { i, key ->
                byKey[key]?.let { newIndexOf[i] = tracks.size; tracks.add(it) }
            }

            if (tracks.isEmpty()) {
                null
            } else {
                val savedIndex = prefs.getInt(KEY_INDEX, 0)
                val savedKey = prefs.getString(KEY_CURRENT, null)
                val savedPosition = prefs.getLong(KEY_POSITION, 0L)
                val exact = savedIndex in keys.indices && newIndexOf[savedIndex] >= 0 &&
                    (savedKey == null || keys[savedIndex] == savedKey)
                val (index, position) = when {
                    exact -> newIndexOf[savedIndex] to savedPosition
                    savedKey != null && tracks.any { it.key == savedKey } -> tracks.indexOfFirst { it.key == savedKey } to savedPosition
                    else -> {
                        // The current track is gone: continue with the next one that still exists.
                        val next = (savedIndex.coerceAtLeast(0) until keys.size).firstOrNull { newIndexOf[it] >= 0 }
                        (next?.let { newIndexOf[it] } ?: 0) to 0L
                    }
                }
                Saved(
                    items = tracks.map { it.toMediaItem(library.covers[it.albumKey]) },
                    index = index,
                    positionMs = position,
                    shuffle = prefs.getBoolean(KEY_SHUFFLE, false),
                    repeatMode = prefs.getInt(KEY_REPEAT, Player.REPEAT_MODE_OFF),
                )
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "Saved queue unreadable", e)
        null
    }

    /** Lets pending writes finish, then stops the writer thread. */
    fun close() = io.shutdown()

    private fun readKeys(): List<String>? {
        if (queueFile.exists()) {
            val json = JSONArray(queueFile.readText())
            return (0 until json.length()).map { json.getString(it) }
        }
        if (legacyQueueFile.exists()) {
            val json = JSONArray(legacyQueueFile.readText())
            return (0 until json.length()).mapNotNull { json.optJSONObject(it)?.optString("id")?.takeIf { id -> id.isNotEmpty() } }
        }
        return null
    }

    private companion object {
        const val TAG = "PlaybackStateStore"
        const val KEY_INDEX = "index"
        const val KEY_CURRENT = "current_key"
        const val KEY_POSITION = "position_ms"
        const val KEY_SHUFFLE = "shuffle"
        const val KEY_REPEAT = "repeat_mode"
    }
}
