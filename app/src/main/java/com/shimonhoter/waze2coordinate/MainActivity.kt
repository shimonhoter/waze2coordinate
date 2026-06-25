package com.shimonhoter.waze2coordinate

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
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

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // OkHttp client שעוקב אחרי redirects אוטומטית - בלי שום proxy, כי זה native HTTP ולא דפדפן
    private val httpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnConvert.setOnClickListener { handleConvert() }
        binding.btnCopy.setOnClickListener { copyToClipboard() }
        binding.btnOpenMaps.setOnClickListener { openInGoogleMaps() }

        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    /**
     * מטפל בקישור שמגיע מ-Share intent (אפליקציה אחרת ששיתפה טקסט/קישור),
     * או מ-VIEW intent (לחיצה ישירה על קישור waze.com/ul/...).
     * אם זוהה קישור Waze תקין - ממלא את השדה ומתחיל פענוח אוטומטית.
     */
    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return

        val sharedUrl: String? = when (intent.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        }

        if (!sharedUrl.isNullOrBlank()) {
            val extractedUrl = extractWazeUrlFromText(sharedUrl)
            if (extractedUrl != null) {
                binding.editUrl.setText(extractedUrl)
                handleConvert()
            }
        }
    }

    /** טקסט משותף עלול להכיל מילים נוספות מסביב לקישור - מחלצים רק את ה-URL */
    private fun extractWazeUrlFromText(text: String): String? {
        val pattern = Pattern.compile("https?://(www\\.)?waze\\.com/\\S+")
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
                val coords = withContext(Dispatchers.IO) { resolveCoordinates(url) }
                setLoading(false)
                if (coords != null) {
                    showResult(coords)
                } else {
                    showError(getString(R.string.error_no_coords))
                }
            } catch (e: Exception) {
                setLoading(false)
                showError(getString(R.string.error_network) + "\n" + (e.message ?: ""))
            }
        }
    }

    /**
     * עוקב אחרי שרשרת ה-redirect של קישור Waze (כולל ul/<id> מקוצר) ומחלץ קואורדינטות
     * מה-URL הסופי. ה-OkHttp עוקב אחרי redirects אוטומטית, אז ה-response.request.url
     * בסוף השרשרת הוא כתובת ה-Waze המלאה עם ה-ll= או q=.
     */
    private fun resolveCoordinates(inputUrl: String): Coordinates? {
        val request = Request.Builder()
            .url(inputUrl)
            .header("User-Agent", "Mozilla/5.0 (Android) Waze2Coordinate/1.0")
            .build()

        httpClient.newCall(request).execute().use { response ->
            val finalUrl = response.request.url.toString()
            var coords = extractCoordsFromUrl(finalUrl)
            if (coords != null) return coords

            // אם הקואורדינטות לא נמצאות ב-URL הסופי (יתכן ש-Waze מטמיע אותן בתוך גוף הדף),
            // נבדוק גם את תוכן התשובה.
            val body = response.body?.string() ?: ""
            coords = extractCoordsFromUrl(body)
            return coords
        }
    }

    private fun extractCoordsFromUrl(text: String): Coordinates? {
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

        // תבנית 3: to/ll.lat,lon
        pattern = Pattern.compile("to/ll\\.(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)", Pattern.CASE_INSENSITIVE)
        matcher = pattern.matcher(text)
        if (matcher.find()) {
            return Coordinates(matcher.group(1)!!, matcher.group(2)!!)
        }

        // תבנית 4: גנרית - כל זוג מספרים עשרוניים תקפים כקואורדינטות (lat -90..90, lon -180..180)
        pattern = Pattern.compile("(-?\\d{1,3}\\.\\d{3,})\\s*,\\s*(-?\\d{1,3}\\.\\d{3,})")
        matcher = pattern.matcher(text)
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
            Toast.makeText(this, "לא נמצאה אפליקציית מפות", Toast.LENGTH_SHORT).show()
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
