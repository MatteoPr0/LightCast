package com.lightcast.receiver.player

import android.content.Context
import android.media.AudioFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.ui.PlayerView
import com.lightcast.receiver.PlaybackState
import java.util.concurrent.CopyOnWriteArrayList

data class TrackInfo(
    val index: Int,
    val name: String,
    val language: String,
    val isSelected: Boolean
)

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
    private var trackSelector: DefaultTrackSelector? = null
    private var currentTitle: String = ""
    private val handler = Handler(Looper.getMainLooper())
    private var isSyncRunning = false

    private val cachedAudioTracks = CopyOnWriteArrayList<TrackInfo>()
    private val cachedSubtitleTracks = CopyOnWriteArrayList<TrackInfo>()

    val isPlaying: Boolean
        get() = exoPlayer?.isPlaying == true

    val currentPositionSec: Double
        get() = (exoPlayer?.currentPosition ?: 0L) / 1000.0

    val durationSec: Double
        get() = ((exoPlayer?.duration?.takeIf { it != C.TIME_UNSET } ?: 0L)) / 1000.0

    init {
        initializePlayer()
    }

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

        trackSelector = DefaultTrackSelector(context).apply {
            setParameters(
                buildUponParameters()
                    .setPreferredAudioLanguage("ita")
                    .setPreferredTextLanguage("ita")
                    .setAllowAudioMixedMimeTypeAdaptiveness(true)
                    .setAllowAudioNonSeamlessAdaptiveness(true)
                    .setTunnelingEnabled(false)
            )
        }

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)

        val dataSourceFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)
        val extractorsFactory = DefaultExtractorsFactory().apply {
            setConstantBitrateSeekingEnabled(true)
            setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS)
        }
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory)

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        exoPlayer = ExoPlayer.Builder(context, renderersFactory)
            .setLoadControl(loadControl)
            .setTrackSelector(trackSelector!!)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
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

            override fun onTracksChanged(tracks: Tracks) {
                updateCachedTracks(tracks)
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

    private fun updateCachedTracks(tracks: Tracks) {
        val audioList = mutableListOf<TrackInfo>()
        val subList = mutableListOf<TrackInfo>()

        var audioIdx = 0
        var subIdx = 0

        for (group in tracks.groups) {
            if (group.type == C.TRACK_TYPE_AUDIO) {
                for (i in 0 until group.length) {
                    val format = group.getTrackFormat(i)
                    val isSelected = group.isTrackSelected(i)
                    val lang = format.language ?: "und"
                    val label = format.label ?: format.language ?: "Audio ${audioIdx + 1}"
                    val channelCount = format.channelCount
                    val channelsStr = if (channelCount > 2) " ($channelCount ch)" else ""
                    audioList.add(TrackInfo(audioIdx, "$label$channelsStr", lang, isSelected))
                    audioIdx++
                }
            } else if (group.type == C.TRACK_TYPE_TEXT) {
                for (i in 0 until group.length) {
                    val format = group.getTrackFormat(i)
                    val isSelected = group.isTrackSelected(i)
                    val lang = format.language ?: "und"
                    val label = format.label ?: format.language ?: "Sottotitoli ${subIdx + 1}"
                    subList.add(TrackInfo(subIdx, label, lang, isSelected))
                    subIdx++
                }
            }
        }

        cachedAudioTracks.clear()
        cachedAudioTracks.addAll(audioList)

        cachedSubtitleTracks.clear()
        cachedSubtitleTracks.addAll(subList)
    }

    fun play(url: String, title: String) {
        currentTitle = title
        val uri = Uri.parse(url)
        val path = uri.path?.lowercase() ?: ""
        val query = uri.query?.lowercase() ?: ""

        val mediaItemBuilder = MediaItem.Builder().setUri(uri)
        if (path.endsWith(".m3u8") && !query.contains("format=ts") && !query.contains("format=mp4")) {
            mediaItemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
        } else if (path.endsWith(".mpd") && !query.contains("format=ts")) {
            mediaItemBuilder.setMimeType(MimeTypes.APPLICATION_MPD)
        } else if (path.endsWith(".ts") || query.contains("format=ts")) {
            mediaItemBuilder.setMimeType(MimeTypes.VIDEO_MP2T)
        } else if (path.endsWith(".mp4") || path.endsWith(".m4v") || query.contains("format=mp4")) {
            mediaItemBuilder.setMimeType(MimeTypes.VIDEO_MP4)
        } else if (path.endsWith(".mkv")) {
            mediaItemBuilder.setMimeType(MimeTypes.VIDEO_MATROSKA)
        }

        val mediaItem = mediaItemBuilder.build()

        exoPlayer?.apply {
            setMediaItem(mediaItem)
            prepare()
            playWhenReady = true
        }

        emitStateUpdate("playing")
    }

    fun getAudioTracks(): List<TrackInfo> {
        return cachedAudioTracks.toList()
    }

    fun getSubtitleTracks(): List<TrackInfo> {
        return cachedSubtitleTracks.toList()
    }

    fun selectAudioTrack(targetIndex: Int): String {
        var selectedName = ""
        handler.post {
            val player = exoPlayer ?: return@post
            val tracks = player.currentTracks
            var idx = 0
            for (group in tracks.groups) {
                if (group.type == C.TRACK_TYPE_AUDIO) {
                    for (i in 0 until group.length) {
                        if (idx == targetIndex) {
                            val format = group.getTrackFormat(i)
                            val lang = format.language
                            val selector = trackSelector ?: return@post
                            val params = selector.parameters.buildUpon()
                            if (lang != null) {
                                params.setPreferredAudioLanguage(lang)
                            }
                            params.setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                            selector.setParameters(params)
                            selectedName = format.label ?: format.language ?: "Audio ${idx + 1}"
                            return@post
                        }
                        idx++
                    }
                }
            }
        }
        return selectedName
    }

    fun cycleAudioTrack(): String {
        val tracks = getAudioTracks()
        if (tracks.isEmpty()) return "Nessuna traccia audio"
        if (tracks.size == 1) return "Traccia unica: ${tracks[0].name}"
        val curIndex = tracks.indexOfFirst { it.isSelected }.let { if (it == -1) 0 else it }
        val nextIndex = (curIndex + 1) % tracks.size
        selectAudioTrack(nextIndex)
        return "Audio: ${tracks[nextIndex].name} (${nextIndex + 1}/${tracks.size})"
    }

    fun cycleSubtitleTrack(): String {
        val subs = getSubtitleTracks()
        if (subs.isEmpty()) return "Nessun sottotitolo disponibile"
        val curIndex = subs.indexOfFirst { it.isSelected }
        if (curIndex == -1) {
            selectSubtitleTrack(0)
            return "Sottotitoli: ${subs[0].name} (1/${subs.size})"
        } else if (curIndex == subs.size - 1) {
            disableSubtitles()
            return "Sottotitoli disattivati"
        } else {
            val nextIndex = curIndex + 1
            selectSubtitleTrack(nextIndex)
            return "Sottotitoli: ${subs[nextIndex].name} (${nextIndex + 1}/${subs.size})"
        }
    }

    fun selectSubtitleTrack(targetIndex: Int): String {
        var selectedName = ""
        handler.post {
            val player = exoPlayer ?: return@post
            val tracks = player.currentTracks
            var idx = 0
            for (group in tracks.groups) {
                if (group.type == C.TRACK_TYPE_TEXT) {
                    for (i in 0 until group.length) {
                        if (idx == targetIndex) {
                            val format = group.getTrackFormat(i)
                            val lang = format.language
                            val selector = trackSelector ?: return@post
                            val params = selector.parameters.buildUpon()
                            if (lang != null) {
                                params.setPreferredTextLanguage(lang)
                            }
                            params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                            selector.setParameters(params)
                            selectedName = format.label ?: format.language ?: "Sottotitoli ${idx + 1}"
                            return@post
                        }
                        idx++
                    }
                }
            }
        }
        return selectedName
    }

    fun disableSubtitles() {
        handler.post {
            val selector = trackSelector ?: return@post
            selector.setParameters(
                selector.parameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            )
        }
    }

    fun pause() {
        handler.post { exoPlayer?.pause() }
        emitStateUpdate("paused")
    }

    fun resume() {
        handler.post { exoPlayer?.play() }
        emitStateUpdate("playing")
    }

    fun togglePlayPause() {
        if (isPlaying) pause() else resume()
    }

    fun seekBy(deltaSeconds: Int) {
        handler.post {
            val player = exoPlayer ?: return@post
            val newPos = (player.currentPosition + (deltaSeconds * 1000L)).coerceIn(0L, player.duration.coerceAtLeast(0L))
            player.seekTo(newPos)
            emitStateUpdate(if (player.isPlaying) "playing" else "paused")
        }
    }

    fun seekTo(seconds: Double) {
        handler.post {
            val player = exoPlayer ?: return@post
            val posMs = (seconds * 1000.0).toLong().coerceIn(0L, player.duration.coerceAtLeast(0L))
            player.seekTo(posMs)
            emitStateUpdate(if (player.isPlaying) "playing" else "paused")
        }
    }

    fun setVolume(volumeFraction: Float) {
        handler.post {
            exoPlayer?.volume = volumeFraction.coerceIn(0f, 1f)
            emitStateUpdate(if (isPlaying) "playing" else "paused")
        }
    }

    fun stop() {
        stopPeriodicSync()
        cachedAudioTracks.clear()
        cachedSubtitleTracks.clear()
        handler.post {
            exoPlayer?.stop()
            exoPlayer?.clearMediaItems()
        }
        emitStateUpdate("idle")
    }

    fun release() {
        stop()
        handler.post {
            exoPlayer?.release()
            exoPlayer = null
        }
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
