package com.naibaf7.openrower.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.UUID

/**
 * Connects to a BT05 / HM-10 BLE UART module and emits parsed rowing messages.
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
 * BLE MTU is 20 bytes by default, so a single Arduino text line may arrive
 * split across multiple notifications. We buffer incoming bytes and flush
 * complete lines (delimited by '\n') to [RowingDataParser].
 *
 * ── Pairing ───────────────────────────────────────────────────────────────
 * No Classic pairing needed. Use BLE scan to discover the MAC address, then
 * connect directly via [connect]. The module's default name is "BT05".
 */
class BleUartManager(
    private val context: Context,
    private val adapter: BluetoothAdapter
) {

    companion object {
        private val UART_SERVICE_UUID = UUID.fromString("0000FFE0-0000-1000-8000-00805F9B34FB")
        private val UART_CHAR_UUID    = UUID.fromString("0000FFE1-0000-1000-8000-00805F9B34FB")
        private val CCCD_UUID         = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
    }

    private var gatt: BluetoothGatt? = null

    /**
     * Connect to the BT05 at [macAddress] and return a flow of parsed messages.
     * Cancel the collecting coroutine to disconnect.
     */
    fun connect(macAddress: String): Flow<RowingDataParser.Message> = callbackFlow {
        val device = adapter.getRemoteDevice(macAddress)
        val lineBuffer = StringBuilder()

        val callback = object : BluetoothGattCallback() {

            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED    -> g.discoverServices()
                    BluetoothProfile.STATE_DISCONNECTED -> close()
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) { close(); return }

                val char = g.getService(UART_SERVICE_UUID)
                    ?.getCharacteristic(UART_CHAR_UUID)
                    ?: run { close(); return }

                g.setCharacteristicNotification(char, true)

                // Write ENABLE_NOTIFICATION_VALUE to CCCD descriptor
                char.getDescriptor(CCCD_UUID)?.let { cccd ->
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    g.writeDescriptor(cccd)
                }
            }

            // API < 33
            @Deprecated("Used for API < 33")
            override fun onCharacteristicChanged(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic
            ) {
                if (characteristic.uuid == UART_CHAR_UUID) {
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
        }
    }

    fun disconnect() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
    }
}
