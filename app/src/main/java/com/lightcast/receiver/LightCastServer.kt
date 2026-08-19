package com.lightcast.receiver

import android.content.Context
import android.util.Log
import com.lightcast.receiver.player.TrackInfo
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
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
        fun onGetAudioTracks(): List<TrackInfo>
        fun onGetSubtitleTracks(): List<TrackInfo>
        fun onSelectTrack(type: String, index: Int): String
    }

    var playbackState = PlaybackState()
    var dlnaServer: com.lightcast.receiver.dlna.DlnaServer? = null

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

        if (uri.startsWith("/dlna/")) {
            dlnaServer?.handleHttpRequest(uri, session)?.let {
                addCorsHeaders(it)
                return it
            }
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
                    val deviceName = "LightCast TV"
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
                uri == "/api/tracks" && method == Method.GET -> {
                    val audioList = listener.onGetAudioTracks()
                    val subList = listener.onGetSubtitleTracks()
                    val json = JSONObject().apply {
                        val audioArr = JSONArray()
                        for (t in audioList) {
                            audioArr.put(JSONObject().apply {
                                put("index", t.index)
                                put("name", t.name)
                                put("language", t.language)
                                put("isSelected", t.isSelected)
                            })
                        }
                        put("audio", audioArr)

                        val subArr = JSONArray()
                        for (t in subList) {
                            subArr.put(JSONObject().apply {
                                put("index", t.index)
                                put("name", t.name)
                                put("language", t.language)
                                put("isSelected", t.isSelected)
                            })
                        }
                        put("subtitles", subArr)
                    }
                    newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
                }
                uri == "/api/tracks/select" && method == Method.POST -> {
                    val body = HashMap<String, String>()
                    session.parseBody(body)
                    val postData = body["postData"] ?: ""
                    val json = JSONObject(postData)
                    val type = json.optString("type", "audio")
                    val index = json.optInt("index", 0)
                    val result = listener.onSelectTrack(type, index)
                    newFixedLengthResponse(Response.Status.OK, "application/json", """{"status":"ok","selected":"$result"}""")
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

                    listener.onCastMedia(url, title, type)
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
                    
                    val originalFilename = session.parms["mediaFile"] ?: session.parms["file"] ?: "uploaded_media.mp4"
                    val tempFilePath = files["mediaFile"] ?: files["file"]
                    
                    if (tempFilePath != null) {
                        val tempFile = File(tempFilePath)
                        val ext = originalFilename.substringAfterLast('.', "mp4")
                        val destFile = File(mediaDir, "media_${System.currentTimeMillis()}.$ext")
                        
                        tempFile.copyTo(destFile, overwrite = true)
                        tempFile.delete()

                        val host = session.headers["host"] ?: "127.0.0.1:$serverPort"
                        val fileUrl = "http://$host/media/${destFile.name}"
                        val lowerExt = ext.lowercase()
                        val mimeType = when (lowerExt) {
                            "jpg", "jpeg" -> "image/jpeg"
                            "png" -> "image/png"
                            "gif" -> "image/gif"
                            "webp" -> "image/webp"
                            "mp3" -> "audio/mpeg"
                            "mkv" -> "video/matroska"
                            "webm" -> "video/webm"
                            else -> "video/mp4"
                        }

                        listener.onCastMedia(fileUrl, originalFilename, mimeType)
                        newFixedLengthResponse(Response.Status.OK, "application/json", """{"status":"ok","url":"$fileUrl"}""")
                    } else {
                        newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", """{"error":"No file uploaded"}""")
                    }
                }
                uri.startsWith("/media/") -> {
                    val filename = uri.removePrefix("/media/")
                    val file = File(mediaDir, filename)
                    if (file.exists() && file.isFile) {
                        val ext = filename.substringAfterLast('.', "")
                        val mime = when (ext.lowercase()) {
                            "mp4" -> "video/mp4"
                            "mkv" -> "video/x-matroska"
                            "webm" -> "video/webm"
                            "mp3" -> "audio/mpeg"
                            "jpg", "jpeg" -> "image/jpeg"
                            "png" -> "image/png"
                            "gif" -> "image/gif"
                            "webp" -> "image/webp"
                            else -> "application/octet-stream"
                        }
                        serveFileWithRange(session, file, mime)
                    } else {
                        newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "File not found")
                    }
                }
                else -> {
                    newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404 Not Found")
                }
            }

            addCorsHeaders(response)
            return response
        } catch (e: Exception) {
            Log.e("LightCastServer", "Error serving $uri: ${e.message}", e)
            val err = newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Internal Server Error: ${e.message}")
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

    private fun serveFileWithRange(session: IHTTPSession, file: File, mimeType: String): Response {
        val fileLength = file.length()
        val rangeHeader = session.headers["range"]

        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            val range = rangeHeader.substring(6).split("-")
            val start = range[0].toLongOrNull() ?: 0L
            val end = if (range.size > 1 && range[1].isNotEmpty()) {
                range[1].toLongOrNull() ?: (fileLength - 1)
            } else {
                fileLength - 1
            }

            if (start >= fileLength || end >= fileLength || start > end) {
                val res = newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, "text/plain", "")
                res.addHeader("Content-Range", "bytes */$fileLength")
                return res
            }

            val contentLength = end - start + 1
            val fis = FileInputStream(file)
            fis.skip(start)

            val res = newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, mimeType, fis, contentLength)
            res.addHeader("Content-Range", "bytes $start-$end/$fileLength")
            res.addHeader("Content-Length", contentLength.toString())
            res.addHeader("Accept-Ranges", "bytes")
            return res
        }

        val fis = FileInputStream(file)
        val res = newFixedLengthResponse(Response.Status.OK, mimeType, fis, fileLength)
        res.addHeader("Content-Length", fileLength.toString())
        res.addHeader("Accept-Ranges", "bytes")
        return res
    }

    private fun addCorsHeaders(response: Response) {
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS, HEAD")
        response.addHeader("Access-Control-Allow-Headers", "Origin, X-Requested-With, Content-Type, Accept, Range")
    }
}
