package com.smartapes.openrower.bluetooth

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbRequest
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.Ch34xSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer

class UsbSerialManager(private val context: Context) {

    companion object {
        private const val ACTION_USB_PERMISSION = "com.smartapes.openrower.USB_PERMISSION"
        private const val BAUD_RATE = 38_400   // CH340 Arduino; ignored by the ESP32's native USB
        private const val QINHENG_VID = 0x1A86
        private const val ESPRESSIF_VID = 0x303A
        private const val READ_WAIT_MS = 500L
        private const val READ_BUF_SIZE = 4096
    }

    @Volatile private var activePort: UsbSerialPort? = null

    /**
     * [onStatus] is called on the IO thread whenever Phase 1 transitions to a new step.
     * Use it to surface diagnostic text in the UI so we can see exactly where a hang occurs.
     * [onReady] is called once the port is open and commands can be sent with [send].
     */
    fun connect(onStatus: (String) -> Unit = {}, onReady: () -> Unit = {}): Flow<RowingDataParser.Message> = callbackFlow {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

        val permChannel = Channel<Boolean>(Channel.CONFLATED)
        val permReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != ACTION_USB_PERMISSION) return
                permChannel.trySend(
                    intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                )
            }
        }
        // RECEIVER_EXPORTED: the USB permission broadcast is sent by the system (android UID)
        // via our PendingIntent, so RECEIVER_NOT_EXPORTED would silently drop it.
        ContextCompat.registerReceiver(
            context, permReceiver,
            IntentFilter(ACTION_USB_PERMISSION),
            ContextCompat.RECEIVER_EXPORTED
        )

        val permissionPi = PendingIntent.getBroadcast(
            context, 0,
            Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        var openPort: UsbSerialPort? = null
        var openConnection: UsbDeviceConnection? = null

        val job = launch(Dispatchers.IO) {
            var permissionRequested = false
            var lastDeviceName: String? = null
            var lastStatus = ""
            fun status(msg: String) { if (msg != lastStatus) { lastStatus = msg; onStatus(msg) } }

            status("Plug in the USB OTG cable")

            // ── Phase 1: find device → permission → open port ─────────────────
            while (openPort == null) {
                var pendingConnection: UsbDeviceConnection? = null
                try {
                    val driver = resolveDriver(usbManager)
                    if (driver == null) {
                        permissionRequested = false
                        status("Plug in the USB OTG cable")
                        delay(1_000)
                        continue
                    }

                    val device = driver.device
                    val vidPid = "%04x:%04x".format(device.vendorId, device.productId)

                    if (device.deviceName != lastDeviceName) {
                        permissionRequested = false
                        lastDeviceName = device.deviceName
                    }

                    if (!usbManager.hasPermission(device)) {
                        if (!permissionRequested) {
                            status("Found $vidPid – requesting USB permission…")
                            usbManager.requestPermission(device, permissionPi)
                            permissionRequested = true
                        } else {
                            status("Waiting for USB permission on $vidPid…")
                        }
                        val granted = withTimeoutOrNull(60_000) { permChannel.receive() }
                        if (granted != true) {
                            status("USB permission denied – will retry")
                            permissionRequested = false
                        }
                        continue
                    }

                    status("Opening USB serial port $vidPid…")
                    val connection = usbManager.openDevice(device)
                        ?: run { delay(1_000); continue }
                    pendingConnection = connection

                    val port = driver.ports.firstOrNull()
                        ?: run { connection.close(); pendingConnection = null; delay(1_000); continue }

                    port.open(connection)
                    port.setParameters(BAUD_RATE, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                    if (device.vendorId == ESPRESSIF_VID) {
                        // The ESP32-C3 USB Serial/JTAG port uses RTS as chip reset and DTR as the
                        // boot-mode select (esptool's auto-reset). Hold both inactive, DTR first, so
                        // opening the port never reboots the board or drops it into the bootloader.
                        port.setDTR(false)
                        port.setRTS(false)
                    }
                    openPort = port
                    openConnection = connection
                    pendingConnection = null
                    activePort = port
                    onReady()

                } catch (e: CancellationException) {
                    runCatching { pendingConnection?.close() }
                    throw e
                } catch (e: Exception) {
                    status("USB error: ${e::class.simpleName}: ${e.message?.take(60)}")
                    runCatching { pendingConnection?.close() }
                    delay(1_000)
                }
            }

            // ── Phase 2: read via UsbRequest (avoids bulkTransfer on API 36+) ─
            val port       = openPort       ?: return@launch
            val connection = openConnection ?: return@launch

            // Prefer the CDC data interface: the ESP32's USB device also exposes a JTAG
            // interface with its own bulk endpoints. CH340 has a single vendor interface.
            val interfaces = (0 until port.device.interfaceCount).map { port.device.getInterface(it) }
            val bulkIn: UsbEndpoint? = (interfaces.filter { it.interfaceClass == UsbConstants.USB_CLASS_CDC_DATA } + interfaces)
                .asSequence()
                .flatMap { iface ->
                    (0 until iface.endpointCount).asSequence().map { iface.getEndpoint(it) }
                }
                .firstOrNull {
                    it.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                    it.direction == UsbConstants.USB_DIR_IN
                }

            if (bulkIn == null) {
                close(Exception("No bulk IN endpoint on USB device"))
                return@launch
            }

            trySend(RowingDataParser.Message.Heartbeat)

            val usbReq = UsbRequest()
            usbReq.initialize(connection, bulkIn)
            val buf = ByteBuffer.allocate(READ_BUF_SIZE)
            val lineBuffer = StringBuilder()

            try {
                while (isActive) {
                    buf.clear()
                    if (!usbReq.queue(buf)) break

                    val completed = connection.requestWait(READ_WAIT_MS)
                    if (completed == null) continue
                    if (completed != usbReq) continue

                    val bytesRead = buf.position()
                    if (bytesRead > 0) {
                        lineBuffer.append(String(buf.array(), 0, bytesRead, Charsets.UTF_8))
                        var nl = lineBuffer.indexOf('\n')
                        while (nl >= 0) {
                            val line = lineBuffer.substring(0, nl).trim()
                            lineBuffer.delete(0, nl + 1)
                            val msg = RowingDataParser.parseLine(line)
                            if (msg != null) trySend(msg)
                            nl = lineBuffer.indexOf('\n')
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                close(e)
            } finally {
                usbReq.close()
            }
        }

        awaitClose {
            job.cancel()
            activePort = null
            runCatching { context.unregisterReceiver(permReceiver) }
            runCatching { openPort?.close() }
        }
    }

    /** Write [text] to the open port (blocking, call from an IO thread). False if not open. */
    fun send(text: String): Boolean {
        val port = activePort ?: return false
        return runCatching { port.write(text.toByteArray(Charsets.UTF_8), 200) }.isSuccess
    }

    private fun resolveDriver(usbManager: UsbManager): UsbSerialDriver? {
        UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
            .firstOrNull()?.let { return it }

        val devices = usbManager.deviceList.values
        if (devices.isEmpty()) return null

        devices.firstOrNull { it.vendorId == ESPRESSIF_VID }
            ?.let { return CdcAcmSerialDriver(it) }

        devices.firstOrNull { it.vendorId == QINHENG_VID }
            ?.let { return Ch34xSerialDriver(it) }

        if (devices.size == 1) return Ch34xSerialDriver(devices.first())

        return null
    }
}
