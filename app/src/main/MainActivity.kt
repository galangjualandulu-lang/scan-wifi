package com.example.wifiscanner

import android.Manifest
import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.*
import android.widget.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var wifi: WifiManager
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private val cache = HashMap<String, String>()
    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private var gen = 0

    private val phoneSsid = Regex("iphone|ipad|galaxy|samsung|android|redmi|poco|xiaomi|oppo|vivo|realme|infinix|tecno|itel|huawei|honor|pixel|oneplus|nokia|^direct-|hotspot", RegexOption.IGNORE_CASE)
    private val phoneVendor = Regex("samsung|xiaomi|oppo|vivo|apple|realme|infinix|transsion|tecno|itel|huawei|honor|google|oneplus|motorola|nokia|lenovo|asus|sony", RegexOption.IGNORE_CASE)
    private val routerVendor = Regex("tp-link|d-link|tenda|zte|mikrotik|netgear|ubiquiti|totolink|fiberhome|zyxel|cisco|ruijie|linksys|aruba|mercusys", RegexOption.IGNORE_CASE)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) = render()
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 32) }
        val btn = Button(this).apply { text = "Pindai WiFi"; setOnClickListener { scan() } }
        status = TextView(this).apply { setPadding(0, 16, 0, 16) }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(btn); root.addView(status)
        root.addView(ScrollView(this).apply { addView(list) })
        val splash = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(TextView(this@MainActivity).apply {
                text = "Bilang Galang Ganteng Dulu"
                setTextColor(Color.WHITE)
                textSize = 24f
                setTypeface(null, Typeface.BOLD)
                gravity = android.view.Gravity.CENTER
            }, FrameLayout.LayoutParams(-1, -1))
        }
        setContentView(splash)
        ui.postDelayed({ setContentView(root) }, 2500)
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
        scan()
    }

    override fun onPause() { super.onPause(); unregisterReceiver(receiver) }

    private fun scan() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1); return
        }
        if (!wifi.isWifiEnabled) { status.text = "Nyalakan WiFi dulu."; return }
        status.text = "Memindai... (pastikan Lokasi/GPS aktif)"
        @Suppress("DEPRECATION") wifi.startScan()
        render()
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, g: IntArray) {
        if (g.isNotEmpty() && g[0] == PackageManager.PERMISSION_GRANTED) scan()
        else status.text = "Izin lokasi dibutuhkan Android untuk membaca daftar WiFi."
    }

    private fun channel(f: Int) = when {
        f in 2412..2484 -> (f - 2407) / 5
        f in 5000..5900 -> (f - 5000) / 5
        else -> (f - 5950) / 5
    }

    private fun classify(r: ScanResult, vendor: String?): Pair<Boolean, String> {
        var score = 0; val why = mutableListOf<String>()
        if (r.BSSID.substring(1, 2).toInt(16) in listOf(2, 6, 10, 14)) { score += 2; why += "MAC acak/virtual" }
        @Suppress("DEPRECATION") if (phoneSsid.containsMatchIn(r.SSID)) { score += 2; why += "nama mirip HP" }
        if (!vendor.isNullOrEmpty()) {
            if (routerVendor.containsMatchIn(vendor)) { score -= 3; why += "vendor router" }
            else if (phoneVendor.containsMatchIn(vendor)) { score += 2; why += "vendor HP" }
        }
        return Pair(score >= 2, why.joinToString().ifEmpty { "-" })
    }

    private fun fetchVendor(mac: String): String {
        cache[mac]?.let { return it }
        var v = ""
        try {
            Thread.sleep(1100) // batas API gratis 1 req/detik
            val c = URL("https://api.macvendors.com/$mac").openConnection() as HttpURLConnection
            c.connectTimeout = 5000; c.readTimeout = 5000
            if (c.responseCode == 200) v = c.inputStream.bufferedReader().readText()
        } catch (_: Exception) {}
        cache[mac] = v
        return v
    }

    private fun render() {
        val results = wifi.scanResults.sortedByDescending { it.level }
        val myGen = ++gen
        list.removeAllViews()
        status.text = "${results.size} jaringan ditemukan"
        for (r in results) {
            @Suppress("DEPRECATION") val name = r.SSID.ifEmpty { "(tersembunyi)" }
            val title = TextView(this).apply { textSize = 16f; setTypeface(null, Typeface.BOLD) }
            val info = TextView(this).apply {
                text = "${r.BSSID.uppercase()} • CH ${channel(r.frequency)} • ${r.level} dBm"
            }
            val extra = TextView(this).apply { setPadding(0, 0, 0, 24) }
            list.addView(title); list.addView(info); list.addView(extra)

            fun bind(v: String?) {
                val (isPhone, why) = classify(r, v)
                title.text = (if (isPhone) "📱 HP/HOTSPOT  " else "📡 Router/AP  ") + name
                title.setTextColor(if (isPhone) Color.parseColor("#C62828") else Color.parseColor("#1565C0"))
                extra.text = "Vendor: ${if (v.isNullOrEmpty()) "?" else v}\nAlasan: $why"
            }
            bind(cache[r.BSSID.uppercase()])
            if (!cache.containsKey(r.BSSID.uppercase())) io.execute {
                if (myGen != gen) return@execute
                val v = fetchVendor(r.BSSID.uppercase())
                ui.post { if (myGen == gen) bind(v) }
            }
        }
    }
}
