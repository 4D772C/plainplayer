package de.plainplayer.app

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import java.io.File
import java.text.Normalizer
import java.util.Locale

const val UNKNOWN_ARTIST = "Unknown artist"
const val UNKNOWN_ALBUM = "Unknown album"

/** A local audio document inside the user-selected music folder. Audio files stay where they are. */
data class LocalTrack(
    /** Path relative to the selected music folder, e.g. "Artist/Album/01 Song.flac". Stable identity of the track. */
    val key: String,
    val documentId: String,
    val uri: Uri,
    val title: String,
    val artist: String,
    /** Album artist tag, falling back to the track artist. Used for album grouping. */
    val albumArtist: String,
    val album: String,
    val durationMs: Long,
    val trackNumber: Int,
    val discNumber: Int,
    val lastModified: Long,
    val size: Long,
) {
    /**
     * Album identity = album folder + album title. Artist tags are deliberately not part of it, so
     * "Band feat. Someone" stays on the same album as "Band", while two different albums that share a
     * title ("Greatest Hits") stay apart because they live in different folders.
     */
    val albumKey: String get() = albumKeyOf(albumFolder(folder), album)

    /** Relative folder of the file, "" for the music root. */
    val folder: String get() = key.substringBeforeLast('/', "")
}

/**
 * Player item for this track. The mediaId is the track key; artwork is a small cover file, so nothing
 * large crosses the session binder. Used by the UI and by the service when restoring a saved queue.
 */
fun LocalTrack.toMediaItem(coverPath: String?): MediaItem {
    val metadata = MediaMetadata.Builder()
        .setTitle(title)
        .setArtist(artist)
        .setAlbumArtist(albumArtist)
        .setAlbumTitle(album)
        .setTrackNumber(trackNumber.takeIf { it > 0 })
        .setDiscNumber(discNumber.takeIf { it > 0 })
        .setArtworkUri(coverPath?.let { Uri.fromFile(File(it)) })
        .setIsPlayable(true)
        .setIsBrowsable(false)
        .build()
    return MediaItem.Builder().setMediaId(key).setUri(uri).setMediaMetadata(metadata).build()
}

fun albumKeyOf(albumFolder: String, album: String): String =
    "${albumFolder.lowercase(Locale.ROOT)}::${album.trim().lowercase(Locale.ROOT)}"

private val DiscFolder = Regex("""^(cd|disc|disk)[\s._-]*\d+([\s._-].*)?$""", RegexOption.IGNORE_CASE)

/** "Artist/Album/CD2" → "Artist/Album": disc subfolders belong to their parent album. */
fun albumFolder(folder: String): String {
    val last = folder.substringAfterLast('/')
    return if (DiscFolder.matches(last)) folder.substringBeforeLast('/', "") else folder
}

private val Featuring = Regex("""\s*[(\[]?\s*\b(feat\.?|ft\.?|featuring)\s.*$""", RegexOption.IGNORE_CASE)

/** "Band feat. Someone" → "Band". */
fun primaryArtist(artist: String): String = artist.replace(Featuring, "").trim().ifEmpty { artist }

/**
 * Artist shown for an album: the album artist tag if all tracks agree, otherwise the main artist
 * without "feat." credits if that is the same for all tracks, otherwise "Various artists".
 */
fun albumDisplayArtist(tracks: List<LocalTrack>): String {
    tracks.map { it.albumArtist.trim() }.distinctBy { it.lowercase(Locale.ROOT) }.singleOrNull()
        ?.let { return primaryArtist(it) }
    tracks.map { primaryArtist(it.albumArtist) }.distinctBy { it.lowercase(Locale.ROOT) }.singleOrNull()
        ?.let { return it }
    return "Various artists"
}

/** Disc number, then track number (untagged tracks last), then title. */
val AlbumTrackOrder: Comparator<LocalTrack> =
    compareBy<LocalTrack> { it.discNumber.coerceAtLeast(1) }
        .thenBy { if (it.trackNumber > 0) it.trackNumber else Int.MAX_VALUE }
        .thenBy { it.title.lowercase(Locale.ROOT) }

data class MusicAlbum(
    val key: String,
    val title: String,
    val artist: String,
    /** Already sorted with [AlbumTrackOrder]. */
    val tracks: List<LocalTrack>,
    val coverPath: String?,
    val multiDisc: Boolean,
)

fun trackCount(n: Int): String = if (n == 1) "1 track" else "$n tracks"

fun formatTime(ms: Long): String {
    val total = (ms.coerceAtLeast(0L) / 1000L).toInt()
    return if (total >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", total / 3600, (total / 60) % 60, total % 60)
    else String.format(Locale.ROOT, "%d:%02d", total / 60, total % 60)
}

// ---- Audiobooks -------------------------------------------------------------------------------

/** Folder names (anywhere in the path, any case) whose contents are treated as audiobooks. */
private val AudiobookFolderNames = setOf("audiobooks", "audiobook", "audio books", "hörbücher", "hörbuch", "hoerbuecher", "hoerbuch")

private fun normalizedFolderName(name: String): String =
    Normalizer.normalize(name.trim(), Normalizer.Form.NFC).lowercase(Locale.ROOT)

/**
 * Book key for a track inside an audiobook folder, null for music. One book = one folder below
 * "Audiobooks"/"Hörbücher" (any depth, e.g. "Hörbücher/Author/Title"); disc folders (CD1, CD2) belong
 * to their book. Files lying directly in the audiobook folder are books of their own.
 */
fun audiobookKeyOf(trackKey: String): String? {
    val folder = trackKey.substringBeforeLast('/', "")
    if (folder.isEmpty()) return null
    val segments = folder.split('/')
    val root = segments.indexOfFirst { normalizedFolderName(it) in AudiobookFolderNames }
    if (root < 0) return null
    val bookFolder = albumFolder(folder)
    return if (bookFolder.count { it == '/' } <= root) trackKey else bookFolder
}

data class Audiobook(
    val key: String,
    val title: String,
    val author: String,
    /** In file order (natural sort of the path: "2 …" before "10 …", CD1 before CD2). */
    val tracks: List<LocalTrack>,
    val coverPath: String?,
    val durationMs: Long,
)

/** Compares strings with numbers by value: "Part 2" < "Part 10". Case-insensitive. */
val NaturalOrder: Comparator<String> = Comparator { a, b -> compareNatural(a, b) }

private fun compareNatural(a: String, b: String): Int {
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        if (a[i].isDigit() && b[j].isDigit()) {
            val startA = i
            while (i < a.length && a[i].isDigit()) i++
            val startB = j
            while (j < b.length && b[j].isDigit()) j++
            val numA = a.substring(startA, i).trimStart('0')
            val numB = b.substring(startB, j).trimStart('0')
            if (numA.length != numB.length) return numA.length - numB.length
            val c = numA.compareTo(numB)
            if (c != 0) return c
        } else {
            val c = a[i].lowercaseChar().compareTo(b[j].lowercaseChar())
            if (c != 0) return c
            i++
            j++
        }
    }
    return (a.length - i) - (b.length - j)
}
