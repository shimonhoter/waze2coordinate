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
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.graphics.Rect
import android.webkit.JavascriptInterface
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
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
    private var isMapExpanded = false
    private var embeddedMapHeightPx = 0

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
        applySavedThemeMode()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnToggleTheme.setOnClickListener { toggleThemeMode() }

        binding.sourceToggle.check(binding.btnSourceWaze.id)
        updateHintForSource()

        binding.sourceToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                currentSource = if (checkedId == binding.btnSourceMaps.id) Source.MAPS else Source.WAZE
                updateHintForSource()
            }
        }

        setupMapWebView()
        setupMapResizeHandle()

        binding.btnExpandMap.setOnClickListener { expandMap() }
        binding.btnCollapseMap.setOnClickListener { collapseMap() }

        binding.btnConvert.setOnClickListener { handleConvert() }
        binding.coordPill.setOnClickListener { copyToClipboard() }
        binding.btnOpenMaps.setOnClickListener { openInGoogleMaps() }
        binding.btnNavigateWaze.setOnClickListener { navigateWithWaze() }
        binding.btnSendMessage.setOnClickListener { showSendMessageChooser() }

        handleIncomingIntent(intent)
    }

    /**
     * טוגל מצב כהה/בהיר ידני (לא תלוי בהגדרת המערכת) - נשמר ב-SharedPreferences
     * ונטען בכל פתיחה של האפליקציה, לפני super.onCreate() כדי שהתמה תיכנס לתוקף
     * מיד בלי "קפיצה" חזותית.
     */
    private fun applySavedThemeMode() {
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val isDark = prefs.getBoolean("dark_mode", false)
        AppCompatDelegate.setDefaultNightMode(
            if (isDark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )
    }

    private fun toggleThemeMode() {
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val isDark = prefs.getBoolean("dark_mode", false)
        prefs.edit().putBoolean("dark_mode", !isDark).apply()
        AppCompatDelegate.setDefaultNightMode(
            if (!isDark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )
        recreate()
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
                if (isMapExpanded) collapseMap()
            }
        }
    }

    private fun setupMapWebView() {
        val webView = binding.mapWebView
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.addJavascriptInterface(MapJsBridge(), "AndroidBridge")

        // WebChromeClient.onJsPrompt חיוני כאן: WebView הרגיל לא מטפל ב-window.prompt()
        // של JavaScript מאליו (בניגוד לדפדפן רגיל) - בלי handler מפורש, הקריאה חוזרת
        // באופן שקט עם null/ריק בלי שום UI נראה למשתמש. זה היה הגורם לכך שבחירת כלי
        // הטקסט "לא עבדה" - ה-prompt() בתוך placeTextLabelAt() נכשל בשקט ומחזיר מצב
        // בחירה מיד אחרי, בלי שהמשתמש קיבל הזדמנות להקליד טקסט בכלל.
        webView.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onJsPrompt(
                view: android.webkit.WebView?,
                url: String?,
                message: String?,
                defaultValue: String?,
                result: android.webkit.JsPromptResult?
            ): Boolean {
                val input = android.widget.EditText(this@MainActivity)
                input.setText(defaultValue.orEmpty())

                androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle(message.orEmpty())
                    .setView(input)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        result?.confirm(input.text.toString())
                    }
                    .setNegativeButton(android.R.string.cancel) { _, _ ->
                        result?.cancel()
                    }
                    .setCancelable(false)
                    .setOnCancelListener {
                        // רשת ביטחון: אם הדיאלוג נסגר בכל זאת בלי אחד מהכפתורים (לדוגמה
                        // כפתור חזרה של המכשיר) - חובה לקרוא ל-cancel(), אחרת ה-JS thread
                        // נשאר תקוע לנצח מחכה לתשובה שלא תגיע
                        result?.cancel()
                    }
                    .show()

                return true
            }
        }

        // טוען את הציורים השמורים מהדיסק לתוך המפה ברגע שהדף סיים להיטען, ומאתחל
        // אותה למצב "מוטמעת" (ללא סרגל כלים/חיפוש/GPS - רק מפה נקייה עם סמן בחירה).
        webView.webViewClient = object : android.webkit.WebViewClient() {
            override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                super.onPageFinished(view, url)
                webView.evaluateJavascript("setEmbeddedChromeVisible(false)", null)
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

    /**
     * ידית הגרירה שמתחת לאזור המפה המוטמעת - גרירה אנכית משנה את גובה ה-FrameLayout
     * שעוטף את ה-WebView בזמן אמת. מוגבל לטווח גובה הגיוני (120dp עד 70% מגובה המסך)
     * כדי שהאזור לא יתכווץ/יתפוצץ למידות לא שמישות.
     */
    private fun setupMapResizeHandle() {
        val minHeightPx = (120 * resources.displayMetrics.density).toInt()
        var startHeight = 0
        var startY = 0f

        binding.mapResizeHandle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startHeight = binding.embeddedMapContainer.height
                    startY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val maxHeightPx = (resources.displayMetrics.heightPixels * 0.7).toInt()
                    val delta = (event.rawY - startY).toInt()
                    val newHeight = (startHeight + delta).coerceIn(minHeightPx, maxHeightPx)
                    val params = binding.embeddedMapContainer.layoutParams
                    params.height = newHeight
                    binding.embeddedMapContainer.layoutParams = params
                    embeddedMapHeightPx = newHeight
                    true
                }
                else -> true
            }
        }
    }

    /**
     * מרחיב את המפה למסך מלא: מעלה את ה-container הקיים (לא יוצר WebView חדש - כך
     * שלא נטען מחדש ולא מאבד מצב) לשכבה העליונה ביותר של המסך, משנה את מימדיו ל-
     * match_parent, ומבקש מה-JS להציג את כל פקדי הממשק (סרגל כלים, חיפוש, GPS).
     */
    private fun expandMap() {
        isMapExpanded = true
        embeddedMapHeightPx = binding.embeddedMapContainer.height

        val container = binding.embeddedMapContainer
        (container.parent as? ViewGroup)?.let { oldParent ->
            oldParent.removeView(container)
        }

        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        binding.rootFrame.addView(container, params)
        container.bringToFront()

        binding.btnExpandMap.visibility = View.GONE
        binding.btnCollapseMap.visibility = View.VISIBLE
        binding.btnCollapseMap.bringToFront()

        binding.mapWebView.evaluateJavascript("setEmbeddedChromeVisible(true)", null)
    }

    /**
     * מחזיר את המפה למצב מוטמעת: מקבל snapshot "נקי" (ללא UI) כמו בעבר, מחזיר את
     * ה-container לכרטיס המקורי בתוך הגלילה, ומסתיר את פקדי הממשק המתקדמים מהמפה.
     */
    private fun collapseMap() {
        binding.mapWebView.evaluateJavascript("setCleanCaptureMode(true)") {
            binding.mapWebView.postDelayed({
                captureMapSnapshot { uri ->
                    cachedSnapshotUri = uri
                    binding.mapWebView.evaluateJavascript("setCleanCaptureMode(false)", null)
                    binding.mapWebView.evaluateJavascript("setEmbeddedChromeVisible(false)", null)

                    val container = binding.embeddedMapContainer
                    (container.parent as? ViewGroup)?.removeView(container)

                    // מחזירים את ה-container לכרטיס המקורי, בדיוק במקום שבו הוא חי ב-XML
                    val cardContent = binding.mapCardContent
                    val params = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        if (embeddedMapHeightPx > 0) embeddedMapHeightPx else (240 * resources.displayMetrics.density).toInt()
                    )
                    cardContent.addView(container, 0, params)

                    isMapExpanded = false
                    binding.btnCollapseMap.visibility = View.GONE
                    binding.btnExpandMap.visibility = View.VISIBLE
                }
            }, 16)
        }
    }

    /**
     * כשבוחרים נקודה במפה (במצב select, גם מוטמע וגם מורחב) - בונים קישור Google Maps
     * תקני (q=lat,lon), שמים בשדה, עוברים אוטומטית לטוגל "קישור Google Maps", וממירים
     * אותו (מיידי, ללא קריאת רשת כי הקואורדינטות כבר בקישור עצמו). אם המפה הייתה
     * מורחבת למסך מלא בזמן הבחירה - מכווצים אותה בחזרה אוטומטית; במצב מוטמע נשארת
     * המפה גלויה כרגיל בעמוד הראשי.
     */
    private fun onMapTapped(lat: String, lon: String) {
        currentSource = Source.MAPS
        binding.sourceToggle.check(binding.btnSourceMaps.id)
        updateHintForSource()

        val mapsUrl = "https://www.google.com/maps?q=$lat,$lon"
        binding.editUrl.setText(mapsUrl)
        handleConvert()

        if (isMapExpanded) {
            collapseMap()
        }
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
        binding.resultLayout.visibility = View.GONE

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
        binding.coordCombined.text = "${coords.lat}, ${coords.lon}"
        binding.resultLayout.visibility = View.VISIBLE

        // ממרכזים את המפה המוטמעת על התוצאה - היא תמיד גלויה בעמוד, אין צורך להרחיב
        // אותה אוטומטית. ה-snapshot ל-WhatsApp נלכד בנפרד בעת כיווץ (אם המשתמש הרחיב
        // את המפה והוסיף ציורים), או נכשל בחן אם נדרש בלי שהמפה הורחבה כלל.
        binding.mapWebView.evaluateJavascript(
            "centerOnCoordinates('${coords.lat}', '${coords.lon}')", null
        )
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
     * פותח תפריט בחירה קטן (AlertDialog) בין שליחה ב-SMS לשליחה ב-WhatsApp - כניסה אחת
     * במקום שני כפתורים נפרדים, כדי לצמצם את העומס הוויזואלי במסך התוצאה.
     */
    private fun showSendMessageChooser() {
        if (lastCoords == null) return
        val options = arrayOf(getString(R.string.btn_send_sms), getString(R.string.btn_send_whatsapp))
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.send_message_title)
            .setItems(options) { _, index ->
                if (index == 0) sendViaSms() else sendViaWhatsapp()
            }
            .show()
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
     * רק המפה והסימונים עליה, בלי שום פקד ממשק. אם יש כבר תצלום במטמון (מהרחבה/כיווץ
     * קודמים בסשן הזה) - משתמשים בו. אחרת לוכדים כרגע תצלום של המפה המוטמעת כמו שהיא,
     * כי במצב מוטמע אין UI מוצג מעליה מלכתחילה (chrome מוסתר כברירת מחדל).
     * אם הצילום נכשל מסיבה כלשהי - נשלח לפחות הטקסט בלבד, כדי שהפעולה לא תיכשל כליל.
     */
    private fun sendViaWhatsapp() {
        val coords = lastCoords ?: return

        if (cachedSnapshotUri != null) {
            launchWhatsappIntent(coords, cachedSnapshotUri)
        } else {
            captureMapSnapshot { uri ->
                cachedSnapshotUri = uri
                launchWhatsappIntent(coords, uri)
            }
        }
    }

    private fun launchWhatsappIntent(coords: Coordinates, snapshotUri: Uri?) {
        val message = buildShareMessage(coords)
        val activityContext = this

        val intent = Intent(Intent.ACTION_SEND).apply {
            setPackage("com.whatsapp")
            putExtra(Intent.EXTRA_TEXT, message)
            if (snapshotUri != null) {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, snapshotUri)
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
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnConvert.isEnabled = !loading
    }

    private fun showError(msg: String) {
        binding.errorText.text = msg
        binding.errorText.visibility = View.VISIBLE
    }

    private fun hideError() {
        binding.errorText.visibility = View.GONE
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
