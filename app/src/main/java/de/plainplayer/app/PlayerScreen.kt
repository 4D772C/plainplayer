package de.plainplayer.app

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Replay30
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.Player
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import java.util.Locale

internal val Ink = Color(0xFF101110)
internal val Panel = Color(0xFF1A1D19)
internal val Muted = Color(0xFF9A9F94)
internal val Acid = Color(0xFFD7FF64)

@Composable
fun PlayerScreen(vm: PlayerViewModel, onPickFolder: () -> Unit) {
    val snackbarHost = remember { SnackbarHostState() }
    LaunchedEffect(vm) {
        // collectLatest: a new message replaces the one on screen instead of queueing behind it
        // (ten quick actions used to mean ten snackbars, one after the other).
        vm.messages.collectLatest { msg ->
            val result = snackbarHost.showSnackbar(
                message = msg.text,
                actionLabel = msg.actionLabel,
                duration = if (msg.actionLabel != null) SnackbarDuration.Long else SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) msg.action?.invoke()
        }
    }

    // System back closes an open album/playlist instead of leaving the app.
    BackHandler(enabled = vm.hasDetailOpen) { vm.closeDetail() }

    var tracksToAdd by remember { mutableStateOf<List<LocalTrack>>(emptyList()) }
    var createWithKeys by remember { mutableStateOf<List<String>?>(null) } // null = dialog hidden
    var playlistToDelete by remember { mutableStateOf<UserPlaylist?>(null) }
    var showSleepDialog by remember { mutableStateOf(false) }

    val album = vm.selectedAlbum
    val playlist = vm.selectedPlaylist
    val book = vm.selectedBook

    // Phones in landscape (or very short screens): player and library side by side, so the library
    // list keeps enough room. Short portrait screens get a smaller now-playing panel.
    val configuration = LocalConfiguration.current
    val landscape = configuration.screenWidthDp > configuration.screenHeightDp && configuration.screenHeightDp < 600
    val compact = configuration.screenHeightDp < 640

    Scaffold(containerColor = Ink, snackbarHost = { SnackbarHost(snackbarHost) }) { padding ->
        val library: @Composable ColumnScope.() -> Unit = {
            LibrarySection(
                vm = vm,
                album = album,
                playlist = playlist,
                book = book,
                onPickFolder = onPickFolder,
                onAdd = { tracksToAdd = it },
                onCreatePlaylist = { createWithKeys = emptyList() },
                onDeletePlaylist = { playlistToDelete = it },
            )
        }
        if (landscape) {
            Row(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                    PlayerSection(vm, onPickFolder, onSleep = { showSleepDialog = true }, compact = true)
                }
                Spacer(Modifier.width(20.dp))
                Column(Modifier.weight(1.3f).fillMaxHeight().padding(top = 12.dp)) { library() }
            }
        } else {
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
                PlayerSection(vm, onPickFolder, onSleep = { showSleepDialog = true }, compact = compact)
                library()
                if (!compact) {
                    Text("OFFLINE BY DESIGN  ·  NO ADS  ·  NO TRACKING", color = Muted.copy(alpha = .65f), fontSize = 9.sp, letterSpacing = 1.1.sp, modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 10.dp, top = 4.dp))
                }
            }
        }
    }

    if (tracksToAdd.isNotEmpty()) {
        AddToPlaylistDialog(
            vm = vm,
            tracks = tracksToAdd,
            onDismiss = { tracksToAdd = emptyList() },
            onNewPlaylist = { createWithKeys = tracksToAdd.map { it.key }; tracksToAdd = emptyList() },
        )
    }
    createWithKeys?.let { keys ->
        CreatePlaylistDialog(vm, keys, onDone = { created ->
            createWithKeys = null
            if (created && keys.isEmpty()) {
                vm.libraryTab = LibraryTab.Playlists
            }
        })
    }
    if (showSleepDialog) SleepTimerDialog(vm, onDismiss = { showSleepDialog = false })
    playlistToDelete?.let { pl ->
        AlertDialog(
            onDismissRequest = { playlistToDelete = null },
            title = { Text("Delete playlist?") },
            text = { Text("“${pl.name}” (${trackCount(pl.entries.size)}) will be deleted. Your music files are not affected.") },
            confirmButton = { TextButton(onClick = { vm.deletePlaylist(pl); playlistToDelete = null }) { Text("DELETE") } },
            dismissButton = { TextButton(onClick = { playlistToDelete = null }) { Text("CANCEL") } },
        )
    }
}

