package de.plainplayer.app

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

sealed interface ScanResult {
    data class Success(val snapshot: LibrarySnapshot, val skippedFolders: Int, val untaggedFiles: Int) : ScanResult
    data class Failure(val message: String) : ScanResult
}

/**
 * Walks the selected SAF tree. Incremental: files whose size and modification time match the
 * previous snapshot are taken from the cache; only new or changed files are opened.
 */
class LibraryScanner(private val context: Context, private val coverStore: CoverStore) {

    private class Listed(val documentId: String, val key: String, val name: String, val lastModified: Long, val size: Long)
    private class ReadResult(val track: LocalTrack, val tagsReadable: Boolean, val picture: ByteArray?, val pictureRequested: Boolean)

    /**
     * @param onProgress number of files done so far; called from background threads.
     * @param checkpoint receives each batch of newly read tracks and the covers found so far; called on
     *   the scanning thread. Persisting them lets an interrupted scan resume where it stopped.
     */
    suspend fun scan(
        tree: Uri,
        previous: LibrarySnapshot,
        onProgress: (Int) -> Unit,
        checkpoint: (tracks: List<LocalTrack>, covers: Map<String, String?>) -> Unit,
    ): ScanResult = withContext(Dispatchers.IO) {
        val rootId = try {
            DocumentsContract.getTreeDocumentId(tree)
        } catch (e: Exception) {
            return@withContext ScanResult.Failure("The saved music folder is invalid. Please choose it again.")
        }

        // 1. List the tree (cheap: directory queries only).
        val audio = ArrayList<Listed>()
        val folderImages = HashMap<String, Pair<Int, String>>() // folder -> (rank, documentId)
        val skippedPrefixes = ArrayList<String>()
        val usedKeys = HashSet<String>()
        val visited = HashSet<String>()
        val pending = ArrayDeque<Pair<String, String>>() // documentId, relative prefix ("" or "a/b/")
        pending.add(rootId to "")
        while (pending.isNotEmpty()) {
            ensureActive()
            val (dirId, prefix) = pending.removeFirst()
            if (!visited.add(dirId)) continue
            val listed = try {
                listChildren(tree, dirId) { id, name, mime, lastModified, size ->
                    when {
                        mime == DocumentsContract.Document.MIME_TYPE_DIR -> pending.add(id to "$prefix$name/")
                        isAudio(name, mime) -> {
                            var key = prefix + name
                            if (!usedKeys.add(key)) key = "$key#$id".also { usedKeys.add(it) }
                            audio += Listed(id, key, name, lastModified, size)
                        }
                        else -> {
                            val rank = coverRank(name)
                            if (rank >= 0) {
                                val folder = prefix.removeSuffix("/")
                                val existing = folderImages[folder]
                                if (existing == null || rank < existing.first) folderImages[folder] = rank to id
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Cannot list $prefix", e)
                false
            }
            if (!listed) {
                if (prefix.isEmpty()) {
                    return@withContext ScanResult.Failure(
                        "The music folder can't be read. The storage may be removed or access was revoked – choose the folder again."
                    )
                }
                skippedPrefixes += prefix
            }
        }

        // 2. Read metadata of new/changed files only – several files in parallel, in batches.
        //    After each batch the new tracks are saved (checkpoint), so an interrupted scan resumes.
        val previousByKey = previous.tracks.associateBy { it.key }
        val coverPaths = ConcurrentHashMap<String, String>() // album key -> cover file (found ones only)
        previous.covers.forEach { (album, path) -> if (path != null && File(path).exists()) coverPaths[album] = path }
        val coverClaimed = ConcurrentHashMap.newKeySet<String>() // one embedded-picture read per album
        val triedEmbedded = ConcurrentHashMap.newKeySet<String>()
        val untagged = AtomicInteger()
        val result = ArrayList<LocalTrack>(audio.size)
        val toRead = ArrayList<Pair<Listed, Uri>>()
        audio.forEach { file ->
            val uri = DocumentsContract.buildDocumentUriUsingTree(tree, file.documentId)
            val cached = previousByKey[file.key]
            val unchanged = cached != null && cached.size == file.size && cached.lastModified == file.lastModified &&
                (file.size > 0 || file.lastModified > 0)
            if (cached != null && unchanged) {
                result.add(cached.copy(documentId = file.documentId, uri = uri))
            } else {
                toRead.add(file to uri)
            }
        }
        val processed = AtomicInteger(result.size)
        onProgress(processed.get())

        if (toRead.isNotEmpty()) {
            val pool = Executors.newFixedThreadPool(READ_THREADS)
            val readers = pool.asCoroutineDispatcher()
            try {
                for (batch in toRead.chunked(CHECKPOINT_EVERY)) {
                    ensureActive()
                    val read = coroutineScope {
                        batch.map { (file, uri) ->
                            async(readers) {
                                ensureActive()
                                readAndStoreCover(file, uri, coverPaths, coverClaimed, triedEmbedded, untagged).also {
                                    val n = processed.incrementAndGet()
                                    if (n % 20 == 0) onProgress(n)
                                }
                            }
                        }.awaitAll()
                    }
                    result += read
                    runCatching { checkpoint(read, HashMap<String, String?>(coverPaths)) }
                        .onFailure { Log.w(TAG, "Checkpoint failed", it) }
                    onProgress(processed.get())
                }
            } finally {
                readers.close()
            }
        }

        // Folders that could not be listed keep their previously known tracks instead of vanishing.
        if (skippedPrefixes.isNotEmpty()) {
            val present = result.mapTo(HashSet()) { it.key }
            previous.tracks.forEach { t ->
                if (t.key !in present && skippedPrefixes.any { t.key.startsWith(it) }) result += t
            }
        }

        // 3. Covers for albums that still have none: embedded picture, then cover.jpg/folder.jpg/….
        val covers = HashMap<String, String?>()
        result.groupBy { it.albumKey }.forEach { (albumKey, albumTracks) ->
            ensureActive()
            var path: String? = coverPaths[albumKey]
            val checkedBefore = previous.covers.containsKey(albumKey) && previous.covers[albumKey] == null
            if (path == null && !checkedBefore) {
                for (t in albumTracks.take(3)) {
                    if (t.key in triedEmbedded) continue
                    val picture = readEmbeddedPicture(t.uri) ?: continue
                    path = coverStore.save(albumKey, picture)
                    if (path != null) break
                }
            }
            if (path == null) {
                folderImages[albumTracks.first().folder]?.let { (_, imageId) ->
                    readLimited(DocumentsContract.buildDocumentUriUsingTree(tree, imageId))?.let { path = coverStore.save(albumKey, it) }
                }
            }
            covers[albumKey] = path
        }
        coverStore.retainOnly(covers.values.filterNotNull())
        onProgress(audio.size)

        ScanResult.Success(LibrarySnapshot(result, covers), skippedPrefixes.size, untagged.get())
    }

    private inline fun listChildren(
        tree: Uri,
        parentId: String,
        onChild: (id: String, name: String, mime: String, lastModified: Long, size: Long) -> Unit
    ): Boolean {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val cursor = context.contentResolver.query(children, PROJECTION, null, null, null) ?: return false
        cursor.use { c ->
            val idCol = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val modifiedCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            val sizeCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            while (c.moveToNext()) {
                val id = c.getString(idCol) ?: continue
                val name = if (nameCol >= 0) c.getString(nameCol) ?: "" else ""
                val mime = if (mimeCol >= 0) c.getString(mimeCol) ?: "" else ""
                val modified = if (modifiedCol >= 0 && !c.isNull(modifiedCol)) c.getLong(modifiedCol) else 0L
                val size = if (sizeCol >= 0 && !c.isNull(sizeCol)) c.getLong(sizeCol) else 0L
                onChild(id, name, mime, modified, size)
            }
        }
        return true
    }

    /** Runs on a reader thread. Only the first changed track of an album reads its embedded picture. */
    private fun readAndStoreCover(
        file: Listed,
        uri: Uri,
        coverPaths: ConcurrentHashMap<String, String>,
        coverClaimed: MutableSet<String>,
        triedEmbedded: MutableSet<String>,
        untagged: AtomicInteger,
    ): LocalTrack {
        val read = readTrack(file, uri) { albumKey -> !coverPaths.containsKey(albumKey) && coverClaimed.add(albumKey) }
        if (!read.tagsReadable) untagged.incrementAndGet()
        if (read.pictureRequested) triedEmbedded.add(file.key)
        read.picture?.let { picture ->
            coverStore.save(read.track.albumKey, picture)?.let { coverPaths[read.track.albumKey] = it }
        }
        return read.track
    }

    private fun readTrack(file: Listed, uri: Uri, wantCover: (albumKey: String) -> Boolean): ReadResult {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            fun meta(key: Int) = retriever.extractMetadata(key)?.trim()?.takeIf { it.isNotEmpty() }
            val artist = meta(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: UNKNOWN_ARTIST
            val track = LocalTrack(
                key = file.key,
                documentId = file.documentId,
                uri = uri,
                title = meta(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: file.name.substringBeforeLast('.'),
                artist = artist,
                albumArtist = meta(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST) ?: artist,
                album = meta(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: folderName(file.key),
                durationMs = meta(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L,
                trackNumber = parseNumber(meta(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)),
                discNumber = parseNumber(meta(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)),
                lastModified = file.lastModified,
                size = file.size,
            )
            val requested = wantCover(track.albumKey)
            val picture = if (requested) retriever.embeddedPicture else null
            ReadResult(track, true, picture, requested)
        } catch (e: Exception) {
            // Broken or unusual tags: keep the file, ExoPlayer may still play it.
            ReadResult(fallbackTrack(file, uri), false, null, false)
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun fallbackTrack(file: Listed, uri: Uri) = LocalTrack(
        key = file.key, documentId = file.documentId, uri = uri,
        title = file.name.substringBeforeLast('.'), artist = UNKNOWN_ARTIST, albumArtist = UNKNOWN_ARTIST,
        album = folderName(file.key), durationMs = 0L, trackNumber = 0, discNumber = 0,
        lastModified = file.lastModified, size = file.size,
    )

    private fun readEmbeddedPicture(uri: Uri): ByteArray? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.embeddedPicture
        } catch (e: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun readLimited(uri: Uri, limit: Int = 25 * 1024 * 1024): ByteArray? = try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                total += n
                if (total > limit) return null
                out.write(buffer, 0, n)
            }
            out.toByteArray()
        }
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val TAG = "LibraryScanner"
        /** SAF reads are mostly waiting on the storage provider, so a few in parallel help a lot. */
        const val READ_THREADS = 4
        const val CHECKPOINT_EVERY = 250
        val PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        val AUDIO_MIME = setOf("audio/mpeg", "audio/mp3", "audio/flac", "audio/x-flac")
        val COVER_NAMES = listOf("cover", "folder", "front", "album", "albumart")
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")

        fun isAudio(name: String, mime: String): Boolean =
            name.endsWith(".mp3", ignoreCase = true) || name.endsWith(".flac", ignoreCase = true) || mime in AUDIO_MIME

        fun coverRank(name: String): Int {
            val lower = name.lowercase(Locale.ROOT)
            if (lower.substringAfterLast('.', "") !in IMAGE_EXTENSIONS) return -1
            return COVER_NAMES.indexOf(lower.substringBeforeLast('.'))
        }

        fun folderName(key: String): String =
            key.substringBeforeLast('/', "").substringAfterLast('/').ifEmpty { UNKNOWN_ALBUM }

        fun parseNumber(value: String?): Int =
            value?.substringBefore('/')?.trim()?.toIntOrNull()?.coerceAtLeast(0) ?: 0
    }
}
