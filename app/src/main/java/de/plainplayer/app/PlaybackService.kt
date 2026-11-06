package de.plainplayer.app

import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
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
import androidx.core.content.ContextCompat
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.ListenableFutureTask
import com.google.common.util.concurrent.MoreExecutors
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private lateinit var stateStore: PlaybackStateStore
    private val restoreExecutor = Executors.newSingleThreadExecutor()
    private lateinit var restored: ListenableFuture<PlaybackStateStore.Saved?>
    private val handler = Handler(Looper.getMainLooper())

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
        if (player.playbackState == Player.STATE_ENDED) {
            // Finished the queue: next time start it from the beginning instead of at its very end.
            stateStore.savePosition(0, null, 0L, player.shuffleModeEnabled, player.repeatMode)
        } else {
            stateStore.savePosition(
                player.currentMediaItemIndex, player.currentMediaItem?.mediaId,
                player.currentPosition, player.shuffleModeEnabled, player.repeatMode
            )
        }
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