@Composable
private fun PlayerSection(vm: PlayerViewModel, onPickFolder: () -> Unit, onSleep: () -> Unit, compact: Boolean) {
    Header(vm, onPickFolder, onSleep, compact)
    NowPlayingPanel(vm.nowPlaying, compact, onOpen = { vm.openCurrent() })
    Spacer(Modifier.height(if (compact) 6.dp else 10.dp))
    PositionBar(vm)
    Controls(vm)
}

@Composable
private fun ColumnScope.LibrarySection(
    vm: PlayerViewModel,
    album: MusicAlbum?,
    playlist: UserPlaylist?,
    book: Audiobook?,
    onPickFolder: () -> Unit,
    onAdd: (List<LocalTrack>) -> Unit,
    onCreatePlaylist: () -> Unit,
    onDeletePlaylist: (UserPlaylist) -> Unit,
) {
    // Title row
    Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        when {
            album != null -> {
                BackButton("Back to albums") { vm.closeDetail() }
                Text(album.title, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(trackCount(album.tracks.size).uppercase(Locale.ROOT), color = Muted, fontSize = 10.sp)
            }
            playlist != null -> {
                BackButton("Back to playlists") { vm.closeDetail() }
                Text(playlist.name, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                IconButton(onClick = { onDeletePlaylist(playlist) }) { Icon(Icons.Default.Delete, contentDescription = "Delete playlist", tint = Muted) }
            }
            book != null -> {
                BackButton("Back to books") { vm.closeDetail() }
                Text(book.title, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(formatTime(book.durationMs), color = Muted, fontSize = 10.sp)
            }
            else -> {
                Text("LIBRARY", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.8.sp, modifier = Modifier.weight(1f))
                val albumCount = vm.albums.size
                Text(
                    "${trackCount(vm.tracks.size).uppercase(Locale.ROOT)} · $albumCount ${if (albumCount == 1) "ALBUM" else "ALBUMS"}",
                    color = Muted, fontSize = 9.sp
                )
            }
        }
    }

    val fill = Modifier.weight(1f)
    when {
        !vm.libraryLoaded -> Box(fill.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Acid) }
        album != null -> AlbumDetail(vm, album, onAdd = onAdd, modifier = fill)
        playlist != null -> PlaylistDetail(vm, playlist, modifier = fill)
        book != null -> BookDetail(vm, book, modifier = fill)
        else -> Column(fill) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 7.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                LibraryTab.entries.forEach { tab ->
                    val chosen = vm.libraryTab == tab
                    Text(
                        tab.label.uppercase(Locale.ROOT), color = if (chosen) Ink else Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                        modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(if (chosen) Acid else Panel)
                            .selectable(selected = chosen, role = Role.Tab, onClick = { vm.libraryTab = tab })
                            .padding(horizontal = 16.dp, vertical = 11.dp)
                    )
                }
            }
            val listModifier = Modifier.weight(1f)
            when {
                vm.libraryTab == LibraryTab.Playlists -> PlaylistList(vm, onCreate = onCreatePlaylist, modifier = listModifier)
                vm.libraryTab == LibraryTab.Books -> BookList(vm, listModifier)
                vm.tracks.isEmpty() -> EmptyLibrary(vm, onPickFolder, listModifier)
                vm.libraryTab == LibraryTab.Albums -> AlbumList(vm, listModifier)
                else -> SongList(vm, onAdd = onAdd, modifier = listModifier)
            }
        }
    }
}

