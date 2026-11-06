package de.plainplayer.app

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class LibraryTab(val label: String) { Albums("Albums"), Songs("Songs"), Playlists("Playlists") }

data class UiMessage(val text: String, val actionLabel: String? = null, val action: (() -> Unit)? = null)

data class NowPlaying(val title: String, val artist: String, val album: String, val coverPath: String?)

/** Immutable, pre-computed view of the library. Built off the main thread. */
private class Library(
    val tracks: List<LocalTrack>,
    val albums: List<MusicAlbum>,
    val byKey: Map<String, LocalTrack>,
    val byDocumentId: Map<String, LocalTrack>,
    val covers: Map<String, String?>,
) {
    /** Maps an outdated playlist key (v1 SAF URI, or a path relative to another root) to a current key. */
    fun repairKey(old: String): String? {
        if (old.startsWith("content://")) {
            val documentId = runCatching { DocumentsContract.getDocumentId(Uri.parse(old)) }.getOrNull() ?: return null
            return byDocumentId[documentId]?.key
        }
        return tracks.filter { it.key.endsWith("/$old") || old.endsWith("/${it.key}") }.singleOrNull()?.key
    }

    companion object {
        val EMPTY = build(LibrarySnapshot.EMPTY)

        fun build(snapshot: LibrarySnapshot): Library {
            val tracks = snapshot.tracks.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
            val albums = tracks.groupBy { it.albumKey }.map { (key, albumTracks) ->
                val sorted = albumTracks.sortedWith(AlbumTrackOrder)
                val first = sorted.first()
                MusicAlbum(
                    key = key,
                    title = first.album,
                    artist = albumDisplayArtist(sorted),
                    tracks = sorted,
                    coverPath = snapshot.covers[key],
                    multiDisc = sorted.any { it.discNumber > 1 },
                )
            }.sortedWith(
                compareBy<MusicAlbum, String>(String.CASE_INSENSITIVE_ORDER) { it.artist }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }
            )
            return Library(
                tracks = tracks,
                albums = albums,
                byKey = tracks.associateBy { it.key },
                byDocumentId = tracks.associateBy { it.documentId },
                covers = snapshot.covers,
            )
        }
    }
}

/**
 * Holds library, playlists and the connection to [PlaybackService]. Survives configuration changes,
 * so rotating the device no longer cancels a scan or rebuilds the player queue.
 *
 * The player's own queue is the single source of truth for "now playing": each MediaItem carries the
 * track key as mediaId, and the UI reads the current item from the controller.
 */
class PlayerViewModel(application: Application) : AndroidViewModel(application) {
    private val app: Application get() = getApplication()
    private val prefs = application.getSharedPreferences("plainplayer", Context.MODE_PRIVATE)
    private val libraryCache = LibraryCache.get(application)
    private val coverStore = CoverStore(application)
    private val scanner = LibraryScanner(application, coverStore)
    private val playlistStore = PlaylistStore(application)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _messages = Channel<UiMessage>(Channel.BUFFERED)
    val messages: Flow<UiMessage> = _messages.receiveAsFlow()

    // ---- Library state -------------------------------------------------------------------------
    private var library = Library.EMPTY
    var tracks by mutableStateOf<List<LocalTrack>>(emptyList())
        private set
    var albums by mutableStateOf<List<MusicAlbum>>(emptyList())
        private set
    var playlists by mutableStateOf<List<UserPlaylist>>(emptyList())
        private set
    var libraryLoaded by mutableStateOf(false)
        private set
    var selectedFolder by mutableStateOf(prefs.getString(KEY_TREE, null))
        private set
    var scanning by mutableStateOf(false)
        private set
    var scanProgress by mutableIntStateOf(0)
        private set
    private var scanJob: Job? = null

    // ---- Navigation ----------------------------------------------------------------------------
    var libraryTab by mutableStateOf(LibraryTab.Albums)
    var selectedAlbumKey by mutableStateOf<String?>(null)
        private set
    var selectedPlaylistId by mutableStateOf<Long?>(null)
        private set
    val selectedAlbum: MusicAlbum? get() = selectedAlbumKey?.let { key -> albums.firstOrNull { it.key == key } }
    val selectedPlaylist: UserPlaylist? get() = selectedPlaylistId?.let { id -> playlists.firstOrNull { it.id == id } }

