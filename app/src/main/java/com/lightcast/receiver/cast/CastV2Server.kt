package com.lightcast.receiver.cast

import android.util.Log
import com.lightcast.receiver.PlaybackState
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.net.ssl.SSLServerSocket

class CastV2Server(
    val port: Int = 8009,
    private val deviceName: String,
    private val listener: CastV2Listener
) {
    interface CastV2Listener {
        fun onCastMedia(url: String, title: String, type: String)
        fun onControlMedia(action: String, value: Any?)
        fun onGetMediaStatus(): PlaybackState
    }

    private var serverSocket: SSLServerSocket? = null
    private var isRunning = false
    private val threadPool = Executors.newCachedThreadPool()
    private val activeClients = ConcurrentHashMap<String, ClientSession>()
    
    private var currentSessionId: String? = null
    private var currentAppId: String = "CC1AD845" // Default Media Receiver
    private var currentMediaSessionId = 1
    private var currentVolume = 1.0
    private var isMuted = false

    fun start() {
        if (isRunning) return
        isRunning = true

        threadPool.execute {
            try {
                val sslContext = CastCertificateGenerator.getSSLContext()
                val serverFactory = sslContext.serverSocketFactory
                serverSocket = serverFactory.createServerSocket(port) as SSLServerSocket
                serverSocket?.needClientAuth = false

                Log.d("CastV2Server", "Cast V2 TLS Server listening on port $port")

                while (isRunning) {
                    val clientSocket = serverSocket?.accept() ?: break
                    threadPool.execute { handleClient(clientSocket) }
                }
            } catch (e: Exception) {
                if (isRunning) {
                    Log.e("CastV2Server", "Server error: ${e.message}", e)
                }
            }
        }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        activeClients.values.forEach { it.close() }
        activeClients.clear()
        threadPool.shutdownNow()
    }

    private fun handleClient(socket: Socket) {
        var clientSession: ClientSession? = null
        try {
            val dis = DataInputStream(socket.getInputStream())
            val dos = DataOutputStream(socket.getOutputStream())
            clientSession = ClientSession(socket, dos)

            while (isRunning && !socket.isClosed) {
                val len = dis.readInt()
                if (len <= 0 || len > 65536) break

                val payload = ByteArray(len)
                dis.readFully(payload)

                val castMsg = CastMessage.parseFrom(payload)
                activeClients[castMsg.sourceId] = clientSession
                handleCastMessage(castMsg, clientSession)
            }
        } catch (e: IOException) {
            // Client disconnected normally
        } catch (e: Exception) {
            Log.e("CastV2Server", "Client error: ${e.message}")
        } finally {
            clientSession?.close()
        }
    }

    private fun handleCastMessage(msg: CastMessage, client: ClientSession) {
        val payloadStr = msg.payloadUtf8 ?: return
        Log.d("CastV2Server", "Received [${msg.namespace}]: $payloadStr")

        try {
            val json = JSONObject(payloadStr)
            val type = json.optString("type", "")
            val requestId = json.optInt("requestId", 0)

            when (msg.namespace) {
                "urn:x-cast:com.google.cast.tp.connection" -> {
                    if (type == "CONNECT") {
                        sendReceiverStatus(msg.sourceId, client, requestId)
                    } else if (type == "CLOSE") {
                        client.close()
                    }
                }

                "urn:x-cast:com.google.cast.tp.heartbeat" -> {
                    if (type == "PING") {
                        val pongMsg = CastMessage(
                            protocolVersion = 0,
                            sourceId = msg.destinationId,
                            destinationId = msg.sourceId,
                            namespace = "urn:x-cast:com.google.cast.tp.heartbeat",
                            payloadType = 0,
                            payloadUtf8 = """{"type":"PONG"}"""
                        )
                        client.sendMessage(pongMsg)
                    }
                }

                "urn:x-cast:com.google.cast.receiver" -> {
                    when (type) {
                        "GET_STATUS" -> {
                            sendReceiverStatus(msg.sourceId, client, requestId)
                        }
                        "LAUNCH" -> {
                            currentAppId = json.optString("appId", "CC1AD845")
                            currentSessionId = UUID.randomUUID().toString()
                            sendReceiverStatus(msg.sourceId, client, requestId)
                        }
                        "STOP" -> {
                            currentSessionId = null
                            listener.onControlMedia("stop", null)
                            sendReceiverStatus(msg.sourceId, client, requestId)
                        }
                        "SET_VOLUME" -> {
                            val volumeObj = json.optJSONObject("volume")
                            if (volumeObj != null) {
                                if (volumeObj.has("level")) {
                                    currentVolume = volumeObj.optDouble("level", 1.0)
                                    listener.onControlMedia("volume", currentVolume)
                                }
                                if (volumeObj.has("muted")) {
                                    isMuted = volumeObj.optBoolean("muted", false)
                                    listener.onControlMedia("mute", isMuted)
                                }
                            }
                            sendReceiverStatus(msg.sourceId, client, requestId)
                        }
                    }
                }

                "urn:x-cast:com.google.cast.media" -> {
                    when (type) {
                        "LOAD" -> {
                            val mediaObj = json.optJSONObject("media")
                            val contentId = mediaObj?.optString("contentId", "") ?: ""
                            val contentType = mediaObj?.optString("contentType", "video/mp4") ?: "video/mp4"
                            val metadata = mediaObj?.optJSONObject("metadata")
                            val title = metadata?.optString("title", "Cast Stream") ?: "Cast Stream"

                            if (contentId.isNotEmpty()) {
                                currentMediaSessionId++
                                listener.onCastMedia(contentId, title, contentType)
                                sendMediaStatus(msg.sourceId, client, requestId, "PLAYING", contentId, title, contentType)
                            }
                        }
                        "PLAY" -> {
                            listener.onControlMedia("play", null)
                            sendCurrentMediaStatus(msg.sourceId, client, requestId, "PLAYING")
                        }
                        "PAUSE" -> {
                            listener.onControlMedia("pause", null)
                            sendCurrentMediaStatus(msg.sourceId, client, requestId, "PAUSED")
                        }
                        "SEEK" -> {
                            val curTime = json.optDouble("currentTime", 0.0)
                            listener.onControlMedia("seekTo", curTime)
                            sendCurrentMediaStatus(msg.sourceId, client, requestId, null)
                        }
                        "GET_STATUS" -> {
                            sendCurrentMediaStatus(msg.sourceId, client, requestId, null)
                        }
                    }
                }

                "urn:x-cast:com.google.cast.tp.deviceauth" -> {
                    // Send minimal auth challenge response if requested
                    val authResp = CastMessage(
                        protocolVersion = 0,
                        sourceId = msg.destinationId,
                        destinationId = msg.sourceId,
                        namespace = "urn:x-cast:com.google.cast.tp.deviceauth",
                        payloadType = 0,
                        payloadUtf8 = """{"type":"DEVICE_AUTH"}"""
                    )
                    client.sendMessage(authResp)
                }
            }
        } catch (e: Exception) {
            Log.e("CastV2Server", "Error parsing message: ${e.message}", e)
        }
    }

    private fun sendReceiverStatus(destinationId: String, client: ClientSession, requestId: Int) {
        val root = JSONObject().apply {
            put("type", "RECEIVER_STATUS")
            put("requestId", requestId)

            val statusObj = JSONObject().apply {
                val volumeObj = JSONObject().apply {
                    put("level", currentVolume)
                    put("muted", isMuted)
                    put("stepInterval", 0.05)
                }
                put("volume", volumeObj)

                val applications = JSONArray()
                if (currentSessionId != null) {
                    val app = JSONObject().apply {
                        put("appId", currentAppId)
                        put("displayName", deviceName)
                        put("isIdleScreen", false)
                        put("sessionId", currentSessionId)
                        put("statusText", "LightCast Running")
                        put("transportId", currentSessionId)
                        val nsArray = JSONArray().apply {
                            put(JSONObject().apply { put("name", "urn:x-cast:com.google.cast.media") })
                            put(JSONObject().apply { put("name", "urn:x-cast:com.google.cast.tp.connection") })
                        }
                        put("namespaces", nsArray)
                    }
                    applications.put(app)
                }
                put("applications", applications)
                put("userEq", JSONObject())
            }
            put("status", statusObj)
        }

        val resp = CastMessage(
            protocolVersion = 0,
            sourceId = "receiver-0",
            destinationId = destinationId,
            namespace = "urn:x-cast:com.google.cast.receiver",
            payloadType = 0,
            payloadUtf8 = root.toString()
        )
        client.sendMessage(resp)
    }

    private fun sendCurrentMediaStatus(destinationId: String, client: ClientSession, requestId: Int, overrideState: String?) {
        val state = listener.onGetMediaStatus()
        sendMediaStatus(
            destinationId = destinationId,
            client = client,
            requestId = requestId,
            playerState = overrideState ?: if (state.state == "playing") "PLAYING" else if (state.state == "paused") "PAUSED" else "IDLE",
            contentId = "",
            title = state.title,
            contentType = "video/mp4"
        )
    }

    private fun sendMediaStatus(
        destinationId: String,
        client: ClientSession,
        requestId: Int,
        playerState: String,
        contentId: String,
        title: String,
        contentType: String
    ) {
        val playback = listener.onGetMediaStatus()
        val curTime = playback.currentTime
        val dur = playback.duration

        val root = JSONObject().apply {
            put("type", "MEDIA_STATUS")
            put("requestId", requestId)

            val statusArray = JSONArray()
            val mediaStatus = JSONObject().apply {
                put("mediaSessionId", currentMediaSessionId)
                put("playbackRate", 1)
                put("playerState", playerState)
                put("currentTime", curTime)
                put("supportedMediaCommands", 274447) // Play, Pause, Seek, Stream Volume

                val volumeObj = JSONObject().apply {
                    put("level", currentVolume)
                    put("muted", isMuted)
                }
                put("volume", volumeObj)

                val media = JSONObject().apply {
                    put("contentId", contentId)
                    put("contentType", contentType)
                    put("streamType", "BUFFERED")
                    put("duration", dur)

                    val metadata = JSONObject().apply {
                        put("metadataType", 0)
                        put("title", title)
                    }
                    put("metadata", metadata)
                }
                put("media", media)
            }
            statusArray.put(mediaStatus)
            put("status", statusArray)
        }

        val resp = CastMessage(
            protocolVersion = 0,
            sourceId = "receiver-0",
            destinationId = destinationId,
            namespace = "urn:x-cast:com.google.cast.media",
            payloadType = 0,
            payloadUtf8 = root.toString()
        )
        client.sendMessage(resp)
    }

    private class ClientSession(
        private val socket: Socket,
        private val dos: DataOutputStream
    ) {
        @Synchronized
        fun sendMessage(msg: CastMessage) {
            try {
                val bytes = msg.toByteArray()
                dos.writeInt(bytes.size)
                dos.write(bytes)
                dos.flush()
            } catch (e: Exception) {
                Log.e("CastV2Server", "Error sending message: ${e.message}")
            }
        }

        fun close() {
            try {
                socket.close()
            } catch (_: Exception) {}
        }
    }
}