@Composable
private fun Header(vm: PlayerViewModel, onPickFolder: () -> Unit, onSleep: () -> Unit, compact: Boolean) {
    val top = if (compact) 6.dp else 18.dp
    val bottom = if (vm.scanning) 6.dp else if (compact) 6.dp else 16.dp
    Row(Modifier.fillMaxWidth().padding(top = top, bottom = bottom), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Logo in the colour of the app name, no taller than the name's text size (12 sp, so it
                // also follows the system font scale), centred on that line.
                val logoHeight = with(LocalDensity.current) { 12.sp.toDp() }
                Icon(
                    painter = painterResource(R.drawable.ic_plainplayer_logo),
                    contentDescription = null, // decorative: the name follows right after it
                    tint = Acid,
                    modifier = Modifier.height(logoHeight).aspectRatio(13f / 12f),
                )
                Spacer(Modifier.width(7.dp))
                Text("PLAINPLAYER", color = Acid, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.5.sp)
            }
            Text("LOCAL AUDIO", color = Muted, fontSize = 10.sp, letterSpacing = 1.8.sp)
        }
        IconButton(onClick = { if (vm.selectedFolder == null) onPickFolder() else vm.refresh() }, enabled = !vm.scanning) {
            if (vm.scanning) CircularProgressIndicator(Modifier.size(20.dp), color = Acid, strokeWidth = 2.dp)
            else Icon(Icons.Default.Refresh, contentDescription = "Refresh library", tint = Acid)
        }
        val sleeping = vm.sleepAt > 0L || vm.sleepAtEndOfTrack
        if (sleeping) {
            Text(
                if (vm.sleepAtEndOfTrack) "END" else clockTime(vm.sleepAt),
                color = Acid, fontSize = 10.sp, modifier = Modifier.clickable(onClick = onSleep)
            )
        }
        IconButton(onClick = onSleep) {
            Icon(Icons.Default.Bedtime, contentDescription = if (sleeping) "Sleep timer on" else "Sleep timer", tint = if (sleeping) Acid else Muted)
        }
        IconButton(onClick = onPickFolder) { Icon(Icons.Default.FolderOpen, contentDescription = "Choose music folder", tint = Acid) }
    }
    if (vm.scanning) {
        Text(
            if (vm.scanProgress > 0) "Reading music folder… ${vm.scanProgress} files" else "Reading music folder…",
            color = Muted, fontSize = 10.sp
        )
        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 10.dp), color = Acid, trackColor = Panel)
    }
}

