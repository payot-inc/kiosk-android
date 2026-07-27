package dev.payot.kiosk

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import org.json.JSONObject

/**
 * 지폐기(Bill Acceptor) — USB-OTG 시리얼(CH340, vid 0x1A86), 9600bps 고정.
 *
 * ASCII 라인 프로토콜 (CRLF 종단):
 *   앱 -> 지폐기 : "RUN\r\n"(투입구 개방) / "STOP\r\n"(차폐)
 *   지폐기 -> 앱 : "RUN:OK" / "STOP:OK" / "BILL:{금액}" (지폐 1장당 비동기 푸시)
 *
 * 합계 누적은 JS 쪽에서 한다(장치는 매 장당 단건 통보).
 * 상세: guide/지폐기-명령체계.md
 */
class BillAcceptor(
    private val context: Context,
    private val emit: (String, JSONObject) -> Unit
) : SerialInputOutputManager.Listener {

    companion object {
        private const val TAG = "BillAcceptor"
        private const val BAUD_RATE = 9600
        private const val VID_CH340 = 0x1A86
        private const val ACTION_USB_PERMISSION = "dev.payot.kiosk.USB_PERMISSION"
    }

    private val usbManager
        get() = context.getSystemService(Context.USB_SERVICE) as UsbManager

    private var port: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null
    @Volatile private var running = false
    private val lineBuffer = StringBuilder()

    private fun isConnected() = port != null

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val granted =
                        intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted) connect() else emitError("USB 권한이 거부되었습니다")
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val detached: UsbDevice? =
                        @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    if (detached != null && detached.deviceId == port?.device?.deviceId) {
                        disconnect()
                    }
                }
            }
        }
    }

    fun register() {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(
            context, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    fun unregister() {
        runCatching { context.unregisterReceiver(usbReceiver) }
    }

    fun connect() {
        if (isConnected()) {
            emitConnection(true)
            return
        }
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        if (drivers.isEmpty()) {
            emitError("USB 시리얼 장치를 찾을 수 없습니다")
            return
        }
        // CH340(vid 0x1A86) 지폐기만 연결한다. 없으면 다른 USB 시리얼(예: FTDI 카드리더)을
        // 지폐기로 오인/점유하지 않도록 여기서 중단한다.
        val driver = drivers.firstOrNull { it.device.vendorId == VID_CH340 }
            ?: run { emitError("지폐기(CH340)를 찾을 수 없습니다"); return }
        val device = driver.device

        if (!usbManager.hasPermission(device)) {
            val pi = PendingIntent.getBroadcast(
                context, 0,
                Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
                PendingIntent.FLAG_MUTABLE
            )
            usbManager.requestPermission(device, pi)
            return // 권한 승인되면 usbReceiver 가 connect() 재호출
        }

        try {
            val connection = usbManager.openDevice(device)
                ?: throw IllegalStateException("openDevice 실패")
            val p = driver.ports[0]
            p.open(connection)
            p.setParameters(BAUD_RATE, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            port = p
            // 단일 read 스레드 (JS 폴링 금지 — 바이트 유실 사고 이력 있음)
            ioManager = SerialInputOutputManager(p, this).also { it.start() }
            emitConnection(true, device)
        } catch (e: Exception) {
            Log.e(TAG, "connect failed", e)
            closeQuietly()
            emitError("지폐기 연결 실패: ${e.message}")
        }
    }

    fun disconnect() {
        val wasConnected = isConnected()
        closeQuietly()
        if (wasConnected) emitConnection(false)
    }

    fun write(command: String) {
        val bytes = "$command\r\n".toByteArray(Charsets.US_ASCII)
        try {
            when {
                port != null -> port!!.write(bytes, 1000)
                else -> {
                    emitError("지폐기가 연결되어 있지 않습니다")
                    return
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "write failed: $command", e)
            // 채널이 끊긴 상태(broken pipe 등): 낡은 연결을 정리해 재연결이 가능하게 하고
            // 웹에 연결 해제를 알린다.
            closeQuietly()
            emitError("전송 실패(${command}): ${e.message}")
            emitConnection(false)
        }
    }

    fun status(): JSONObject = JSONObject()
        .put("connected", isConnected())
        .put("running", running)

    // ---------- SerialInputOutputManager.Listener ----------

    override fun onNewData(data: ByteArray) {
        synchronized(lineBuffer) {
            lineBuffer.append(String(data, Charsets.US_ASCII))
            while (true) {
                val newline = lineBuffer.indexOf("\n")
                if (newline < 0) break
                val line = lineBuffer.substring(0, newline).trim()
                lineBuffer.delete(0, newline + 1)
                if (line.isNotEmpty()) handleLine(line)
            }
        }
    }

    override fun onRunError(e: Exception) {
        Log.e(TAG, "serial run error", e)
        closeQuietly()
        emitError("시리얼 통신 오류: ${e.message}")
        emitConnection(false)
    }

    // ---------- 내부 ----------

    private fun handleLine(line: String) {
        emit("kiosk:bill-line", JSONObject().put("line", line))
        val upper = line.uppercase()
        when {
            upper.contains("RUN:OK") -> {
                running = true
                emit("kiosk:bill-status", JSONObject().put("running", true))
            }
            upper.contains("STOP:OK") -> {
                running = false
                emit("kiosk:bill-status", JSONObject().put("running", false))
            }
            upper.startsWith("BILL:") -> {
                val amount = line.substringAfter(":").trim().toIntOrNull()
                if (amount != null) {
                    emit("kiosk:bill-inserted", JSONObject().put("amount", amount))
                } else {
                    emitError("금액 파싱 실패: $line")
                }
            }
        }
    }

    private fun closeQuietly() {
        runCatching { ioManager?.stop() }
        ioManager = null
        runCatching { port?.close() }
        port = null
        running = false
        synchronized(lineBuffer) { lineBuffer.setLength(0) }
    }

    private fun emitConnection(connected: Boolean, device: UsbDevice? = null) {
        val json = JSONObject().put("connected", connected)
        if (device != null) {
            json.put(
                "device",
                "%04x:%04x %s".format(
                    device.vendorId, device.productId, device.productName ?: ""
                ).trim()
            )
        }
        emit("kiosk:bill-connection", json)
    }

    private fun emitError(message: String) {
        emit("kiosk:bill-error", JSONObject().put("message", message))
    }
}
