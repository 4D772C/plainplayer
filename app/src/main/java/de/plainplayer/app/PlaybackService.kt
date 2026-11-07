package de.plainplayer.app

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mp3.Mp3Extractor
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.core.content.ContextCompat
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.ListenableFutureTask
import com.google.common.util.concurrent.MoreExecutors
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/** Custom session command for the sleep timer, sent by the app's own controller. */
object SleepTimer {
    const val ACTION = "de.plainplayer.app.SLEEP_TIMER"
    /** Minutes until pause; 0 = off; [END_OF_TRACK] = pause when the current track/chapter ends. */
    const val EXTRA_MINUTES = "minutes"
    const val END_OF_TRACK = -1
    /** Session extras published by the service so the UI can show the timer. */
    const val EXTRA_SLEEP_AT = "sleep_at"
    const val EXTRA_END_OF_TRACK = "sleep_end_of_track"
}

class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private lateinit var stateStore: PlaybackStateStore
    private lateinit var bookProgress: BookProgressStore
    private val restoreExecutor = Executors.newSingleThreadExecutor()
    private lateinit var restored: ListenableFuture<PlaybackStateStore.Saved?>
    private val handler = Handler(Looper.getMainLooper())

    private var sleepAt = 0L
    private var sleepAtEndOfTrack = false
    private val sleepRunnable = Runnable {
        mediaSession?.player?.pause()
        setSleepTimer(0)
    }

    /** While playing, the position is saved every few seconds (cheap SharedPreferences write). */
    private val positionSaver = object : Runnable {
        override fun run() {
            mediaSession?.player?.let { savePosition(it) }
            handler.postDelayed(this, POSITION_SAVE_INTERVAL_MS)
        }
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        stateStore = PlaybackStateStore(this)
        bookProgress = BookProgressStore(this)

        // Accurate seeking in VBR MP3s that lack a Xing/VBRI header.
        val extractors = DefaultExtractorsFactory().setMp3ExtractorFlags(Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING)
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this, extractors))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true) // pause when headphones are unplugged / Bluetooth drops
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()

        player.addListener(SkipUnplayableTracks(player))
        player.addListener(PersistState(player))

        // Restore the last session in the background (it can be a long queue) and apply it on the main
        // thread when ready. The screen never waits for it.
        val task = ListenableFutureTask.create(Callable { stateStore.load() })
        restored = task
        restoreExecutor.execute(task)
        task.addListener({ applyRestored(player, task) }, ContextCompat.getMainExecutor(this))

        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(openApp)
            .setCallback(SessionCallback())
            .build()
    }

    private fun applyRestored(player: ExoPlayer, task: ListenableFutureTask<PlaybackStateStore.Saved?>) {
        if (mediaSession == null) return // service already destroyed
        val saved = runCatching { task.get() }.getOrNull() ?: return
        // The user (or a resume request) may already have started something else meanwhile.
        if (player.mediaItemCount > 0) return
        player.setMediaItems(saved.items, saved.index, saved.positionMs)
        player.shuffleModeEnabled = saved.shuffle
        player.repeatMode = saved.repeatMode
        player.prepare() // loads duration etc.; playback only starts when the user presses play
    }

    private fun savePosition(player: Player) {
        if (player.mediaItemCount == 0) return
        val currentKey = player.currentMediaItem?.mediaId
        val book = currentKey?.let { audiobookKeyOf(it) }
        if (player.playbackState == Player.STATE_ENDED) {
            // Finished the queue: next time start it from the beginning instead of at its very end.
            stateStore.savePosition(0, null, 0L, player.shuffleModeEnabled, player.repeatMode)
            if (book != null) bookProgress.save(book, null, 0L, finished = true)
        } else {
            stateStore.savePosition(
                player.currentMediaItemIndex, currentKey,
                player.currentPosition, player.shuffleModeEnabled, player.repeatMode
            )
            if (book != null) bookProgress.save(book, currentKey, player.currentPosition, finished = false)
        }
    }

    /** Audiobooks play at the chosen book speed, music always at normal speed. */
    private fun applySpeed(player: Player) {
        val isBook = player.currentMediaItem?.mediaId?.let { audiobookKeyOf(it) } != null
        val target = if (isBook) bookProgress.speed else 1f
        if (player.playbackParameters.speed != target) player.setPlaybackSpeed(target)
    }

    private fun setSleepTimer(minutes: Int) {
        handler.removeCallbacks(sleepRunnable)
        sleepAt = 0L
        sleepAtEndOfTrack = false
        when {
            minutes > 0 -> {
                sleepAt = System.currentTimeMillis() + minutes * 60_000L
                handler.postDelayed(sleepRunnable, minutes * 60_000L)
            }
            minutes == SleepTimer.END_OF_TRACK -> sleepAtEndOfTrack = true
        }
        mediaSession?.setSessionExtras(Bundle().apply {
            putLong(SleepTimer.EXTRA_SLEEP_AT, sleepAt)
            putBoolean(SleepTimer.EXTRA_END_OF_TRACK, sleepAtEndOfTrack)
        })
    }

    private fun saveQueue(player: Player) {
        stateStore.saveQueue((0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId })
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        mediaSession?.player?.let { savePosition(it) }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        handler.removeCallbacks(positionSaver)
        handler.removeCallbacks(sleepRunnable)
        mediaSession?.run {
            savePosition(player)
            player.release()
            release()
        }
        mediaSession = null
        restoreExecutor.shutdown()
        stateStore.close()
        super.onDestroy()
    }

    private inner class PersistState(private val player: Player) : Player.Listener {
        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            // Only when the queue itself changes, not when a track's duration becomes known.
            if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) saveQueue(player)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // "Sleep at end of track": the next track has just started – stop right at its beginning.
            if (sleepAtEndOfTrack && (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                    reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT)
            ) {
                player.pause()
                setSleepTimer(0)
            }
            applySpeed(player)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED && sleepAtEndOfTrack) setSleepTimer(0)
        }

        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(
                    Player.EVENT_MEDIA_ITEM_TRANSITION,
                    Player.EVENT_POSITION_DISCONTINUITY,
                    Player.EVENT_IS_PLAYING_CHANGED,
                    Player.EVENT_PLAYBACK_STATE_CHANGED,
                    Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                    Player.EVENT_REPEAT_MODE_CHANGED,
                    Player.EVENT_TIMELINE_CHANGED,
                )
            ) savePosition(player)
            if (events.contains(Player.EVENT_IS_PLAYING_CHANGED)) {
                handler.removeCallbacks(positionSaver)
                if (player.isPlaying) handler.postDelayed(positionSaver, POSITION_SAVE_INTERVAL_MS)
            }
        }
    }

    /**
     * Called by Media3 when playback is resumed while the player is empty, e.g. from the system media
     * controls after a reboot or from a Bluetooth play button (Android 13+ / MediaButtonReceiver).
     */
    private inner class SessionCallback : MediaSession.Callback {
        /** Only PlainPlayer itself gets the sleep-timer command; other controllers get the defaults. */
        @OptIn(UnstableApi::class)
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            if (controller.packageName != packageName) return super.onConnect(session, controller)
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommand(SleepTimer.ACTION, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == SleepTimer.ACTION) {
                setSleepTimer(args.getInt(SleepTimer.EXTRA_MINUTES, 0))
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return super.onCustomCommand(session, controller, customCommand, args)
        }

        @OptIn(UnstableApi::class)
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            // Waits for the background restore instead of reading the queue a second time.
            return Futures.transform(
                restored,
                { saved: PlaybackStateStore.Saved? ->
                    saved?.let { MediaSession.MediaItemsWithStartPosition(it.items, it.index, it.positionMs) }
                        ?: throw IllegalStateException("Nothing to resume")
                },
                MoreExecutors.directExecutor(),
            )
        }
    }

    /** A missing or corrupt file must not stop the whole queue. Gives up after a few failures in a row. */
    private class SkipUnplayableTracks(private val player: Player) : Player.Listener {
        private var failuresInARow = 0

        override fun onPlayerError(error: PlaybackException) {
            if (failuresInARow < MAX_SKIPS && player.hasNextMediaItem()) {
                failuresInARow++
                player.seekToNextMediaItem()
                player.prepare()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) failuresInARow = 0
        }

        private companion object {
            const val MAX_SKIPS = 5
        }
    }

    private companion object {
        const val POSITION_SAVE_INTERVAL_MS = 5_000L
    }
}
