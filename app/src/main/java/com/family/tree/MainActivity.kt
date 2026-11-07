package com.family.tree

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONObject
import java.io.File

/**
 * Shows the family tree page (assets/index.html) full screen.
 * The page talks to this class through the "AndroidBridge" object to
 * save its data on the phone, share the tree file, and import trees.
 */
class MainActivity : ComponentActivity() {

    private lateinit var web: WebView
    private var pageReady = false
    private var pendingImport: String? = null
    private var pendingSave: String? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    private val dataFile by lazy { File(filesDir, "tree.json") }

    // Photo picker for <input type="file"> in the page
    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        fileCallback?.onReceiveValue(if (uri != null) arrayOf(uri) else null)
        fileCallback = null
    }

    // "Import a tree": pick an .html or .json file
    private val openDoc = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) readUri(uri)?.let { deliverImport(it) }
    }

    // "Save a copy": let the person choose where to save
    private val createLaunchers = mapOf(
        "text/html" to registerForActivityResult(ActivityResultContracts.CreateDocument("text/html")) { onCreated(it) },
        "application/json" to registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { onCreated(it) },
        "application/octet-stream" to registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { onCreated(it) }
    )

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val paper = if (night) Color.parseColor("#1C1610") else Color.parseColor("#FFFBF1")

        WindowCompat.setDecorFitsSystemWindows(window, false)
        val root = FrameLayout(this).apply { setBackgroundColor(paper) }
        web = WebView(this).apply { setBackgroundColor(paper) }
        root.addView(web, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)
        WindowCompat.getInsetsController(window, root).apply {
            isAppearanceLightStatusBars = !night
            isAppearanceLightNavigationBars = !night
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
        }
        web.addJavascriptInterface(Bridge(), "AndroidBridge")

        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView?, callback: ValueCallback<Array<Uri>>?, params: FileChooserParams?
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                pickImage.launch("image/*")
                return true
            }
        }
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                pageReady = true
                pendingImport?.let { deliverImport(it) }
                pendingImport = null
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url ?: return false
                if (url.scheme == "file") return false
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, url)) }
                return true
            }
        }

        // Back button closes open windows in the page first
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript("(window.androidBack && window.androidBack()) ? 'y' : 'n'") { result ->
                    if (result?.contains("y") != true) {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            }
        })

        handleIncoming(intent)
        if (savedInstanceState != null) web.restoreState(savedInstanceState)
        else web.loadUrl("file:///android_asset/index.html")
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    /** A tree file opened with or shared to this app */
    @Suppress("DEPRECATION")
    private fun handleIncoming(intent: Intent?) {
        val i = intent ?: return
        val uri: Uri? = when (i.action) {
            Intent.ACTION_VIEW -> i.data
            Intent.ACTION_SEND ->
                if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            else -> null
        }
        if (uri == null) return
        val text = readUri(uri) ?: return
        if (pageReady) deliverImport(text) else pendingImport = text
    }

    private fun readUri(uri: Uri): String? = try {
        contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
    } catch (e: Exception) {
        null
    }

    private fun deliverImport(text: String) =
        runJs("window.onAndroidImport && window.onAndroidImport(${JSONObject.quote(text)})")

    private fun runJs(code: String) = runOnUiThread { web.evaluateJavascript(code, null) }

    private fun onCreated(uri: Uri?) {
        val content = pendingSave
        pendingSave = null
        if (uri == null || content == null) return
        runCatching {
            contentResolver.openOutputStream(uri, "wt")?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
        }
    }

    /** Functions the page can call as window.AndroidBridge.xxx() */
    inner class Bridge {
        @JavascriptInterface
        fun loadData(): String = try {
            if (dataFile.exists()) dataFile.readText(Charsets.UTF_8) else ""
        } catch (e: Exception) {
            ""
        }

        @JavascriptInterface
        fun saveData(json: String): Boolean = try {
            val tmp = File(filesDir, "tree.json.tmp")
            tmp.writeText(json, Charsets.UTF_8)
            tmp.renameTo(dataFile)
        } catch (e: Exception) {
            false
        }

        @JavascriptInterface
        fun shareFile(content: String, name: String, mime: String) {
            val dir = File(cacheDir, "share").apply { mkdirs() }
            val file = File(dir, name)
            file.writeText(content, Charsets.UTF_8)
            val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.files", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            runOnUiThread { startActivity(Intent.createChooser(send, getString(R.string.share_title))) }
        }

        @JavascriptInterface
        fun saveFileAs(content: String, name: String, mime: String) {
            val key = if (createLaunchers.containsKey(mime)) mime else "application/octet-stream"
            pendingSave = content
            runOnUiThread { createLaunchers.getValue(key).launch(name) }
        }

        @JavascriptInterface
        fun importFile() {
            runOnUiThread { openDoc.launch(arrayOf("text/html", "application/json", "application/octet-stream", "text/plain")) }
        }
    }
}
