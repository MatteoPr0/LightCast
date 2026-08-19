package com.lightcast.receiver.cast

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class CastMdnsServer(
    private val context: Context,
    private val deviceName: String,
    private val castPort: Int = 8009
) {
    private var isRunning = false
    private val executor = Executors.newCachedThreadPool()
    private var scheduler: ScheduledExecutorService? = null
    private var socket: MulticastSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    fun start() {
        if (isRunning) return
        isRunning = true

        executor.execute {
            try {
                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                multicastLock = wifiManager.createMulticastLock("LightCastMdnsLock").apply {
                    setReferenceCounted(true)
                    acquire()
                }

                val group = InetAddress.getByName("224.0.0.251")
                val wlanIface = getActiveWlanInterface()

                socket = MulticastSocket(5353).apply {
                    reuseAddress = true
                    timeToLive = 255
                    try {
                        loopbackMode = true
                    } catch (_: Exception) {}

                    if (wlanIface != null) {
                        try {
                            networkInterface = wlanIface
                            joinGroup(InetSocketAddress(group, 5353), wlanIface)
                            Log.d("CastMdnsServer", "Joined 224.0.0.251 on interface ${wlanIface.name}")
                        } catch (e: Exception) {
                            Log.w("CastMdnsServer", "joinGroup with iface error: ${e.message}")
                            joinGroup(group)
                        }
                    } else {
                        joinGroup(group)
                    }
                }

                Log.d("CastMdnsServer", "mDNS Multicast Server listening on 224.0.0.251:5353")

                // Start periodic beacon every 4 seconds to populate sender caches
                scheduler = Executors.newSingleThreadScheduledExecutor().apply {
                    scheduleWithFixedDelay({
                        if (isRunning) {
                            try {
                                val localIp = getLocalIpAddress()
                                val responseData = buildMdnsResponse(deviceName, localIp, castPort)
                                val beaconPacket = DatagramPacket(responseData, responseData.size, group, 5353)
                                socket?.send(beaconPacket)
                            } catch (_: Exception) {}
                        }
                    }, 1, 4, TimeUnit.SECONDS)
                }

                val buffer = ByteArray(2048)
                while (isRunning) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket?.receive(packet) ?: break

                    if (packet.length < 12) continue
                    val queryBytes = ByteArray(packet.length)
                    System.arraycopy(packet.data, packet.offset, queryBytes, 0, packet.length)

                    if (isCastQuery(queryBytes)) {
                        val localIp = getLocalIpAddress()
                        val requestedSubtypes = extractCastSubtypes(queryBytes)

                        val responseData = buildMdnsResponse(
                            deviceName,
                            localIp,
                            castPort,
                            requestedSubtypes
                        )

                        try {
                            val multicastPacket = DatagramPacket(responseData, responseData.size, group, 5353)
                            socket?.send(multicastPacket)
                        } catch (_: Exception) {}

                        try {
                            val unicastPacket = DatagramPacket(responseData, responseData.size, packet.address, packet.port)
                            socket?.send(unicastPacket)
                        } catch (_: Exception) {}

                        Log.d(
                            "CastMdnsServer",
                            "Responded to Cast mDNS query from " +
                                "${packet.address.hostAddress}:${packet.port} " +
                                "subtypes=${requestedSubtypes.joinToString()}"
                        )
                    }
                }
            } catch (e: Exception) {
                if (isRunning) {
                    Log.e("CastMdnsServer", "mDNS error: ${e.message}")
                }
            }
        }
    }

    fun stop() {
        isRunning = false
        try {
            scheduler?.shutdownNow()
        } catch (_: Exception) {}
        try {
            socket?.close()
        } catch (_: Exception) {}
        try {
            if (multicastLock?.isHeld == true) multicastLock?.release()
        } catch (_: Exception) {}
        executor.shutdownNow()
    }

    private fun isCastQuery(data: ByteArray): Boolean {
        if (data.size < 12) return false
        val flags = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
        val isQuery = (flags and 0x8000) == 0
        if (!isQuery) return false

        val str = String(data, Charsets.ISO_8859_1)
        return str.contains("_googlecast") ||
                str.contains("LightCast") ||
                str.contains("_display") ||
                str.contains("_sub") ||
                str.contains("_dns-sd") ||
                str.contains("_fb_")
    }

    private fun buildMdnsResponse(
        name: String,
        ip: String,
        port: Int,
        subtypes: List<String> = emptyList()
    ): ByteArray {
        val bos = ByteArrayOutputStream()
        val dos = DataOutputStream(bos)

        val serviceType = "_googlecast._tcp.local"
        val safeName = name.replace(" ", "-")
        val instanceName = "$safeName.$serviceType"
        val hostName = "$safeName.local"

        // Header
        dos.writeShort(0x0000) // Transaction ID
        dos.writeShort(0x8400) // Flags: Response + Authoritative
        dos.writeShort(0x0000) // Questions
        dos.writeShort(1 + subtypes.size) // Answers: base PTR + requested subtype PTRs
        dos.writeShort(0x0000) // Authority
        dos.writeShort(0x0003) // Additional: 3 (SRV, TXT, A Records)

        // 1. PTR Record: _googlecast._tcp.local -> instanceName (Class 0x0001 per RFC 6762)
        writeDnsName(dos, serviceType)
        dos.writeShort(12) // Type: PTR
        dos.writeShort(0x0001) // Class: IN (Shared, no flush bit)
        dos.writeInt(120) // TTL: 120s
        val ptrData = ByteArrayOutputStream()
        writeDnsName(DataOutputStream(ptrData), instanceName)
        val ptrBytes = ptrData.toByteArray()
        dos.writeShort(ptrBytes.size)
        dos.write(ptrBytes)

        // PTR dinamici per i subtype richiesti dal sender.
        // Esempio:
        // _B79F1B0A._sub._googlecast._tcp.local
        //   -> LightCast-TV._googlecast._tcp.local
        for (subtype in subtypes.distinct()) {
            writeDnsName(dos, subtype)
            dos.writeShort(12)       // PTR
            dos.writeShort(0x0001)   // IN, shared
            dos.writeInt(120)

            val subtypePtrData = ByteArrayOutputStream()
            writeDnsName(
                DataOutputStream(subtypePtrData),
                instanceName
            )

            val subtypePtrBytes = subtypePtrData.toByteArray()
            dos.writeShort(subtypePtrBytes.size)
            dos.write(subtypePtrBytes)
        }

        // 2. SRV Record: instanceName -> hostName:port
        writeDnsName(dos, instanceName)
        dos.writeShort(33) // Type: SRV
        dos.writeShort(0x8001) // Class: IN, Flush Cache
        dos.writeInt(120)
        val srvData = ByteArrayOutputStream()
        val srvDos = DataOutputStream(srvData)
        srvDos.writeShort(0) // Priority
        srvDos.writeShort(0) // Weight
        srvDos.writeShort(port) // Port (8009)
        writeDnsName(srvDos, hostName)
        val srvBytes = srvData.toByteArray()
        dos.writeShort(srvBytes.size)
        dos.write(srvBytes)

        // 3. TXT Record: instanceName -> attributes
        writeDnsName(dos, instanceName)
        dos.writeShort(16) // Type: TXT
        dos.writeShort(0x8001)
        dos.writeInt(120)
        val txtAttrs = listOf(
            "id=f3b4c10a4a821e90b8f041235b849201",
            "cd=B6204C63ECA2F6E3B87A84B0F77DE0F4",
            "rm=",
            "ve=05",
            "md=Chromecast",
            "ic=/setup/icon.png",
            "fn=$name",
            "ca=4101",
            "st=0",
            "rs=",
            "bs=FA8FCA7568DE",
            "nf=1"
        )
        val txtData = ByteArrayOutputStream()
        for (attr in txtAttrs) {
            val attrBytes = attr.toByteArray(Charsets.UTF_8)
            txtData.write(attrBytes.size)
            txtData.write(attrBytes)
        }
        val txtBytes = txtData.toByteArray()
        dos.writeShort(txtBytes.size)
        dos.write(txtBytes)

        // 4. A Record: hostName -> IP
        writeDnsName(dos, hostName)
        dos.writeShort(1) // Type: A
        dos.writeShort(0x8001)
        dos.writeInt(120)
        val ipParts = ip.split(".")
        dos.writeShort(4)
        for (p in ipParts) {
            dos.writeByte(p.toIntOrNull() ?: 0)
        }

        return bos.toByteArray()
    }

    private fun extractCastSubtypes(data: ByteArray): List<String> {
        if (data.size < 12) return emptyList()

        val questionCount =
            ((data[4].toInt() and 0xff) shl 8) or
                (data[5].toInt() and 0xff)

        var offset = 12
        val result = LinkedHashSet<String>()

        repeat(questionCount) {
            val parsed = readDnsName(data, offset) ?: return@repeat
            val qName = parsed.first
            offset = parsed.second

            // QTYPE + QCLASS
            if (offset + 4 > data.size) return@repeat
            offset += 4

            val lower = qName.lowercase()

            if (
                lower.startsWith("_") &&
                lower.endsWith("._sub._googlecast._tcp.local")
            ) {
                result.add(qName)
            }
        }

        return result.toList()
    }

    private fun readDnsName(
        data: ByteArray,
        startOffset: Int
    ): Pair<String, Int>? {
        if (startOffset !in data.indices) return null

        val labels = mutableListOf<String>()

        var offset = startOffset
        var nextOffset = -1
        var jumped = false
        var guard = 0

        while (offset < data.size && guard++ < 128) {
            val length = data[offset].toInt() and 0xff

            // Fine del nome.
            if (length == 0) {
                if (!jumped) {
                    nextOffset = offset + 1
                }

                if (nextOffset < 0) {
                    nextOffset = offset + 1
                }

                return Pair(
                    labels.joinToString("."),
                    nextOffset
                )
            }

            // DNS compression pointer.
            if ((length and 0xc0) == 0xc0) {
                if (offset + 1 >= data.size) return null

                val pointer =
                    ((length and 0x3f) shl 8) or
                        (data[offset + 1].toInt() and 0xff)

                if (pointer !in data.indices) return null

                if (!jumped) {
                    nextOffset = offset + 2
                }

                offset = pointer
                jumped = true
                continue
            }

            // Label normale.
            if ((length and 0xc0) != 0) return null

            offset += 1

            if (offset + length > data.size) return null

            labels.add(
                String(
                    data,
                    offset,
                    length,
                    Charsets.ISO_8859_1
                )
            )

            offset += length

            if (!jumped) {
                nextOffset = offset
            }
        }

        return null
    }

    private fun writeDnsName(dos: DataOutputStream, domain: String) {
        val parts = domain.split(".")
        for (part in parts) {
            if (part.isEmpty()) continue
            val b = part.toByteArray(Charsets.UTF_8)
            dos.writeByte(b.size)
            dos.write(b)
        }
        dos.writeByte(0) // Root
    }

    private fun getActiveWlanInterface(): NetworkInterface? {
        try {
            val iface = NetworkInterface.getByName("wlan0")
            if (iface != null && iface.isUp) return iface

            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val i = interfaces.nextElement()
                if (i.isUp && !i.isLoopback && (i.name.startsWith("wlan") || i.name.startsWith("eth") || i.name.startsWith("en"))) {
                    return i
                }
            }
        } catch (_: Exception) {}
        return null
    }

    private fun getLocalIpAddress(): String {
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
