package de.plainplayer.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/** Where a book was left. [trackKey] null + [finished] = listened to the end. */
data class BookProgress(val trackKey: String?, val positionMs: Long, val finished: Boolean, val updatedAt: Long)

/**
 * Per-book listening position and the audiobook playback speed. Written by [PlaybackService] while a
 * book plays, read by the UI. SharedPreferences: a few small entries, one per book.
 */
class BookProgressStore(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Speed used whenever an audiobook plays. Music always plays at 1×. */
    var speed: Float
        get() = prefs.getFloat(KEY_SPEED, 1f)
        set(value) { prefs.edit().putFloat(KEY_SPEED, value).apply() }

    fun save(bookKey: String, trackKey: String?, positionMs: Long, finished: Boolean) {
        val json = JSONObject()
            .put("track", trackKey ?: JSONObject.NULL)
            .put("pos", positionMs.coerceAtLeast(0L))
            .put("finished", finished)
            .put("updated", System.currentTimeMillis())
        prefs.edit().putString(PREFIX + bookKey, json.toString()).apply()
    }

    fun all(): Map<String, BookProgress> = prefs.all.mapNotNull { (key, value) ->
        if (!key.startsWith(PREFIX) || value !is String) return@mapNotNull null
        val progress = runCatching {
            val o = JSONObject(value)
            BookProgress(
                trackKey = if (o.isNull("track")) null else o.getString("track"),
                positionMs = o.optLong("pos", 0L),
                finished = o.optBoolean("finished", false),
                updatedAt = o.optLong("updated", 0L),
            )
        }.getOrNull() ?: return@mapNotNull null
        key.removePrefix(PREFIX) to progress
    }.toMap()

    /** The listener is held weakly by Android: keep a reference to it. */
    fun addListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.registerOnSharedPreferenceChangeListener(listener)

    fun removeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.unregisterOnSharedPreferenceChangeListener(listener)

    companion object {
        private const val PREFS = "plainplayer_books"
        private const val PREFIX = "book:"
        private const val KEY_SPEED = "speed"
        val SPEEDS = listOf(0.8f, 1f, 1.1f, 1.25f, 1.5f, 1.75f, 2f)
    }
}
