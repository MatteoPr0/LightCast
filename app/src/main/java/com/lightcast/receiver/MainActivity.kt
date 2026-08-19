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
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URLEncoder

class MainActivity : AppCompatActivity(), LightCastServer.ServerListener {
    private var webView: WebView? = null
    private var httpServer: LightCastServer? = null
    private val serverPort = 8080
    private var isPlaying = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUI()

        try {
            startHttpServer()
            setupOptimizedWebView()
            setContentView(webView)

            try {
                startService(Intent(this, CastDiscoveryService::class.java))
            } catch (_: Exception) {}

            val ip = getLocalIpAddress()
            val deviceName = Build.MODEL ?: "LightCast TV"
            val encodedName = URLEncoder.encode(deviceName, "UTF-8")
            val targetUrl = "file:///android_asset/receiver.html?ip=$ip&port=$serverPort&name=$encodedName"
            
            webView?.loadUrl(targetUrl)
        } catch (e: Exception) {
            val errorView = TextView(this).apply {
                text = "LightCast Init Error:\n" + e.message
                setTextColor(0xFFFF0000.toInt())
                textSize = 18f
                setPadding(32, 32, 32, 32)
                setBackgroundColor(0xFF000000.toInt())
            }
            setContentView(errorView)
        }
    }

    private fun startHttpServer() {
        try {
            httpServer = LightCastServer(serverPort, this, this)
            httpServer?.start()
            Log.d("MainActivity", "LightCast Server started on port $serverPort")
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to start HTTP server: ${e.message}", e)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupOptimizedWebView() {
        webView = WebView(this).apply {
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

        // Bridge to receive playback updates from Javascript
        val bridge = LightCastBridge { state ->
            isPlaying = (state.state == "playing")
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
            val safeUrl = url.replace("'", "\\'")
            val safeTitle = title.replace("'", "\\'")
            val safeType = type.replace("'", "\\'")
            webView?.evaluateJavascript("window.playMedia('$safeUrl', '$safeTitle', '$safeType')", null)
        }
    }

    override fun onControlMedia(action: String, value: Any?) {
        runOnUiThread {
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
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                webView?.evaluateJavascript("window.controlMedia('toggle', null)", null)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                webView?.evaluateJavascript("window.controlMedia('play', null)", null)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                webView?.evaluateJavascript("window.controlMedia('pause', null)", null)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_STOP -> {
                webView?.evaluateJavascript("window.controlMedia('stop', null)", null)
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                webView?.evaluateJavascript("window.controlMedia('seek', 10)", null)
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                webView?.evaluateJavascript("window.controlMedia('seek', -10)", null)
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (isPlaying) {
                    webView?.evaluateJavascript("window.controlMedia('stop', null)", null)
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
