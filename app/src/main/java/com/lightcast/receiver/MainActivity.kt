package com.lightcast.receiver

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.*
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.ui.PlayerView
import com.lightcast.receiver.cast.CastMdnsServer
import com.lightcast.receiver.cast.CastV2Server
import com.lightcast.receiver.cast.EurekaServer
import com.lightcast.receiver.dlna.DlnaServer
import com.lightcast.receiver.player.LightCastPlayerManager
import com.lightcast.receiver.player.TrackInfo
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URLEncoder

class MainActivity : AppCompatActivity(),
    LightCastServer.ServerListener,
    CastV2Server.CastV2Listener,
    DlnaServer.DlnaListener,
    LightCastPlayerManager.PlayerStateListener {

    private var webView: WebView? = null
    private var playerView: PlayerView? = null
    private var playerTopOverlay: View? = null
    private var playerMediaTitle: TextView? = null

    private var playerManager: LightCastPlayerManager? = null
    private var httpServer: LightCastServer? = null
    private var eurekaServer: EurekaServer? = null
    private var castV2Server: CastV2Server? = null
    private var castMdnsServer: CastMdnsServer? = null
    private var dlnaServer: DlnaServer? = null

    private val httpPort = 8080
    private val eurekaPort = 8008
    private val castPort = 8009

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        try {
            setContentView(R.layout.activity_main)
            hideSystemUI()

            webView = findViewById(R.id.dashboardWebView)
            playerView = findViewById(R.id.exoPlayerView)
            playerTopOverlay = findViewById(R.id.playerTopOverlay)
            playerMediaTitle = findViewById(R.id.playerMediaTitle)

            val deviceName = "LightCast TV"

            startHttpServer()
            startEurekaServer(deviceName)
            startCastV2Server(deviceName)
            startCastMdnsServer(deviceName)
            startDlnaServer(deviceName)
            setupOptimizedWebView()
            setupExoPlayer()

            val ip = getLocalIpAddress()
            val encodedName = URLEncoder.encode(deviceName, "UTF-8")
            val targetUrl = "file:///android_asset/receiver.html?ip=$ip&port=$httpPort&name=$encodedName"

            webView?.loadUrl(targetUrl)
        } catch (e: Exception) {
            Log.e("MainActivity", "Error in onCreate: ${e.message}", e)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemUI()
        }
    }

    private fun startHttpServer() {
        try {
            httpServer = LightCastServer(httpPort, this, this)
            httpServer?.start()
            Log.d("MainActivity", "LightCast Web Server running on port $httpPort")
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to start HTTP server: ${e.message}", e)
        }
    }

    private fun startEurekaServer(deviceName: String) {
        try {
            eurekaServer = EurekaServer(eurekaPort, deviceName, this, object : EurekaServer.EurekaListener {
                override fun onDialLaunch(appName: String, data: String) {
                    runOnUiThread {
                        Log.d("MainActivity", "DIAL launch request for $appName: $data")
                        if (appName.equals("YouTube", ignoreCase = true)) {
                            val videoId = if (data.contains("v=")) {
                                data.substringAfter("v=").substringBefore('&').substringBefore(' ')
                            } else {
                                data.trim()
                            }
                            if (videoId.isNotEmpty()) {
                                onCastMedia("https://www.youtube.com/embed/$videoId?autoplay=1", "YouTube: $videoId", "video/mp4")
                            }
                        }
                    }
                }
            })
            eurekaServer?.start()
            Log.d("MainActivity", "LightCast Eureka Server running on port $eurekaPort")
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to start Eureka server: ${e.message}", e)
        }
    }

    private fun startCastV2Server(deviceName: String) {
        try {
            castV2Server = CastV2Server(castPort, deviceName, this)
            castV2Server?.start()
            Log.d("MainActivity", "LightCast Cast V2 Server running on port $castPort")
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to start Cast V2 server: ${e.message}", e)
        }
    }

    private fun startCastMdnsServer(deviceName: String) {
        try {
            castMdnsServer = CastMdnsServer(this, deviceName, castPort)
            castMdnsServer?.start()
            Log.d("MainActivity", "LightCast pure Kotlin mDNS Server started")
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to start mDNS server: ${e.message}", e)
        }
    }

    private fun startDlnaServer(deviceName: String) {
        try {
            dlnaServer = DlnaServer(this, deviceName, this)
            dlnaServer?.start()
            httpServer?.dlnaServer = dlnaServer
            Log.d("MainActivity", "LightCast DLNA MediaRenderer started")
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to start DLNA server: ${e.message}", e)
        }
    }

    private fun setupExoPlayer() {
        playerView?.let { pv ->
            pv.controllerShowTimeoutMs = 3500
            playerManager = LightCastPlayerManager(this, pv, this)
            pv.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
                playerTopOverlay?.visibility = visibility
            })
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupOptimizedWebView() {
        webView?.apply {
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            setBackgroundColor(0xFF000000.toInt())
        }

        webView?.settings?.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            loadsImagesAutomatically = true
            blockNetworkImage = false
            setSupportZoom(false)
            displayZoomControls = false
            builtInZoomControls = false
            allowFileAccess = true
            allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                safeBrowsingEnabled = false
            }
        }

        val bridge = LightCastBridge { state ->
            httpServer?.playbackState = state
        }
        webView?.addJavascriptInterface(bridge, "AndroidBridge")

        webView?.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                request.grant(request.resources)
            }
        }

        webView?.webViewClient = object : WebViewClient() {
            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                recreate()
                return true
            }
        }
    }

    override fun onCastMedia(url: String, title: String, type: String) {
        runOnUiThread {
            if (type.startsWith("image/")) {
                playerManager?.stop()
                playerView?.visibility = View.GONE
                playerTopOverlay?.visibility = View.GONE
                webView?.visibility = View.VISIBLE

                val safeUrl = url.replace("'", "\\'")
                val safeTitle = title.replace("'", "\\'")
                webView?.evaluateJavascript("window.playMedia('$safeUrl', '$safeTitle', '$type')", null)
            } else {
                webView?.visibility = View.GONE
                playerView?.visibility = View.VISIBLE
                playerMediaTitle?.text = title
                playerTopOverlay?.visibility = View.VISIBLE

                playerManager?.play(url, title)
            }
        }
    }

    override fun onControlMedia(action: String, value: Any?) {
        runOnUiThread {
            if (playerView?.visibility == View.VISIBLE) {
                when (action) {
                    "play" -> playerManager?.resume()
                    "pause" -> playerManager?.pause()
                    "toggle" -> playerManager?.togglePlayPause()
                    "seek" -> {
                        val delta = (value as? Number)?.toInt() ?: 10
                        playerManager?.seekBy(delta)
                    }
                    "seekTo" -> {
                        val sec = (value as? Number)?.toDouble() ?: 0.0
                        playerManager?.seekTo(sec)
                    }
                    "volume" -> {
                        val vol = (value as? Number)?.toFloat() ?: 1f
                        playerManager?.setVolume(vol)
                    }
                    "cycle_audio" -> {
                        val msg = playerManager?.cycleAudioTrack() ?: ""
                        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                    }
                    "cycle_subtitles" -> {
                        val msg = playerManager?.cycleSubtitleTrack() ?: ""
                        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                    }
                    "stop" -> stopPlaybackAndShowDashboard()
                }
            } else {
                val jsVal = when (value) {
                    is Number -> value.toString()
                    is String -> "'$value'"
                    is Boolean -> value.toString()
                    null -> "null"
                    else -> "'$value'"
                }
                webView?.evaluateJavascript("window.controlMedia('$action', $jsVal)", null)
            }
        }
    }

    override fun onGetAudioTracks(): List<TrackInfo> {
        return playerManager?.getAudioTracks() ?: emptyList()
    }

    override fun onGetSubtitleTracks(): List<TrackInfo> {
        return playerManager?.getSubtitleTracks() ?: emptyList()
    }

    override fun onSelectTrack(type: String, index: Int): String {
        return if (type == "audio") {
            playerManager?.selectAudioTrack(index) ?: ""
        } else {
            if (index < 0) {
                playerManager?.disableSubtitles()
                "Disattivati"
            } else {
                playerManager?.selectSubtitleTrack(index) ?: ""
            }
        }
    }

    override fun onGetMediaStatus(): PlaybackState {
        return httpServer?.playbackState ?: PlaybackState()
    }

    override fun onPlayerStateChanged(state: PlaybackState) {
        httpServer?.playbackState = state
    }

    override fun onPlayerEnded() {
        runOnUiThread {
            stopPlaybackAndShowDashboard()
        }
    }

    override fun onPlayerError(errorMessage: String) {
        runOnUiThread {
            Log.e("MainActivity", "Playback error: $errorMessage")
            Toast.makeText(this, "Errore stream: $errorMessage", Toast.LENGTH_LONG).show()
            stopPlaybackAndShowDashboard()
        }
    }

    private fun stopPlaybackAndShowDashboard() {
        playerManager?.stop()
        playerView?.visibility = View.GONE
        playerTopOverlay?.visibility = View.GONE
        webView?.visibility = View.VISIBLE

        httpServer?.playbackState = PlaybackState(state = "idle")
    }

    private fun getLocalIpAddress(): String {
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ipInt = wifiManager.connectionInfo.ipAddress
            if (ipInt != 0) {
                return String.format(
                    "%d.%d.%d.%d",
                    ipInt and 0xff,
                    ipInt shr 8 and 0xff,
                    ipInt shr 16 and 0xff,
                    ipInt shr 24 and 0xff
                )
            }
        } catch (_: Exception) {}

        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress ?: "127.0.0.1"
                    }
                }
            }
        } catch (_: Exception) {}

        return "127.0.0.1"
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (playerView?.visibility == View.VISIBLE) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    playerManager?.togglePlayPause()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY -> {
                    playerManager?.resume()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    playerManager?.pause()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_STOP -> {
                    stopPlaybackAndShowDashboard()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    playerManager?.seekBy(10)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                    playerManager?.seekBy(-10)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_PROG_BLUE -> {
                    val msg = playerManager?.cycleAudioTrack() ?: ""
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CAPTIONS, KeyEvent.KEYCODE_PROG_YELLOW -> {
                    val msg = playerManager?.cycleSubtitleTrack() ?: ""
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                    return true
                }
                KeyEvent.KEYCODE_BACK -> {
                    stopPlaybackAndShowDashboard()
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun hideSystemUI() {
        try {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
            windowInsetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
        } catch (_: Exception) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
        }
    }

    override fun onDestroy() {
        try {
            dlnaServer?.stop()
        } catch (_: Exception) {}
        try {
            castMdnsServer?.stop()
        } catch (_: Exception) {}
        try {
            castV2Server?.stop()
        } catch (_: Exception) {}
        try {
            eurekaServer?.stop()
        } catch (_: Exception) {}
        try {
            playerManager?.release()
        } catch (_: Exception) {}
        try {
            httpServer?.stop()
        } catch (_: Exception) {}
        try {
            stopService(Intent(this, CastDiscoveryService::class.java))
        } catch (_: Exception) {}
        webView?.apply {
            loadUrl("about:blank")
            clearHistory()
            removeAllViews()
            destroy()
        }
        super.onDestroy()
    }
}