@Composable
private fun NowPlayingPanel(np: NowPlaying?, compact: Boolean, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(if (compact) 96.dp else 190.dp).clip(RoundedCornerShape(16.dp)).background(Panel)
            .clickable(enabled = np != null, onClickLabel = "Show album", onClick = onOpen)
            .padding(if (compact) 10.dp else 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(np?.coverPath, Modifier.size(if (compact) 76.dp else 145.dp), large = true)
        Spacer(Modifier.width(if (compact) 12.dp else 16.dp))
        Column(Modifier.weight(1f)) {
            Text(np?.title ?: "Nothing playing", color = Color.White, fontSize = if (compact) 15.sp else 18.sp, fontWeight = FontWeight.SemiBold, maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            Text(np?.artist ?: "Select a track from your library", color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Text(np?.album ?: "MP3  /  FLAC", color = Muted.copy(alpha = .7f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Only this composable updates with the playback position, and only while playing and visible. */
@Composable
private fun PositionBar(vm: PlayerViewModel) {
    var position by remember { mutableLongStateOf(vm.positionMs()) }
    var dragValue by remember { mutableStateOf<Float?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val playing = vm.isPlaying
    val current = vm.currentKey
    // Also re-read after every seek (±30 s, lock screen, slider) – otherwise a paused player shows a stale position.
    val seeks = vm.positionVersion
    LaunchedEffect(playing, current, seeks, lifecycle) {
        position = vm.positionMs()
        if (playing) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    position = vm.positionMs()
                    delay(500)
                }
            }
        }
    }
    val duration = vm.durationMs
    val shown = dragValue ?: position.coerceIn(0L, duration.coerceAtLeast(0L)).toFloat()
    Slider(
        value = shown,
        onValueChange = { dragValue = it },
        onValueChangeFinished = {
            dragValue?.let { vm.seekTo(it.toLong()); position = it.toLong() }
            dragValue = null
        },
        valueRange = 0f..duration.coerceAtLeast(1L).toFloat(),
        enabled = duration > 0,
        colors = SliderDefaults.colors(thumbColor = Acid, activeTrackColor = Acid, inactiveTrackColor = Panel),
        modifier = Modifier.height(27.dp),
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(formatTime(dragValue?.toLong() ?: position), color = Muted, fontSize = 10.sp)
        Text(formatTime(duration), color = Muted, fontSize = 10.sp)
    }
}

@Composable
private fun Controls(vm: PlayerViewModel) {
    if (vm.currentBookKey != null) {
        BookControls(vm)
        return
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { vm.toggleShuffle() }) {
            Icon(Icons.Default.Shuffle, contentDescription = if (vm.shuffle) "Shuffle on" else "Shuffle off", tint = if (vm.shuffle) Acid else Muted)
        }
        IconButton(onClick = { vm.previous() }, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Default.SkipPrevious, contentDescription = "Previous", tint = Color.White, modifier = Modifier.size(32.dp))
        }
        IconButton(onClick = { vm.togglePlayPause() }, modifier = Modifier.size(58.dp).clip(CircleShape).background(Acid)) {
            Icon(if (vm.showPause) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = if (vm.showPause) "Pause" else "Play", tint = Ink, modifier = Modifier.size(34.dp))
        }
        IconButton(onClick = { vm.next() }, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Default.SkipNext, contentDescription = "Next", tint = Color.White, modifier = Modifier.size(32.dp))
        }
        IconButton(onClick = { vm.cycleRepeat() }) {
            val mode = vm.repeatMode
            Icon(
                if (mode == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                contentDescription = when (mode) { Player.REPEAT_MODE_ONE -> "Repeat one"; Player.REPEAT_MODE_ALL -> "Repeat all"; else -> "Repeat off" },
                tint = if (mode != Player.REPEAT_MODE_OFF) Acid else Muted,
            )
        }
    }
}

@Composable
private fun BackButton(description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = description, tint = Acid) }
}

@Composable
private fun EmptyLibrary(vm: PlayerViewModel, onPickFolder: () -> Unit, modifier: Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val text = when {
                vm.scanning -> "Reading music folder…"
                vm.selectedFolder == null -> "Choose a music folder to get started."
                vm.books.isNotEmpty() -> "No music outside the audiobook folder. Your books are in the Books tab."
                else -> "No MP3 or FLAC files found in this folder."
            }
            Text(text, color = Muted, fontSize = 13.sp)
            if (!vm.scanning) TextButton(onClick = onPickFolder) { Text("CHOOSE FOLDER", color = Acid, letterSpacing = 1.sp) }
        }
    }
}

