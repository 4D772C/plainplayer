package de.plainplayer.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class PlaylistNameTakenException(name: String) : Exception("A playlist called \"$name\" already exists")

/** One row of a playlist. [trackKey] is the track's path relative to the music folder (see [LocalTrack.key]). */
data class PlaylistEntry(val id: Long, val trackKey: String)

data class UserPlaylist(val id: Long, val name: String, val entries: List<PlaylistEntry>)

/**
 * Persistent store for user-created playlists. Tracks are referenced by their relative path, not by
 * SAF URI, so playlists survive re-selecting the music folder. The same song may appear more than once.
 * All methods block; call them from a background dispatcher.
 */
class PlaylistStore(context: Context) : SQLiteOpenHelper(context, "plainplayer_playlists.db", null, 2) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE playlists (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL UNIQUE COLLATE NOCASE)")
        createEntriesTable(db)
    }

    private fun createEntriesTable(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE playlist_entries (id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "playlist_id INTEGER NOT NULL REFERENCES playlists(id) ON DELETE CASCADE, " +
                "track_key TEXT NOT NULL, position INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX playlist_entries_by_playlist ON playlist_entries(playlist_id, position)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // v1 stored full SAF document URIs. They are copied as-is and rewritten to relative paths
            // by the view model once the library is loaded (see rewriteKeys).
            createEntriesTable(db)
            db.execSQL(
                "INSERT INTO playlist_entries (playlist_id, track_key, position) " +
                    "SELECT playlist_id, uri, position FROM playlist_tracks " +
                    "WHERE playlist_id IN (SELECT id FROM playlists) ORDER BY playlist_id, position"
            )
            db.execSQL("DROP TABLE playlist_tracks")
        }
    }

    fun all(): List<UserPlaylist> {
        val db = readableDatabase
        val entries = HashMap<Long, MutableList<PlaylistEntry>>()
        db.rawQuery("SELECT id, playlist_id, track_key FROM playlist_entries ORDER BY playlist_id, position, id", null).use { c ->
            while (c.moveToNext()) entries.getOrPut(c.getLong(1)) { mutableListOf() }.add(PlaylistEntry(c.getLong(0), c.getString(2)))
        }
        val result = ArrayList<UserPlaylist>()
        db.rawQuery("SELECT id, name FROM playlists ORDER BY name COLLATE NOCASE", null).use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                result += UserPlaylist(id, c.getString(1), entries[id].orEmpty())
            }
        }
        return result
    }

    /** @throws PlaylistNameTakenException if a playlist with this name (ignoring case) exists. */
    fun create(name: String, trackKeys: List<String>): Long {
        val clean = name.trim()
        val db = writableDatabase
        db.beginTransaction()
        try {
            val taken = db.rawQuery("SELECT 1 FROM playlists WHERE name = ? COLLATE NOCASE", arrayOf(clean)).use { it.moveToFirst() }
            if (taken) throw PlaylistNameTakenException(clean)
            val id = db.insertOrThrow("playlists", null, ContentValues().apply { put("name", clean) })
            appendInTransaction(db, id, trackKeys)
            db.setTransactionSuccessful()
            return id
        } finally {
            db.endTransaction()
        }
    }

    fun add(playlistId: Long, trackKeys: List<String>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            appendInTransaction(db, playlistId, trackKeys)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun appendInTransaction(db: SQLiteDatabase, playlistId: Long, trackKeys: List<String>) {
        if (trackKeys.isEmpty()) return
        var next = db.rawQuery(
            "SELECT COALESCE(MAX(position), -1) + 1 FROM playlist_entries WHERE playlist_id = ?",
            arrayOf(playlistId.toString())
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
        val insert = db.compileStatement("INSERT INTO playlist_entries (playlist_id, track_key, position) VALUES (?, ?, ?)")
        trackKeys.forEach { key ->
            insert.clearBindings()
            insert.bindLong(1, playlistId)
            insert.bindString(2, key)
            insert.bindLong(3, next++)
            insert.executeInsert()
        }
    }

    fun removeEntry(entryId: Long) {
        writableDatabase.delete("playlist_entries", "id = ?", arrayOf(entryId.toString()))
    }

    fun delete(playlistId: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("playlist_entries", "playlist_id = ?", arrayOf(playlistId.toString()))
            db.delete("playlists", "id = ?", arrayOf(playlistId.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Replaces outdated track keys (old SAF URIs, paths relative to a different root) with current ones. */
    fun rewriteKeys(mapping: Map<String, String>) {
        if (mapping.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            mapping.forEach { (old, new) ->
                db.update("playlist_entries", ContentValues().apply { put("track_key", new) }, "track_key = ?", arrayOf(old))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}
