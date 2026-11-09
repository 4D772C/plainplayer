# PlainPlayer – Changes

## Versioning

`MAJOR.MINOR.PATCH`, starting from 0.x while the app is still taking shape:
- **New feature** → raise MINOR, reset PATCH (0.2.0 → 0.3.0)
- **Bug fix only** → raise PATCH (0.2.0 → 0.2.1)
- **1.0.0** when the feature set is considered complete and stable

`versionCode` (in `app/build.gradle.kts`) is separate and must go up by 1 with every build you install, or Android refuses the update. It never goes down, even though the version name was reset to 0.2.0.

## 0.2.4 – 2026-11-09

- **Logo in the header:** the PlainPlayer figure (13 × 12 pixel grid, `res/drawable/ic_plainplayer_logo.xml`) in front of the app name, in the same lime colour, as tall as the two text lines. Vector, so it stays sharp on every screen.

## 0.2.3 – 2026-11-08

Bug fix and usability release from a full review of 0.2.2.

**Fixed**
- **Position display didn't move after a seek while paused** (±30 s in a book, lock-screen seek, slider). The display now refreshes after every seek.
- **Leaving a book could lose up to 5 s of its position.** When you switch from a book to music, another book or a cleared queue, the exact position is saved at that moment.
- **Music shuffle/repeat stayed off after listening to a book.** Starting a book switches them off; starting music now restores your last music setting.
- **Landscape on phones left almost no room for the library.** Player and library are now side by side in landscape; short screens get a smaller now-playing panel and no footer.
- **Repeated error messages** when several files in a row can't be played: one message per burst (the files are still skipped).
- **Playlist repair was skipped when the folder only contained audiobooks.**
- **Misleading empty state:** with only audiobooks, Albums/Songs said "No MP3 or FLAC files found"; it now points to the Books tab.

**Known, not changed**
- Lock screen / notification show previous/next chapter for books, not ±30 s.
- No search or fast-scroll for very long song lists.
- The library is re-checked on every app start (background, but costs battery on big SD-card libraries).
- Replacing an existing `cover.jpg` is only picked up after choosing the folder again.

## 0.2.2 – 2026-11-08

Bug fix release, based on a second external review of 0.2.1 (BUG-08 … BUG-11).

**Fixed**
- **BUG-08 – Queue kept files of the previous music folder.** Two folders can contain identical relative paths. The "is this the same queue?" check now compares the file link as well as the path, so the queue is rebuilt with the new files. In addition, changing the folder stops and clears the player.
- **BUG-09 – Failed folder permission still changed the library.** If Android refuses permanent access, the folder change is rejected: folder, library, playlists and playback stay as they were.
- **BUG-10 – Quick successive folder selections could race.** Folder switching is now one serialized operation: a newer selection cancels the older one, the running scan is awaited, and only the last selection is applied. The index is cleared *before* the new folder is saved, so folder and index always match, even if the app is killed in between.
- **BUG-11 – Books split into "Part 1"/"Part 2" folders became separate books.** Part folders are now recognised: `CD1`, `Disc 2`, `Part 3`, `Teil 4`, `Tape 1`, `Kassette 2`, `Seite A1` or a plain number (`01`). `Band 1`, `Band 2` stay separate books (volumes of a series). A `cover.jpg` in the book folder above the parts is found.
- Single-file books no longer take an author name from their folder.

**Added**
- **Regression tests** (`app/src/test/…/LibraryRulesTest.kt`, run with `./gradlew test`): album/disc folders, featuring, book detection incl. part folders and decomposed umlauts, author folder, natural sort, cover downscaling. They run on the PC, no phone needed.

**Unchanged on purpose**
- **Book progress is shared between music folders** when the book has the same path in both. That's the same book, so continuing where you left off is the intended behaviour.
- **Replacing an existing `cover.jpg`** is still not detected by a normal refresh; choosing the folder again picks it up.

## 0.2.1 – 2026-11-08

Bug fix release, based on an external review of 0.2.0 (BUG-01 … BUG-07).

