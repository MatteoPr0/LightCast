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
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.*
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.ui.PlayerView
import com.lightcast.receiver.player.LightCastPlayerManager
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URLEncoder

class MainActivity : AppCompatActivity(), LightCastServer.ServerListener, LightCastPlayerManager.PlayerStateListener {
    private var webView: WebView? = null
    private var playerView: PlayerView? = null
    private var playerTopOverlay: View? = null
    private var playerMediaTitle: TextView? = null
    
    private var playerManager: LightCastPlayerManager? = null
    private var httpServer: LightCastServer? = null
    private val serverPort = 8080

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUI()

        try {
            setContentView(R.layout.activity_main)

            webView = findViewById(R.id.dashboardWebView)
            playerView = findViewById(R.id.exoPlayerView)
            playerTopOverlay = findViewById(R.id.playerTopOverlay)
            playerMediaTitle = findViewById(R.id.playerMediaTitle)

            startHttpServer()
            setupOptimizedWebView()
            setupExoPlayer()

            try {
                startService(Intent(this, CastDiscoveryService::class.java))
            } catch (_: Exception) {}

            val ip = getLocalIpAddress()
            val deviceName = Build.MODEL ?: "LightCast TV"
            val encodedName = URLEncoder.encode(deviceName, "UTF-8")
            val targetUrl = "file:///android_asset/receiver.html?ip=$ip&port=$serverPort&name=$encodedName"

            webView?.loadUrl(targetUrl)
        } catch (e: Exception) {
            Log.e("MainActivity", "Error in onCreate: ${e.message}", e)
        }
    }

    private fun startHttpServer() {
        try {
            httpServer = LightCastServer(serverPort, this, this)
            httpServer?.start()
            Log.d("MainActivity", "LightCast Server running on port $serverPort")
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to start HTTP server: ${e.message}", e)
        }
    }

    private fun setupExoPlayer() {
        playerView?.let { pv ->
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
                stopPlaybackAndShowDashboard()
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
                KeyEvent.KEYCODE_BACK -> {
                    stopPlaybackAndShowDashboard()
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
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
