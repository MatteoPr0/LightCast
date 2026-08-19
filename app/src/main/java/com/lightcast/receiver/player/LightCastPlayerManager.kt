package com.lightcast.receiver.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import com.lightcast.receiver.PlaybackState

class LightCastPlayerManager(
    private val context: Context,
    private val playerView: PlayerView,
    private val stateListener: PlayerStateListener
) {
    interface PlayerStateListener {
        fun onPlayerStateChanged(state: PlaybackState)
        fun onPlayerEnded()
        fun onPlayerError(errorMessage: String)
    }

    private var exoPlayer: ExoPlayer? = null
    private var currentTitle: String = ""
    private val handler = Handler(Looper.getMainLooper())
    private var isSyncRunning = false

    val isPlaying: Boolean
        get() = exoPlayer?.isPlaying == true

    val currentPositionSec: Double
        get() = (exoPlayer?.currentPosition ?: 0L) / 1000.0

    val durationSec: Double
        get() = ((exoPlayer?.duration?.takeIf { it != C.TIME_UNSET } ?: 0L)) / 1000.0

    init {
        initializePlayer()
    }

    private fun initializePlayer() {
        val renderersFactory = DefaultRenderersFactory(context).apply {
            setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            setEnableDecoderFallback(true)
        }

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 10_000,
                /* maxBufferMs = */ 60_000,
                /* bufferForPlaybackMs = */ 1_000,
                /* bufferForPlaybackAfterRebufferMs = */ 2_000
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val trackSelector = DefaultTrackSelector(context)

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)

        val dataSourceFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)

        exoPlayer = ExoPlayer.Builder(context, renderersFactory)
            .setLoadControl(loadControl)
            .setTrackSelector(trackSelector)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()

        exoPlayer?.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> {
                        emitStateUpdate("playing")
                        startPeriodicSync()
                    }
                    Player.STATE_BUFFERING -> {
                        emitStateUpdate("buffering")
                    }
                    Player.STATE_ENDED -> {
                        stopPeriodicSync()
                        emitStateUpdate("idle")
                        stateListener.onPlayerEnded()
                    }
                    Player.STATE_IDLE -> {
                        stopPeriodicSync()
                    }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                emitStateUpdate(if (isPlaying) "playing" else "paused")
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e("LightCastPlayer", "ExoPlayer Error: ${error.message}", error)
                stopPeriodicSync()
                emitStateUpdate("error")
                stateListener.onPlayerError(error.localizedMessage ?: "Errore di riproduzione")
            }
        })

        playerView.player = exoPlayer
    }

    fun play(url: String, title: String) {
        currentTitle = title
        val uri = Uri.parse(url)
        val mediaItem = MediaItem.fromUri(uri)

        exoPlayer?.apply {
            setMediaItem(mediaItem)
            prepare()
            playWhenReady = true
        }

        emitStateUpdate("playing")
    }

    fun pause() {
        exoPlayer?.pause()
        emitStateUpdate("paused")
    }

    fun resume() {
        exoPlayer?.play()
        emitStateUpdate("playing")
    }

    fun togglePlayPause() {
        if (isPlaying) pause() else resume()
    }

    fun seekBy(deltaSeconds: Int) {
        val player = exoPlayer ?: return
        val newPos = (player.currentPosition + (deltaSeconds * 1000L)).coerceIn(0L, player.duration.coerceAtLeast(0L))
        player.seekTo(newPos)
        emitStateUpdate(if (player.isPlaying) "playing" else "paused")
    }

    fun seekTo(seconds: Double) {
        val player = exoPlayer ?: return
        val posMs = (seconds * 1000.0).toLong().coerceIn(0L, player.duration.coerceAtLeast(0L))
        player.seekTo(posMs)
        emitStateUpdate(if (player.isPlaying) "playing" else "paused")
    }

    fun setVolume(volumeFraction: Float) {
        exoPlayer?.volume = volumeFraction.coerceIn(0f, 1f)
        emitStateUpdate(if (isPlaying) "playing" else "paused")
    }

    fun stop() {
        stopPeriodicSync()
        exoPlayer?.stop()
        exoPlayer?.clearMediaItems()
        emitStateUpdate("idle")
    }

    fun release() {
        stop()
        exoPlayer?.release()
        exoPlayer = null
    }

    private fun startPeriodicSync() {
        if (isSyncRunning) return
        isSyncRunning = true
        handler.post(syncRunnable)
    }

    private fun stopPeriodicSync() {
        isSyncRunning = false
        handler.removeCallbacks(syncRunnable)
    }

    private val syncRunnable = object : Runnable {
        override fun run() {
            if (!isSyncRunning || exoPlayer == null) return
            val player = exoPlayer ?: return
            val state = if (player.isPlaying) "playing" else if (player.playbackState == Player.STATE_BUFFERING) "buffering" else "paused"
            emitStateUpdate(state)
            handler.postDelayed(this, 1000)
        }
    }

    private fun emitStateUpdate(state: String) {
        val playbackState = PlaybackState(
            state = state,
            currentTime = currentPositionSec,
            duration = durationSec,
            title = currentTitle,
            volume = (exoPlayer?.volume ?: 1f).toDouble(),
            isMuted = (exoPlayer?.volume ?: 1f) == 0f
        )
        stateListener.onPlayerStateChanged(playbackState)
    }
}
