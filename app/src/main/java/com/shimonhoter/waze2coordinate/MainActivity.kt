package com.shimonhoter.waze2coordinate

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.view.View
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
        binding.btnCloseMap.setOnClickListener { closeFullscreenMap() }
        binding.btnCenterGps.setOnClickListener { requestLocationAndCenter() }
        binding.btnToggleGpsFollow.setOnClickListener { toggleGpsFollow() }

        binding.mapModeToggle.check(binding.btnModeSelect.id)
        binding.mapModeToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val mode = when (checkedId) {
                    binding.btnModeDraw.id -> "draw"
                    binding.btnModeEdit.id -> "edit"
                    else -> "select"
                }
                binding.mapWebView.evaluateJavascript("setMapMode('$mode')", null)
                updateMapHintForMode(mode)
            }
        }

        binding.btnMapStreet.setOnClickListener { switchMapTileStyle("street") }
        binding.btnMapSatellite.setOnClickListener { switchMapTileStyle("satellite") }

        binding.btnAddressSearch.setOnClickListener { performAddressSearch() }
        binding.editAddressSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                performAddressSearch()
                true
            } else {
                false
            }
        }

        binding.btnConvert.setOnClickListener { handleConvert() }
        binding.btnCopy.setOnClickListener { copyToClipboard() }
        binding.btnOpenMaps.setOnClickListener { openInGoogleMaps() }
        binding.btnNavigateWaze.setOnClickListener { navigateWithWaze() }
        binding.btnSendSms.setOnClickListener { sendViaSms() }
        binding.btnSendWhatsapp.setOnClickListener { sendViaWhatsapp() }

        handleIncomingIntent(intent)
    }

    private fun switchMapTileStyle(style: String) {
        binding.mapWebView.evaluateJavascript("switchMapStyle('$style')", null)
        val selectedColor = android.graphics.Color.parseColor("#3498db")
        val unselectedColor = android.graphics.Color.parseColor("#cbd5e1")
        binding.btnMapStreet.setTextColor(if (style == "street") selectedColor else unselectedColor)
        binding.btnMapSatellite.setTextColor(if (style == "satellite") selectedColor else unselectedColor)
    }

    private fun updateMapHintForMode(mode: String) {
        binding.mapHintText.text = when (mode) {
            "draw" -> getString(R.string.map_tap_hint_draw)
            "edit" -> getString(R.string.map_tap_hint_edit)
            else -> getString(R.string.map_tap_hint)
        }
    }

    private fun performAddressSearch() {
        val query = binding.editAddressSearch.text?.toString()?.trim()
        if (query.isNullOrBlank()) return
        binding.mapWebView.evaluateJavascript("searchAddress(${JSONObject.quote(query)})", null)
    }

    /**
     * Bridge בין דף ה-Leaflet שרץ ב-WebView לקוד Kotlin. מטפל בהקשות לבחירת נקודה,
     * שינויים בציורים (לשמירה אוטומטית), ותוצאות חיפוש כתובת.
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
                if (found) {
                    binding.editAddressSearch.clearFocus()
                    val inputManager = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                    inputManager.hideSoftInputFromWindow(binding.editAddressSearch.windowToken, 0)
                } else {
                    Toast.makeText(this@MainActivity, getString(R.string.error_address_not_found), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun setupMapWebView() {
        val webView = binding.mapWebView
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.addJavascriptInterface(MapJsBridge(), "AndroidBridge")

        // טוען את הציורים השמורים מהדיסק לתוך המפה ברגע שהדף סיים להיטען
        webView.webViewClient = object : android.webkit.WebViewClient() {
            override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                super.onPageFinished(view, url)
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

    /** סוגר את חלון המפה ומחזיר למסך הראשי */
    private fun closeFullscreenMap() {
        isMapVisible = false
        binding.mapFullscreenContainer.visibility = View.GONE
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
     * מאתר את המיקום הנוכחי של המכשיר באמצעות LocationManager (GPS + Network providers,
     * כל מה שזמין) ומרכז את המפה אליו. משתמש ב-getLastKnownLocation לתשובה מיידית,
     * ובמקביל מבקש עדכון חד-פעמי טרי (single update) כדי לדייק אם יש המתנה.
     */
    @SuppressLint("MissingPermission")
    private fun centerMapOnGps() {
        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager

        val providers = locationManager.getProviders(true)
        var lastKnown: android.location.Location? = null
        for (provider in providers) {
            val loc = locationManager.getLastKnownLocation(provider)
            if (loc != null && (lastKnown == null || loc.time > lastKnown!!.time)) {
                lastKnown = loc
            }
        }

        if (lastKnown != null) {
            sendGpsToMap(lastKnown!!.latitude, lastKnown!!.longitude)
        }

        // בקשת עדכון חד-פעמי טרי, ליתר דיוק (בעיקר רלוונטי אם אין עדיין lastKnownLocation בכלל)
        val bestProvider = locationManager.getBestProvider(
            android.location.Criteria().apply { accuracy = android.location.Criteria.ACCURACY_FINE },
            true
        )
        if (bestProvider != null) {
            try {
                locationManager.requestSingleUpdate(bestProvider, object : android.location.LocationListener {
                    override fun onLocationChanged(location: android.location.Location) {
                        sendGpsToMap(location.latitude, location.longitude)
                    }
                }, Looper.getMainLooper())
            } catch (e: SecurityException) {
                // הרשאה לא קיימת בפועל (race condition נדיר) - מתעלמים, ה-lastKnown מספיק
            }
        } else if (lastKnown == null) {
            Toast.makeText(this, getString(R.string.error_location_unavailable), Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendGpsToMap(lat: Double, lon: Double) {
        binding.mapWebView.evaluateJavascript("centerOnGps('$lat', '$lon')", null)
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
     * מתחיל מעקב רציף אחרי מיקום ה-GPS, ומרכז את המפה אוטומטית בכל עדכון מיקום.
     * נשאר פעיל כל עוד הטוגל מודלק, גם אם המשתמש סוגר ופותח את המפה מחדש.
     */
    @SuppressLint("MissingPermission")
    private fun startGpsFollow() {
        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        val listener = android.location.LocationListener { location ->
            sendGpsToMap(location.latitude, location.longitude)
        }
        gpsFollowListener = listener

        try {
            val providers = locationManager.getProviders(true)
            for (provider in providers) {
                locationManager.requestLocationUpdates(provider, 3000L, 5f, listener, Looper.getMainLooper())
            }
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
        val color = if (isGpsFollowActive) {
            android.graphics.Color.parseColor("#3498db")
        } else {
            android.graphics.Color.parseColor("#95a5a6")
        }
        binding.btnToggleGpsFollow.imageTintList = android.content.res.ColorStateList.valueOf(color)
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

        // מצלמים snapshot של המפה כעת, כשהיא גלויה ומורכזת בפועל על המסך - ושומרים אותו
        // במטמון. כך, גם כשהמפה תיסגר כדי שהמשתמש יגיע לכפתורי השליחה, יהיה לנו תצלום
        // תקף לשימוש בשליחה ב-WhatsApp.
        binding.mapWebView.postDelayed({ cachedSnapshotUri = captureMapSnapshot() }, 600)
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
     * שומר את התמונה כקובץ זמני בתיקיית ה-cache ומחזיר Uri דרך FileProvider שניתן לשתף
     * עם אפליקציות אחרות (WhatsApp וכו'). מחזיר null אם המפה אינה פתוחה כרגע (ולכן אין
     * ל-WebView מימדים בפועל) או אם הצילום נכשל מסיבה אחרת.
     */
    private fun captureMapSnapshot(): Uri? {
        val webView = binding.mapWebView
        if (webView.width == 0 || webView.height == 0) return null

        return try {
            val bitmap = Bitmap.createBitmap(webView.width, webView.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            webView.draw(canvas)

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
     * שולח ב-WhatsApp את אותו טקסט בתור קאפשן, בצירוף תצלום (snapshot) של המפה כתמונה.
     * אם המפה פתוחה כרגע על המסך - מצלמים תצלום טרי. אחרת משתמשים בתצלום שנשמר במטמון
     * מהרגע שהמפה נפתחה ומורכזה אוטומטית בעת החישוב (ב-showResult). אם שני המקורות נכשלים,
     * נשלח לפחות הטקסט בלבד כדי שהפעולה לא תיכשל כליל.
     */
    private fun sendViaWhatsapp() {
        val coords = lastCoords ?: return
        val message = buildShareMessage(coords)
        val snapshotUri = if (isMapVisible) captureMapSnapshot() else null
        val finalSnapshotUri = snapshotUri ?: cachedSnapshotUri
        val activityContext = this

        val intent = Intent(Intent.ACTION_SEND).apply {
            setPackage("com.whatsapp")
            putExtra(Intent.EXTRA_TEXT, message)
            if (finalSnapshotUri != null) {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, finalSnapshotUri)
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