@Composable
private fun AlbumList(vm: PlayerViewModel, modifier: Modifier) {
    LazyColumn(modifier, contentPadding = PaddingValues(bottom = 14.dp)) {
        items(vm.albums, key = { it.key }) { album ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { vm.openAlbum(album.key) }.padding(vertical = 7.dp, horizontal = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(album.coverPath, Modifier.size(54.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(album.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(album.artist, color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text("${album.tracks.size}", color = Muted, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun SongList(vm: PlayerViewModel, onAdd: (List<LocalTrack>) -> Unit, modifier: Modifier) {
    LazyColumn(modifier, contentPadding = PaddingValues(bottom = 14.dp)) {
        itemsIndexed(vm.tracks, key = { _, track -> track.key }) { index, track ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    TrackRow(numberLabel(index + 1), track.title, track.artist, track.durationMs, track.key == vm.currentKey, vm.isPlaying) { vm.playLibrary(index) }
                }
                IconButton(onClick = { onAdd(listOf(track)) }) { Icon(Icons.Default.Add, contentDescription = "Add to playlist", tint = Acid) }
            }
        }
    }
}

@Composable
private fun AlbumDetail(vm: PlayerViewModel, album: MusicAlbum, onAdd: (List<LocalTrack>) -> Unit, modifier: Modifier) {
    LazyColumn(modifier, contentPadding = PaddingValues(bottom = 14.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(album.coverPath, Modifier.size(68.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(album.artist, color = Muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(formatTime(album.tracks.sumOf { it.durationMs }), color = Muted, fontSize = 11.sp)
                }
                TextButton(onClick = { vm.playAlbum(album) }) { Text("PLAY ALBUM", color = Acid, fontSize = 10.sp) }
                TextButton(onClick = { onAdd(album.tracks) }) {
                    Icon(Icons.Default.Add, contentDescription = null, tint = Acid)
                    Text("ADD", color = Acid, fontSize = 10.sp)
                }
            }
        }
        itemsIndexed(album.tracks, key = { _, track -> track.key }) { _, track ->
            val label = when {
                track.trackNumber <= 0 -> "–"
                album.multiDisc -> "${track.discNumber.coerceAtLeast(1)}.${numberLabel(track.trackNumber)}"
                else -> numberLabel(track.trackNumber)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    TrackRow(label, track.title, track.artist, track.durationMs, track.key == vm.currentKey, vm.isPlaying) { vm.playAlbum(album, track) }
                }
                IconButton(onClick = { onAdd(listOf(track)) }) { Icon(Icons.Default.Add, contentDescription = "Add to playlist", tint = Acid) }
            }
        }
    }
}

@Composable
private fun PlaylistList(vm: PlayerViewModel, onCreate: () -> Unit, modifier: Modifier) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onCreate) {
                Icon(Icons.Default.Add, contentDescription = null, tint = Acid)
                Spacer(Modifier.width(5.dp))
                Text("NEW PLAYLIST", color = Acid, fontSize = 10.sp)
            }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 14.dp)) {
            items(vm.playlists, key = { it.id }) { playlist ->
                val missing = playlist.entries.count { vm.resolve(it.trackKey) == null }
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { vm.openPlaylist(playlist.id) }.padding(horizontal = 7.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(Panel), contentAlignment = Alignment.Center) { Text("♫", color = Acid, fontSize = 23.sp) }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(playlist.name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            trackCount(playlist.entries.size) + if (missing > 0) " · $missing missing" else "",
                            color = Muted, fontSize = 11.sp
                        )
                    }
                    Text("›", color = Muted, fontSize = 22.sp)
                }
            }
            if (vm.playlists.isEmpty()) item {
                Text("No playlists yet. Create one, then add songs from the Songs or Albums tab.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(12.dp))
            }
        }
    }
}

@Composable
private fun PlaylistDetail(vm: PlayerViewModel, playlist: UserPlaylist, modifier: Modifier) {
    val rows = remember(playlist, vm.tracks) { playlist.entries.map { it to vm.resolve(it.trackKey) } }
    val available = rows.count { it.second != null }
    val missing = rows.size - available
    LazyColumn(modifier, contentPadding = PaddingValues(bottom = 14.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(trackCount(available) + if (missing > 0) " · $missing missing" else "", color = Muted, fontSize = 11.sp)
                    if (missing > 0) Text("Missing tracks are not in the current music folder. They reappear when the file is found again.", color = Muted.copy(alpha = .65f), fontSize = 9.sp)
                }
                TextButton(onClick = { vm.playPlaylist(playlist) }, enabled = available > 0) { Text("PLAY ALL", color = if (available > 0) Acid else Muted, fontSize = 10.sp) }
            }
        }
        itemsIndexed(rows, key = { _, row -> row.first.id }) { index, (entry, track) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    if (track != null) {
                        TrackRow(numberLabel(index + 1), track.title, track.artist, track.durationMs, track.key == vm.currentKey, vm.isPlaying) {
                            vm.playPlaylist(playlist, entry.id)
                        }
                    } else {
                        TrackRow(numberLabel(index + 1), entry.trackKey.substringAfterLast('/').substringBefore('#'), "Missing", null, isCurrent = false, playing = false, enabled = false, onClick = null)
                    }
                }
                IconButton(onClick = { vm.removeEntry(entry) }) { Icon(Icons.Default.Delete, contentDescription = "Remove from playlist", tint = Muted) }
            }
        }
    }
}