**Fixed**
- **BUG-02 – Repeat One looped an audiobook chapter.** Starting a book now always turns repeat off (before, only "repeat all" was switched off).
- **BUG-03 – Folder access could be lost.** If Android refuses to store access to a newly chosen folder, the access to the previous folder is no longer given up.
- **BUG-04 – Old folder data after an interrupted rescan.** Choosing a folder now empties the library index first, so checkpoints of an interrupted scan only ever contain the new folder.
- **BUG-07 – Covers of multi-disc albums.** `cover.jpg` in the album folder is now found for tracks in `CD1`/`CD2` subfolders (also for audiobooks).

**Hardened**
- **BUG-01 – Changing folders during a scan.** Not reproducible: a cancelled coroutine job reports `isActive = false` immediately, so the new scan did start. The old scan is now awaited (`cancelAndJoin`) before the new one begins anyway, so it can't touch the new index while it winds down.
- **BUG-05 – Audiobook author from folders.** The old check gave correct results but was hard to follow. Replaced by `audiobookAuthorFolder()`, which reads the folder between the audiobook folder and the book directly.

**Not a bug**
- **BUG-06 – cover.jpg added later.** The "checked before" flag only skips re-reading *embedded* pictures; the folder-image lookup runs on every refresh, so a newly added `cover.jpg` is picked up. Known limit: *replacing* an existing cover image with a different one is not detected until the folder is chosen again.

## 0.2.0 – 2026-11-08

First consolidated version after the initial prototype (0.1.0). Includes the internal builds 1.1–1.4, listed below with the newest first.

### Audiobooks & sleep timer
- **Audiobook folders:** everything inside a folder named `Audiobooks`, `Audiobook`, `Hörbücher`, `Hörbuch` (any case, anywhere in the path) is treated as audiobooks. One subfolder = one book (`Hörbücher/Title/` or `Hörbücher/Author/Title/`); `CD1`/`CD2` subfolders belong to their book; single files directly in the folder are books of their own. Books don't appear in Albums or Songs.
- **Books tab** with cover, author, "Part 3 of 12 · 4:12:30 left" / "Finished". Book view with CONTINUE/START and the part list; tapping a part plays from there.
- **Progress per book:** saved while listening (track + position), independent of the music queue. Continuing starts 5 s earlier. Parts are ordered by file path with natural number sorting (`2` before `10`, `CD1` before `CD2`).
- **Book controls** while a book plays: speed (0.8–2×, tap to cycle; remembered for all books, music always 1×), back/forward 30 s (crosses into the previous/next part), next part. Shuffle and repeat-all are switched off when a book starts.
- **Sleep timer** (moon icon in the header, for music too): 15/30/45/60 minutes or "end of track/chapter". Runs in the playback service, so it works with the app closed; the header shows the time it will pause.
- New files: `BookProgressStore.kt`. Changed: `LocalTrack.kt`, `PlaybackService.kt`, `PlayerViewModel.kt`, `PlayerScreen.kt`.


### Large libraries
- **Scan reads 4 files in parallel** (SAF reads mostly wait on the storage provider). Only the first changed track of an album reads its embedded cover.
- **Scan checkpoints:** every 250 newly read files are saved to the library database. If the app is closed or killed mid-scan, the next refresh only reads what is still missing. Incremental refreshes now compare against the database, so checkpoints are used within the same session too.
- **Saved queue is compact:** only track keys are stored (~1 MB for 20,000 tracks instead of several MB). Titles, file links and covers are rebuilt from the library database, so a restored queue also picks up new file links after the folder was re-selected. Tracks that disappeared are left out; if the current track is gone, playback continues with the next existing one.
- **Restore runs in the background:** the service no longer reads the queue on the main thread at start-up. A resume request (system controls, headset button) waits for the same background load.
- `LibraryCache` is now one shared instance per process (UI + playback service), avoiding "database is locked".
- The queue file of the previous build is migrated automatically.


