package il.kosher.dialer

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.*

@SuppressLint("MissingPermission")
class MainActivity : Activity() {
    private var devices = listOf<BluetoothDevice>()
    private lateinit var spinner: Spinner
    private lateinit var status: TextView
    private lateinit var list: ListView
    private lateinit var search: EditText
    private lateinit var numberBox: EditText
    private var showCalls = false
    private var contacts = listOf<Entry>()
    private var calls = listOf<Entry>()
    private var shown = listOf<Entry>()
    private var hfp: HfpDialer? = null

    private fun dp(i: Int) = (i * resources.displayMetrics.density).toInt()
    private fun btn(t: String, f: () -> Unit) = Button(this).apply {
        text = t; setOnClickListener { f() }
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }
    private fun row(vararg v: View) = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; v.forEach { addView(it) } }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(24), dp(12), dp(8)) }
        spinner = Spinner(this)
        status = TextView(this).apply { text = "בחר מכשיר מזווג ולחץ 'רענן'"; gravity = Gravity.CENTER }
        numberBox = EditText(this).apply { hint = "מספר לחיוג"; inputType = android.text.InputType.TYPE_CLASS_PHONE }
        search = EditText(this).apply { hint = "חיפוש" }
        list = ListView(this)
        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(e: Editable?) { render() }
            override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
            override fun onTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
        })
        list.setOnItemClickListener { _, _, i, _ ->
            val e = shown[i]
            if (e.number.isBlank()) return@setOnItemClickListener
            numberBox.setText(e.number)
            AlertDialog.Builder(this).setMessage("לחייג ל-${e.name.ifBlank { e.number }}?")
                .setPositiveButton("חייג") { _, _ -> dial(e.number) }.setNegativeButton("ביטול", null).show()
        }
        root.addView(spinner)
        root.addView(row(btn("רענן") { refresh() }, btn("אנשי קשר") { showCalls = false; render() }, btn("יומן שיחות") { showCalls = true; render() }))
        root.addView(status)
        root.addView(numberBox)
        root.addView(row(btn("חייג") { dial(numberBox.text.toString()) }, btn("נתק") { hangup() }))
        root.addView(search)
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        ensurePermissions()
    }

    private fun ensurePermissions() {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1)
        else loadDevices()
    }
    override fun onRequestPermissionsResult(r: Int, p: Array<out String>, g: IntArray) { if (g.isNotEmpty() && g[0] == 0) loadDevices() else status.text = "נדרשת הרשאת בלוטות'" }

    private fun loadDevices() {
        val ad: BluetoothAdapter? = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (ad == null || !ad.isEnabled) { status.text = "הפעל בלוטות' וזווג את הטלפון הכשר בהגדרות"; return }
        devices = ad.bondedDevices.toList()
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, devices.map { it.name ?: it.address })
    }

    private fun selected(): BluetoothDevice? = devices.getOrNull(spinner.selectedItemPosition)

    private fun refresh() {
        val d = selected() ?: run { status.text = "אין מכשיר מזווג"; return }
        status.text = "מתחבר... (ייתכן שתידרש אישור בטלפון הכשר)"
        Thread {
            val c = PbapClient(d)
            try {
                c.connect()
                val ct = c.contacts()
                val cl = try { c.callLog() } catch (e: Exception) { emptyList() }
                contacts = ct.sortedBy { it.name }; calls = cl
                runOnUiThread { status.text = "נטענו ${ct.size} אנשי קשר ו-${cl.size} שיחות"; render() }
            } catch (e: Exception) {
                runOnUiThread { status.text = "שגיאה: ${e.message}" }
            } finally { c.close() }
        }.start()
    }

    private fun render() {
        val q = search.text.toString().trim()
        val src = if (showCalls) calls else contacts
        shown = src.filter { q.isEmpty() || it.name.contains(q, true) || it.number.contains(q) }
        val lines = shown.map {
            if (showCalls) {
                val t = when { it.type.contains("RECEIVED") -> "נכנסת"; it.type.contains("DIALED") -> "יוצאת"; it.type.contains("MISSED") -> "שלא נענתה"; else -> "" }
                "${it.name.ifBlank { it.number }}\n${it.number}  •  $t  •  ${it.date}"
            } else "${it.name.ifBlank { "(ללא שם)" }}\n${it.number}"
        }
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, lines)
    }

    private fun dial(number: String) {
        val d = selected() ?: return
        if (number.isBlank()) return
        status.text = "מחייג..."
        Thread {
            try {
                hfp?.close()
                hfp = HfpDialer(d).also { it.connect() }
                val ok = hfp!!.dial(number)
                runOnUiThread { status.text = if (ok) "מחייג ל-$number (השיחה מתנהלת בטלפון הכשר)" else "הטלפון הכשר דחה את החיוג" }
            } catch (e: Exception) { runOnUiThread { status.text = "שגיאת חיוג: ${e.message}" } }
        }.start()
    }

    private fun hangup() { Thread { try { hfp?.hangup() } catch (_: Exception) {}; runOnUiThread { status.text = "נותק" } }.start() }
}
