package de.plainplayer.app

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class LibraryTab(val label: String) { Albums("Albums"), Songs("Songs"), Playlists("Playlists"), Books("Books") }

data class UiMessage(val text: String, val actionLabel: String? = null, val action: (() -> Unit)? = null)

data class NowPlaying(val title: String, val artist: String, val album: String, val coverPath: String?)

/** Immutable, pre-computed view of the library. Built off the main thread. */
private class Library(
    /** Music only (audiobook tracks are in [books]). */
    val tracks: List<LocalTrack>,
    val albums: List<MusicAlbum>,
    val books: List<Audiobook>,
    /** Everything, for playlists and key repair. */
    val allTracks: List<LocalTrack>,
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
        return matchMovedKey(old, byKey.keys)
    }

    companion object {
        val EMPTY = build(LibrarySnapshot.EMPTY)

        fun build(snapshot: LibrarySnapshot): Library {
            val (bookTracks, music) = snapshot.tracks.partition { audiobookKeyOf(it.key) != null }
            val tracks = music.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
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
            val books = bookTracks.groupBy { audiobookKeyOf(it.key)!! }.map { (key, parts) ->
                val ordered = parts.sortedWith(compareBy(NaturalOrder) { it.key })
                val folderName = key.substringAfterLast('/')
                val isSingleFile = parts.size == 1 && parts[0].key == key
                val tagged = albumDisplayArtist(ordered)
                Audiobook(
                    key = key,
                    // A loose file is its own book: use its name without extension.
                    title = if (isSingleFile) folderName.substringBeforeLast('.') else folderName,
                    author = when {
                        tagged != UNKNOWN_ARTIST && tagged != "Various artists" -> tagged
                        isSingleFile -> ""
                        else -> audiobookAuthorFolder(key) ?: ""
                    },
                    tracks = ordered,
                    coverPath = ordered.firstNotNullOfOrNull { snapshot.covers[it.albumKey] },
                    durationMs = ordered.sumOf { it.durationMs },
                )
            }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
            val all = snapshot.tracks
            return Library(
                tracks = tracks,
                albums = albums,
                books = books,
                allTracks = all,
                byKey = all.associateBy { it.key },
                byDocumentId = all.associateBy { it.documentId },
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
    var books by mutableStateOf<List<Audiobook>>(emptyList())
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
    var selectedBookKey by mutableStateOf<String?>(null)
        private set
    val selectedBook: Audiobook? get() = selectedBookKey?.let { key -> books.firstOrNull { it.key == key } }

    fun openAlbum(key: String) { closeDetail(); selectedAlbumKey = key }
    fun openPlaylist(id: Long) { closeDetail(); selectedPlaylistId = id }
    fun openBook(key: String) { closeDetail(); selectedBookKey = key }
    fun closeDetail() { selectedAlbumKey = null; selectedPlaylistId = null; selectedBookKey = null }
    val hasDetailOpen: Boolean get() = selectedAlbumKey != null || selectedPlaylistId != null || selectedBookKey != null

    fun resolve(trackKey: String): LocalTrack? = library.byKey[trackKey]

    /** Shows the album or audiobook of the current track in the library (tap on "now playing"). */
    fun openCurrent() {
        val key = currentKey ?: return
        val bookKey = audiobookKeyOf(key)
        if (bookKey != null) {
            if (books.any { it.key == bookKey }) {
                libraryTab = LibraryTab.Books
                openBook(bookKey)
            }
            return
        }
        val track = library.byKey[key] ?: return
        libraryTab = LibraryTab.Albums
        openAlbum(track.albumKey)
    }

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
    /** Increases with every seek, so the position display also updates while paused. */
    var positionVersion by mutableIntStateOf(0)
        private set
    private var lastErrorMessageAt = 0L
    private var lastReconnectAt = 0L
    private var cleared = false

    // ---- Audiobooks / sleep timer ---------------------------------------------------------------
    private val bookStore = BookProgressStore(application)
    var bookProgress by mutableStateOf<Map<String, BookProgress>>(emptyMap())
        private set
    var bookSpeed by mutableStateOf(bookStore.speed)
        private set
    /** Book of the current track, or null when music is playing. */
    val currentBookKey: String? get() = currentKey?.let { audiobookKeyOf(it) }
    /** Wall-clock time (ms) when the sleep timer pauses playback; 0 = no timed sleep. */
    var sleepAt by mutableLongStateOf(0L)
        private set
    var sleepAtEndOfTrack by mutableStateOf(false)
        private set
    // Held as a field: SharedPreferences keeps listeners only weakly.
    private val bookPrefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        bookProgress = bookStore.all()
        bookSpeed = bookStore.speed
    }

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            readPlayerState()
            if (events.contains(Player.EVENT_POSITION_DISCONTINUITY)) positionVersion++
        }

        override fun onPlayerError(error: PlaybackException) {
            // The service skips unplayable files; one message per burst is enough.
            val now = SystemClock.elapsedRealtime()
            if (now - lastErrorMessageAt > ERROR_MESSAGE_INTERVAL_MS) {
                val title = controller?.currentMediaItem?.mediaMetadata?.title ?: "this track"
                val hint = if (currentBookKey != null) "Is the storage with your books available?" else "Unplayable files are skipped."
                post("Can't play “$title” (${error.errorCodeName}). $hint")
            }
            lastErrorMessageAt = now
        }
    }

    init {
        bookProgress = bookStore.all()
        bookStore.addListener(bookPrefsListener)
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
        books = built.books
    }

    private var folderSwitchJob: Job? = null

    /**
     * Switches to a new music folder as one serialized operation:
     * 1. Access must be stored permanently – otherwise nothing changes (old folder, library and
     *    playback stay as they are).
     * 2. A previous switch and the running scan are cancelled and awaited.
     * 3. The index is cleared *before* the new folder is saved, so even if the app is killed in between,
     *    folder and index always belong together.
     * 4. The player queue still points at files of the old folder (same relative paths can exist in
     *    both), so it is stopped and cleared.
     * Selecting several folders quickly: only the last one wins.
     */
    fun chooseFolder(uri: Uri) {
        val resolver = app.contentResolver
        val persisted = try {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            true
        } catch (e: SecurityException) {
            false
        }
        if (!persisted) {
            post("Android did not allow permanent access to this folder. Your current music folder is unchanged.")
            return
        }
        val newFolder = uri.toString()
        pendingFolder = newFolder
        val previousSwitch = folderSwitchJob
        folderSwitchJob = viewModelScope.launch {
            previousSwitch?.cancelAndJoin()
            scanJob?.cancelAndJoin()
            if (pendingFolder != newFolder) return@launch // a newer selection took over
            withContext(Dispatchers.IO) { runCatching { libraryCache.replace(LibrarySnapshot.EMPTY) } }
            if (pendingFolder != newFolder) return@launch
            prefs.edit().putString(KEY_TREE, newFolder).apply()
            selectedFolder = newFolder
            // Only one music folder is used: give back the grants of all others.
            resolver.persistedUriPermissions.filter { it.uri != uri }.forEach {
                runCatching { resolver.releasePersistableUriPermission(it.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            }
            closeDetail()
            controller?.let { it.stop(); it.clearMediaItems() }
            applyLibrary(LibrarySnapshot.EMPTY)
            refresh(full = true)
        }
    }

    /** Folder of the most recent [chooseFolder] call; used to let only the last selection win. */
    private var pendingFolder: String? = null

    /**
     * Rescans the selected folder. Incremental unless [full]. A folder that can't be read leaves the
     * existing library untouched. [quiet] suppresses the success message (used for the start-up check).
     */
    fun refresh(full: Boolean = false, quiet: Boolean = false) {
        val tree = selectedFolder ?: return
        if (scanJob?.isActive == true) return
        val job = viewModelScope.launch {
            val scanScope = this
            scanning = true
            scanProgress = 0
            // First scan of a folder: show what has been read so far instead of an empty library until
            // the very end. The tracks are usable (playable) right away.
            val showPartial = library.allTracks.isEmpty()
            val partialTracks = ArrayList<LocalTrack>()
            var batches = 0
            var partialJob: Job? = null
            try {
                // The database (not the in-memory library) also holds checkpoints of an interrupted scan.
                val previous = if (full) LibrarySnapshot.EMPTY else withContext(Dispatchers.IO) { libraryCache.load() }
                val result = scanner.scan(
                    tree = Uri.parse(tree),
                    previous = previous,
                    onProgress = { n -> mainHandler.post { scanProgress = n } },
                    checkpoint = { batch, covers ->
                        libraryCache.upsert(batch, covers)
                        if (showPartial) {
                            partialTracks.addAll(batch)
                            batches++
                            if (batches == 1 || batches % PARTIAL_EVERY_BATCHES == 0) {
                                val soFar = LibrarySnapshot(ArrayList(partialTracks), covers)
                                partialJob?.cancel()
                                partialJob = scanScope.launch { applyLibrary(soFar) }
                            }
                        }
                    },
                )
                // A partial update still in flight must not overwrite the final result.
                partialJob?.cancelAndJoin()
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
                            // Files with unreadable tags are retried on every scan; only mention them
                            // when the user asked for the scan, not on every app start.
                            if (result.untaggedFiles > 0 && !quiet) add("${result.untaggedFiles} file(s) have unreadable tags")
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
                // Never repair against an empty library (e.g. during a full rescan): nothing would match.
                if (repair && lib.allTracks.isNotEmpty()) {
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
        val future = MediaController.Builder(app, token)
            .setListener(object : MediaController.Listener {
                override fun onExtrasChanged(controller: MediaController, extras: Bundle) = readSleepState(extras)

                /**
                 * The playback service was stopped or crashed. Without reconnecting, every button would
                 * silently do nothing until the app is restarted. Only for a controller that was really
                 * connected (not for a failed connection attempt), and not in a tight loop.
                 */
                override fun onDisconnected(controller: MediaController) {
                    if (cleared || this@PlayerViewModel.controller !== controller) return
                    this@PlayerViewModel.controller = null
                    isPlaying = false
                    showPause = false
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastReconnectAt > RECONNECT_MIN_INTERVAL_MS) {
                        lastReconnectAt = now
                        connectController()
                    }
                }
            })
            .buildAsync()
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
            // First start with this version: take over the current music shuffle/repeat as the saved choice.
            if (!prefs.contains(KEY_MUSIC_SHUFFLE) && connected.currentMediaItem?.mediaId?.let { audiobookKeyOf(it) } == null) {
                saveMusicModes(connected.shuffleModeEnabled, connected.repeatMode)
            }
            // Deliberately no queue changes here: whatever is playing keeps playing.
            readPlayerState()
            readSleepState(connected.sessionExtras)
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

    private fun readSleepState(extras: Bundle) {
        sleepAt = extras.getLong(SleepTimer.EXTRA_SLEEP_AT, 0L)
        sleepAtEndOfTrack = extras.getBoolean(SleepTimer.EXTRA_END_OF_TRACK, false)
    }

    fun positionMs(): Long = controller?.currentPosition?.coerceAtLeast(0L) ?: 0L

    /** Plays [queue] from [startIndex]. The queue is only replaced when it actually differs. */
    private fun play(queue: List<LocalTrack>, startIndex: Int, startPositionMs: Long = 0L) {
        val c = controller ?: run { playerNotReady(); return }
        if (queue.isEmpty()) return
        val index = startIndex.coerceIn(0, queue.lastIndex)
        // Music gets back the shuffle/repeat it had before a book switched them off.
        if (audiobookKeyOf(queue[0].key) == null) {
            c.shuffleModeEnabled = prefs.getBoolean(KEY_MUSIC_SHUFFLE, false)
            c.repeatMode = prefs.getInt(KEY_MUSIC_REPEAT, Player.REPEAT_MODE_OFF)
        }
        // Same paths are not enough: two music folders can contain identical relative paths, so the
        // file link must match too. (If the controller has no link for an item, the queue is rebuilt.)
        val sameQueue = c.mediaItemCount == queue.size &&
            queue.indices.all {
                val item = c.getMediaItemAt(it)
                item.mediaId == queue[it].key && item.localConfiguration?.uri == queue[it].uri
            }
        if (sameQueue) c.seekTo(index, startPositionMs)
        else c.setMediaItems(queue.map { it.toMediaItem(library.covers[it.albumKey]) }, index, startPositionMs)
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
        val c = controller ?: run { playerNotReady(); return }
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
    fun toggleShuffle() {
        val c = controller ?: return
        c.shuffleModeEnabled = !c.shuffleModeEnabled
        saveMusicModes(c.shuffleModeEnabled, c.repeatMode)
    }

    fun cycleRepeat() {
        val c = controller ?: return
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        saveMusicModes(c.shuffleModeEnabled, c.repeatMode)
    }

    /** Shuffle and repeat are only offered for music, so the last choice there is remembered for music. */
    private fun saveMusicModes(shuffle: Boolean, repeat: Int) {
        prefs.edit().putBoolean(KEY_MUSIC_SHUFFLE, shuffle).putInt(KEY_MUSIC_REPEAT, repeat).apply()
    }

    // ---- Audiobooks ----------------------------------------------------------------------------

    /**
     * Continues [book] where it was left (a few seconds earlier, for context), or plays it from
     * [startTrack]. A finished or new book starts at the beginning.
     */
    fun playBook(book: Audiobook, startTrack: LocalTrack? = null) {
        val c = controller ?: run { playerNotReady(); return }
        if (book.tracks.isEmpty()) return
        // Books are listened to in order.
        c.shuffleModeEnabled = false
        // No repeat for books: "repeat one" left over from music would loop a single chapter.
        c.repeatMode = Player.REPEAT_MODE_OFF
        if (startTrack == null && currentBookKey == book.key && c.playbackState != Player.STATE_ENDED) {
            // Already loaded (e.g. paused): just continue.
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            c.play()
            return
        }
        val progress = bookProgress[book.key]
        val (index, position) = when {
            startTrack != null -> book.tracks.indexOfFirst { it.key == startTrack.key }.coerceAtLeast(0) to 0L
            progress != null && !progress.finished && progress.trackKey != null -> {
                val i = book.tracks.indexOfFirst { it.key == progress.trackKey }
                if (i >= 0) i to (progress.positionMs - RESUME_REWIND_MS).coerceAtLeast(0L) else 0 to 0L
            }
            else -> 0 to 0L
        }
        // Where the listener was before tapping a part – a mis-tap in a long book must be reversible.
        val before: Pair<Int, Long>? = if (startTrack == null) null else when {
            currentBookKey == book.key ->
                book.tracks.indexOfFirst { it.key == currentKey }.takeIf { it >= 0 }?.let { it to c.currentPosition }
            progress != null && !progress.finished && progress.trackKey != null ->
                book.tracks.indexOfFirst { it.key == progress.trackKey }.takeIf { it >= 0 }?.let { it to progress.positionMs }
            else -> null
        }
        play(book.tracks, index, position)
        if (before != null && (before.first != index || before.second > UNDO_JUMP_MIN_MS)) {
            _messages.trySend(
                UiMessage("Jumped to part ${index + 1}. You were at part ${before.first + 1}, ${formatTime(before.second)}.", "UNDO") {
                    play(book.tracks, before.first, before.second)
                }
            )
        }
    }

    /** Jumps by [deltaMs] (e.g. ±30 s), continuing into the previous/next part when needed. */
    fun skipBy(deltaMs: Long) {
        val c = controller ?: return
        val target = c.currentPosition + deltaMs
        val duration = c.duration
        when {
            target < 0 && c.hasPreviousMediaItem() -> {
                val previous = c.previousMediaItemIndex
                val previousDuration = library.byKey[c.getMediaItemAt(previous).mediaId]?.durationMs ?: 0L
                c.seekTo(previous, (previousDuration + target).coerceAtLeast(0L))
            }
            duration > 0 && target >= duration && c.hasNextMediaItem() ->
                c.seekTo(c.nextMediaItemIndex, (target - duration).coerceAtLeast(0L))
            else -> c.seekTo(target.coerceIn(0L, if (duration > 0) duration else Long.MAX_VALUE))
        }
    }

    fun cycleSpeed() {
        val speeds = BookProgressStore.SPEEDS
        val next = speeds[(speeds.indexOfFirst { it >= bookSpeed - 0.001f } + 1).let { if (it in speeds.indices) it else 0 }]
        bookStore.speed = next
        bookSpeed = next
        if (currentBookKey != null) controller?.setPlaybackSpeed(next)
    }

    /** Remaining listening time and part number for the book list, from the saved progress. */
    fun bookStatus(book: Audiobook): String {
        val p = bookProgress[book.key]
        val index = p?.trackKey?.let { key -> book.tracks.indexOfFirst { it.key == key } } ?: -1
        return when {
            p?.finished == true -> "Finished"
            index < 0 -> if (book.tracks.size > 1) "${book.tracks.size} parts · ${formatTime(book.durationMs)}" else formatTime(book.durationMs)
            else -> {
                val left = book.tracks.drop(index).sumOf { it.durationMs } - p!!.positionMs
                "Part ${index + 1} of ${book.tracks.size} · ${formatTime(left.coerceAtLeast(0L))} left"
            }
        }
    }

    // ---- Sleep timer ---------------------------------------------------------------------------

    /** [minutes] > 0, 0 = off, [SleepTimer.END_OF_TRACK] = at the end of the current track/chapter. */
    fun setSleepTimer(minutes: Int) {
        val c = controller ?: return
        c.sendCustomCommand(
            SessionCommand(SleepTimer.ACTION, Bundle.EMPTY),
            Bundle().apply { putInt(SleepTimer.EXTRA_MINUTES, minutes) },
        )
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

    fun removeEntry(entry: PlaylistEntry) {
        viewModelScope.launch {
            val removed = withContext(Dispatchers.IO) { runCatching { playlistStore.removeEntry(entry.id) }.getOrNull() }
            reloadPlaylists()
            if (removed != null) {
                val name = library.byKey[removed.trackKey]?.title ?: removed.trackKey.substringAfterLast('/')
                _messages.trySend(UiMessage("Removed “$name”.", "UNDO") { dbAction { playlistStore.restoreEntry(removed) } })
            }
        }
    }

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

    /** No connection to the playback service (yet, or any more): say so and try to connect again. */
    private fun playerNotReady() {
        post("Player is not ready yet. Please try again in a moment.")
        if (controllerFuture?.isDone != false) {
            lastReconnectAt = SystemClock.elapsedRealtime()
            connectController()
        }
    }

    private fun post(text: String) {
        _messages.trySend(UiMessage(text))
    }

    override fun onCleared() {
        cleared = true
        bookStore.removeListener(bookPrefsListener)
        controller?.removeListener(playerListener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controller = null
        playlistStore.close()
        super.onCleared()
    }

    private companion object {
        const val KEY_TREE = "music_tree"
        /** When continuing a book, start a little earlier to pick up the thread. */
        const val RESUME_REWIND_MS = 5_000L
        /** Tapping the part you are in restarts it; offer a way back if you were further in than this. */
        const val UNDO_JUMP_MIN_MS = 10_000L
        const val KEY_MUSIC_SHUFFLE = "music_shuffle"
        const val KEY_MUSIC_REPEAT = "music_repeat"
        const val ERROR_MESSAGE_INTERVAL_MS = 10_000L
        /** During a first scan the visible library is refreshed every this many checkpoints (250 files each). */
        const val PARTIAL_EVERY_BATCHES = 4
        const val RECONNECT_MIN_INTERVAL_MS = 5_000L
    }
}
