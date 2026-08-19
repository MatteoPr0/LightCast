package com.lightcast.receiver

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

data class PlaybackState(
    var state: String = "idle",
    var currentTime: Double = 0.0,
    var duration: Double = 0.0,
    var title: String = "",
    var volume: Double = 1.0,
    var isMuted: Boolean = false
)

class LightCastServer(
    val serverPort: Int,
    private val context: Context,
    private val listener: ServerListener
) : NanoHTTPD(serverPort) {

    interface ServerListener {
        fun onCastMedia(url: String, title: String, type: String)
        fun onControlMedia(action: String, value: Any?)
    }

    var playbackState = PlaybackState()

    private val mediaDir by lazy {
        File(context.cacheDir, "cast_media").apply { mkdirs() }
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        if (method == Method.OPTIONS) {
            val res = newFixedLengthResponse(Response.Status.OK, "text/plain", "")
            addCorsHeaders(res)
            return res
        }

        try {
            val response = when {
                uri == "/" || uri == "/index.html" -> {
                    serveAsset("sender.html", "text/html")
                }
                uri == "/qrcode.min.js" -> {
                    serveAsset("qrcode.min.js", "application/javascript")
                }
                uri == "/api/status" && method == Method.GET -> {
                    val json = JSONObject().apply {
                        put("state", playbackState.state)
                        put("currentTime", playbackState.currentTime)
                        put("duration", playbackState.duration)
                        put("title", playbackState.title)
                        put("volume", playbackState.volume)
                        put("isMuted", playbackState.isMuted)
                    }
                    newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
                }
                uri == "/api/cast" && method == Method.POST -> {
                    val body = HashMap<String, String>()
                    session.parseBody(body)
                    val postData = body["postData"] ?: ""
                    val json = JSONObject(postData)
                    val url = json.optString("url", "")
                    val title = json.optString("title", "Cast Stream")
                    val type = json.optString("type", "video/mp4")

                    if (url.isNotEmpty()) {
                        playbackState.title = title
                        playbackState.state = "playing"
                        listener.onCastMedia(url, title, type)
                    }
                    newFixedLengthResponse(Response.Status.OK, "application/json", """{"status":"ok"}""")
                }
                uri == "/api/control" && method == Method.POST -> {
                    val body = HashMap<String, String>()
                    session.parseBody(body)
                    val postData = body["postData"] ?: ""
                    val json = JSONObject(postData)
                    val action = json.optString("action", "")
                    val value = json.opt("value")

                    listener.onControlMedia(action, value)
                    newFixedLengthResponse(Response.Status.OK, "application/json", """{"status":"ok"}""")
                }
                uri == "/api/upload" && method == Method.POST -> {
                    val files = HashMap<String, String>()
                    session.parseBody(files)
                    
                    var savedFileName = ""
                    for ((key, tempFilePath) in files) {
                        if (key == "mediaFile" || key.startsWith("mediaFile")) {
                            val tempFile = File(tempFilePath)
                            val targetFile = File(mediaDir, "stream_${System.currentTimeMillis()}_${tempFile.name}")
                            tempFile.copyTo(targetFile, overwrite = true)
                            savedFileName = targetFile.name
                            break
                        }
                    }

                    if (savedFileName.isNotEmpty()) {
                        val localStreamUrl = "http://127.0.0.1:$serverPort/media/$savedFileName"
                        playbackState.title = "File Locale"
                        playbackState.state = "playing"
                        listener.onCastMedia(localStreamUrl, "File Locale", "video/mp4")
                        newFixedLengthResponse(Response.Status.OK, "application/json", """{"status":"ok","file":"$savedFileName"}""")
                    } else {
                        newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", """{"error":"No file uploaded"}""")
                    }
                }
                uri.startsWith("/media/") -> {
                    val fileName = uri.removePrefix("/media/")
                    val file = File(mediaDir, fileName)
                    if (file.exists()) {
                        serveMediaFile(session, file)
                    } else {
                        newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "File not found")
                    }
                }
                uri == "/setup/eureka_info" -> {
                    val json = JSONObject().apply {
                        put("name", "LightCast")
                        put("device_info", JSONObject().apply {
                            put("manufacturer", "LightCast")
                            put("model_name", "LightCast Receiver")
                        })
                    }
                    newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
                }
                else -> {
                    newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404 Not Found")
                }
            }

            addCorsHeaders(response)
            return response
        } catch (e: Exception) {
            Log.e("LightCastServer", "Error serving request $uri: ${e.message}", e)
            val errResponse = newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Server Error: ${e.message}")
            addCorsHeaders(errResponse)
            return errResponse
        }
    }

    private fun serveAsset(assetName: String, mimeType: String): Response {
        return try {
            val input: InputStream = context.assets.open(assetName)
            newChunkedResponse(Response.Status.OK, mimeType, input)
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Asset not found")
        }
    }

    private fun serveMediaFile(session: IHTTPSession, file: File): Response {
        val rangeHeader = session.headers["range"]
        val fileLength = file.length()
        val mimeType = when (file.extension.lowercase()) {
            "mp4", "m4v" -> "video/mp4"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            "mp3" -> "audio/mpeg"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            else -> "video/mp4"
        }

        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            var rangeFrom = 0L
            var rangeTo = fileLength - 1
            val rangeSpec = rangeHeader.removePrefix("bytes=")
            val parts = rangeSpec.split("-")
            if (parts.isNotEmpty() && parts[0].isNotEmpty()) {
                rangeFrom = parts[0].toLongOrNull() ?: 0L
            }
            if (parts.size > 1 && parts[1].isNotEmpty()) {
                rangeTo = parts[1].toLongOrNull() ?: (fileLength - 1)
            }
            if (rangeTo >= fileLength) rangeTo = fileLength - 1
            val sendLength = rangeTo - rangeFrom + 1

            val fis = FileInputStream(file)
            fis.skip(rangeFrom)

            val res = newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, mimeType, fis, sendLength)
            res.addHeader("Content-Range", "bytes $rangeFrom-$rangeTo/$fileLength")
            res.addHeader("Content-Length", "$sendLength")
            res.addHeader("Accept-Ranges", "bytes")
            return res
        } else {
            val res = newFixedLengthResponse(Response.Status.OK, mimeType, FileInputStream(file), fileLength)
            res.addHeader("Content-Length", "$fileLength")
            res.addHeader("Accept-Ranges", "bytes")
            return res
        }
    }

    private fun addCorsHeaders(response: Response) {
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS, HEAD")
        response.addHeader("Access-Control-Allow-Headers", "Origin, X-Requested-With, Content-Type, Accept, Range")
    }
}
