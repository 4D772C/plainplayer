package de.plainplayer.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.util.Log

/** Indexed library plus one cover file path per album key (null = album checked, no cover found). */
data class LibrarySnapshot(val tracks: List<LocalTrack>, val covers: Map<String, String?>) {
    companion object {
        val EMPTY = LibrarySnapshot(emptyList(), emptyMap())
    }
}

/**
 * Small app-private index. Audio files remain in their original locations.
 * Covers are stored as small JPEG files (see [CoverStore]); the database only holds their paths,
 * so no row can exceed the 2 MB CursorWindow limit.
 */
class LibraryCache private constructor(context: Context) :
    SQLiteOpenHelper(context, "plainplayer-library.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE tracks (
                track_key TEXT PRIMARY KEY, document_id TEXT NOT NULL, uri TEXT NOT NULL,
                title TEXT NOT NULL, artist TEXT NOT NULL, album_artist TEXT NOT NULL, album TEXT NOT NULL,
                duration_ms INTEGER NOT NULL, track_number INTEGER NOT NULL, disc_number INTEGER NOT NULL,
                last_modified INTEGER NOT NULL, size INTEGER NOT NULL
            )"""
        )
        db.execSQL("CREATE TABLE albums (album_key TEXT PRIMARY KEY, cover_path TEXT)")
    }

    /** This is a cache: on any schema change it is dropped and rebuilt by the next scan. */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS tracks")
        db.execSQL("DROP TABLE IF EXISTS albums")
        onCreate(db)
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = onUpgrade(db, oldVersion, newVersion)

    /** Never throws: a broken cache simply results in an empty snapshot and a rescan. */
    fun load(): LibrarySnapshot = try {
        val db = readableDatabase
        val covers = HashMap<String, String?>()
        db.rawQuery("SELECT album_key, cover_path FROM albums", null).use { c ->
            while (c.moveToNext()) covers[c.getString(0)] = if (c.isNull(1)) null else c.getString(1)
        }
        val tracks = ArrayList<LocalTrack>()
        db.rawQuery(
            "SELECT track_key, document_id, uri, title, artist, album_artist, album, duration_ms, " +
                "track_number, disc_number, last_modified, size FROM tracks", null
        ).use { c ->
            while (c.moveToNext()) {
                tracks += LocalTrack(
                    key = c.getString(0),
                    documentId = c.getString(1),
                    uri = Uri.parse(c.getString(2)),
                    title = c.getString(3),
                    artist = c.getString(4),
                    albumArtist = c.getString(5),
                    album = c.getString(6),
                    durationMs = c.getLong(7),
                    trackNumber = c.getInt(8),
                    discNumber = c.getInt(9),
                    lastModified = c.getLong(10),
                    size = c.getLong(11),
                )
            }
        }
        LibrarySnapshot(tracks, covers)
    } catch (e: Exception) {
        Log.w(TAG, "Library cache unreadable, starting empty", e)
        LibrarySnapshot.EMPTY
    }

    /** Replaces the whole index with the result of a completed scan. */
    fun replace(snapshot: LibrarySnapshot) = inTransaction { db ->
        db.delete("tracks", null, null)
        db.delete("albums", null, null)
        insertAll(db, snapshot.tracks, snapshot.covers)
    }

    /**
     * Adds or updates rows without touching the rest. Used as a checkpoint while a long scan is running,
     * so an interrupted scan continues from here next time instead of starting over.
     */
    fun upsert(tracks: List<LocalTrack>, covers: Map<String, String?>) = inTransaction { db ->
        insertAll(db, tracks, covers)
    }

    private inline fun inTransaction(block: (SQLiteDatabase) -> Unit) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            block(db)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun insertAll(db: SQLiteDatabase, tracks: List<LocalTrack>, covers: Map<String, String?>) {
        val insertTrack = db.compileStatement(
            "INSERT OR REPLACE INTO tracks (track_key, document_id, uri, title, artist, album_artist, album, " +
                "duration_ms, track_number, disc_number, last_modified, size) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)"
        )
        tracks.forEach { t ->
            insertTrack.clearBindings()
            insertTrack.bindString(1, t.key)
            insertTrack.bindString(2, t.documentId)
            insertTrack.bindString(3, t.uri.toString())
            insertTrack.bindString(4, t.title)
            insertTrack.bindString(5, t.artist)
            insertTrack.bindString(6, t.albumArtist)
            insertTrack.bindString(7, t.album)
            insertTrack.bindLong(8, t.durationMs)
            insertTrack.bindLong(9, t.trackNumber.toLong())
            insertTrack.bindLong(10, t.discNumber.toLong())
            insertTrack.bindLong(11, t.lastModified)
            insertTrack.bindLong(12, t.size)
            insertTrack.executeInsert()
        }
        val insertAlbum = db.compileStatement("INSERT OR REPLACE INTO albums (album_key, cover_path) VALUES (?, ?)")
        covers.forEach { (key, path) ->
            insertAlbum.clearBindings()
            insertAlbum.bindString(1, key)
            if (path == null) insertAlbum.bindNull(2) else insertAlbum.bindString(2, path)
            insertAlbum.executeInsert()
        }
    }

    companion object {
        private const val TAG = "LibraryCache"

        @Volatile
        private var instance: LibraryCache? = null

        /**
         * One shared instance per process: the UI and the playback service (which rebuilds a saved queue
         * from it) read the same database, and a single connection avoids "database is locked" errors.
         * It is never closed; the process owns it.
         */
        fun get(context: Context): LibraryCache =
            instance ?: synchronized(this) {
                instance ?: LibraryCache(context.applicationContext).also { instance = it }
            }
    }
}
