package il.kosher.dialer

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.util.UUID

/** חיוג לטלפון הכשר באמצעות פקודות AT של פרופיל דיבורית (HFP). השיחה עצמה מתנהלת במכשיר הכשר. */
@SuppressLint("MissingPermission")
class HfpDialer(private val device: BluetoothDevice) {
    private var socket: BluetoothSocket? = null

    private fun cmd(s: String, timeout: Long = 3000): String {
        socket!!.outputStream.write((s + "\r").toByteArray()); socket!!.outputStream.flush()
        val inp = socket!!.inputStream
        val sb = StringBuilder()
        val end = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < end) {
            if (inp.available() > 0) {
                val buf = ByteArray(inp.available()); val n = inp.read(buf); sb.append(String(buf, 0, n))
                if (sb.contains("OK") || sb.contains("ERROR")) break
            } else Thread.sleep(50)
        }
        return sb.toString()
    }

    fun connect() {
        socket = device.createRfcommSocketToServiceRecord(UUID.fromString("0000111f-0000-1000-8000-00805f9b34fb"))
        socket!!.connect()
        cmd("AT+BRSF=0"); cmd("AT+CIND=?"); cmd("AT+CIND?"); cmd("AT+CMER=3,0,0,1,0")
    }

    fun dial(number: String): Boolean {
        val clean = number.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
        return !cmd("ATD$clean;", 5000).contains("ERROR")
    }

    fun hangup() { cmd("AT+CHUP") }

    fun close() { try { socket?.close() } catch (_: Exception) {} }
}
