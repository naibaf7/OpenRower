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
 * Connects to a standard BLE Heart Rate Profile device (e.g. Polar H7)
 * and emits heart rate values in beats per minute.
 *
 * Compatible with any BLE device that implements the Heart Rate Service
 * (UUID 0x180D) and Heart Rate Measurement characteristic (UUID 0x2A37).
 *
 * To support additional HR monitor models, simply ensure the device
 * advertises the standard Heart Rate Service – no code changes required.
 */
class HeartRateManager(
    private val context: Context,
    private val adapter: BluetoothAdapter
) {

    companion object {
        private val HR_SERVICE_UUID = UUID.fromString("0000180D-0000-1000-8000-00805F9B34FB")
        private val HR_MEASUREMENT_UUID = UUID.fromString("00002A37-0000-1000-8000-00805F9B34FB")
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
    }

    private var gatt: BluetoothGatt? = null

    /**
     * Connect to the BLE HR monitor at [macAddress] and return a flow of BPM values.
     * Disconnect by cancelling the collecting coroutine.
     */
    fun connect(macAddress: String): Flow<Int> = callbackFlow {
        val device = adapter.getRemoteDevice(macAddress)

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    g.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    close()
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) { close(); return }
                val char = g.getService(HR_SERVICE_UUID)
                    ?.getCharacteristic(HR_MEASUREMENT_UUID) ?: run { close(); return }

                g.setCharacteristicNotification(char, true)
                val descriptor = char.getDescriptor(CCCD_UUID)
                descriptor?.let {
                    it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    g.writeDescriptor(it)
                }
            }

            @Deprecated("Used for API < 33")
            override fun onCharacteristicChanged(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic
            ) {
                if (characteristic.uuid == HR_MEASUREMENT_UUID) {
                    val bpm = parseHeartRate(characteristic.value)
                    if (bpm > 0) trySend(bpm)
                }
            }

            override fun onCharacteristicChanged(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray
            ) {
                if (characteristic.uuid == HR_MEASUREMENT_UUID) {
                    val bpm = parseHeartRate(value)
                    if (bpm > 0) trySend(bpm)
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

    /**
     * Parse the BLE Heart Rate Measurement characteristic value.
     * Byte 0 is a flags field; bit 0 indicates uint16 (1) vs uint8 (0) HR value.
     */
    private fun parseHeartRate(data: ByteArray): Int {
        if (data.isEmpty()) return 0
        val flags = data[0].toInt() and 0xFF
        return if (flags and 0x01 != 0) {
            // 16-bit HR value
            if (data.size < 3) 0
            else (data[1].toInt() and 0xFF) or ((data[2].toInt() and 0xFF) shl 8)
        } else {
            // 8-bit HR value
            if (data.size < 2) 0
            else data[1].toInt() and 0xFF
        }
    }
}
