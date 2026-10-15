package com.jorendorff.wordfeed

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "Wordfeed"

/**
 * A WebView showing assets/reader.html for one source. All the per-site
 * scraping and the rendering happen in JavaScript (assets/extractors.js), so
 * tools/snap.mjs can exercise exactly the same code in desktop Chromium.
 */
@SuppressLint("ViewConstructor", "SetJavaScriptEnabled")
class ReaderView(
    context: Context,
    private val vm: FeedViewModel,
    private val source: Source,
) : WebView(context) {
    private val bridge = ReaderBridge(this, vm, source)
    private var loadedKey: String? = null

    init {
        settings.javaScriptEnabled = true
        // Draw while offscreen, so a page swiped in for the first time
        // doesn't flash blank while it renders.
        settings.offscreenPreRaster = true
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                if (uri.scheme == "http" || uri.scheme == "https") {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                    } catch (e: ActivityNotFoundException) {
                        // nothing can open it
                    }
                    return true
                }
                return false
            }

            override fun onPageFinished(view: WebView, url: String) {
                Log.d(TAG, "${source.id}: page finished $url")
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                Log.d(TAG, "${source.id}: console ${m.messageLevel()}: ${m.message()} (${m.sourceId()}:${m.lineNumber()})")
                return true
            }
        }
        addJavascriptInterface(bridge, "WordfeedNative")
    }

    /**
     * (Re)loads the reader if the generation or theme changed, but only while
     * [active] (its tab is on screen). Inactive pages keep whatever they last
     * showed, or stay empty, so we don't fetch sources nobody is looking at.
     */
    fun show(generation: Int, dark: Boolean, active: Boolean) {
        val key = "$generation/$dark"
        Log.d(TAG, "${source.id}: show key=$key active=$active loaded=$loadedKey size=${width}x$height")
        if (!active) return
        if (key == loadedKey) return
        loadedKey = key
        bridge.dark = dark
        setBackgroundColor((if (dark) ReaderDarkBg else ReaderLightBg).toInt())
        vm.startedLoading(source)
        loadUrl("file:///android_asset/reader.html")
    }
}

/** The JavaScript side of this is documented at the top of assets/reader.js. */
class ReaderBridge(
    private val web: WebView,
    private val vm: FeedViewModel,
    private val source: Source,
) {
    @Volatile
    var dark = false
    private val bodies = ConcurrentHashMap<String, String>()
    private val fetchedUrls = ConcurrentHashMap.newKeySet<String>()
    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun config(): String =
        JSONObject().put("source", JSONObject(source.json)).put("dark", dark).toString()

    @JavascriptInterface
    fun fetch(id: String, url: String) {
        Log.d(TAG, "${source.id}: fetch $url")
        fetchedUrls.add(url)
        main.post {
            vm.viewModelScope.launch {
                val result = vm.fetch(url)
                bodies[id] = result.getOrElse { it.message ?: it.toString() }
                Log.d(TAG, "${source.id}: fetched $url ok=${result.isSuccess} ${bodies[id]?.length} chars")
                web.evaluateJavascript(
                    "window.__wf && window.__wf.ready(${JSONObject.quote(id)}, ${result.isSuccess})",
                    null,
                )
            }
        }
    }

    @JavascriptInterface
    fun take(id: String): String = bodies.remove(id) ?: ""

    @JavascriptInterface
    fun rendered(key: String) {
        Log.d(TAG, "${source.id}: rendered key=$key")
        val urls = fetchedUrls.toList()
        fetchedUrls.clear()
        main.post {
            if (key.isEmpty()) vm.forget(urls)
            vm.reportRendered(source, key)
        }
    }
}
