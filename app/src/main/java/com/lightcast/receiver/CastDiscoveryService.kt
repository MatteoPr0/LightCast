package com.lightcast.receiver

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.IBinder

class CastDiscoveryService : Service() {
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        registerCastService()
        return START_STICKY
    }

    private fun registerCastService() {
        nsdManager = (getSystemService(Context.NSD_SERVICE) as NsdManager)

        val deviceName = android.os.Build.MODEL ?: "LightCast TV"
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "LightCast-$deviceName"
            serviceType = "_googlecast._tcp."
            port = 8080
            setAttribute("id", "lightcast-receiver-01")
            setAttribute("ve", "05")
            setAttribute("md", "LightCast Receiver")
            setAttribute("ic", "/setup/icon.png")
            setAttribute("fn", deviceName)
            setAttribute("ca", "4101")
            setAttribute("st", "0")
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {}
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
        }

        try {
            nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        try {
            registrationListener?.let { nsdManager?.unregisterService(it) }
        } catch (_: Exception) {}
        super.onDestroy()
    }
}