    fun openAlbum(key: String) { selectedPlaylistId = null; selectedAlbumKey = key }
    fun openPlaylist(id: Long) { selectedAlbumKey = null; selectedPlaylistId = id }
    fun closeDetail() { selectedAlbumKey = null; selectedPlaylistId = null }

    fun resolve(trackKey: String): LocalTrack? = library.byKey[trackKey]

    // ---- Player state --------------------------------------------------------------------------
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    var nowPlaying by mutableStateOf<NowPlaying?>(null)
        private set
    var currentKey by mutableStateOf<String?>(null)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    /** True when the main button should show "pause" (playing or about to play). */
    var showPause by mutableStateOf(false)
        private set
    var durationMs by mutableLongStateOf(0L)
        private set
    var shuffle by mutableStateOf(false)
        private set
    var repeatMode by mutableIntStateOf(Player.REPEAT_MODE_OFF)
        private set

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = readPlayerState()

        override fun onPlayerError(error: PlaybackException) {
            val title = controller?.currentMediaItem?.mediaMetadata?.title ?: "this track"
            post("Can't play “$title” (${error.errorCodeName}).")
        }
    }

    init {
        connectController()
        loadLibrary()
    }

    // ---- Library -------------------------------------------------------------------------------

    private fun loadLibrary() {
        viewModelScope.launch {
            val snapshot = withContext(Dispatchers.IO) { libraryCache.load() }
            applyLibrary(snapshot)
            reloadPlaylists(repair = true)
            libraryLoaded = true
            // Look for added, removed or changed files in the background. Unchanged files are not re-read.
            if (selectedFolder != null) refresh(quiet = snapshot.tracks.isNotEmpty())
        }
    }

    private suspend fun applyLibrary(snapshot: LibrarySnapshot) {
        val built = withContext(Dispatchers.Default) { Library.build(snapshot) }
        library = built
        tracks = built.tracks
        albums = built.albums
    }

    fun chooseFolder(uri: Uri) {
        val resolver = app.contentResolver
        val persisted = try {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            true
        } catch (e: SecurityException) {
            false
        }
        if (!persisted) post("Access to this folder could not be saved. You may need to choose it again after a restart.")
        // Only one music folder is used: give back grants for previously chosen folders.
        resolver.persistedUriPermissions.filter { it.uri != uri }.forEach {
            runCatching { resolver.releasePersistableUriPermission(it.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        prefs.edit().putString(KEY_TREE, uri.toString()).apply()
        selectedFolder = uri.toString()
        closeDetail()
        scanJob?.cancel()
        refresh(full = true)
    }

    /**
     * Rescans the selected folder. Incremental unless [full]. A folder that can't be read leaves the
     * existing library untouched. [quiet] suppresses the success message (used for the start-up check).
     */
    fun refresh(full: Boolean = false, quiet: Boolean = false) {
        val tree = selectedFolder ?: return
        if (scanJob?.isActive == true) return
        val job = viewModelScope.launch {
            scanning = true
            scanProgress = 0
            try {
                // The database (not the in-memory library) also holds checkpoints of an interrupted scan.
                val previous = if (full) LibrarySnapshot.EMPTY else withContext(Dispatchers.IO) { libraryCache.load() }
                val result = scanner.scan(
                    tree = Uri.parse(tree),
                    previous = previous,
                    onProgress = { n -> mainHandler.post { scanProgress = n } },
                    checkpoint = { batch, covers -> libraryCache.upsert(batch, covers) },
                )
                when (result) {
                    is ScanResult.Failure -> post(result.message)
                    is ScanResult.Success -> {
                        val saved = withContext(Dispatchers.IO) { runCatching { libraryCache.replace(result.snapshot) }.isSuccess }
                        applyLibrary(result.snapshot)
                        CoverBitmaps.clear()
                        reloadPlaylists(repair = true)
                        val count = result.snapshot.tracks.size
                        val problems = buildList {
                            if (result.skippedFolders > 0) add("${result.skippedFolders} folder(s) could not be read")
                            if (result.untaggedFiles > 0) add("${result.untaggedFiles} file(s) have unreadable tags")
                            if (!saved) add("the library could not be cached")
                        }
                        when {
                            problems.isNotEmpty() -> post("${trackCount(count)} indexed – ${problems.joinToString("; ")}.")
                            count == 0 -> post("No MP3 or FLAC files found in this folder.")
                            !quiet -> post("${trackCount(count)} indexed.")
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                post("Refreshing the library failed. Your previous library is unchanged.")
            } finally {
                if (scanJob === coroutineContext[Job]) scanning = false
            }
        }
        scanJob = job
    }

    private suspend fun reloadPlaylists(repair: Boolean = false) {
        val lib = library
        val loaded = withContext(Dispatchers.IO) {
            try {
                var all = playlistStore.all()
                if (repair && lib.tracks.isNotEmpty()) {
                    val fixes = all.asSequence()
                        .flatMap { it.entries.asSequence() }
                        .map { it.trackKey }
                        .distinct()
                        .filter { it !in lib.byKey }
                        .mapNotNull { old -> lib.repairKey(old)?.let { old to it } }
                        .toMap()
                    if (fixes.isNotEmpty()) {
                        playlistStore.rewriteKeys(fixes)
                        all = playlistStore.all()
                    }
                }
                all
            } catch (e: Exception) {
                null
            }
        }
        if (loaded != null) playlists = loaded else post("Playlists could not be loaded.")
    }

    // ---- Player --------------------------------------------------------------------------------

    private fun connectController() {
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val future = MediaController.Builder(app, token).buildAsync()
        controllerFuture = future
        future.addListener({
            if (future.isCancelled) return@addListener
            val connected = try {
                future.get()
            } catch (e: Exception) {
                post("Playback service could not be started.")
                return@addListener
            }
            controller = connected
            connected.addListener(playerListener)
            // Deliberately no queue changes here: whatever is playing keeps playing.
            readPlayerState()
        }, ContextCompat.getMainExecutor(app))
    }

    private fun readPlayerState() {
        val c = controller ?: return
        val item = c.currentMediaItem
        currentKey = item?.mediaId?.takeIf { it.isNotEmpty() }
        nowPlaying = item?.mediaMetadata?.let { m ->
            NowPlaying(
                title = m.title?.toString() ?: "Unknown title",
                artist = m.artist?.toString() ?: "",
                album = m.albumTitle?.toString() ?: "",
                coverPath = m.artworkUri?.takeIf { it.scheme == "file" }?.path,
            )
        }
        isPlaying = c.isPlaying
        showPause = c.playWhenReady && c.playbackState != Player.STATE_ENDED && c.playbackState != Player.STATE_IDLE
        durationMs = c.duration.coerceAtLeast(0L) // C.TIME_UNSET is negative
        shuffle = c.shuffleModeEnabled
        repeatMode = c.repeatMode
    }

    fun positionMs(): Long = controller?.currentPosition?.coerceAtLeast(0L) ?: 0L

    /** Plays [queue] from [startIndex]. The queue is only replaced when it actually differs. */
    private fun play(queue: List<LocalTrack>, startIndex: Int) {
        val c = controller ?: run { post("Player is not ready yet."); return }
        if (queue.isEmpty()) return
        val index = startIndex.coerceIn(0, queue.lastIndex)
        val sameQueue = c.mediaItemCount == queue.size &&
            queue.indices.all { c.getMediaItemAt(it).mediaId == queue[it].key }
        if (sameQueue) c.seekToDefaultPosition(index)
        else c.setMediaItems(queue.map { it.toMediaItem(library.covers[it.albumKey]) }, index, 0L)
        c.prepare()
        c.play()
    }

    fun playLibrary(index: Int) = play(tracks, index)

    fun playAlbum(album: MusicAlbum, start: LocalTrack? = null) =
        play(album.tracks, start?.let { s -> album.tracks.indexOfFirst { it.key == s.key } } ?: 0)

    fun playPlaylist(playlist: UserPlaylist, startEntryId: Long? = null) {
        val available = playlist.entries.mapNotNull { entry -> library.byKey[entry.trackKey]?.let { entry to it } }
        if (available.isEmpty()) {
            post("None of this playlist's tracks are in the current music folder.")
            return
        }
        val start = startEntryId?.let { id -> available.indexOfFirst { it.first.id == id } }?.takeIf { it >= 0 } ?: 0
        play(available.map { it.second }, start)
    }

    fun togglePlayPause() {
        val c = controller ?: return
        when {
            showPause -> c.pause()
            c.mediaItemCount == 0 -> if (tracks.isNotEmpty()) play(tracks, 0)
            else -> {
                if (c.playbackState == Player.STATE_ENDED) c.seekToDefaultPosition(0)
                if (c.playbackState == Player.STATE_IDLE) c.prepare()
                c.play()
            }
        }
    }

    /** Restarts the current track if more than a few seconds in, otherwise goes to the previous one. */
    fun previous() { controller?.seekToPrevious() }
    fun next() { controller?.seekToNext() }
    fun seekTo(ms: Long) { controller?.seekTo(ms) }
    fun toggleShuffle() { controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled } }
    fun cycleRepeat() {
        controller?.let {
            it.repeatMode = when (it.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
        }
    }

    // ---- Playlists -----------------------------------------------------------------------------

    /** [onResult] receives null on success or an error text to show in the dialog. */
    fun createPlaylist(name: String, trackKeys: List<String>, onResult: (String?) -> Unit) {
        val clean = name.trim()
        if (clean.isEmpty()) { onResult("Enter a name."); return }
        viewModelScope.launch {
            val error = withContext(Dispatchers.IO) {
                try {
                    playlistStore.create(clean, trackKeys)
                    null
                } catch (e: PlaylistNameTakenException) {
                    "A playlist called “$clean” already exists."
                } catch (e: Exception) {
                    "The playlist could not be saved."
                }
            }
            if (error == null) {
                reloadPlaylists()
                if (trackKeys.isNotEmpty()) post("Added ${trackCount(trackKeys.size)} to “$clean”.")
            }
            onResult(error)
        }
    }

    fun addToPlaylist(playlist: UserPlaylist, trackKeys: List<String>) = dbAction(
        success = "Added ${trackCount(trackKeys.size)} to “${playlist.name}”."
    ) { playlistStore.add(playlist.id, trackKeys) }

    fun removeEntry(entry: PlaylistEntry) = dbAction { playlistStore.removeEntry(entry.id) }

    fun deletePlaylist(playlist: UserPlaylist) {
        if (selectedPlaylistId == playlist.id) selectedPlaylistId = null
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { playlistStore.delete(playlist.id) }.isSuccess }
            reloadPlaylists()
            if (ok) _messages.trySend(UiMessage("Deleted “${playlist.name}”.", "UNDO") { restorePlaylist(playlist) })
            else post("The playlist could not be deleted.")
        }
    }

    private fun restorePlaylist(playlist: UserPlaylist) = dbAction {
        val keys = playlist.entries.map { it.trackKey }
        try {
            playlistStore.create(playlist.name, keys)
        } catch (e: PlaylistNameTakenException) {
            playlistStore.create("${playlist.name} (restored)", keys)
        }
    }

    private fun dbAction(success: String? = null, block: () -> Unit) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { block() }.isSuccess }
            reloadPlaylists()
            if (!ok) post("The playlist could not be saved.") else if (success != null) post(success)
        }
    }

    private fun post(text: String) {
        _messages.trySend(UiMessage(text))
    }

    override fun onCleared() {
        controller?.removeListener(playerListener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controller = null
        playlistStore.close()
        super.onCleared()
    }

    private companion object {
        const val KEY_TREE = "music_tree"
    }
}
