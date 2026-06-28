package com.shimonhoter.waze2coordinate

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.graphics.Rect
import android.webkit.JavascriptInterface
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.shimonhoter.waze2coordinate.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
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

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            if (pendingGpsFollowAfterPermission) {
                pendingGpsFollowAfterPermission = false
                startGpsFollow()
            } else {
                centerMapOnGps()
            }
        } else {
            pendingGpsFollowAfterPermission = false
            Toast.makeText(this, getString(R.string.error_location_permission_denied), Toast.LENGTH_SHORT).show()
        }
    }

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

        binding.btnPickOnMap.setOnClickListener { openFullscreenMap() }

        binding.btnConvert.setOnClickListener { handleConvert() }
        binding.btnCopy.setOnClickListener { copyToClipboard() }
        binding.btnOpenMaps.setOnClickListener { openInGoogleMaps() }
        binding.btnNavigateWaze.setOnClickListener { navigateWithWaze() }
        binding.btnSendSms.setOnClickListener { sendViaSms() }
        binding.btnSendWhatsapp.setOnClickListener { sendViaWhatsapp() }

        handleIncomingIntent(intent)
    }

    /**
     * Bridge בין דף ה-Leaflet שרץ ב-WebView לקוד Kotlin. מטפל בהקשות לבחירת נקודה,
     * שינויים בציורים (לשמירה אוטומטית), תוצאות חיפוש כתובת, ובקשות GPS/סגירה
     * שמגיעות מהכפתורים שכעת מוצגים בתוך ה-HTML עצמו (לא ב-native).
     */
    inner class MapJsBridge {
        @JavascriptInterface
        fun onMapPointSelected(lat: String, lon: String) {
            runOnUiThread {
                onMapTapped(lat, lon)
            }
        }

        @JavascriptInterface
        fun onShapesChanged(shapesJson: String) {
            runOnUiThread {
                saveShapesToDisk(shapesJson)
            }
        }

        @JavascriptInterface
        fun onAddressSearchResult(found: Boolean, lat: String, lon: String, displayName: String) {
            runOnUiThread {
                if (!found) {
                    Toast.makeText(this@MainActivity, getString(R.string.error_address_not_found), Toast.LENGTH_SHORT).show()
                }
            }
        }

        @JavascriptInterface
        fun onRequestGpsCenter() {
            runOnUiThread {
                requestLocationAndCenter()
            }
        }

        @JavascriptInterface
        fun onToggleGpsFollow() {
            runOnUiThread {
                toggleGpsFollow()
            }
        }

        @JavascriptInterface
        fun onRequestCloseMap() {
            runOnUiThread {
                closeFullscreenMap()
            }
        }
    }

    private fun setupMapWebView() {
        val webView = binding.mapWebView
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.addJavascriptInterface(MapJsBridge(), "AndroidBridge")

        // טוען את הציורים השמורים מהדיסק לתוך המפה ברגע שהדף סיים להיטען,
        // ומציג את כפתור הסגירה (מוסתר כברירת מחדל ב-HTML, רלוונטי רק בתוך מעטפת native)
        webView.webViewClient = object : android.webkit.WebViewClient() {
            override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                super.onPageFinished(view, url)
                webView.evaluateJavascript("showCloseButton()", null)
                val savedJson = loadShapesFromDisk()
                if (savedJson != null) {
                    webView.evaluateJavascript("loadShapesFromJson(${JSONObject.quote(savedJson)})", null)
                }
            }
        }

        webView.loadUrl("file:///android_asset/map.html")
    }

    /** קובץ שמירת הציורים על הדיסק - נשמר ב-filesDir כך שהוא פרטי לאפליקציה ולא נמחק עם cache */
    private val shapesFile: File
        get() = File(filesDir, "saved_shapes.json")

    private fun saveShapesToDisk(shapesJson: String) {
        try {
            shapesFile.writeText(shapesJson)
        } catch (e: Exception) {
            // כשל בשמירה לא אמור לקרוס את האפליקציה - הציור נשאר תקף בזיכרון להמשך הסשן
        }
    }

    private fun loadShapesFromDisk(): String? {
        return try {
            if (shapesFile.exists()) shapesFile.readText() else null
        } catch (e: Exception) {
            null
        }
    }

    /** פותח את חלון המפה במסך מלא */
    private fun openFullscreenMap() {
        isMapVisible = true
        binding.mapFullscreenContainer.visibility = View.VISIBLE
    }

    /**
     * סוגר את חלון המפה ומחזיר למסך הראשי. לפני הסגירה בפועל, מסתיר זמנית (דרך JS)
     * את כל פקדי הממשק (סרגל כלים, חיפוש, GPS, רמזים, וגם בקרות הזום הטבעיות של
     * Leaflet) ומצלם snapshot "נקי" שמכיל רק את המפה והסימונים עליה - בדיוק כפי
     * שהמשתמש ראה אותם ברגע הסגירה, בלי קשר לאיזה תפריט/מצב היה פתוח. הפעולה כולה
     * חולפת באופן אסינכרוני (פחות מ-frame), כך שהמשתמש לא רואה את ה"ניקוי" החזותי.
     */
    private fun closeFullscreenMap() {
        binding.mapWebView.evaluateJavascript("setCleanCaptureMode(true)") {
            // השהיה של frame אחד (16ms) כדי להבטיח שהדפדפן סיים לצייר את מצב ה-UI הנקי
            // לפני שלוכדים את ה-snapshot.
            binding.mapWebView.postDelayed({
                captureMapSnapshot { uri ->
                    cachedSnapshotUri = uri
                    binding.mapWebView.evaluateJavascript("setCleanCaptureMode(false)", null)
                    isMapVisible = false
                    binding.mapFullscreenContainer.visibility = View.GONE
                }
            }, 16)
        }
    }

    /**
     * כשבוחרים נקודה במפה - בונים קישור Google Maps תקני (q=lat,lon), שמים בשדה,
     * עוברים אוטומטית לטוגל "קישור Google Maps", ממירים אותו (מיידי, ללא קריאת רשת
     * כי הקואורדינטות כבר בקישור עצמו), וסוגרים את המפה בחזרה למסך הראשי.
     */
    private fun onMapTapped(lat: String, lon: String) {
        currentSource = Source.MAPS
        binding.sourceToggle.check(binding.btnSourceMaps.id)
        updateHintForSource()

        val mapsUrl = "https://www.google.com/maps?q=$lat,$lon"
        binding.editUrl.setText(mapsUrl)
        handleConvert()

        closeFullscreenMap()
    }

    /** בודק הרשאת מיקום ומבקש אותה אם חסרה; אם יש הרשאה כבר - ממרכז ישירות */
    private fun requestLocationAndCenter() {
        val hasPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

        if (hasPermission) {
            centerMapOnGps()
        } else {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    /**
     * מאתר את המיקום הנוכחי של המכשיר ומרכז את המפה אליו. משתמש *רק* ב-GPS provider
     * (לא Network provider, שמדויק בהרבה פחות ויכול לסטות מאות מטרים - זה היה הגורם
     * לסטייה שנראתה לעומת Google Maps). מסנן גם fixes ישנים (מעל 30 שניות) או לא מדויקים
     * (accuracy גרוע מ-50 מטר) מתוך ה-lastKnownLocation, כדי לא להציג מיקום מיושן/שגוי
     * לפני שמתקבל fix טרי.
     */
    @SuppressLint("MissingPermission")
    private fun centerMapOnGps() {
        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager

        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            Toast.makeText(this, getString(R.string.error_location_unavailable), Toast.LENGTH_SHORT).show()
            return
        }

        val lastKnown = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        val isFresh = lastKnown != null && (System.currentTimeMillis() - lastKnown.time) < 30_000
        val isAccurate = lastKnown != null && (!lastKnown.hasAccuracy() || lastKnown.accuracy <= 50f)

        if (lastKnown != null && isFresh && isAccurate) {
            sendGpsToMap(lastKnown.latitude, lastKnown.longitude, lastKnown.accuracy)
        }

        try {
            locationManager.requestSingleUpdate(LocationManager.GPS_PROVIDER, object : android.location.LocationListener {
                override fun onLocationChanged(location: android.location.Location) {
                    sendGpsToMap(location.latitude, location.longitude, location.accuracy)
                }
            }, Looper.getMainLooper())
        } catch (e: SecurityException) {
            // הרשאה לא קיימת בפועל (race condition נדיר) - מתעלמים
        }
    }

    private fun sendGpsToMap(lat: Double, lon: Double, accuracy: Float? = null) {
        val accuracyArg = accuracy?.toString() ?: "null"
        binding.mapWebView.evaluateJavascript("centerOnGps('$lat', '$lon', $accuracyArg)", null)
    }

    // ============ מעקב GPS רציף (טוגל הדלקה/כיבוי) ============
    private var isGpsFollowActive = false
    private var gpsFollowListener: android.location.LocationListener? = null

    private fun toggleGpsFollow() {
        if (isGpsFollowActive) {
            stopGpsFollow()
        } else {
            val hasPermission = ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    this, Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED

            if (hasPermission) {
                startGpsFollow()
            } else {
                pendingGpsFollowAfterPermission = true
                locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
    }

    private var pendingGpsFollowAfterPermission = false

    /**
     * מתחיל מעקב רציף אחרי מיקום ה-GPS (provider מדויק בלבד, לא Network), ומרכז את המפה
     * אוטומטית בכל עדכון מיקום. מעדכן כל שנייה (1000ms) כפי שנדרש. נשאר פעיל כל עוד
     * הטוגל מודלק, גם אם המשתמש סוגר ופותח את המפה מחדש.
     */
    @SuppressLint("MissingPermission")
    private fun startGpsFollow() {
        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager

        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            Toast.makeText(this, getString(R.string.error_location_unavailable), Toast.LENGTH_SHORT).show()
            return
        }

        val listener = android.location.LocationListener { location ->
            sendGpsToMap(location.latitude, location.longitude, location.accuracy)
        }
        gpsFollowListener = listener

        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
            isGpsFollowActive = true
            updateGpsFollowButtonState()
            Toast.makeText(this, getString(R.string.gps_follow_on), Toast.LENGTH_SHORT).show()
        } catch (e: SecurityException) {
            Toast.makeText(this, getString(R.string.error_location_permission_denied), Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopGpsFollow() {
        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        gpsFollowListener?.let { locationManager.removeUpdates(it) }
        gpsFollowListener = null
        isGpsFollowActive = false
        updateGpsFollowButtonState()
        Toast.makeText(this, getString(R.string.gps_follow_off), Toast.LENGTH_SHORT).show()
    }

    private fun updateGpsFollowButtonState() {
        val activeStr = if (isGpsFollowActive) "true" else "false"
        binding.mapWebView.evaluateJavascript("setGpsFollowUiState($activeStr)", null)
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
    private var cachedSnapshotUri: Uri? = null

    private fun showResult(coords: Coordinates) {
        lastCoords = coords
        binding.latValue.text = coords.lat
        binding.lonValue.text = coords.lon
        binding.resultLayout.visibility = android.view.View.VISIBLE

        openFullscreenMap()
        binding.mapWebView.evaluateJavascript(
            "centerOnCoordinates('${coords.lat}', '${coords.lon}')", null
        )
        // ה-snapshot ל-WhatsApp נלכד אוטומטית ברגע הסגירה בפועל של חלון המפה
        // (closeFullscreenMap), נקי מכל פקד ממשק - לא כאן.
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

    /**
     * בונה את טקסט ההודעה לשליחה: תיאור חופשי (אם הוזן), קואורדינטות, קישור Google Maps
     * וקישור ניווט Waze. אותו טקסט משמש גם ל-SMS וגם ל-WhatsApp (ב-WhatsApp הוא מצורף
     * כקאפשן לתמונת ה-snapshot).
     */
    private fun buildShareMessage(coords: Coordinates): String {
        val description = binding.editDescription.text?.toString()?.trim().orEmpty()
        val mapsLink = "https://www.google.com/maps?q=${coords.lat},${coords.lon}"
        val wazeLink = "https://waze.com/ul?ll=${coords.lat},${coords.lon}&navigate=yes"

        val lines = mutableListOf<String>()
        if (description.isNotEmpty()) {
            lines.add("${getString(R.string.msg_point_label)}: $description")
        }
        lines.add("${getString(R.string.msg_coords_label)}: ${coords.lon}, ${coords.lat}")
        lines.add("${getString(R.string.msg_maps_label)}: $mapsLink")
        lines.add("${getString(R.string.msg_waze_label)}: $wazeLink")

        return lines.joinToString("\n")
    }

    /**
     * מצלם snapshot של ה-WebView שמציג את המפה, בדיוק כפי שהיא מוצגת על מסך המשתמש כרגע.
     *
     * משתמש ב-PixelCopy (זמין מ-API 24, תואם ל-minSdk של הפרויקט) ולא ב-webView.draw(canvas):
     * Google מציינת במפורש שאסור לקרוא ל-draw() על WebView, כי הוא לא לוכד נכון שכבות
     * שמורכבות בנפרד ע"י ה-GPU (hardware-composited layers) - וזה היה הגורם לכך שסימונים
     * (markers) של Leaflet, שמוצבים ע"י Chromium באמצעות transform נפרד, נראו "קופצים"
     * למיקום שגוי בתמונה שנשמרה, לעומת המיקום הנכון שהוצג בפועל במפה החיה.
     * PixelCopy לעומת זאת קורא את ה-Surface האמיתי שמוצג על המסך (את ה-Window כולו),
     * ולכן לוכד את כל השכבות המורכבות בדיוק כפי שהן נראות בפועל.
     *
     * PixelCopy הוא אסינכרוני (callback), אז גם הפונקציה הזו אסינכרונית.
     */
    private fun captureMapSnapshot(onResult: (Uri?) -> Unit) {
        val webView = binding.mapWebView
        if (webView.width == 0 || webView.height == 0) {
            onResult(null)
            return
        }

        val window = this.window
        if (window == null) {
            onResult(null)
            return
        }

        try {
            val location = IntArray(2)
            webView.getLocationInWindow(location)
            val rect = Rect(
                location[0], location[1],
                location[0] + webView.width, location[1] + webView.height
            )

            val bitmap = Bitmap.createBitmap(webView.width, webView.height, Bitmap.Config.ARGB_8888)

            PixelCopy.request(window, rect, bitmap, { copyResult ->
                if (copyResult == PixelCopy.SUCCESS) {
                    onResult(saveBitmapAndGetUri(bitmap))
                } else {
                    onResult(null)
                }
            }, Handler(Looper.getMainLooper()))
        } catch (e: Exception) {
            onResult(null)
        }
    }

    private fun saveBitmapAndGetUri(bitmap: Bitmap): Uri? {
        return try {
            val imagesDir = File(cacheDir, "images")
            if (!imagesDir.exists()) imagesDir.mkdirs()
            val file = File(imagesDir, "map_snapshot_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
            }
            FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * שולח SMS עם תוכן ההודעה (תיאור + קואורדינטות + קישורי Maps/Waze).
     * SMS לא תומך בצירוף תמונה באופן סטנדרטי, ולכן רק טקסט נשלח - אך הקישור ל-Google Maps
     * שבתוך הטקסט מציג ויזואלית את אותה נקודה כשהנמען פותח אותו.
     */
    private fun sendViaSms() {
        val coords = lastCoords ?: return
        val message = buildShareMessage(coords)

        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("smsto:")
            putExtra("sms_body", message)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.error_no_sms_app), Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * שולח ב-WhatsApp את אותו טקסט בתור קאפשן, בצירוף תצלום (snapshot) "נקי" של המפה -
     * רק המפה והסימונים עליה, בלי שום פקד ממשק (סרגל כלים, חיפוש, GPS, רמזים, בקרות זום).
     * התצלום נלכד אוטומטית בכל סגירה של חלון המפה (closeFullscreenMap), בדיוק כפי שהמפה
     * נראתה באותו רגע - לא כאן מחדש, כדי לא לסכן צילום שמכיל UI אם המפה עדיין פתוחה.
     * אם הצילום נכשל מסיבה כלשהי - נשלח לפחות הטקסט בלבד, כדי שהפעולה לא תיכשל כליל.
     */
    private fun sendViaWhatsapp() {
        val coords = lastCoords ?: return
        val message = buildShareMessage(coords)
        val activityContext = this

        val intent = Intent(Intent.ACTION_SEND).apply {
            setPackage("com.whatsapp")
            putExtra(Intent.EXTRA_TEXT, message)
            if (cachedSnapshotUri != null) {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, cachedSnapshotUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else {
                type = "text/plain"
                Toast.makeText(activityContext, getString(R.string.error_snapshot_failed), Toast.LENGTH_SHORT).show()
            }
        }

        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.error_no_whatsapp_app), Toast.LENGTH_SHORT).show()
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

    override fun onDestroy() {
        super.onDestroy()
        // עוצרים מעקב GPS רציף כדי לא להמשיך לצרוך סוללה אחרי שהאפליקציה נסגרה
        gpsFollowListener?.let {
            val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
            locationManager.removeUpdates(it)
        }
    }
}
