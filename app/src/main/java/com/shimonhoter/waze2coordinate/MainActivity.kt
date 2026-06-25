package com.shimonhoter.waze2coordinate

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.JavascriptInterface
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.shimonhoter.waze2coordinate.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

data class Coordinates(val lat: String, val lon: String)

enum class Source { WAZE, MAPS }

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // OkHttp client שעוקב אחרי redirects אוטומטית - בלי שום proxy, כי זה native HTTP ולא דפדפן
    private val httpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private var currentSource: Source = Source.WAZE
    private var isMapVisible = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.sourceToggle.check(binding.btnSourceWaze.id)
        updateHintForSource()

        binding.sourceToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                currentSource = if (checkedId == binding.btnSourceMaps.id) Source.MAPS else Source.WAZE
                updateHintForSource()
            }
        }

        setupMapWebView()

        binding.btnPickOnMap.setOnClickListener { toggleMapVisibility() }

        binding.mapStyleToggle.check(binding.btnMapStreet.id)
        binding.mapStyleToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val style = if (checkedId == binding.btnMapSatellite.id) "satellite" else "street"
                binding.mapWebView.evaluateJavascript("switchMapStyle('$style')", null)
            }
        }

        binding.btnConvert.setOnClickListener { handleConvert() }
        binding.btnCopy.setOnClickListener { copyToClipboard() }
        binding.btnOpenMaps.setOnClickListener { openInGoogleMaps() }
        binding.btnNavigateWaze.setOnClickListener { navigateWithWaze() }

        handleIncomingIntent(intent)
    }

    /**
     * Bridge בין דף ה-Leaflet שרץ ב-WebView לקוד Kotlin. בכל הקשה על המפה,
     * הדף קורא ל-onMapPointSelected עם הקואורדינטות הנבחרות.
     */
    inner class MapJsBridge {
        @JavascriptInterface
        fun onMapPointSelected(lat: String, lon: String) {
            runOnUiThread {
                onMapTapped(lat, lon)
            }
        }
    }

    private fun setupMapWebView() {
        val webView = binding.mapWebView
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.addJavascriptInterface(MapJsBridge(), "AndroidBridge")
        webView.loadUrl("file:///android_asset/map.html")
    }

    private fun toggleMapVisibility() {
        isMapVisible = !isMapVisible
        binding.mapContainer.visibility = if (isMapVisible) View.VISIBLE else View.GONE
        binding.btnPickOnMap.text = if (isMapVisible) {
            getString(R.string.btn_pick_on_map_close)
        } else {
            getString(R.string.btn_pick_on_map)
        }
    }

    /**
     * כשבוחרים נקודה במפה - בונים קישור Google Maps תקני (q=lat,lon) ושמים בשדה,
     * עוברים אוטומטית לטוגל "קישור Google Maps", וממירים את הקישור (שלמעשה כבר
     * מכיל את הקואורדינטות במלואן, כך שהפענוח מיידי ולא דורש קריאת רשת).
     */
    private fun onMapTapped(lat: String, lon: String) {
        currentSource = Source.MAPS
        binding.sourceToggle.check(binding.btnSourceMaps.id)
        updateHintForSource()

        val mapsUrl = "https://www.google.com/maps?q=$lat,$lon"
        binding.editUrl.setText(mapsUrl)
        handleConvert()
    }

    private fun updateHintForSource() {
        binding.editUrlLayout.hint = if (currentSource == Source.MAPS) {
            getString(R.string.hint_url_maps)
        } else {
            getString(R.string.hint_url)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    /**
     * מטפל בקישור שמגיע מ-Share intent (אפליקציה אחרת ששיתפה טקסט/קישור),
     * או מ-VIEW intent (לחיצה ישירה על קישור waze.com/ul/... או Google Maps).
     * מזהה אוטומטית אם זה קישור Waze או Maps ובוחר בטוגל המתאים.
     */
    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return

        val sharedText: String? = when (intent.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        }

        if (!sharedText.isNullOrBlank()) {
            val detectedSource = detectSource(sharedText)
            if (detectedSource != null) {
                currentSource = detectedSource
                binding.sourceToggle.check(
                    if (detectedSource == Source.MAPS) binding.btnSourceMaps.id else binding.btnSourceWaze.id
                )
                updateHintForSource()
            }

            val extractedUrl = extractUrlFromText(sharedText)
            if (extractedUrl != null) {
                binding.editUrl.setText(extractedUrl)
                handleConvert()
            }
        }
    }

    /** מזהה אם הטקסט מכיל קישור Waze או Google Maps */
    private fun detectSource(text: String): Source? {
        return when {
            text.contains("waze.com", ignoreCase = true) -> Source.WAZE
            text.contains("google.com/maps", ignoreCase = true) ||
                text.contains("maps.app.goo.gl", ignoreCase = true) ||
                text.contains("goo.gl/maps", ignoreCase = true) -> Source.MAPS
            else -> null
        }
    }

    /** טקסט משותף עלול להכיל מילים נוספות מסביב לקישור - מחלצים רק את ה-URL */
    private fun extractUrlFromText(text: String): String? {
        val pattern = Pattern.compile("https?://\\S+")
        val matcher = pattern.matcher(text)
        return if (matcher.find()) matcher.group() else text.trim()
    }

    private fun handleConvert() {
        val url = binding.editUrl.text?.toString()?.trim()

        hideError()
        binding.resultLayout.visibility = android.view.View.GONE

        if (url.isNullOrBlank()) {
            showError(getString(R.string.error_empty_url))
            return
        }

        setLoading(true)

        lifecycleScope.launch {
            try {
                val coords = withContext(Dispatchers.IO) { resolveCoordinates(url, currentSource) }
                setLoading(false)
                if (coords != null) {
                    showResult(coords)
                } else {
                    val debugInfo = "URL סופי: $lastFinalUrl\nקוד HTTP: $lastHttpCode\nתחילת תגובה: $lastBodySnippet"
                    showError(getString(R.string.error_no_coords) + "\n\n" + debugInfo)
                }
            } catch (e: Exception) {
                setLoading(false)
                showError(getString(R.string.error_network) + "\n" + (e.message ?: ""))
            }
        }
    }

    /**
     * עוקב אחרי שרשרת ה-redirect של הקישור (Waze מקוצר או Google Maps מקוצר) ומחלץ
     * קואורדינטות מה-URL הסופי. ה-OkHttp עוקב אחרי redirects אוטומטית, אז ה-response.request.url
     * בסוף השרשרת הוא כתובת היעד המלאה עם הקואורדינטות.
     */
    private fun resolveCoordinates(inputUrl: String, source: Source): Coordinates? {
        val request = Request.Builder()
            .url(inputUrl)
            .header("User-Agent", "Mozilla/5.0 (Android) Waze2Coordinate/1.0")
            .build()

        httpClient.newCall(request).execute().use { response ->
            val finalUrl = response.request.url.toString()
            lastFinalUrl = finalUrl
            lastHttpCode = response.code

            var coords = extractCoordsFromUrl(finalUrl, source)
            if (coords != null) return coords

            // אם הקואורדינטות לא נמצאות ב-URL הסופי (יתכן שהן מוטמעות בתוך גוף הדף),
            // נבדוק גם את תוכן התשובה.
            val body = response.body?.string() ?: ""
            lastBodySnippet = body.take(500)
            coords = extractCoordsFromUrl(body, source)
            return coords
        }
    }

    private var lastFinalUrl: String = ""
    private var lastHttpCode: Int = 0
    private var lastBodySnippet: String = ""

    private fun extractCoordsFromUrl(text: String, source: Source): Coordinates? {
        return when (source) {
            Source.WAZE -> extractWazeCoords(text)
            Source.MAPS -> extractMapsCoords(text)
        }
    }

    private fun extractWazeCoords(text: String): Coordinates? {
        // תבנית 1: ll=lat,lon או q=lat,lon (פסיק רגיל, לא מקודד)
        var pattern = Pattern.compile("[?&](ll|q)=(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)", Pattern.CASE_INSENSITIVE)
        var matcher = pattern.matcher(text)
        if (matcher.find()) {
            return Coordinates(matcher.group(2)!!, matcher.group(3)!!)
        }

        // תבנית 2: ll=lat%2Clon (קידוד URL מפורש)
        pattern = Pattern.compile("[?&](ll|q)=(-?\\d+\\.\\d+)%2C(-?\\d+\\.\\d+)", Pattern.CASE_INSENSITIVE)
        matcher = pattern.matcher(text)
        if (matcher.find()) {
            return Coordinates(matcher.group(2)!!, matcher.group(3)!!)
        }

        // תבנית 3: to=ll.lat%2Clon (פורמט live-map/directions הנוכחי של Waze)
        pattern = Pattern.compile("to=ll\\.(-?\\d+\\.\\d+)%2C(-?\\d+\\.\\d+)", Pattern.CASE_INSENSITIVE)
        matcher = pattern.matcher(text)
        if (matcher.find()) {
            return Coordinates(matcher.group(1)!!, matcher.group(2)!!)
        }

        // תבנית 3ב: to=ll.lat,lon (גרסה לא מקודדת, רק במקרה)
        pattern = Pattern.compile("to=ll\\.(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)", Pattern.CASE_INSENSITIVE)
        matcher = pattern.matcher(text)
        if (matcher.find()) {
            return Coordinates(matcher.group(1)!!, matcher.group(2)!!)
        }

        // תבנית 3ג: to/ll.lat,lon (פורמט ישן/חלופי, נשמר לגיבוי)
        pattern = Pattern.compile("to/ll\\.(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)", Pattern.CASE_INSENSITIVE)
        matcher = pattern.matcher(text)
        if (matcher.find()) {
            return Coordinates(matcher.group(1)!!, matcher.group(2)!!)
        }

        return extractGenericCoords(text)
    }

    private fun extractMapsCoords(text: String): Coordinates? {
        // תבנית 1: /@lat,lon,zoom (פורמט הנפוץ ביותר - פין על המפה / מיקום ב-place)
        var pattern = Pattern.compile("@(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)")
        var matcher = pattern.matcher(text)
        if (matcher.find()) {
            return Coordinates(matcher.group(1)!!, matcher.group(2)!!)
        }

        // תבנית 2: ?q=lat,lon או &query=lat,lon (חיפוש לפי קואורדינטות)
        pattern = Pattern.compile("[?&](q|query)=(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)", Pattern.CASE_INSENSITIVE)
        matcher = pattern.matcher(text)
        if (matcher.find()) {
            return Coordinates(matcher.group(2)!!, matcher.group(3)!!)
        }

        // תבנית 3: !3dlat!4dlon (מוטמע בתוך פרמטר ה-data של עמודי place מורכבים)
        pattern = Pattern.compile("!3d(-?\\d+\\.\\d+)!4d(-?\\d+\\.\\d+)")
        matcher = pattern.matcher(text)
        if (matcher.find()) {
            return Coordinates(matcher.group(1)!!, matcher.group(2)!!)
        }

        // תבנית 4: ?q=lat,lon מקודד עם %2C
        pattern = Pattern.compile("[?&](q|query)=(-?\\d+\\.\\d+)%2C(-?\\d+\\.\\d+)", Pattern.CASE_INSENSITIVE)
        matcher = pattern.matcher(text)
        if (matcher.find()) {
            return Coordinates(matcher.group(2)!!, matcher.group(3)!!)
        }

        return extractGenericCoords(text)
    }

    /** תבנית גנרית-אחרונה: כל זוג מספרים עשרוניים תקפים כקואורדינטות (lat -90..90, lon -180..180) */
    private fun extractGenericCoords(text: String): Coordinates? {
        val pattern = Pattern.compile("(-?\\d{1,3}\\.\\d{3,})\\s*,\\s*(-?\\d{1,3}\\.\\d{3,})")
        val matcher = pattern.matcher(text)
        while (matcher.find()) {
            val lat = matcher.group(1)!!.toDoubleOrNull()
            val lon = matcher.group(2)!!.toDoubleOrNull()
            if (lat != null && lon != null && kotlin.math.abs(lat) <= 90 && kotlin.math.abs(lon) <= 180) {
                return Coordinates(matcher.group(1)!!, matcher.group(2)!!)
            }
        }
        return null
    }

    private var lastCoords: Coordinates? = null

    private fun showResult(coords: Coordinates) {
        lastCoords = coords
        binding.latValue.text = coords.lat
        binding.lonValue.text = coords.lon
        binding.resultLayout.visibility = android.view.View.VISIBLE

        if (isMapVisible) {
            binding.mapWebView.evaluateJavascript(
                "centerOnCoordinates('${coords.lat}', '${coords.lon}')", null
            )
        }
    }

    private fun copyToClipboard() {
        val coords = lastCoords ?: return
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("coordinates", "${coords.lat}, ${coords.lon}")
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, getString(R.string.copied), Toast.LENGTH_SHORT).show()
    }

    private fun openInGoogleMaps() {
        val coords = lastCoords ?: return
        val uri = Uri.parse("geo:${coords.lat},${coords.lon}?q=${coords.lat},${coords.lon}")
        val intent = Intent(Intent.ACTION_VIEW, uri)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.error_no_maps_app), Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * פותח ניווט ב-Waze לקואורדינטות שהתקבלו, דרך הסכמה הייעודית waze:// (רק אפליקציית
     * Waze יכולה לטפל בה - אין סיכון להתנגשות עם ה-VIEW filter של האפליקציה שלנו על
     * waze.com/ul, שעלולה לקרות אם משתמשים בקישור https:// במקום).
     * אם Waze לא מותקן, מוצגת הודעה למשתמש.
     */
    private fun navigateWithWaze() {
        val coords = lastCoords ?: return
        val uri = Uri.parse("waze://?ll=${coords.lat},${coords.lon}&navigate=yes")
        val intent = Intent(Intent.ACTION_VIEW, uri)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.error_no_waze_app), Toast.LENGTH_SHORT).show()
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.progressBar.visibility = if (loading) android.view.View.VISIBLE else android.view.View.GONE
        binding.btnConvert.isEnabled = !loading
    }

    private fun showError(msg: String) {
        binding.errorText.text = msg
        binding.errorText.visibility = android.view.View.VISIBLE
    }

    private fun hideError() {
        binding.errorText.visibility = android.view.View.GONE
    }
}
