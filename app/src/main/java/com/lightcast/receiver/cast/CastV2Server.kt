package com.lightcast.receiver.cast

import android.util.Log
import com.lightcast.receiver.PlaybackState
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Socket
import java.security.Signature
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.net.ssl.SSLException
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

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
    
    private var currentSessionId: String = "9bdf5c17-1eed-4c8b-ad70-c004f83bf947"
    private var currentAppId: String = "E8C28D3C" // Backdrop
    private var currentDisplayName: String = "Backdrop"
    private var isIdle = true
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
                serverSocket?.wantClientAuth = false

                // Cast compatibility test:
                // WVC offers TLS 1.3 + TLS 1.2, but currently closes
                // immediately after our TLS 1.3 server flight.
                // Force TLS 1.2 so we can isolate that behavior.
                serverSocket?.enabledProtocols = arrayOf("TLSv1.2")

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
            val sslSocket = socket as? SSLSocket
            val remote = "${socket.inetAddress.hostAddress}:${socket.port}"

            Log.d(
                "CastV2Server",
                "TCP ACCEPTED from $remote socketClass=${socket.javaClass.simpleName}"
            )

            if (sslSocket != null) {
                Log.d(
                    "CastV2Server",
                    "TLS HANDSHAKE START from $remote " +
                        "enabledProtocols=${sslSocket.enabledProtocols.joinToString()} " +
                        "enabledCiphers=${sslSocket.enabledCipherSuites.size}"
                )

                try {
                    sslSocket.startHandshake()
                } catch (e: SSLException) {
                    Log.e(
                        "CastV2Server",
                        "TLS HANDSHAKE FAILED from $remote " +
                            "${e.javaClass.simpleName}: ${e.message}",
                        e
                    )
                    return
                }

                Log.d(
                    "CastV2Server",
                    "TLS CONNECTED from $remote " +
                        "protocol=${sslSocket.session.protocol} " +
                        "cipher=${sslSocket.session.cipherSuite}"
                )
            }

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
            Log.d(
                "CastV2Server",
                "Client disconnected from ${socket.inetAddress.hostAddress}:${socket.port} " +
                    "${e.javaClass.simpleName}: ${e.message}"
            )
        } catch (e: Exception) {
            Log.e("CastV2Server", "Client error: ${e.message}")
        } finally {
            clientSession?.close()
        }
    }

    private fun handleCastMessage(msg: CastMessage, client: ClientSession) {
        Log.d(
            "CastV2Server",
            "RX namespace=${msg.namespace} source=${msg.sourceId} " +
                "dest=${msg.destinationId} payloadType=${msg.payloadType}"
        )

        if (msg.namespace == "urn:x-cast:com.google.cast.tp.deviceauth") {
            handleDeviceAuth(msg, client)
            return
        }

        val payloadStr = msg.payloadUtf8 ?: return
        Log.d("CastV2Server", "Received [${msg.namespace}] (dest: ${msg.destinationId}) from ${msg.sourceId}: $payloadStr")

        try {
            val json = JSONObject(payloadStr)
            val type = json.optString("type", "")
            val requestId = json.optInt("requestId", 0)

            when (msg.namespace) {
                "urn:x-cast:com.google.cast.tp.connection" -> {
                    if (type == "CONNECT") {
                        // Accept connection to receiver-0 or to transportId
                        val sysMsg = CastMessage(
                            protocolVersion = 0,
                            sourceId = "SystemSender",
                            destinationId = msg.sourceId,
                            namespace = "urn:x-cast:com.google.cast.system",
                            payloadType = 0,
                            payloadUtf8 = """{"type":"ready","launchingSenderId":"${msg.sourceId}"}"""
                        )
                        client.sendMessage(sysMsg)

                        val connectEvent = CastMessage(
                            protocolVersion = 0,
                            sourceId = "SystemSender",
                            destinationId = msg.sourceId,
                            namespace = "urn:x-cast:com.google.cast.system",
                            payloadType = 0,
                            payloadUtf8 = """{"type":"senderconnected","senderId":"${msg.sourceId}"}"""
                        )
                        client.sendMessage(connectEvent)

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

                "urn:x-cast:com.google.cast.setup" -> {
                    if (type == "eureka_info") {
                        sendEurekaInfo(
                            destinationId = msg.sourceId,
                            client = client,
                            requestId = json.optInt("request_id", 0)
                        )
                    }
                }

                "urn:x-cast:com.google.cast.receiver" -> {
                    when (type) {
                        "GET_APP_AVAILABILITY" -> {
                            sendAppAvailability(
                                destinationId = msg.sourceId,
                                client = client,
                                requestId = requestId,
                                appIds = json.optJSONArray("appId")
                            )
                        }
                        "GET_STATUS" -> {
                            sendReceiverStatus(msg.sourceId, client, requestId)
                        }
                        "LAUNCH" -> {
                            currentAppId = json.optString("appId", "CC1AD845")
                            currentDisplayName = if (currentAppId == "CC1AD845") "Default Media Receiver" else deviceName
                            currentSessionId = UUID.randomUUID().toString()
                            isIdle = false

                            val readyMsg = CastMessage(
                                protocolVersion = 0,
                                sourceId = "SystemSender",
                                destinationId = msg.sourceId,
                                namespace = "urn:x-cast:com.google.cast.system",
                                payloadType = 0,
                                payloadUtf8 = """{"type":"ready","launchingSenderId":"${msg.sourceId}"}"""
                            )
                            client.sendMessage(readyMsg)

                            sendReceiverStatus(msg.sourceId, client, requestId)
                        }
                        "STOP" -> {
                            currentAppId = "E8C28D3C"
                            currentDisplayName = "Backdrop"
                            isIdle = true
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
                                isIdle = false
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
            }
        } catch (e: Exception) {
            Log.e("CastV2Server", "Error parsing message: ${e.message}", e)
        }
    }

    private fun handleDeviceAuth(msg: CastMessage, client: ClientSession) {
        try {
            val payload = msg.payloadBinary

            if (payload == null) {
                Log.w("CastV2Server", "AUTH: payload binary mancante")
                return
            }

            val challenge = CastMessage.parseAuthChallenge(payload)

            if (challenge == null) {
                Log.w(
                    "CastV2Server",
                    "AUTH: impossibile leggere DeviceAuthMessage.challenge"
                )
                return
            }

            val privateKey = CastCertificateGenerator.privateKey
            val certDer = CastCertificateGenerator.certificateDer

            if (privateKey == null || certDer == null) {
                Log.e("CastV2Server", "AUTH: chiave/certificato TLS non disponibili")
                return
            }

            val nonce = challenge.senderNonce

            val nonceHex = nonce.joinToString("") {
                "%02x".format(it.toInt() and 0xff)
            }

            Log.d(
                "CastV2Server",
                "AUTH CHALLENGE RECEIVED nonceBytes=${nonce.size} " +
                    "nonce=$nonceHex sigAlg=${challenge.signatureAlgorithm} " +
                    "hashAlg=${challenge.hashAlgorithm}"
            )

            // Proto default = RSASSA_PKCS1v15 (1).
            val signatureAlgorithm =
                if (challenge.signatureAlgorithm == 0) 1
                else challenge.signatureAlgorithm

            if (signatureAlgorithm != 1) {
                Log.w(
                    "CastV2Server",
                    "AUTH: signature algorithm $signatureAlgorithm non ancora supportato"
                )
                return
            }

            val hashAlgorithm = challenge.hashAlgorithm

            val javaSignatureAlgorithm = when (hashAlgorithm) {
                0 -> "SHA1withRSA"
                1 -> "SHA256withRSA"
                else -> {
                    Log.w(
                        "CastV2Server",
                        "AUTH: hash algorithm $hashAlgorithm non supportato"
                    )
                    return
                }
            }

            // Chromium verifica la firma su:
            // sender_nonce || DER del certificato TLS peer.
            val signatureInput = ByteArray(nonce.size + certDer.size)

            nonce.copyInto(
                destination = signatureInput,
                destinationOffset = 0
            )

            certDer.copyInto(
                destination = signatureInput,
                destinationOffset = nonce.size
            )

            val signature = Signature.getInstance(javaSignatureAlgorithm).apply {
                initSign(privateKey)
                update(signatureInput)
            }.sign()

            val authRespBytes = CastMessage.buildAuthResponse(
                signature = signature,
                certDer = certDer,
                senderNonce = nonce,
                signatureAlgorithm = signatureAlgorithm,
                hashAlgorithm = hashAlgorithm
            )

            val authMsg = CastMessage(
                protocolVersion = 0,
                sourceId = msg.destinationId.ifBlank { "receiver-0" },
                destinationId = msg.sourceId,
                namespace = "urn:x-cast:com.google.cast.tp.deviceauth",
                payloadType = 1,
                payloadBinary = authRespBytes
            )

            client.sendMessage(authMsg)

            Log.d(
                "CastV2Server",
                "AUTH RESPONSE SENT signatureBytes=${signature.size} " +
                    "certBytes=${certDer.size} responseBytes=${authRespBytes.size}"
            )
        } catch (e: Exception) {
            Log.e(
                "CastV2Server",
                "Error handling device auth: ${e.message}",
                e
            )
        }
    }

    private fun sendAppAvailability(
        destinationId: String,
        client: ClientSession,
        requestId: Int,
        appIds: JSONArray?
    ) {
        val availability = JSONObject()

        if (appIds != null) {
            for (i in 0 until appIds.length()) {
                val appId = appIds.optString(i, "")
                if (appId.isNotEmpty()) {
                    // LightCast accetta LAUNCH per gli appId richiesti dal sender.
                    availability.put(appId, "APP_AVAILABLE")
                }
            }
        }

        val payload = JSONObject().apply {
            put("responseType", "GET_APP_AVAILABILITY")
            put("requestId", requestId)
            put("availability", availability)
        }

        client.sendMessage(
            CastMessage(
                protocolVersion = 0,
                sourceId = "receiver-0",
                destinationId = destinationId,
                namespace = "urn:x-cast:com.google.cast.receiver",
                payloadType = 0,
                payloadUtf8 = payload.toString()
            )
        )

        Log.d(
            "CastV2Server",
            "TX GET_APP_AVAILABILITY requestId=$requestId availability=$availability"
        )
    }

    private fun sendEurekaInfo(
        destinationId: String,
        client: ClientSession,
        requestId: Int
    ) {
        val data = JSONObject().apply {
            put("version", 12)
            put("name", deviceName)

            put("device_info", JSONObject().apply {
                put("ssdp_udn", "uuid:f3b4c10a-4a82-1e90-b8f0-41235b849201")
                put("manufacturer", "LightCast")
                put("product_name", "LightCast TV")
            })

            put("build_info", JSONObject().apply {
                put("build_type", 2)
                put("cast_build_revision", "1.0")
                put("system_build_number", "LightCast")
            })
        }

        val payload = JSONObject().apply {
            put("type", "eureka_info")
            put("request_id", requestId)
            put("response_code", 200)
            put("response_string", "OK")
            put("data", data)
        }

        client.sendMessage(
            CastMessage(
                protocolVersion = 0,
                sourceId = "receiver-0",
                destinationId = destinationId,
                namespace = "urn:x-cast:com.google.cast.setup",
                payloadType = 0,
                payloadUtf8 = payload.toString()
            )
        )

        Log.d(
            "CastV2Server",
            "TX eureka_info requestId=$requestId"
        )
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
                val app = JSONObject().apply {
                    put("appId", currentAppId)
                    put("displayName", currentDisplayName)
                    put("isIdleScreen", isIdle)
                    put("sessionId", currentSessionId)
                    put("statusText", if (isIdle) "Pronto alla trasmissione" else "Riproduzione in corso")
                    put("transportId", currentSessionId)
                    val nsArray = JSONArray().apply {
                        put(JSONObject().apply { put("name", "urn:x-cast:com.google.cast.media") })
                        put(JSONObject().apply { put("name", "urn:x-cast:com.google.cast.cac") })
                        put(JSONObject().apply { put("name", "urn:x-cast:com.google.cast.system") })
                        put(JSONObject().apply { put("name", "urn:x-cast:com.google.cast.tp.connection") })
                        put(JSONObject().apply { put("name", "urn:x-cast:com.google.cast.tp.heartbeat") })
                    }
                    put("namespaces", nsArray)
                }
                applications.put(app)
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
            sourceId = currentSessionId, // CRITICAL: Respond with the active session transportId
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
