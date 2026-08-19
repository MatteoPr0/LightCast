package com.lightcast.receiver

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.util.concurrent.Executors

class CastDiscoveryService : Service() {
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var ssdpSocket: MulticastSocket? = null
    private var isSsdpRunning = false
    private val ssdpExecutor = Executors.newSingleThreadExecutor()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        acquireLocks()
        registerCastService()
        startSsdpResponder()
        return START_STICKY
    }

    private fun acquireLocks() {
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifiManager.createMulticastLock("LightCastMulticastLock").apply {
                setReferenceCounted(true)
                acquire()
            }
            Log.d("CastDiscoveryService", "MulticastLock acquired successfully")
        } catch (e: Exception) {
            Log.e("CastDiscoveryService", "Error acquiring MulticastLock: ${e.message}")
        }

        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LightCast:DiscoveryWakeLock").apply {
                setReferenceCounted(false)
                acquire(24 * 60 * 60 * 1000L) // 24h
            }
        } catch (_: Exception) {}
    }

    private fun registerCastService() {
        nsdManager = (getSystemService(Context.NSD_SERVICE) as NsdManager)

        val deviceName = android.os.Build.MODEL ?: "LightCast TV"
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "LightCast-$deviceName"
            serviceType = "_googlecast._tcp"
            port = 8009
            setAttribute("id", "f3b4c10a4a821e90b8f041235b849201")
            setAttribute("ve", "05")
            setAttribute("md", "LightCast Receiver")
            setAttribute("ic", "/setup/icon.png")
            setAttribute("fn", deviceName)
            setAttribute("ca", "4101")
            setAttribute("st", "0")
            setAttribute("rs", "")
            setAttribute("bs", "FA8FCA7568DE")
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                Log.d("CastDiscoveryService", "Google Cast mDNS service registered: ${serviceInfo.serviceName}")
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e("CastDiscoveryService", "mDNS registration failed: errorCode=$errorCode")
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                Log.d("CastDiscoveryService", "mDNS service unregistered")
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e("CastDiscoveryService", "mDNS unregistration failed: errorCode=$errorCode")
            }
        }

        try {
            nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            Log.e("CastDiscoveryService", "Error in registerService: ${e.message}")
        }
    }

    private fun startSsdpResponder() {
        if (isSsdpRunning) return
        isSsdpRunning = true

        ssdpExecutor.execute {
            try {
                val group = InetAddress.getByName("239.255.255.250")
                ssdpSocket = MulticastSocket(1900).apply {
                    reuseAddress = true
                    joinGroup(group)
                }

                val buffer = ByteArray(2048)
                while (isSsdpRunning) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    ssdpSocket?.receive(packet) ?: break

                    val msg = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    if (msg.contains("M-SEARCH", true)) {
                        val isDial = msg.contains("urn:dial-multicast:service:dial:1", true)
                        val isSsdpAll = msg.contains("ssdp:all", true)
                        val isCast = msg.contains("googlecast", true) || msg.contains("dial", true)

                        if (isDial || isSsdpAll || isCast) {
                            val localIp = packet.address.hostAddress ?: "127.0.0.1"
                            val responseStr = "HTTP/1.1 200 OK\r\n" +
                                    "CACHE-CONTROL: max-age=1800\r\n" +
                                    "EXT:\r\n" +
                                    "LOCATION: http://$localIp:8080/dd.xml\r\n" +
                                    "SERVER: Linux/3.0 UPnP/1.0 LightCast/1.0\r\n" +
                                    "ST: urn:dial-multicast:service:dial:1\r\n" +
                                    "USN: uuid:f3b4c10a-4a82-1e90-b8f0-41235b849201::urn:dial-multicast:service:dial:1\r\n" +
                                    "BOOTID.UPNP.ORG: 1\r\n" +
                                    "CONFIGID.UPNP.ORG: 1\r\n\r\n"

                            val resBytes = responseStr.toByteArray(Charsets.UTF_8)
                            val resPacket = DatagramPacket(resBytes, resBytes.size, packet.address, packet.port)
                            ssdpSocket?.send(resPacket)
                        }
                    }
                }
            } catch (_: Exception) {
                // Stopped or socket error
            }
        }
    }

    override fun onDestroy() {
        isSsdpRunning = false
        try {
            ssdpSocket?.close()
        } catch (_: Exception) {}
        ssdpExecutor.shutdownNow()

        try {
            registrationListener?.let { nsdManager?.unregisterService(it) }
        } catch (_: Exception) {}

        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (_: Exception) {}

        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {}

        super.onDestroy()
    }
}
