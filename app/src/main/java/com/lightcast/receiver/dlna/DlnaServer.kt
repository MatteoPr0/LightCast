package com.lightcast.receiver.dlna

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.lightcast.receiver.PlaybackState
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.util.UUID
import java.util.concurrent.Executors
import java.util.regex.Pattern

class DlnaServer(
    private val context: Context,
    private val deviceName: String,
    private val listener: DlnaListener
) {
    interface DlnaListener {
        fun onCastMedia(url: String, title: String, type: String)
        fun onControlMedia(action: String, value: Any?)
        fun onGetMediaStatus(): PlaybackState
    }

    private val uuid = "f3b4c10a-4a82-1e90-b8f0-41235b849201"
    private var isRunning = false
    private val executor = Executors.newCachedThreadPool()
    private var ssdpSocket: MulticastSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    fun start() {
        if (isRunning) return
        isRunning = true

        executor.execute {
            startSsdpListener()
        }
    }

    fun stop() {
        isRunning = false
        try {
            ssdpSocket?.close()
        } catch (_: Exception) {}
        try {
            if (multicastLock?.isHeld == true) multicastLock?.release()
        } catch (_: Exception) {}
        executor.shutdownNow()
    }

    private fun startSsdpListener() {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifiManager.createMulticastLock("LightCastDlnaLock").apply {
                setReferenceCounted(true)
                acquire()
            }

            val group = InetAddress.getByName("239.255.255.250")
            ssdpSocket = MulticastSocket(1900).apply {
                reuseAddress = true
                timeToLive = 4
                try {
                    val iface = NetworkInterface.getByName("wlan0")
                    if (iface != null) {
                        joinGroup(InetSocketAddress(group, 1900), iface)
                    } else {
                        joinGroup(group)
                    }
                } catch (_: Exception) {
                    joinGroup(group)
                }
            }

            Log.d("DlnaServer", "DLNA SSDP Multicast listening on 239.255.255.250:1900")

            val buffer = ByteArray(2048)
            while (isRunning) {
                val packet = DatagramPacket(buffer, buffer.size)
                ssdpSocket?.receive(packet) ?: break

                val msg = String(packet.data, packet.offset, packet.length)
                if (msg.startsWith("M-SEARCH")) {
                    val localIp = getLocalIp()

                    if (msg.contains("dial") || msg.contains("ssdp:all") || msg.contains("rootdevice")) {
                        val dialResp = "HTTP/1.1 200 OK\r\n" +
                                "CACHE-CONTROL: max-age=1800\r\n" +
                                "EXT:\r\n" +
                                "LOCATION: http://$localIp:8008/ssdp/device-desc.xml\r\n" +
                                "SERVER: Linux/3.14.0 UPnP/1.0 LightCast/1.0\r\n" +
                                "ST: urn:dial-multicast:org:service:dial:1\r\n" +
                                "USN: uuid:$uuid::urn:dial-multicast:org:service:dial:1\r\n" +
                                "BOOTID.UPNP.ORG: 1\r\n" +
                                "CONFIGID.UPNP.ORG: 1\r\n" +
                                "WAKEUP: MAC=FA:8F:CA:75:68:DE;Timeout=10\r\n\r\n"

                        val b = dialResp.toByteArray(Charsets.UTF_8)
                        try {
                            ssdpSocket?.send(DatagramPacket(b, b.size, packet.address, packet.port))
                        } catch (_: Exception) {}
                    }

                    if (msg.contains("MediaRenderer") || msg.contains("AVTransport") || msg.contains("ssdp:all")) {
                        val dlnaResp = "HTTP/1.1 200 OK\r\n" +
                                "CACHE-CONTROL: max-age=1800\r\n" +
                                "EXT:\r\n" +
                                "LOCATION: http://$localIp:8080/dlna/device-desc.xml\r\n" +
                                "SERVER: Android/13 UPnP/1.0 LightCast/2.0\r\n" +
                                "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n" +
                                "USN: uuid:$uuid::urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"

                        val b = dlnaResp.toByteArray(Charsets.UTF_8)
                        try {
                            ssdpSocket?.send(DatagramPacket(b, b.size, packet.address, packet.port))
                        } catch (_: Exception) {}
                    }
                }
            }
        } catch (e: Exception) {
            if (isRunning) {
                Log.e("DlnaServer", "SSDP Error: ${e.message}")
            }
        }
    }

    fun handleHttpRequest(uri: String, session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response? {
        val method = session.method

        if (uri == "/dlna/device-desc.xml") {
            val localIp = getLocalIp()
            val xml = """<?xml version="1.0"?>
<root xmlns="urn:schemas-upnp-org:device-1-0">
  <specVersion>
    <major>1</major>
    <minor>0</minor>
  </specVersion>
  <device>
    <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
    <friendlyName>$deviceName (LightCast DLNA)</friendlyName>
    <manufacturer>Google Antigravity</manufacturer>
    <modelName>LightCast MediaRenderer</modelName>
    <modelNumber>2.0</modelNumber>
    <UDN>uuid:$uuid</UDN>
    <serviceList>
      <service>
        <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
        <serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>
        <controlURL>/dlna/avtransport/control</controlURL>
        <eventSubURL>/dlna/avtransport/events</eventSubURL>
        <SCPDURL>/dlna/avtransport/scpd.xml</SCPDURL>
      </service>
      <service>
        <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
        <serviceId>urn:upnp-org:serviceId:RenderingControl</serviceId>
        <controlURL>/dlna/rendering/control</controlURL>
        <eventSubURL>/dlna/rendering/events</eventSubURL>
        <SCPDURL>/dlna/rendering/scpd.xml</SCPDURL>
      </service>
      <service>
        <serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType>
        <serviceId>urn:upnp-org:serviceId:ConnectionManager</serviceId>
        <controlURL>/dlna/connection/control</controlURL>
        <eventSubURL>/dlna/connection/events</eventSubURL>
        <SCPDURL>/dlna/connection/scpd.xml</SCPDURL>
      </service>
    </serviceList>
  </device>
</root>"""
            val res = NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/xml", xml)
            res.addHeader("Application-URL", "http://$localIp:8080/apps/")
            return res
        }

        if (uri == "/dlna/avtransport/scpd.xml" || uri == "/dlna/rendering/scpd.xml" || uri == "/dlna/connection/scpd.xml") {
            val scpd = """<?xml version="1.0"?>
<scpd xmlns="urn:schemas-upnp-org:service-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <actionList></actionList>
  <serviceStateTable></serviceStateTable>
</scpd>"""
            return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/xml", scpd)
        }

        if (uri == "/dlna/avtransport/control" && method == NanoHTTPD.Method.POST) {
            val files = HashMap<String, String>()
            session.parseBody(files)
            val postData = files["postData"] ?: ""
            val soapAction = session.headers["soapaction"] ?: ""

            return handleAvTransportSoap(soapAction, postData)
        }

        if (uri == "/dlna/rendering/control" && method == NanoHTTPD.Method.POST) {
            val files = HashMap<String, String>()
            session.parseBody(files)
            val postData = files["postData"] ?: ""
            val soapAction = session.headers["soapaction"] ?: ""

            return handleRenderingSoap(soapAction, postData)
        }

        if (uri == "/dlna/connection/control" && method == NanoHTTPD.Method.POST) {
            val xml = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:GetProtocolInfoResponse xmlns:u="urn:schemas-upnp-org:service:ConnectionManager:1">
      <Source></Source>
      <Sink>http-get:*:*:*,http-get:*:video/mp4:*,http-get:*:video/x-matroska:*,http-get:*:video/webm:*,http-get:*:application/vnd.apple.mpegurl:*</Sink>
    </u:GetProtocolInfoResponse>
  </s:Body>
</s:Envelope>"""
            return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/xml", xml)
        }

        return null
    }

    private fun handleAvTransportSoap(action: String, body: String): NanoHTTPD.Response {
        Log.d("DlnaServer", "AVTransport Action: $action")

        if (action.contains("SetAVTransportURI")) {
            val uriMatcher = Pattern.compile("<CurrentURI>(.*?)</CurrentURI>", Pattern.DOTALL).matcher(body)
            val metaMatcher = Pattern.compile("<CurrentURIMetaData>(.*?)</CurrentURIMetaData>", Pattern.DOTALL).matcher(body)

            val mediaUrl = if (uriMatcher.find()) uriMatcher.group(1)!!.replace("&amp;", "&") else ""
            var title = "DLNA Stream"
            if (metaMatcher.find()) {
                val rawMeta = metaMatcher.group(1)!!
                val titleMatcher = Pattern.compile("&lt;dc:title&gt;(.*?)&lt;/dc:title&gt;").matcher(rawMeta)
                if (titleMatcher.find()) {
                    title = titleMatcher.group(1)!!
                }
            }

            if (mediaUrl.isNotEmpty()) {
                listener.onCastMedia(mediaUrl, title, "video/mp4")
            }

            val resp = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body><u:SetAVTransportURIResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1"/></s:Body>
</s:Envelope>"""
            return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/xml", resp)
        }

        if (action.contains("Play")) {
            listener.onControlMedia("play", null)
            val resp = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body><u:PlayResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1"/></s:Body>
</s:Envelope>"""
            return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/xml", resp)
        }

        if (action.contains("Pause")) {
            listener.onControlMedia("pause", null)
            val resp = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body><u:PauseResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1"/></s:Body>
</s:Envelope>"""
            return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/xml", resp)
        }

        if (action.contains("Stop")) {
            listener.onControlMedia("stop", null)
            val resp = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body><u:StopResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1"/></s:Body>
</s:Envelope>"""
            return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/xml", resp)
        }

        if (action.contains("GetPositionInfo")) {
            val status = listener.onGetMediaStatus()
            val curTimeStr = formatDuration(status.currentTime)
            val durStr = formatDuration(status.duration)

            val resp = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:GetPositionInfoResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
      <Track>1</Track>
      <TrackDuration>$durStr</TrackDuration>
      <TrackMetaData></TrackMetaData>
      <TrackURI></TrackURI>
      <RelTime>$curTimeStr</RelTime>
      <AbsTime>$curTimeStr</RelTime>
      <RelCount>0</RelCount>
      <AbsCount>0</AbsCount>
    </u:GetPositionInfoResponse>
  </s:Body>
</s:Envelope>"""
            return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/xml", resp)
        }

        if (action.contains("GetTransportInfo")) {
            val status = listener.onGetMediaStatus()
            val stateStr = if (status.state == "playing") "PLAYING" else if (status.state == "paused") "PAUSED_PLAYBACK" else "STOPPED"

            val resp = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:GetTransportInfoResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
      <CurrentTransportState>$stateStr</CurrentTransportState>
      <CurrentTransportStatus>OK</CurrentTransportStatus>
      <CurrentSpeed>1</CurrentSpeed>
    </u:GetTransportInfoResponse>
  </s:Body>
</s:Envelope>"""
            return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/xml", resp)
        }

        val empty = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body/></s:Envelope>"""
        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/xml", empty)
    }

    private fun handleRenderingSoap(action: String, body: String): NanoHTTPD.Response {
        if (action.contains("SetVolume")) {
            val volMatcher = Pattern.compile("<DesiredVolume>(.*?)</DesiredVolume>").matcher(body)
            if (volMatcher.find()) {
                val vol = volMatcher.group(1)!!.toIntOrNull() ?: 100
                listener.onControlMedia("volume", vol / 100.0)
            }
            val resp = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><u:SetVolumeResponse xmlns:u="urn:schemas-upnp-org:service:RenderingControl:1"/></s:Body></s:Envelope>"""
            return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/xml", resp)
        }

        val empty = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body/></s:Envelope>"""
        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/xml", empty)
    }

    private fun formatDuration(seconds: Double): String {
        val totalSec = seconds.toInt()
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return String.format("%02d:%02d:%02d", h, m, s)
    }

    private fun getLocalIp(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        return addr.hostAddress ?: "127.0.0.1"
                    }
                }
            }
        } catch (_: Exception) {}
        return "127.0.0.1"
    }
}