@Composable
private fun TrackRow(
    label: String,
    title: String,
    subtitle: String,
    durationMs: Long?,
    isCurrent: Boolean,
    playing: Boolean,
    enabled: Boolean = true,
    onClick: (() -> Unit)?,
) {
    val base = Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(if (isCurrent) Panel else Color.Transparent)
    val clickable = if (onClick != null && enabled) base.clickable(onClick = onClick) else base
    val titleColor = when { isCurrent -> Acid; enabled -> Color.White; else -> Muted.copy(alpha = .6f) }
    Row(clickable.padding(horizontal = 9.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (isCurrent && playing) "Ⅱ" else label, color = if (isCurrent) Acid else Muted, fontSize = 11.sp, maxLines = 1, modifier = Modifier.width(36.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = titleColor, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (durationMs != null) Text(formatTime(durationMs), color = Muted, fontSize = 10.sp)
    }
}

@Composable
private fun AddToPlaylistDialog(vm: PlayerViewModel, tracks: List<LocalTrack>, onDismiss: () -> Unit, onNewPlaylist: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (tracks.size > 1) "Add album to playlist" else "Add to playlist") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(if (tracks.size > 1) "${trackCount(tracks.size)} will be added in album order." else tracks.first().title, color = Muted, fontSize = 12.sp)
                if (vm.playlists.isEmpty()) Text("No playlists yet.", modifier = Modifier.padding(top = 8.dp))
                vm.playlists.forEach { pl ->
                    TextButton(onClick = { vm.addToPlaylist(pl, tracks.map { it.key }); onDismiss() }) {
                        Text("${pl.name} (${pl.entries.size})")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onNewPlaylist) { Text("NEW PLAYLIST") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("CANCEL") } },
    )
}

@Composable
private fun CreatePlaylistDialog(vm: PlayerViewModel, trackKeys: List<String>, onDone: (created: Boolean) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { onDone(false) },
        title = { Text("New playlist") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; error = null },
                    singleLine = true,
                    label = { Text("Playlist name") },
                    isError = error != null,
                    supportingText = { error?.let { Text(it) } },
                )
                if (trackKeys.isNotEmpty()) Text("${trackCount(trackKeys.size)} will be added.", color = Muted, fontSize = 12.sp)
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && !saving, onClick = {
                saving = true
                vm.createPlaylist(name, trackKeys) { err ->
                    saving = false
                    if (err == null) {
                        onDone(true)
                    } else {
                        error = err
                    }
                }
            }) { Text("CREATE") }
        },
        dismissButton = { TextButton(onClick = { onDone(false) }) { Text("CANCEL") } },
    )
}

/** Audiobook mode: speed, ±30 s, play/pause, next part. */
@Composable
private fun BookControls(vm: PlayerViewModel) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { vm.cycleSpeed() }, modifier = Modifier.width(64.dp)) {
            Text(speedLabel(vm.bookSpeed), color = if (vm.bookSpeed != 1f) Acid else Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        IconButton(onClick = { vm.skipBy(-30_000L) }, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Default.Replay30, contentDescription = "Back 30 seconds", tint = Color.White, modifier = Modifier.size(32.dp))
        }
        IconButton(onClick = { vm.togglePlayPause() }, modifier = Modifier.size(58.dp).clip(CircleShape).background(Acid)) {
            Icon(if (vm.showPause) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = if (vm.showPause) "Pause" else "Play", tint = Ink, modifier = Modifier.size(34.dp))
        }
        IconButton(onClick = { vm.skipBy(30_000L) }, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Default.Forward30, contentDescription = "Forward 30 seconds", tint = Color.White, modifier = Modifier.size(32.dp))
        }
        IconButton(onClick = { vm.next() }) {
            Icon(Icons.Default.SkipNext, contentDescription = "Next part", tint = Muted)
        }
    }
}