### Resume where you left off
- New `PlaybackStateStore.kt`: saves the queue (as JSON in app storage, only when the queue changes, on a background thread) and track index, position, shuffle and repeat (SharedPreferences: on every track change, pause, seek, mode change, and every 5 s while playing).
- `PlaybackService` restores the last session on start, paused at the saved position. Finished queues restart from the first track.
- `onPlaybackResumption` + `MediaButtonReceiver` in the manifest: resume from the system media controls after a reboot (Android 13+) or with a headset/Bluetooth play button while the app is closed.
- Shuffle is restored as "on", but with a new random order.

### Code review fixes, restructuring, album grouping

#### Update: album grouping
- Albums are grouped by **folder + album title** instead of album artist + title. Tracks with "feat." in the artist no longer end up in a separate album.
- Disc subfolders (`CD1`, `cd 2`, `Disc 01`, `Disc 1 - …`) are treated as part of the parent folder's album.
- Album artist shown in the list: album-artist tag if consistent, else the main artist without "feat./ft./featuring", else "Various artists".
- No database change. On the first refresh after updating, covers are re-assigned to the new album keys once (embedded art is read again for the first tracks of each album).


Numbers refer to the review (PlainPlayer-Review.md).

#### New file structure

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

#### Critical
- **#1 Queue hijack** – the controller connection no longer touches the queue. A queue is only set when the user starts playback, and only replaced if it actually differs (`mediaId` comparison).
- **#2 Startup crash** – covers are no longer stored as BLOBs. The cache keeps a path to a ≤512 px JPEG; `load()` never throws (falls back to rescan). The v1 cache is dropped on upgrade.
- **#3 Refresh wiping the library** – an unreadable root returns a `Failure` and leaves the library untouched. Subfolders that can't be listed are counted, reported, and keep their previously known tracks.
- **#4 Memory** – no artwork in track objects. Covers are extracted once per album, downscaled immediately, and decoded for the UI off the main thread with a 24 MB LRU cache.

#### High
- **#5** Audio focus, pause on "becoming noisy", local wake lock.
- **#6** No library re-send per tap; artwork goes to Media3 as `artworkUri` (file), not bytes.
- **#7** Playlists reference tracks by path relative to the music folder. Old v1 URIs and paths relative to a different root (parent/sub folder chosen) are repaired automatically. Missing tracks are shown greyed out with a count instead of hidden.
- **#8** State moved to `PlayerViewModel`; scan runs in `viewModelScope`.
- **#9** System Back closes album/playlist views.

#### Medium
- **#10** Shuffle/repeat (and play/pause, now playing) are read from the player via `onEvents`.
- **#11** Snackbar for all messages; progress bar + file count while scanning; refresh disabled during a scan.
- **#12** Albums grouped by album artist; sorted by disc → track. Multi-disc albums show `1.01`, `2.01` …
- **#13** Unplayable files: message in the app, service skips to the next track (max. 5 in a row).
- **#14** Delete playlist asks for confirmation and offers UNDO.
- **#15** Notification tap opens the app (`setSessionActivity`, `singleTop`).
- **#16** All DB work on IO, multi-adds in one transaction, `all()` uses 2 queries instead of N+1.
- **#17** Files with unreadable tags are kept (title from file name, album from folder name).

#### Low
- Position polling only while playing and the screen is visible; only the slider recomposes.
- Album rows show real track numbers; "Previous" restarts the track when > 3 s in.
- Duplicate songs in a playlist allowed; playlist names unique ignoring case; foreign keys enabled.
- Old folder grants released when a new folder is chosen; failed `takePersistableUriPermission` is reported.
- VBR MP3 index seeking; unused extension renderer mode removed; `AutoMirrored` back icon; edge-to-edge.

#### Also added (cheap, related to the fixes)
- Incremental refresh by size + modification date, run quietly on every start.
- Folder cover fallback: `cover/folder/front/album/albumart.(jpg|jpeg|png|webp)`.
- Untagged files use the folder name as album instead of one big "Unknown album".
- Albums sorted by artist, then title.

#### Still open (ideas, not planned yet)
- M3U import/export, playlist rename/reorder
- Folder view, search, ReplayGain
- `MediaLibraryService` for Android Auto / Wear
- Unit tests

## 0.1.0

Initial prototype: SAF folder selection, MP3/FLAC scanning, albums/songs, playlists, background playback.
