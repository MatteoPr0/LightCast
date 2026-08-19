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
                uri == "/dd.xml" || uri == "/ssdp/device-desc.xml" -> {
                    val deviceName = android.os.Build.MODEL ?: "LightCast TV"
                    val xml = """<?xml version="1.0"?>
<root xmlns="urn:schemas-upnp-org:device-1-0">
  <specVersion>
    <major>1</major>
    <minor>0</minor>
  </specVersion>
  <device>
    <deviceType>urn:dial-multicast:org:device:dial:1</deviceType>
    <friendlyName>$deviceName</friendlyName>
    <manufacturer>Google Inc.</manufacturer>
    <modelName>LightCast Receiver</modelName>
    <UDN>uuid:f3b4c10a-4a82-1e90-b8f0-41235b849201</UDN>
    <serviceList>
      <service>
        <serviceType>urn:dial-multicast:org:service:dial:1</serviceType>
        <serviceId>urn:dial-multicast:org:serviceId:dial</serviceId>
        <controlURL>/apps</controlURL>
        <eventSubURL></eventSubURL>
        <SCPDURL></SCPDURL>
      </service>
    </serviceList>
  </device>
</root>"""
                    val res = newFixedLengthResponse(Response.Status.OK, "application/xml", xml)
                    res.addHeader("Application-URL", "http://${session.headers["host"] ?: "127.0.0.1:$serverPort"}/apps/")
                    res
                }
                uri.startsWith("/apps/") -> {
                    val appName = uri.removePrefix("/apps/")
                    if (method == Method.GET) {
                        val appXml = """<?xml version="1.0" encoding="UTF-8"?>
<service xmlns="urn:dial-multicast:org:service:dial:1" dialVer="1.7">
  <name>$appName</name>
  <options allowStop="true"/>
  <state>stopped</state>
</service>"""
                        newFixedLengthResponse(Response.Status.OK, "application/xml", appXml)
                    } else if (method == Method.POST) {
                        val res = newFixedLengthResponse(Response.Status.CREATED, "text/plain", "")
                        res.addHeader("Location", "http://${session.headers["host"] ?: "127.0.0.1:$serverPort"}/apps/$appName/run")
                        res
                    } else {
                        newFixedLengthResponse(Response.Status.OK, "text/plain", "OK")
                    }
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
                    
                    val lowerUrl = url.lowercase()
                    val type = when {
                        lowerUrl.endsWith(".jpg") || lowerUrl.endsWith(".jpeg") -> "image/jpeg"
                        lowerUrl.endsWith(".png") -> "image/png"
                        lowerUrl.endsWith(".gif") -> "image/gif"
                        lowerUrl.endsWith(".webp") -> "image/webp"
                        lowerUrl.endsWith(".m3u8") -> "application/x-mpegURL"
                        lowerUrl.endsWith(".mpd") -> "application/dash+xml"
                        lowerUrl.endsWith(".mkv") -> "video/matroska"
                        lowerUrl.endsWith(".mp3") -> "audio/mpeg"
                        else -> json.optString("type", "video/mp4")
                    }

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
                    var detectedMime = "video/mp4"

                    val originalFileName = session.parms["mediaFile"] ?: "video.mp4"
                    val ext = originalFileName.substringAfterLast('.', "mp4").lowercase()

                    detectedMime = when (ext) {
                        "jpg", "jpeg" -> "image/jpeg"
                        "png" -> "image/png"
                        "gif" -> "image/gif"
                        "webp" -> "image/webp"
                        "mkv" -> "video/matroska"
                        "mp4", "m4v" -> "video/mp4"
                        "webm" -> "video/webm"
                        "mp3" -> "audio/mpeg"
                        "flac" -> "audio/flac"
                        else -> "video/mp4"
                    }

                    for ((key, tempFilePath) in files) {
                        if (key == "mediaFile" || key.startsWith("mediaFile")) {
                            val tempFile = File(tempFilePath)
                            val targetFile = File(mediaDir, "media_${System.currentTimeMillis()}.$ext")
                            tempFile.copyTo(targetFile, overwrite = true)
                            savedFileName = targetFile.name
                            break
                        }
                    }

                    if (savedFileName.isNotEmpty()) {
                        val localStreamUrl = "http://127.0.0.1:$serverPort/media/$savedFileName"
                        val displayTitle = originalFileName.substringBeforeLast('.')
                        playbackState.title = displayTitle
                        playbackState.state = "playing"
                        listener.onCastMedia(localStreamUrl, displayTitle, detectedMime)
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
                        put("name", "LightCast TV")
                        put("device_info", JSONObject().apply {
                            put("manufacturer", "Google Inc.")
                            put("model_name", "Eureka Dongle")
                            put("cast_build_revision", "1.56.281627")
                            put("ssdp_udn", "f3b4c10a-4a82-1e90-b8f0-41235b849201")
                            put("mac_address", "FA:8F:CA:75:68:DE")
                        })
                        put("net", JSONObject().apply {
                            put("online", true)
                        })
                        put("version", 8)
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
            val err = newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Internal Error: ${e.message}")
            addCorsHeaders(err)
            return err
        }
    }

    private fun serveAsset(assetName: String, mimeType: String): Response {
        return try {
            val input: InputStream = context.assets.open(assetName)
            newChunkedResponse(Response.Status.OK, mimeType, input)
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Asset not found: $assetName")
        }
    }

    private fun serveMediaFile(session: IHTTPSession, file: File): Response {
        val range = session.headers["range"]
        val fileLength = file.length()
        val mime = when {
            file.name.endsWith(".mp4", true) -> "video/mp4"
            file.name.endsWith(".mkv", true) -> "video/matroska"
            file.name.endsWith(".webm", true) -> "video/webm"
            file.name.endsWith(".mp3", true) -> "audio/mpeg"
            file.name.endsWith(".jpg", true) || file.name.endsWith(".jpeg", true) -> "image/jpeg"
            file.name.endsWith(".png", true) -> "image/png"
            file.name.endsWith(".gif", true) -> "image/gif"
            file.name.endsWith(".webp", true) -> "image/webp"
            else -> "video/mp4"
        }

        if (range != null && range.startsWith("bytes=")) {
            val ranges = range.substring("bytes=".length).split("-")
            val start = ranges[0].toLongOrNull() ?: 0L
            val end = if (ranges.size > 1 && ranges[1].isNotEmpty()) ranges[1].toLongOrNull() ?: (fileLength - 1) else (fileLength - 1)
            val contentLength = end - start + 1

            val fis = FileInputStream(file).apply { skip(start) }
            val res = newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, mime, fis, contentLength)
            res.addHeader("Content-Range", "bytes $start-$end/$fileLength")
            res.addHeader("Accept-Ranges", "bytes")
            res.addHeader("Content-Length", contentLength.toString())
            addCorsHeaders(res)
            return res
        }

        val fis = FileInputStream(file)
        val res = newFixedLengthResponse(Response.Status.OK, mime, fis, fileLength)
        res.addHeader("Accept-Ranges", "bytes")
        res.addHeader("Content-Length", fileLength.toString())
        addCorsHeaders(res)
        return res
    }

    private fun addCorsHeaders(response: Response) {
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS, HEAD")
        response.addHeader("Access-Control-Allow-Headers", "Origin, X-Requested-With, Content-Type, Accept, Range")
    }
}
