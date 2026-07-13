package com.shimonhoter.waze2coordinate

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.webkit.JavascriptInterface
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.shimonhoter.waze2coordinate.ui.MainCallbacks
import com.shimonhoter.waze2coordinate.ui.MainScreen
import com.shimonhoter.waze2coordinate.ui.MainUiState
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

    // ===== Compose state =====
    private var uiState by mutableStateOf(MainUiState())

    // ===== WebView reference (set via AndroidView factory) =====
    private var webView: android.webkit.WebView? = null

    // ===== Business logic state (not UI) =====
    private val httpClient = OkHttpClient.Builder()
        .followRedirects(true).followSslRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
        .build()

    private var currentSource: Source = Source.WAZE
    private var lastCoords: Coordinates? = null
    private var cachedSnapshotUri: Uri? = null
    private var isMapExpanded = false
    private var embeddedMapHeightPx = 0
    private var lastFinalUrl = ""; private var lastHttpCode = 0; private var lastBodySnippet = ""

    // GPS follow
    private var isGpsFollowActive = false
    private var gpsFollowListener: android.location.LocationListener? = null
    private var pendingGpsFollowAfterPermission = false

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            if (pendingGpsFollowAfterPermission) { pendingGpsFollowAfterPermission = false; startGpsFollow() }
            else centerMapOnGps()
        } else {
            pendingGpsFollowAfterPermission = false
            Toast.makeText(this, getString(R.string.error_location_permission_denied), Toast.LENGTH_SHORT).show()
        }
    }

    // ───────────────────────────────────────────────────────────
    // Lifecycle
    // ───────────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        applySavedThemeMode()
        super.onCreate(savedInstanceState)

        // WebView נוצר פעם אחת כאן, לפני setContent, ומועבר ל-MainScreen.
        // AndroidView בתוך MapCard קורא factory = { webView!! } — מחזיר את
        // אותה instance, לעולם לא יוצר חדש — ולכן גם recomposition וגם שינוי
        // isExpanded לא גורמים להרס ה-WebView.
        val wv = android.webkit.WebView(this)
        webView = wv
        setupMapWebView(wv)

        setContent {
            val isDark = remember {
                getSharedPreferences("app_prefs", MODE_PRIVATE).getBoolean("dark_mode", false)
            }
            uiState = uiState.copy(isDarkTheme = isDark)

            MainScreen(
                uiState = uiState,
                webView = wv,
                callbacks = MainCallbacks(
                    onUrlChange         = { uiState = uiState.copy(urlInput = it) },
                    onSourceChange      = { source ->
                        currentSource = source
                        uiState = uiState.copy(source = source, urlInput = "")
                    },
                    onConvert           = ::handleConvert,
                    onCopyCoords        = ::copyToClipboard,
                    onOpenMaps          = ::openInGoogleMaps,
                    onOpenWaze          = ::navigateWithWaze,
                    onSendMessage       = ::showSendMessageChooser,
                    onDescriptionChange = { uiState = uiState.copy(description = it) },
                    onToggleTheme       = ::toggleThemeMode,
                    onExpandMap         = ::expandMap,
                    onCollapseMap       = ::collapseMap,
                    onMapHeightDrag     = { delta ->
                        val density = resources.displayMetrics.density
                        val min = (120 * density).toInt()
                        val max = (resources.displayMetrics.heightPixels * 0.7).toInt()
                        embeddedMapHeightPx = (embeddedMapHeightPx + delta).coerceIn(min, max)
                        uiState = uiState.copy(mapHeightPx = embeddedMapHeightPx)
                    },
                    onGpsCenter         = ::requestLocationAndCenter,
                    onGpsFollow         = ::toggleGpsFollow,
                    onMapHeightMeasured = { measuredPx ->
                        if (measuredPx != embeddedMapHeightPx) {
                            embeddedMapHeightPx = measuredPx
                            uiState = uiState.copy(mapHeightPx = measuredPx)
                        }
                    },
                ),
            )
        }

        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        gpsFollowListener?.let {
            (getSystemService(LOCATION_SERVICE) as LocationManager).removeUpdates(it)
        }
    }

    // ───────────────────────────────────────────────────────────
    // Theme
    // ───────────────────────────────────────────────────────────

    private fun applySavedThemeMode() {
        val isDark = getSharedPreferences("app_prefs", MODE_PRIVATE).getBoolean("dark_mode", false)
        AppCompatDelegate.setDefaultNightMode(
            if (isDark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )
    }

    private fun toggleThemeMode() {
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val isDark = prefs.getBoolean("dark_mode", false)
        prefs.edit().putBoolean("dark_mode", !isDark).apply()
        uiState = uiState.copy(isDarkTheme = !isDark)
        // אין צורך ב-recreate() — Compose מגיב לשינוי isDarkTheme ב-state ישירות
    }

    private fun _dbg(msg: String) {
        webView?.evaluateJavascript("if(window._dbg)window._dbg(${JSONObject.quote("[KT] $msg")})", null)
        android.util.Log.d("W2C_DEBUG", msg)
    }

    // ───────────────────────────────────────────────────────────
    // Adaptive map height
    // ───────────────────────────────────────────────────────────

    /**
     * מחשב גובה מפה אדפטיבי שמותיר מקום לכרטיס התוצאה (אם גלוי) מתחתיו, וממלא את
     * שאר גובה המסך. בגלל שאין ViewTreeObserver ב-Compose, קוראים לזה אחרי שה-UI מרונדר
     * ראשונית דרך LaunchedEffect — עדיין עובד כי ה-density ידוע מיד, ואנחנו מחשבים
     * הכל לפי pixels/density בלי להסתמך על מיקום מדיד של views.
     */
    fun fitMapHeightToScreen(resultCardHeightPx: Int = 0) {
        val density = resources.displayMetrics.density
        val screenHeight = resources.displayMetrics.heightPixels
        // גובה משוער של header + input card + resize handle + margins (dp → px)
        val topContentEstimatePx = (280 * density).toInt()
        val resizeHandlePx = (18 * density).toInt()
        val safetyPx = (8 * density).toInt()

        val available = screenHeight - topContentEstimatePx - resizeHandlePx - resultCardHeightPx - safetyPx
        val min = (120 * density).toInt()
        val max = (screenHeight * 0.85).toInt()
        embeddedMapHeightPx = available.coerceIn(min, max)
        uiState = uiState.copy(mapHeightPx = embeddedMapHeightPx)
    }

    // ───────────────────────────────────────────────────────────
    // WebView setup
    // ───────────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupMapWebView(wv: android.webkit.WebView) {
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        @Suppress("SetJavaScriptEnabled")
        wv.settings.allowFileAccessFromFileURLs = true
        wv.settings.allowUniversalAccessFromFileURLs = true
        wv.addJavascriptInterface(MapJsBridge(), "AndroidBridge")

        wv.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onJsPrompt(
                view: android.webkit.WebView?, url: String?,
                message: String?, defaultValue: String?,
                result: android.webkit.JsPromptResult?
            ): Boolean {
                val input = android.widget.EditText(this@MainActivity).also {
                    it.setText(defaultValue.orEmpty())
                }
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(message.orEmpty()).setView(input)
                    .setPositiveButton(android.R.string.ok) { _, _ -> result?.confirm(input.text.toString()) }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> result?.cancel() }
                    .setCancelable(false)
                    .setOnCancelListener { result?.cancel() }
                    .show()
                return true
            }
        }

        wv.webViewClient = object : android.webkit.WebViewClient() {
            override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                super.onPageFinished(view, url)
                wv.evaluateJavascript("setEmbeddedChromeVisible(false)", null)
                val savedJson = loadShapesFromDisk()
                if (savedJson != null) {
                    wv.evaluateJavascript("loadShapesFromJson(${JSONObject.quote(savedJson)})", null)
                }
                // Set initial adaptive height after WebView is loaded
                fitMapHeightToScreen()
            }
        }

        wv.loadUrl("file:///android_asset/map.html")
    }

    // JS Bridge
    inner class MapJsBridge {
        @JavascriptInterface fun onMapPointSelected(lat: String, lon: String) = runOnUiThread { onMapTapped(lat, lon) }
        @JavascriptInterface fun onShapesChanged(shapesJson: String) = runOnUiThread { saveShapesToDisk(shapesJson) }
        @JavascriptInterface fun onAddressSearchResult(found: Boolean, lat: String, lon: String, displayName: String) {
            if (!found) runOnUiThread { Toast.makeText(this@MainActivity, getString(R.string.error_address_not_found), Toast.LENGTH_SHORT).show() }
        }
        @JavascriptInterface fun onRequestGpsCenter() = runOnUiThread { requestLocationAndCenter() }
        @JavascriptInterface fun onToggleGpsFollow() = runOnUiThread { toggleGpsFollow() }
        @JavascriptInterface fun onRequestCloseMap() = runOnUiThread { if (isMapExpanded) collapseMap() }
    }

    private fun expandMap() {
        isMapExpanded = true
        uiState = uiState.copy(isMapExpanded = true)
        webView?.evaluateJavascript("setEmbeddedChromeVisible(true)", null)
    }

    private fun collapseMap() {
        webView?.evaluateJavascript("setCleanCaptureMode(true)") {
            webView?.postDelayed({
                captureMapSnapshot { uri ->
                    cachedSnapshotUri = uri
                    webView?.evaluateJavascript("setCleanCaptureMode(false)", null)
                    webView?.evaluateJavascript("setEmbeddedChromeVisible(false)", null)
                    isMapExpanded = false
                    uiState = uiState.copy(isMapExpanded = false)
                }
            }, 16)
        }
    }

    // ───────────────────────────────────────────────────────────
    // Map tap → auto-convert
    // ───────────────────────────────────────────────────────────

    private fun onMapTapped(lat: String, lon: String) {
        currentSource = Source.MAPS
        val mapsUrl = "https://www.google.com/maps?q=$lat,$lon"
        uiState = uiState.copy(source = Source.MAPS, urlInput = mapsUrl)
        handleConvert()
        if (isMapExpanded) collapseMap()
    }

    // ───────────────────────────────────────────────────────────
    // GPS
    // ───────────────────────────────────────────────────────────

    private fun requestLocationAndCenter() {
        val hasPerm = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                      ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (hasPerm) centerMapOnGps() else locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    @SuppressLint("MissingPermission")
    private fun centerMapOnGps() {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            Toast.makeText(this, getString(R.string.error_location_unavailable), Toast.LENGTH_SHORT).show(); return
        }
        val last = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        if (last != null && (System.currentTimeMillis() - last.time) < 30_000 && (!last.hasAccuracy() || last.accuracy <= 50f))
            sendGpsToMap(last.latitude, last.longitude, last.accuracy)
        try {
            lm.requestSingleUpdate(LocationManager.GPS_PROVIDER, { loc -> sendGpsToMap(loc.latitude, loc.longitude, loc.accuracy) }, Looper.getMainLooper())
        } catch (e: SecurityException) { }
    }

    private fun sendGpsToMap(lat: Double, lon: Double, accuracy: Float? = null) {
        webView?.evaluateJavascript("centerOnGps('$lat', '$lon', ${accuracy ?: "null"})", null)
    }

    private fun toggleGpsFollow() { if (isGpsFollowActive) stopGpsFollow() else {
        val hasPerm = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                      ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (hasPerm) startGpsFollow() else { pendingGpsFollowAfterPermission = true; locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) }
    }}

    @SuppressLint("MissingPermission")
    private fun startGpsFollow() {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            Toast.makeText(this, getString(R.string.error_location_unavailable), Toast.LENGTH_SHORT).show(); return
        }
        val listener = android.location.LocationListener { loc -> sendGpsToMap(loc.latitude, loc.longitude, loc.accuracy) }
        gpsFollowListener = listener
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
            isGpsFollowActive = true
            webView?.evaluateJavascript("setGpsFollowUiState(true)", null)
            uiState = uiState.copy(isGpsFollowActive = true)
            Toast.makeText(this, getString(R.string.gps_follow_on), Toast.LENGTH_SHORT).show()
        } catch (e: SecurityException) {
            Toast.makeText(this, getString(R.string.error_location_permission_denied), Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopGpsFollow() {
        (getSystemService(LOCATION_SERVICE) as LocationManager).also { lm ->
            gpsFollowListener?.let { lm.removeUpdates(it) }
        }
        gpsFollowListener = null; isGpsFollowActive = false
        webView?.evaluateJavascript("setGpsFollowUiState(false)", null)
        uiState = uiState.copy(isGpsFollowActive = false)
        Toast.makeText(this, getString(R.string.gps_follow_off), Toast.LENGTH_SHORT).show()
    }

    // ───────────────────────────────────────────────────────────
    // Intent / URL handling
    // ───────────────────────────────────────────────────────────

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        val sharedText = when (intent.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        } ?: return

        detectSource(sharedText)?.let { src ->
            currentSource = src
            uiState = uiState.copy(source = src)
        }
        extractUrlFromText(sharedText)?.let { url ->
            uiState = uiState.copy(urlInput = url)
            handleConvert()
        }
    }

    private fun detectSource(text: String): Source? = when {
        text.contains("waze.com", ignoreCase = true) -> Source.WAZE
        text.contains("google.com/maps", ignoreCase = true) ||
        text.contains("maps.app.goo.gl", ignoreCase = true) ||
        text.contains("goo.gl/maps", ignoreCase = true) -> Source.MAPS
        else -> null
    }

    private fun extractUrlFromText(text: String): String? {
        val m = Pattern.compile("https?://\\S+").matcher(text)
        return if (m.find()) m.group() else text.trim().takeIf { it.isNotBlank() }
    }

    // ───────────────────────────────────────────────────────────
    // Coordinate resolution
    // ───────────────────────────────────────────────────────────

    private fun handleConvert() {
        val url = uiState.urlInput.trim()
        uiState = uiState.copy(errorMessage = null, coordinates = null)
        fitMapHeightToScreen(0)

        if (url.isBlank()) { uiState = uiState.copy(errorMessage = getString(R.string.error_empty_url)); return }

        uiState = uiState.copy(isLoading = true)
        lifecycleScope.launch {
            try {
                val coords = withContext(Dispatchers.IO) { resolveCoordinates(url, currentSource) }
                uiState = uiState.copy(isLoading = false)
                if (coords != null) showResult(coords)
                else {
                    val dbg = "URL סופי: $lastFinalUrl\nקוד HTTP: $lastHttpCode\nתחילת תגובה: $lastBodySnippet"
                    uiState = uiState.copy(errorMessage = getString(R.string.error_no_coords) + "\n\n" + dbg)
                }
            } catch (e: Exception) {
                uiState = uiState.copy(isLoading = false, errorMessage = getString(R.string.error_network) + "\n" + (e.message ?: ""))
            }
        }
    }

    private fun resolveCoordinates(inputUrl: String, source: Source): Coordinates? {
        val req = Request.Builder().url(inputUrl).header("User-Agent", "Mozilla/5.0 (Android) Waze2Coordinate/1.0").build()
        httpClient.newCall(req).execute().use { response ->
            val finalUrl = response.request.url.toString()
            lastFinalUrl = finalUrl; lastHttpCode = response.code
            extractCoordsFromUrl(finalUrl, source)?.let { return it }
            val body = response.body?.string() ?: ""
            lastBodySnippet = body.take(500)
            return extractCoordsFromUrl(body, source)
        }
    }

    private fun extractCoordsFromUrl(text: String, source: Source) = when (source) {
        Source.WAZE -> extractWazeCoords(text)
        Source.MAPS -> extractMapsCoords(text)
    }

    private fun extractWazeCoords(text: String): Coordinates? {
        listOf(
            "[?&](ll|q)=(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)" to Pair(2, 3),
            "[?&](ll|q)=(-?\\d+\\.\\d+)%2C(-?\\d+\\.\\d+)" to Pair(2, 3),
            "to=ll\\.(-?\\d+\\.\\d+)%2C(-?\\d+\\.\\d+)" to Pair(1, 2),
            "to=ll\\.(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)" to Pair(1, 2),
            "to/ll\\.(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)" to Pair(1, 2),
        ).forEach { (pat, idx) ->
            val m = Pattern.compile(pat, Pattern.CASE_INSENSITIVE).matcher(text)
            if (m.find()) return Coordinates(m.group(idx.first)!!, m.group(idx.second)!!)
        }
        return extractGenericCoords(text)
    }

    private fun extractMapsCoords(text: String): Coordinates? {
        listOf(
            "@(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)" to Pair(1, 2),
            "[?&](q|query)=(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)" to Pair(2, 3),
            "!3d(-?\\d+\\.\\d+)!4d(-?\\d+\\.\\d+)" to Pair(1, 2),
            "[?&](q|query)=(-?\\d+\\.\\d+)%2C(-?\\d+\\.\\d+)" to Pair(2, 3),
        ).forEach { (pat, idx) ->
            val m = Pattern.compile(pat, Pattern.CASE_INSENSITIVE).matcher(text)
            if (m.find()) return Coordinates(m.group(idx.first)!!, m.group(idx.second)!!)
        }
        return extractGenericCoords(text)
    }

    private fun extractGenericCoords(text: String): Coordinates? {
        val m = Pattern.compile("(-?\\d{1,3}\\.\\d{3,})\\s*,\\s*(-?\\d{1,3}\\.\\d{3,})").matcher(text)
        while (m.find()) {
            val lat = m.group(1)!!.toDoubleOrNull() ?: continue
            val lon = m.group(2)!!.toDoubleOrNull() ?: continue
            if (lat in -90.0..90.0 && lon in -180.0..180.0) return Coordinates(m.group(1)!!, m.group(2)!!)
        }
        return null
    }

    // ───────────────────────────────────────────────────────────
    // Result display
    // ───────────────────────────────────────────────────────────

    private fun showResult(coords: Coordinates) {
        lastCoords = coords
        uiState = uiState.copy(coordinates = "${coords.lat}, ${coords.lon}")
        // estimate result card height for adaptive map recalc (~200dp)
        val resultEstimatePx = (200 * resources.displayMetrics.density).toInt()
        fitMapHeightToScreen(resultEstimatePx)
        webView?.evaluateJavascript("centerOnCoordinates('${coords.lat}', '${coords.lon}')", null)
    }

    private fun copyToClipboard() {
        val coords = lastCoords ?: return
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("coordinates", "${coords.lat}, ${coords.lon}"))
        Toast.makeText(this, getString(R.string.msg_copied), Toast.LENGTH_SHORT).show()
    }

    private fun openInGoogleMaps() {
        val coords = lastCoords ?: return
        val uri = Uri.parse("geo:${coords.lat},${coords.lon}?q=${coords.lat},${coords.lon}")
        try { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
        catch (e: Exception) { Toast.makeText(this, getString(R.string.error_no_maps_app), Toast.LENGTH_SHORT).show() }
    }

    private fun navigateWithWaze() {
        val coords = lastCoords ?: return
        val uri = Uri.parse("waze://?ll=${coords.lat},${coords.lon}&navigate=yes")
        try { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
        catch (e: Exception) { Toast.makeText(this, getString(R.string.error_no_waze_app), Toast.LENGTH_SHORT).show() }
    }

    // ───────────────────────────────────────────────────────────
    // Sharing
    // ───────────────────────────────────────────────────────────

    private fun showSendMessageChooser() {
        if (lastCoords == null) return
        AlertDialog.Builder(this)
            .setTitle(R.string.send_message_title)
            .setItems(arrayOf(getString(R.string.btn_send_sms), getString(R.string.btn_send_whatsapp))) { _, i ->
                if (i == 0) sendViaSms() else sendViaWhatsapp()
            }.show()
    }

    private fun buildShareMessage(coords: Coordinates): String {
        val desc = uiState.description.trim()
        val mapsLink = "https://www.google.com/maps?q=${coords.lat},${coords.lon}"
        val wazeLink = "https://waze.com/ul?ll=${coords.lat},${coords.lon}&navigate=yes"
        return buildList {
            if (desc.isNotEmpty()) add("${getString(R.string.msg_point_label)}: $desc")
            add("${getString(R.string.msg_coords_label)}: ${coords.lon}, ${coords.lat}")
            add("${getString(R.string.msg_maps_label)}: $mapsLink")
            add("${getString(R.string.msg_waze_label)}: $wazeLink")
        }.joinToString("\n")
    }

    private fun sendViaSms() {
        val coords = lastCoords ?: return
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("smsto:"); putExtra("sms_body", buildShareMessage(coords))
        }
        try { startActivity(intent) }
        catch (e: Exception) { Toast.makeText(this, getString(R.string.error_no_sms_app), Toast.LENGTH_SHORT).show() }
    }

    private fun sendViaWhatsapp() {
        val coords = lastCoords ?: return
        if (cachedSnapshotUri != null) launchWhatsappIntent(coords, cachedSnapshotUri)
        else captureMapSnapshot { uri -> cachedSnapshotUri = uri; launchWhatsappIntent(coords, uri) }
    }

    private fun launchWhatsappIntent(coords: Coordinates, snapshotUri: Uri?) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            setPackage("com.whatsapp"); putExtra(Intent.EXTRA_TEXT, buildShareMessage(coords))
            if (snapshotUri != null) { type = "image/png"; putExtra(Intent.EXTRA_STREAM, snapshotUri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            else type = "text/plain"
        }
        if (snapshotUri == null) Toast.makeText(this, getString(R.string.error_snapshot_failed), Toast.LENGTH_SHORT).show()
        try { startActivity(intent) }
        catch (e: Exception) { Toast.makeText(this, getString(R.string.error_no_whatsapp_app), Toast.LENGTH_SHORT).show() }
    }

    // ───────────────────────────────────────────────────────────
    // Map snapshot (PixelCopy)
    // ───────────────────────────────────────────────────────────

    private fun captureMapSnapshot(onResult: (Uri?) -> Unit) {
        val wv = webView ?: return onResult(null)
        if (wv.width == 0 || wv.height == 0) return onResult(null)
        try {
            val loc = IntArray(2); wv.getLocationInWindow(loc)
            val rect = Rect(loc[0], loc[1], loc[0] + wv.width, loc[1] + wv.height)
            val bmp = Bitmap.createBitmap(wv.width, wv.height, Bitmap.Config.ARGB_8888)
            PixelCopy.request(window, rect, bmp, { res ->
                onResult(if (res == PixelCopy.SUCCESS) saveBitmapAndGetUri(bmp) else null)
            }, Handler(Looper.getMainLooper()))
        } catch (e: Exception) { onResult(null) }
    }

    private fun saveBitmapAndGetUri(bmp: Bitmap): Uri? = try {
        val dir = File(cacheDir, "images").also { it.mkdirs() }
        val file = File(dir, "map_snapshot_${System.currentTimeMillis()}.png")
        FileOutputStream(file).use { out -> bmp.compress(Bitmap.CompressFormat.PNG, 90, out) }
        FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
    } catch (e: Exception) { null }

    // ───────────────────────────────────────────────────────────
    // Shapes persistence
    // ───────────────────────────────────────────────────────────

    private val shapesFile: File get() = File(filesDir, "saved_shapes.json")

    private fun saveShapesToDisk(json: String) = try { shapesFile.writeText(json) } catch (_: Exception) {}

    private fun loadShapesFromDisk(): String? = try {
        if (shapesFile.exists()) shapesFile.readText() else null
    } catch (_: Exception) { null }
}
