# PlainPlayer

A small, local-first Android music player for MP3 and FLAC. Kotlin + Jetpack Compose + AndroidX Media3.

## Features

- Select a music folder using Android's Storage Access Framework.
- Recursively indexes `.mp3` and `.flac` files and reads embedded title, artist, album artist, album, duration, track and disc number.
- Browse the library by album or as a flat song list. Albums are grouped by folder + album title, so "feat." credits don't split an album, while same-named albums in different folders stay apart. Disc subfolders (`CD1`, `Disc 2`, …) count as part of their parent album. Tracks are sorted by disc, then track number.
- Album covers: embedded artwork, or `cover.jpg` / `folder.jpg` / `front.jpg` in the album folder. One downscaled (512 px) copy per album is kept in app storage and passed to Media3 for lock-screen/notification metadata.
- Caches the library index in a private SQLite database. On start the cache is shown immediately and the folder is checked in the background; only new or changed files (by size and modification date) are re-read.
- Refresh button runs the same incremental check on demand. Choosing a folder again runs a full rescan.
- Large libraries: files are read 4 at a time, and progress is saved every 250 files, so an interrupted first scan continues where it stopped.
- If the folder can't be read (storage removed, permission revoked), the existing library is kept and a message is shown.
- Background playback through `MediaSessionService`, with previous/next, seek, shuffle and repeat. Pauses for calls and other audio (audio focus) and when headphones are unplugged. Unplayable files are skipped.
- Opening or rotating the app never changes what is currently playing.
- Remembers where you left off: queue, track, position, shuffle and repeat are saved while playing and restored (paused) when the app starts again — also after the app was closed or the phone restarted. The system media controls and a Bluetooth/headset play button can resume the last session directly.
- Audiobooks: put them in a folder named `Audiobooks` or `Hörbücher` inside the music folder, one subfolder per book. They get their own Books tab, remember their position per book, and play with adjustable speed and ±30 s skip.
- Sleep timer (15–60 min or end of track/chapter), running in the playback service.
- No internet permission, analytics, ads, account or cloud service.

## Build on Arch Linux

Use JDK 17, Android SDK Platform 35, Android Build Tools 35.0.0 and Gradle 8.9.

From this directory:

```bash
gradle wrapper --gradle-version 8.9
./gradlew --no-daemon --max-workers=1 assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.

## First run

Tap the folder icon, choose the folder containing the music, and grant access. The app only sees the folder the user explicitly selected. It does not request broad storage access.

## Notes

- The cache stores metadata and small album cover thumbnails, not copies of audio files.
- FLAC playback uses Android's native decoder on supported devices. Codec support can vary by device and Android build.
- Upgrading from 0.1: the library cache is rebuilt once on first start (new schema); playlists are migrated automatically.

## Versioning

Current version: **0.2.3**. New features raise the middle number (0.3.0), bug fixes the last one (0.2.1). See `CHANGES.md`.

## How things are identified

| Data | Identified by | On changing the music folder |
|---|---|---|
| Library index (tracks, covers) | the selected folder | cleared and rebuilt |
| Player queue | path **and** file link | stopped and cleared |
| Saved "resume" queue | path, rebuilt from the index | only tracks found in the new folder are restored |
| Playlists | path relative to the music folder | kept; tracks reappear when found again |
| Audiobook progress | book path relative to the music folder | kept; same book path = same progress |

## Tests

`./gradlew test` runs the JVM unit tests for the library rules (album grouping, book detection, sorting).

## User playlists

The library has three tabs: Albums, Songs and Playlists. Create a playlist from the Playlists tab, or use the `+` control beside a song or album to add it. Playlists are stored locally in `plainplayer_playlists.db`.

Tracks are referenced by their path relative to the music folder, not by Storage Access Framework URI, so playlists survive choosing the folder again (also via a parent or sub folder). A song can appear in a playlist more than once. Tracks whose file is currently not in the library are shown as "missing" and come back when the file is found again. Deleting a playlist asks for confirmation and can be undone from the message that follows.
