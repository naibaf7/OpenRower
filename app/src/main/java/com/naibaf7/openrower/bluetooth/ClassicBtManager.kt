package com.naibaf7.openrower.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.UUID

/**
 * Manages a classic Bluetooth SPP connection to an HC-05 (ZS-040) module.
 *
 * ── HC-05 connection quirks ───────────────────────────────────────────────
 * The HC-05 uses Classic Bluetooth (not BLE) and SPP (Serial Port Profile).
 * Android's SDP lookup via createRfcommSocketToServiceRecord often fails on
 * HC-05 modules because their SDP records are incomplete or not cached yet.
 *
 * Connection strategy (in order):
 *   1. createInsecureRfcommSocketToServiceRecord(SPP_UUID) — standard path,
 *      works when SDP records are correctly cached after pairing.
 *   2. Reflection: device.createRfcommSocket(1) — bypasses SDP entirely and
 *      opens RFCOMM channel 1 directly. HC-05 always listens on channel 1.
 *
 * The device must be paired first (default PIN: 1234). After pairing it
 * appears in BluetoothAdapter.getBondedDevices().
 *
 * cancelDiscovery() must be called before connect() — active discovery
 * interferes with RFCOMM connection establishment.
 */
class ClassicBtManager(private val adapter: BluetoothAdapter) {

    companion object {
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val HC05_RFCOMM_CHANNEL = 1
    }

    private var socket: BluetoothSocket? = null

    /**
     * Connect to the given MAC address and return a flow of parsed messages.
     * Must be collected on a coroutine; all IO runs on [Dispatchers.IO].
     */
    fun connect(macAddress: String): Flow<RowingDataParser.Message> = flow {
        val device = adapter.getRemoteDevice(macAddress)

        // Always cancel discovery before connecting — it degrades RFCOMM throughput
        adapter.cancelDiscovery()

        val sock = openSocket(macAddress)
        socket = sock

        val reader = BufferedReader(InputStreamReader(sock.inputStream))
        try {
            var line = reader.readLine()
            while (line != null) {
                val msg = RowingDataParser.parseLine(line.trim())
                if (msg != null) emit(msg)
                line = reader.readLine()
            }
        } finally {
            runCatching { sock.close() }
            socket = null
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Try two strategies to open an RFCOMM socket:
     *   1. Standard insecure SPP UUID lookup (works when SDP is cached).
     *   2. Reflection on channel 1 (always works for HC-05).
     */
    private fun openSocket(macAddress: String): BluetoothSocket {
        val device = adapter.getRemoteDevice(macAddress)

        // Strategy 1: standard insecure SPP socket
        val uuidSocket = runCatching {
            device.createInsecureRfcommSocketToServiceRecord(SPP_UUID).also { it.connect() }
        }.getOrNull()
        if (uuidSocket != null) return uuidSocket

        // Strategy 2: bypass SDP, connect directly to RFCOMM channel 1
        // HC-05 always advertises SPP on channel 1 regardless of SDP records.
        @Suppress("DiscouragedPrivateApi")
        val rfcommMethod = device.javaClass.getMethod(
            "createRfcommSocket", Int::class.javaPrimitiveType
        )
        val channelSocket = rfcommMethod.invoke(device, HC05_RFCOMM_CHANNEL) as BluetoothSocket
        channelSocket.connect()
        return channelSocket
    }

    /** Close the active socket from any thread. */
    suspend fun disconnect() = withContext(Dispatchers.IO) {
        runCatching { socket?.close() }
        socket = null
    }

    /** Return a list of already-paired devices as (name, address) pairs. */
    fun pairedDevices(): List<Pair<String, String>> =
        adapter.bondedDevices.map { (it.name ?: "Unknown") to it.address }
}
