package com.lightcast.receiver.cast

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface

class EurekaServer(
    val port: Int = 8008,
    private val deviceName: String,
    private val context: Context,
    private val listener: EurekaListener? = null
) : NanoHTTPD(port) {

    interface EurekaListener {
        fun onDialLaunch(appName: String, data: String)
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
            val localIp = getLocalIpAddress()
            val response = when {
                uri.startsWith("/setup/eureka_info") -> {
                    val json = JSONObject().apply {
                        put("bssid", "fa:8f:ca:75:68:de")
                        put("build_version", "1.56.281627")
                        put("cast_build_revision", "1.56.281627")
                        put("closed_caption", JSONObject())
                        put("connected", true)
                        put("ethernet_connected", false)
                        put("has_update", false)
                        put("hotspot_bssid", "fa:8f:ca:75:68:de")
                        put("ip_address", localIp)
                        put("locale", "it-IT")
                        put("location", JSONObject().apply {
                            put("country_code", "IT")
                            put("latitude", 0)
                            put("longitude", 0)
                        })
                        put("mac_address", "FA:8F:CA:75:68:DE")
                        put("name", deviceName)
                        put("net", JSONObject().apply {
                            put("control_port", 8009)
                            put("ethernet_connected", false)
                            put("ip_address", localIp)
                            put("online", true)
                        })
                        put("noise_level", -90)
                        put("opt_in", JSONObject().apply {
                            put("audio_hdr", false)
                            put("audio_surround_mode", 0)
                            put("crash", false)
                            put("device_analytics", false)
                            put("opencast", true)
                            put("stats", false)
                        })
                        put("public_key", "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAz")
                        put("release_track", "stable-channel")
                        put("setup", JSONObject().apply {
                            put("cast_build_revision", "1.56.281627")
                            put("connected", true)
                            put("ethernet_connected", false)
                            put("ip_address", localIp)
                            put("online", true)
                            put("ssid", "LightCast")
                            put("state", 0)
                        })
                        put("setup_state", 60)
                        put("signal_level", -50)
                        put("ssdp_udn", "f3b4c10a-4a82-1e90-b8f0-41235b849201")
                        put("ssdp_uuid", "f3b4c10a-4a82-1e90-b8f0-41235b849201")
                        put("timezone", "Europe/Rome")
                        put("tos_accepted", true)
                        put("uma_desc", "")
                        put("uptime", 3600.0)
                        put("version", 8)
                        put("wpa_configured", true)
                        put("wpa_id", 0)
                        put("wpa_state", 10)
                    }
                    newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
                }
                uri == "/setup/icon.png" -> {
                    serveAsset("qrcode.min.js", "image/png")
                }
                uri == "/dd.xml" || uri == "/ssdp/device-desc.xml" -> {
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
    <modelName>Eureka Dongle</modelName>
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
                    res.addHeader("Application-URL", "http://$localIp:$port/apps/")
                    res
                }
                uri.startsWith("/apps/") -> {
                    val appName = uri.removePrefix("/apps/").substringBefore('/')
                    if (method == Method.GET) {
                        val appXml = """<?xml version="1.0" encoding="UTF-8"?>
<service xmlns="urn:dial-multicast:org:service:dial:1" dialVer="1.7">
  <name>$appName</name>
  <options allowStop="true"/>
  <state>stopped</state>
  <additionalData>
    <cast:capabilities xmlns:cast="urn:google:cast">video_out,audio_out</cast:capabilities>
  </additionalData>
</service>"""
                        newFixedLengthResponse(Response.Status.OK, "application/xml", appXml)
                    } else if (method == Method.POST) {
                        val files = HashMap<String, String>()
                        session.parseBody(files)
                        val postData = files["postData"] ?: ""
                        Log.d("EurekaServer", "DIAL launch for $appName with data: $postData")
                        listener?.onDialLaunch(appName, postData)

                        val res = newFixedLengthResponse(Response.Status.CREATED, "text/plain", "")
                        res.addHeader("Location", "http://$localIp:$port/apps/$appName/run")
                        res
                    } else {
                        newFixedLengthResponse(Response.Status.OK, "text/plain", "OK")
                    }
                }
                else -> {
                    newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404 Not Found")
                }
            }
            addCorsHeaders(response)
            return response
        } catch (e: Exception) {
            Log.e("EurekaServer", "Error serving $uri: ${e.message}", e)
            val err = newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Internal Error: ${e.message}")
            addCorsHeaders(err)
            return err
        }
    }

    private fun serveAsset(assetName: String, mimeType: String): Response {
        return try {
            val input: java.io.InputStream = context.assets.open(assetName)
            newChunkedResponse(Response.Status.OK, mimeType, input)
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Asset not found")
        }
    }

    private fun addCorsHeaders(response: Response) {
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS, HEAD")
        response.addHeader("Access-Control-Allow-Headers", "Origin, X-Requested-With, Content-Type, Accept, Range")
    }

    private fun getLocalIpAddress(): String {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
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
}
