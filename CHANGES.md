# PlainPlayer – Changes

## 1.3.0 – Large libraries
- **Scan reads 4 files in parallel** (SAF reads mostly wait on the storage provider). Only the first changed track of an album reads its embedded cover.
- **Scan checkpoints:** every 250 newly read files are saved to the library database. If the app is closed or killed mid-scan, the next refresh only reads what is still missing. Incremental refreshes now compare against the database, so checkpoints are used within the same session too.
- **Saved queue is compact:** only track keys are stored (~1 MB for 20,000 tracks instead of several MB). Titles, file links and covers are rebuilt from the library database, so a restored queue also picks up new file links after the folder was re-selected. Tracks that disappeared are left out; if the current track is gone, playback continues with the next existing one.
- **Restore runs in the background:** the service no longer reads the queue on the main thread at start-up. A resume request (system controls, headset button) waits for the same background load.
- `LibraryCache` is now one shared instance per process (UI + playback service), avoiding "database is locked".
- The 1.2.0 queue file is migrated automatically.


## 1.2.0 – Resume where you left off
- New `PlaybackStateStore.kt`: saves the queue (as JSON in app storage, only when the queue changes, on a background thread) and track index, position, shuffle and repeat (SharedPreferences: on every track change, pause, seek, mode change, and every 5 s while playing).
- `PlaybackService` restores the last session on start, paused at the saved position. Finished queues restart from the first track.
- `onPlaybackResumption` + `MediaButtonReceiver` in the manifest: resume from the system media controls after a reboot (Android 13+) or with a headset/Bluetooth play button while the app is closed.
- Shuffle is restored as "on", but with a new random order.

# PlainPlayer 1.1.0

## Update: album grouping
- Albums are grouped by **folder + album title** instead of album artist + title. Tracks with "feat." in the artist no longer end up in a separate album.
- Disc subfolders (`CD1`, `cd 2`, `Disc 01`, `Disc 1 - …`) are treated as part of the parent folder's album.
- Album artist shown in the list: album-artist tag if consistent, else the main artist without "feat./ft./featuring", else "Various artists".
- No database change. On the first refresh after updating, covers are re-assigned to the new album keys once (embedded art is read again for the first tracks of each album).


Numbers refer to the review (PlainPlayer-Review.md).

## New file structure

| File | Role |
|---|---|
| `LocalTrack.kt` | Track/album model (replaces `AudioTrack.kt`, which shadowed `android.media.AudioTrack`) |
| `LibraryScanner.kt` | Incremental SAF scan, metadata, covers |
| `CoverStore.kt` | Downscaled cover files + bounded bitmap cache for the UI |
| `LibraryCache.kt` | SQLite index (schema v2, no BLOBs) |
| `PlaylistStore.kt` | Playlists (schema v2, relative-path keys, migration from v1) |
| `PlayerViewModel.kt` | Library, playlists, scan job, MediaController – survives rotation |
| `PlayerScreen.kt` | Compose UI, split into small composables |
| `MainActivity.kt` | Thin host: theme, folder picker |
| `PlaybackService.kt` | Player configuration |

## Critical
- **#1 Queue hijack** – the controller connection no longer touches the queue. A queue is only set when the user starts playback, and only replaced if it actually differs (`mediaId` comparison).
- **#2 Startup crash** – covers are no longer stored as BLOBs. The cache keeps a path to a ≤512 px JPEG; `load()` never throws (falls back to rescan). The v1 cache is dropped on upgrade.
- **#3 Refresh wiping the library** – an unreadable root returns a `Failure` and leaves the library untouched. Subfolders that can't be listed are counted, reported, and keep their previously known tracks.
- **#4 Memory** – no artwork in track objects. Covers are extracted once per album, downscaled immediately, and decoded for the UI off the main thread with a 24 MB LRU cache.

## High
- **#5** Audio focus, pause on "becoming noisy", local wake lock.
- **#6** No library re-send per tap; artwork goes to Media3 as `artworkUri` (file), not bytes.
- **#7** Playlists reference tracks by path relative to the music folder. Old v1 URIs and paths relative to a different root (parent/sub folder chosen) are repaired automatically. Missing tracks are shown greyed out with a count instead of hidden.
- **#8** State moved to `PlayerViewModel`; scan runs in `viewModelScope`.
- **#9** System Back closes album/playlist views.

## Medium
- **#10** Shuffle/repeat (and play/pause, now playing) are read from the player via `onEvents`.
- **#11** Snackbar for all messages; progress bar + file count while scanning; refresh disabled during a scan.
- **#12** Albums grouped by album artist; sorted by disc → track. Multi-disc albums show `1.01`, `2.01` …
- **#13** Unplayable files: message in the app, service skips to the next track (max. 5 in a row).
- **#14** Delete playlist asks for confirmation and offers UNDO.
- **#15** Notification tap opens the app (`setSessionActivity`, `singleTop`).
- **#16** All DB work on IO, multi-adds in one transaction, `all()` uses 2 queries instead of N+1.
- **#17** Files with unreadable tags are kept (title from file name, album from folder name).

## Low
- Position polling only while playing and the screen is visible; only the slider recomposes.
- Album rows show real track numbers; "Previous" restarts the track when > 3 s in.
- Duplicate songs in a playlist allowed; playlist names unique ignoring case; foreign keys enabled.
- Old folder grants released when a new folder is chosen; failed `takePersistableUriPermission` is reported.
- VBR MP3 index seeking; unused extension renderer mode removed; `AutoMirrored` back icon; edge-to-edge.

## Also added (cheap, related to the fixes)
- Incremental refresh by size + modification date, run quietly on every start.
- Folder cover fallback: `cover/folder/front/album/albumart.(jpg|jpeg|png|webp)`.
- Untagged files use the folder name as album instead of one big "Unknown album".
- Albums sorted by artist, then title.

## Not done (concept items, open for a next round)
- Resume last queue after reboot (`onPlaybackResumption`)
- M3U import/export, playlist rename/reorder
- Folder view, search, ReplayGain, sleep timer
- `MediaLibraryService` for Android Auto / Wear
- Unit tests

## Not verified
The project could not be compiled in the review environment (no access to the Android SDK / Google Maven). The sources pass a Kotlin syntax check and were reviewed by hand against the Media3 1.5 / Compose BOM 2024.12 APIs. Please run `./gradlew assembleDebug` and report any compile errors.
