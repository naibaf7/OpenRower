package com.smartapes.openrower.bluetooth

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.os.SystemClock
import com.smartapes.openrower.model.SensorMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One connection to a rowing sensor board, over USB (`address == "USB_SERIAL"`) or BLE UART.
 *
 * As soon as the link is up it tells the board which sensor input to use (`M,<mode>`) and asks
 * for its status (`?`). If the board later reports a different mode — e.g. it was reset, which
 * always starts it with the RING supply off — the mode is sent again (at most every 3 s).
 * Boards without these commands (the Arduino + BT05 build) never answer and are unaffected.
 */
class SensorLink(private val context: Context, private val adapter: BluetoothAdapter?) {

    companion object {
        const val USB_ADDRESS = "USB_SERIAL"
    }

    @Volatile private var usb: UsbSerialManager? = null
    @Volatile private var ble: BleUartManager? = null

    /**
     * @param mode     sensor input to request, or null to leave the board as it is
     * @param commands extra lines sent once the link is ready (e.g. "D" for auto-detect)
     */
    fun connect(
        address: String,
        mode: SensorMode?,
        commands: List<String> = emptyList(),
        onStatus: (String) -> Unit = {}
    ): Flow<RowingDataParser.Message> = channelFlow {
        val sendLine: (String) -> Unit = { line ->
            launch(Dispatchers.IO) {
                usb?.send(line)
                ble?.send(line)
            }
        }
        val onReady = {
            mode?.let { sendLine("M,${it.code}\n") }
            sendLine("?\n")
            commands.forEach { sendLine("$it\n") }
        }
        val source = if (address == USB_ADDRESS) {
            UsbSerialManager(context).also { usb = it }.connect(onStatus = onStatus, onReady = onReady)
        } else {
            val a = adapter ?: throw IllegalStateException("Bluetooth not available")
            BleUartManager(context, a).also { ble = it }.connect(address, onReady)
        }
        var lastResend = 0L
        source.collect { msg ->
            if (mode != null && msg is RowingDataParser.Message.Status && msg.mode != mode.code) {
                val now = SystemClock.elapsedRealtime()
                if (now - lastResend > 3_000) {
                    lastResend = now
                    sendLine("M,${mode.code}\n")
                }
            }
            send(msg)
        }
    }

    /**
     * Ask the board what is plugged into the jack. The board probes RING with its 3.3 V supply
     * only (safe for every sensor) and then returns to its previous mode.
     * Returns null if the board does not answer within [timeoutMs] (older firmware / no board).
     */
    suspend fun detect(address: String, timeoutMs: Long = 15_000): RowingDataParser.Message.DetectResult? =
        withTimeoutOrNull(timeoutMs) {
            connect(address, mode = null, commands = listOf("D"))
                .first { it is RowingDataParser.Message.DetectResult } as RowingDataParser.Message.DetectResult
        }
}
