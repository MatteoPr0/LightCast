package com.lightcast.receiver

import android.util.Log
import android.webkit.JavascriptInterface
import org.json.JSONObject

class LightCastBridge(
    private val onStateUpdate: (PlaybackState) -> Unit
) {
    @JavascriptInterface
    fun onPlaybackStateChanged(stateJson: String) {
        try {
            val json = JSONObject(stateJson)
            val state = PlaybackState(
                state = json.optString("state", "idle"),
                currentTime = json.optDouble("currentTime", 0.0),
                duration = json.optDouble("duration", 0.0),
                title = json.optString("title", ""),
                volume = json.optDouble("volume", 1.0),
                isMuted = json.optBoolean("isMuted", false)
            )
            onStateUpdate(state)
        } catch (e: Exception) {
            Log.e("LightCastBridge", "Error parsing playback state: ${e.message}")
        }
    }

    @JavascriptInterface
    fun onLog(message: String) {
        Log.d("LightCastJS", message)
    }
}
