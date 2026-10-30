package com.smartapes.openrower.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.UUID

/**
 * Connects to a BLE UART (BT05 / HM-10 module, or the OpenRower ESP32 board, which exposes the
 * same service) and emits parsed rowing messages.
 *
 * The BT05 (CC2541, HM-10 clone) is a BLE device that exposes a custom serial
 * UART service — it does NOT appear in Android's Classic Bluetooth settings and
 * cannot be connected with RFCOMM/SPP.
 *
 * ── GATT profile ─────────────────────────────────────────────────────────
 *   Service UUID   : 0000FFE0-0000-1000-8000-00805F9B34FB
 *   Characteristic : 0000FFE1-0000-1000-8000-00805F9B34FB  (notify + write)
 *   CCCD descriptor: 00002902-0000-1000-8000-00805F9B34FB
 *
 * ── Data framing ──────────────────────────────────────────────────────────
 * BLE MTU is 23 bytes by default (20 bytes payload), so a single text line may arrive
 * split across multiple notifications. We buffer incoming bytes and flush complete lines
 * (delimited by '\n') to [RowingDataParser]. A larger MTU and high connection priority are
 * requested so the ESP32 board can batch lines at Concept2 D/E pulse rates; the BT05 simply
 * keeps 23.
 *
 * ── Writing ───────────────────────────────────────────────────────────────
 * [send] queues text for the characteristic (one GATT write in flight at a time).
 * `onReady` fires once notifications are enabled — the point where commands can be sent.
 *
 * ── Pairing ───────────────────────────────────────────────────────────────
 * No Classic pairing needed. Use BLE scan to discover the MAC address, then
 * connect directly via [connect].
 */
class BleUartManager(
    private val context: Context,
    private val adapter: BluetoothAdapter
) {

    companion object {
        private val UART_SERVICE_UUID = UUID.fromString("0000FFE0-0000-1000-8000-00805F9B34FB")
        private val UART_CHAR_UUID    = UUID.fromString("0000FFE1-0000-1000-8000-00805F9B34FB")
        private val CCCD_UUID         = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
        private const val WANTED_MTU  = 185
    }

    private var gatt: BluetoothGatt? = null
    @Volatile private var uartChar: BluetoothGattCharacteristic? = null
    private val writeQueue = ArrayDeque<ByteArray>()
    private var writeInFlight = false

    /**
     * Connect to the device at [macAddress] and return a flow of parsed messages.
     * Cancel the collecting coroutine to disconnect.
     */
    fun connect(macAddress: String, onReady: () -> Unit = {}): Flow<RowingDataParser.Message> = callbackFlow {
        val device = adapter.getRemoteDevice(macAddress)
        val lineBuffer = StringBuilder()

        val callback = object : BluetoothGattCallback() {

            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                        if (!g.requestMtu(WANTED_MTU)) g.discoverServices()
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> close()
                }
            }

            override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
                g.discoverServices()
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) { close(); return }

                val char = g.getService(UART_SERVICE_UUID)
                    ?.getCharacteristic(UART_CHAR_UUID)
                    ?: run { close(); return }
                uartChar = char

                g.setCharacteristicNotification(char, true)

                // Write ENABLE_NOTIFICATION_VALUE to CCCD descriptor
                val cccd = char.getDescriptor(CCCD_UUID)
                if (cccd == null) { onReady(); return }
                if (Build.VERSION.SDK_INT >= 33) {
                    g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(cccd)
                }
            }

            override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                if (descriptor.uuid == CCCD_UUID) onReady()
            }

            override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
                synchronized(writeQueue) { writeInFlight = false }
                pumpWrites()
            }

            // API < 33
            @Deprecated("Used for API < 33")
            override fun onCharacteristicChanged(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic
            ) {
                if (characteristic.uuid == UART_CHAR_UUID) {
                    @Suppress("DEPRECATION")
                    handleBytes(characteristic.value, lineBuffer)
                }
            }

            // API >= 33
            override fun onCharacteristicChanged(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray
            ) {
                if (characteristic.uuid == UART_CHAR_UUID) {
                    handleBytes(value, lineBuffer)
                }
            }

            private fun handleBytes(bytes: ByteArray, buf: StringBuilder) {
                buf.append(String(bytes, Charsets.UTF_8))
                // Flush all complete lines
                var nl = buf.indexOf('\n')
                while (nl >= 0) {
                    val line = buf.substring(0, nl).trim()
                    buf.delete(0, nl + 1)
                    val msg = RowingDataParser.parseLine(line)
                    if (msg != null) trySend(msg)
                    nl = buf.indexOf('\n')
                }
            }
        }

        gatt = device.connectGatt(context, false, callback)

        awaitClose {
            gatt?.disconnect()
            gatt?.close()
            gatt = null
            uartChar = null
        }
    }

    /** Queue [text] for the UART characteristic. Returns false when not connected yet. */
    fun send(text: String): Boolean {
        if (uartChar == null || gatt == null) return false
        synchronized(writeQueue) {
            // one write per <= 20 bytes so it also fits the BT05's default MTU
            text.toByteArray(Charsets.UTF_8).asList().chunked(20).forEach { writeQueue.addLast(it.toByteArray()) }
        }
        pumpWrites()
        return true
    }

    private fun pumpWrites() {
        val g = gatt ?: return
        val c = uartChar ?: return
        val next: ByteArray
        synchronized(writeQueue) {
            if (writeInFlight || writeQueue.isEmpty()) return
            next = writeQueue.removeFirst()
            writeInFlight = true
        }
        val ok = if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(c, next, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            c.value = next
            @Suppress("DEPRECATION")
            g.writeCharacteristic(c)
        }
        if (!ok) synchronized(writeQueue) { writeInFlight = false }
    }

    fun disconnect() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        uartChar = null
    }
}
