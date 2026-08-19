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

class CastDiscoveryService : Service() {
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        acquireLocks()
        registerCastService()
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

    override fun onDestroy() {
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
