package de.buchwald.trainingszeiten

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.util.AtomicFile
import android.util.Base64
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject
import java.io.File

/**
 * Trainingsnachweis: Die Oberfläche liegt als HTML in app/src/main/assets und wird
 * offline über WebViewAssetLoader geladen. Speichern, Teilen, Kopieren und Backups
 * laufen über die [NativeBridge] (im JavaScript als `window.Android`).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    // Offener Datei-Dialog: Rückruf-ID für das JavaScript und die zu schreibenden Bytes
    private var pendingId: String? = null
    private var pendingBytes: ByteArray? = null

    private val createPdf = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { onDocumentCreated(it) }
    private val createCsv = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { onDocumentCreated(it) }
    private val createJson = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { onDocumentCreated(it) }
    private val openBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { onBackupPicked(it) }

    private val storageLock = Any()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Randlos zeichnen; Systemleisten-Abstände setzen wir selbst als Padding
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !night
            isAppearanceLightNavigationBars = !night
        }

        val background = ContextCompat.getColor(this, R.color.app_bg)
        val root = FrameLayout(this).apply { setBackgroundColor(background) }
        webView = WebView(this).apply { setBackgroundColor(background) }
        root.addView(webView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }

        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true) // Debug-Build: chrome://inspect
        }

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            setSupportZoom(false)
        }
        webView.addJavascriptInterface(NativeBridge(), "Android")
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? =
                request?.let { assetLoader.shouldInterceptRequest(it.url) }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url ?: return false
                if (url.host == WebViewAssetLoader.DEFAULT_DOMAIN) return false
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, url)) } // externe Links im Browser
                return true
            }
        }
        webView.loadUrl("https://${WebViewAssetLoader.DEFAULT_DOMAIN}/assets/index.html")
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.removeJavascriptInterface("Android")
            webView.destroy()
        }
        super.onDestroy()
    }

    // ---------- Ergebnisse der Datei-Dialoge ----------

    private fun onDocumentCreated(uri: Uri?) {
        val id = pendingId ?: return
        val bytes = pendingBytes
        pendingId = null
        pendingBytes = null
        if (uri == null || bytes == null) {
            reply(id, "cancelled")
            return
        }
        try {
            val out = contentResolver.openOutputStream(uri) ?: throw IllegalStateException("Kein Ausgabestrom")
            out.use { it.write(bytes) }
            reply(id, "saved")
        } catch (e: Exception) {
            reply(id, "error")
        }
    }

    private fun onBackupPicked(uri: Uri?) {
        val id = pendingId ?: return
        pendingId = null
        if (uri == null) {
            reply(id, "cancelled")
            return
        }
        try {
            val text = contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: throw IllegalStateException("Kein Eingabestrom")
            reply(id, "ok", text)
        } catch (e: Exception) {
            reply(id, "error")
        }
    }

    /** Ruft `window.__nativeDone(id, status, data)` im JavaScript auf. Nur auf dem UI-Thread. */
    private fun reply(id: String, status: String, data: String? = null) {
        val payload = if (data == null) "null" else JSONObject.quote(data)
        webView.evaluateJavascript(
            "window.__nativeDone && window.__nativeDone(${JSONObject.quote(id)}, ${JSONObject.quote(status)}, $payload)",
            null
        )
    }

    private fun dataFile(key: String) = AtomicFile(File(filesDir, "$key.json"))

    /** Wird aus dem JavaScript aufgerufen (läuft auf dem JavaBridge-Thread). */
    inner class NativeBridge {

        private val keyPattern = Regex("^[a-z_]{1,40}$")

        @JavascriptInterface
        fun load(key: String): String? {
            if (!keyPattern.matches(key)) return null
            return synchronized(storageLock) {
                // readFully() greift bei Bedarf auf die Sicherungskopie zurück; fehlt die Datei, gibt es null
                runCatching { dataFile(key).readFully().toString(Charsets.UTF_8) }.getOrNull()
            }
        }

        @JavascriptInterface
        fun store(key: String, value: String) {
            if (!keyPattern.matches(key)) return
            synchronized(storageLock) {
                val file = dataFile(key)
                val out = file.startWrite()
                try {
                    out.write(value.toByteArray(Charsets.UTF_8))
                    file.finishWrite(out)
                } catch (e: Exception) {
                    file.failWrite(out)
                    throw e
                }
            }
        }

        @JavascriptInterface
        fun saveFile(id: String, fileName: String, mime: String, base64: String) {
            val bytes = runCatching { Base64.decode(base64, Base64.DEFAULT) }.getOrNull()
            runOnUiThread {
                if (bytes == null) { reply(id, "error"); return@runOnUiThread }
                if (pendingId != null) { reply(id, "busy"); return@runOnUiThread }
                pendingId = id
                pendingBytes = bytes
                try {
                    when {
                        mime == "application/pdf" -> createPdf.launch(fileName)
                        mime.startsWith("text/csv") -> createCsv.launch(fileName)
                        else -> createJson.launch(fileName)
                    }
                } catch (e: Exception) {
                    pendingId = null
                    pendingBytes = null
                    reply(id, "error")
                }
            }
        }

        @JavascriptInterface
        fun shareFile(id: String, fileName: String, mime: String, base64: String) {
            val bytes = runCatching { Base64.decode(base64, Base64.DEFAULT) }.getOrNull()
            runOnUiThread {
                try {
                    if (bytes == null) throw IllegalArgumentException("Ungültige Daten")
                    val dir = File(cacheDir, "share").apply { mkdirs() }
                    dir.listFiles()?.forEach { it.delete() }
                    val file = File(dir, fileName.replace(Regex("[^A-Za-z0-9._-]"), "_"))
                    file.writeBytes(bytes)
                    val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.fileprovider", file)
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = mime
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, fileName.substringBeforeLast('.').replace('_', ' '))
                        clipData = ClipData.newRawUri(fileName, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(send, getString(R.string.share_title)))
                    reply(id, "shared")
                } catch (e: Exception) {
                    reply(id, "error")
                }
            }
        }

        @JavascriptInterface
        fun copyText(text: String) {
            runOnUiThread {
                getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), text))
            }
        }

        @JavascriptInterface
        fun openBackup(id: String) {
            runOnUiThread {
                if (pendingId != null) { reply(id, "busy"); return@runOnUiThread }
                pendingId = id
                try {
                    openBackup.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                } catch (e: Exception) {
                    pendingId = null
                    reply(id, "error")
                }
            }
        }
    }
}
