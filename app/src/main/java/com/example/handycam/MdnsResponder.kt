package com.example.handycam

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket

private const val MDNS_TAG = "MdnsResponder"
private const val MDNS_ADDR = "224.0.0.251"
private const val MDNS_PORT = 5353
private const val SERVICE_TYPE = "_droidcamobs._tcp.local."

/**
 * Raw mDNS responder for DroidCam OBS plugin discovery.
 *
 * The plugin sends a DNS PTR query with a non-zero query ID, then filters responses
 * by that same ID. Android NsdManager always responds with ID=0 which gets filtered out.
 * This class listens on 224.0.0.251:5353 and echoes the query ID back in responses.
 */
class MdnsResponder(private val context: Context, private val streamPort: Int) {

    private val deviceName = android.os.Build.MODEL.replace(" ", "_")
    @Volatile private var socket: MulticastSocket? = null
    private var thread: Thread? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    fun start() {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("handycam_mdns").apply {
            setReferenceCounted(true)
            acquire()
        }
        try {
            val mdnsGroup = InetAddress.getByName(MDNS_ADDR)
            // reuseAddress must be set BEFORE bind, so use null constructor then bind manually
            socket = MulticastSocket(null).apply {
                reuseAddress = true
                bind(java.net.InetSocketAddress(MDNS_PORT))
                timeToLive = 255
                val wifiIface = getWifiInterface()
                if (wifiIface != null) {
                    joinGroup(java.net.InetSocketAddress(mdnsGroup, MDNS_PORT), wifiIface)
                    Log.i(MDNS_TAG, "Joined multicast on ${wifiIface.name}")
                } else {
                    joinGroup(mdnsGroup)
                    Log.w(MDNS_TAG, "Joined multicast on default interface")
                }
            }
            thread = Thread { listen() }.apply {
                isDaemon = true
                name = "MdnsResponder"
                start()
            }
            Log.i(MDNS_TAG, "Started, device='$deviceName' port=$streamPort")
        } catch (e: Exception) {
            Log.e(MDNS_TAG, "Failed to start", e)
        }
    }

    @Suppress("DEPRECATION") // getConnectionInfo deprecated in API 31; fallback handles failure gracefully
    private fun getWifiInterface(): java.net.NetworkInterface? {
        return try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ip = wm.connectionInfo.ipAddress
            if (ip == 0) return null
            val ipBytes = byteArrayOf(
                (ip and 0xFF).toByte(), (ip shr 8 and 0xFF).toByte(),
                (ip shr 16 and 0xFF).toByte(), (ip shr 24 and 0xFF).toByte()
            )
            java.net.NetworkInterface.getByInetAddress(InetAddress.getByAddress(ipBytes))
        } catch (e: Exception) {
            Log.w(MDNS_TAG, "Could not find WiFi interface", e)
            null
        }
    }

    fun stop() {
        thread?.interrupt()
        socket?.close()
        socket = null
        multicastLock?.release()
        multicastLock = null
    }

    private fun listen() {
        val buf = ByteArray(4096)
        val sock = socket ?: return
        while (!Thread.currentThread().isInterrupted) {
            try {
                val packet = DatagramPacket(buf, buf.size)
                sock.receive(packet)
                handlePacket(packet, sock)
            } catch (e: Exception) {
                if (!Thread.currentThread().isInterrupted) Log.e(MDNS_TAG, "Receive error", e)
                break
            }
        }
    }

    private fun handlePacket(packet: DatagramPacket, sock: MulticastSocket) {
        val data = packet.data
        val len = packet.length
        if (len < 12) return
        val id = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
        val flags = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
        if (flags and 0x8000 != 0) return // skip responses
        val qdCount = ((data[4].toInt() and 0xFF) shl 8) or (data[5].toInt() and 0xFF)
        if (qdCount == 0) return
        var offset = 12
        for (i in 0 until qdCount) {
            val (name, newOffset) = parseName(data, len, offset)
            if (newOffset + 4 > len) return
            val qtype = ((data[newOffset].toInt() and 0xFF) shl 8) or (data[newOffset + 1].toInt() and 0xFF)
            offset = newOffset + 4
            if ((qtype == 12 || qtype == 255) && name.equals(SERVICE_TYPE, ignoreCase = true)) {
                Log.d(MDNS_TAG, "Query from ${packet.address.hostAddress} id=$id")
                sendResponse(id, sock, packet)
                return
            }
        }
    }

    private fun parseName(data: ByteArray, len: Int, start: Int): Pair<String, Int> {
        val sb = StringBuilder()
        var i = start
        while (i < len) {
            val labelLen = data[i].toInt() and 0xFF
            if (labelLen == 0) { i++; break }
            if (labelLen and 0xC0 == 0xC0) {
                val ptr = ((labelLen and 0x3F) shl 8) or (data[i + 1].toInt() and 0xFF)
                val (name, _) = parseName(data, len, ptr)
                if (sb.isNotEmpty()) sb.append('.')
                sb.append(name)
                i += 2; break
            }
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(String(data, i + 1, labelLen))
            i += labelLen + 1
        }
        if (sb.isNotEmpty() && sb.last() != '.') sb.append('.')
        return Pair(sb.toString(), i)
    }

    private fun sendResponse(queryId: Int, sock: MulticastSocket, replyTo: DatagramPacket) {
        val fullName = "$deviceName.$SERVICE_TYPE"
        val hostName = "$deviceName.local."
        val out = ByteArrayOutputStream()
        val dos = DataOutputStream(out)

        // Header: echo back query ID, QR=1 AA=1
        dos.writeShort(queryId)
        dos.writeShort(0x8400)
        dos.writeShort(0)  // QDCOUNT
        dos.writeShort(1)  // ANCOUNT
        dos.writeShort(0)  // NSCOUNT
        dos.writeShort(2)  // ARCOUNT

        // PTR answer
        writeName(dos, SERVICE_TYPE)
        dos.writeShort(12); dos.writeShort(1); dos.writeInt(120)
        val ptrRdata = buildName(fullName)
        dos.writeShort(ptrRdata.size); dos.write(ptrRdata)

        // TXT additional
        writeName(dos, fullName)
        dos.writeShort(16); dos.writeShort(1); dos.writeInt(120)
        val txtEntry = "name=$deviceName".toByteArray(Charsets.UTF_8)
        dos.writeShort(txtEntry.size + 1)
        dos.writeByte(txtEntry.size); dos.write(txtEntry)

        // SRV additional
        writeName(dos, fullName)
        dos.writeShort(33); dos.writeShort(1); dos.writeInt(120)
        val hostBytes = buildName(hostName)
        dos.writeShort(6 + hostBytes.size)
        dos.writeShort(0); dos.writeShort(0)  // priority, weight
        dos.writeShort(streamPort)
        dos.write(hostBytes)

        val bytes = out.toByteArray()
        // Send unicast directly back to querier (plugin uses MDNS_UNICAST_RESPONSE flag)
        sock.send(DatagramPacket(bytes, bytes.size, replyTo.address, replyTo.port))
        Log.d(MDNS_TAG, "Sent response for '$deviceName'")
    }

    private fun writeName(dos: DataOutputStream, name: String) = dos.write(buildName(name))

    private fun buildName(name: String): ByteArray {
        val out = ByteArrayOutputStream()
        for (label in name.trimEnd('.').split('.')) {
            val bytes = label.toByteArray(Charsets.UTF_8)
            out.write(bytes.size); out.write(bytes)
        }
        out.write(0)
        return out.toByteArray()
    }
}