@Composable
private fun BookList(vm: PlayerViewModel, modifier: Modifier) {
    if (vm.books.isEmpty()) {
        Box(modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
            Text(
                "No audiobooks yet. Put them in a folder named “Audiobooks” or “Hörbücher” inside your music folder – one subfolder per book – and refresh.",
                color = Muted, fontSize = 12.sp
            )
        }
        return
    }
    LazyColumn(modifier, contentPadding = PaddingValues(bottom = 14.dp)) {
        items(vm.books, key = { it.key }) { book ->
            val playingThis = vm.currentBookKey == book.key
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (playingThis) Panel else Color.Transparent).clickable { vm.openBook(book.key) }.padding(vertical = 7.dp, horizontal = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(book.coverPath, Modifier.size(54.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(book.title, color = if (playingThis) Acid else Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (book.author.isNotEmpty()) Text(book.author, color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(vm.bookStatus(book), color = Muted.copy(alpha = .8f), fontSize = 10.sp, maxLines = 1)
                }
                IconButton(onClick = { vm.playBook(book) }) { Icon(Icons.Default.PlayArrow, contentDescription = "Continue", tint = Acid) }
            }
        }
    }
}

@Composable
private fun BookDetail(vm: PlayerViewModel, book: Audiobook, modifier: Modifier) {
    val progress = vm.bookProgress[book.key]
    val currentPart = progress?.takeIf { !it.finished }?.trackKey
    LazyColumn(modifier, contentPadding = PaddingValues(bottom = 14.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(book.coverPath, Modifier.size(68.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    if (book.author.isNotEmpty()) Text(book.author, color = Muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(vm.bookStatus(book), color = Muted, fontSize = 11.sp)
                }
                TextButton(onClick = { vm.playBook(book) }) {
                    Text(if (currentPart != null) "CONTINUE" else "START", color = Acid, fontSize = 10.sp)
                }
            }
        }
        itemsIndexed(book.tracks, key = { _, track -> track.key }) { index, track ->
            val label = if (track.key == currentPart && vm.currentKey != track.key) "▸" else numberLabel(index + 1)
            // Show the CD folder for books split into CD1/CD2…, nothing otherwise.
            val subfolder = if (track.folder != book.key) track.folder.substringAfterLast('/') else ""
            TrackRow(label, track.title, subfolder, track.durationMs, track.key == vm.currentKey, vm.isPlaying) {
                vm.playBook(book, track)
            }
        }
    }
}

@Composable
private fun SleepTimerDialog(vm: PlayerViewModel, onDismiss: () -> Unit) {
    val options = listOf(15, 30, 45, 60)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sleep timer") },
        text = {
            Column {
                Text(
                    when {
                        vm.sleepAtEndOfTrack -> "Pauses at the end of the current track."
                        vm.sleepAt > 0L -> "Pauses at ${clockTime(vm.sleepAt)}."
                        else -> "Pause playback after:"
                    },
                    color = Muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp)
                )
                options.forEach { minutes ->
                    TextButton(onClick = { vm.setSleepTimer(minutes); onDismiss() }) { Text("$minutes minutes") }
                }
                TextButton(onClick = { vm.setSleepTimer(SleepTimer.END_OF_TRACK); onDismiss() }) {
                    Text(if (vm.currentBookKey != null) "End of chapter" else "End of track")
                }
            }
        },
        confirmButton = {
            if (vm.sleepAt > 0L || vm.sleepAtEndOfTrack) {
                TextButton(onClick = { vm.setSleepTimer(0); onDismiss() }) { Text("TURN OFF") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("CLOSE") } },
    )
}

private fun clockTime(epochMs: Long): String =
    java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(epochMs))

private fun speedLabel(speed: Float): String =
    (if (speed % 1f == 0f) String.format(Locale.ROOT, "%.0f", speed) else String.format(Locale.ROOT, "%.2f", speed).trimEnd('0')) + "×"

/** Cover image from the app's cover cache, decoded off the main thread at thumbnail size. */
@Composable
private fun Artwork(path: String?, modifier: Modifier = Modifier, large: Boolean = false) {
    val maxPx = if (large) CoverStore.MAX_EDGE_PX else 192
    val bitmap: Bitmap? by produceState(path?.let { CoverBitmaps.cached(it, maxPx) }, path, maxPx) {
        value = path?.let { withContext(Dispatchers.IO) { CoverBitmaps.load(it, maxPx) } }
    }
    val image = remember(bitmap) { bitmap?.asImageBitmap() }
    Box(modifier.clip(RoundedCornerShape(if (large) 14.dp else 8.dp)).background(Color(0xFF292E25)), contentAlignment = Alignment.Center) {
        if (image != null) Image(image, contentDescription = "Album cover", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Text("P", color = Acid, fontSize = if (large) 54.sp else 24.sp, fontWeight = FontWeight.Light)
    }
}

private fun numberLabel(n: Int): String = String.format(Locale.ROOT, "%02d", n)
