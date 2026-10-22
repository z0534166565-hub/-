package il.kosher.dialer

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.io.ByteArrayOutputStream
import java.util.UUID

data class Entry(val name: String, val number: String, val date: String, val type: String)

private fun b(vararg i: Int) = ByteArray(i.size) { i[it].toByte() }
private fun hdr(id: Int, data: ByteArray): ByteArray {
    val len = data.size + 3
    return b(id, len shr 8, len and 0xff) + data
}
private fun u16(a: ByteArray, i: Int) = ((a[i].toInt() and 0xff) shl 8) or (a[i + 1].toInt() and 0xff)

/** לקוח PBAP (גישה לספר טלפונים ויומן שיחות דרך בלוטות') מעל OBEX */
@SuppressLint("MissingPermission")
class PbapClient(private val device: BluetoothDevice) {
    private var socket: BluetoothSocket? = null
    private var connId: ByteArray = ByteArray(0)
    private val serviceUuid = UUID.fromString("0000112f-0000-1000-8000-00805f9b34fb")
    private val target = b(0x79, 0x61, 0x35, 0xf0, 0xf0, 0xc5, 0x11, 0xd8, 0x09, 0x66, 0x08, 0x00, 0x20, 0x0c, 0x9a, 0x66)

    private fun send(packet: ByteArray) { socket!!.outputStream.write(packet); socket!!.outputStream.flush() }

    private fun readPacket(): ByteArray {
        val inp = socket!!.inputStream
        val head = ByteArray(3)
        readFully(head)
        val len = u16(head, 1)
        val rest = ByteArray(len - 3)
        readFully(rest)
        return head + rest
    }
    private fun readFully(buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = socket!!.inputStream.read(buf, off, buf.size - off)
            if (n < 0) throw java.io.IOException("החיבור נסגר")
            off += n
        }
    }

    private fun headers(p: ByteArray, start: Int): List<Pair<Int, ByteArray>> {
        val out = ArrayList<Pair<Int, ByteArray>>()
        var i = start
        while (i < p.size) {
            val id = p[i].toInt() and 0xff
            when (id and 0xC0) {
                0x00, 0x40 -> { val l = u16(p, i + 1); out.add(id to p.copyOfRange(i + 3, i + l)); i += l }
                0x80 -> { out.add(id to p.copyOfRange(i + 1, i + 2)); i += 2 }
                else -> { out.add(id to p.copyOfRange(i + 1, i + 5)); i += 5 }
            }
        }
        return out
    }

    fun connect() {
        socket = device.createRfcommSocketToServiceRecord(serviceUuid)
        socket!!.connect()
        val body = b(0x10, 0x00, 0x20, 0x00) + hdr(0x46, target)
        val len = body.size + 3
        send(b(0x80, len shr 8, len and 0xff) + body)
        val r = readPacket()
        if ((r[0].toInt() and 0xff) != 0xA0) throw java.io.IOException("הטלפון הכשר סירב לגישה לאנשי קשר (PBAP)")
        connId = headers(r, 7).firstOrNull { it.first == 0xCB }?.second ?: ByteArray(0)
    }

    private fun pull(path: String): String {
        var req = ByteArray(0)
        if (connId.isNotEmpty()) req += b(0xCB) + connId
        req += hdr(0x42, "x-bt/phonebook".toByteArray(Charsets.US_ASCII) + b(0))
        req += hdr(0x01, (path).toByteArray(Charsets.UTF_16BE) + b(0, 0))
        req += hdr(0x4C, b(0x07, 0x01, 0x01, 0x04, 0x02, 0xFF, 0xFF))
        var len = req.size + 3
        send(b(0x83, len shr 8, len and 0xff) + req)
        val acc = ByteArrayOutputStream()
        while (true) {
            val r = readPacket()
            val code = r[0].toInt() and 0xff
            if (code != 0x90 && code != 0xA0) throw java.io.IOException("שגיאה בקריאת $path (קוד $code)")
            headers(r, 3).filter { it.first == 0x48 || it.first == 0x49 }.forEach { acc.write(it.second) }
            if (code == 0xA0) break
            len = 3
            send(b(0x83, 0, 3))
        }
        return acc.toString("UTF-8")
    }

    fun contacts() = parseVcards(pull("telecom/pb.vcf"))
    fun callLog() = parseVcards(pull("telecom/cch.vcf"))

    fun close() {
        try { send(b(0x81, 0, 3)) } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
    }
}

fun parseVcards(text: String): List<Entry> {
    val res = ArrayList<Entry>()
    var name = ""; var num = ""; var date = ""; var type = ""; var fn = ""
    for (raw in text.replace("\r\n", "\n").split("\n")) {
        val line = raw.trim()
        if (line.startsWith("BEGIN:VCARD", true)) { name = ""; num = ""; date = ""; type = ""; fn = "" }
        else if (line.startsWith("END:VCARD", true)) {
            val shown = if (fn.isNotBlank()) fn else name
            if (num.isNotBlank() || shown.isNotBlank()) res.add(Entry(shown, num, date, type))
        } else {
            val c = line.indexOf(':')
            if (c < 0) continue
            val key = line.substring(0, c); val v = line.substring(c + 1)
            val base = key.substringBefore(';').uppercase()
            when (base) {
                "FN" -> fn = v.trim()
                "N" -> name = v.split(';').filter { it.isNotBlank() }.reversed().joinToString(" ").trim()
                "TEL" -> if (num.isEmpty()) num = v.trim()
                "X-IRMC-CALL-DATETIME" -> {
                    type = key.substringAfter(';', "").uppercase()
                    date = if (v.length >= 13) "${v.substring(6, 8)}/${v.substring(4, 6)}/${v.substring(0, 4)} ${v.substring(9, 11)}:${v.substring(11, 13)}" else v
                }
            }
        }
    }
    return res
}
