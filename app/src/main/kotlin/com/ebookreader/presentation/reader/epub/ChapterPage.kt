package com.ebookreader.presentation.reader.epub

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File

/**
 * The one origin a chapter page can load anything from. The book's own files and the
 * bundled fonts are both served here, so the page's web fonts are same-origin: from
 * file:// URLs, with file-URL access locked down, the WebView refuses to load them.
 *
 * appassets.androidplatform.net is the host reserved for exactly this; it never resolves
 * to the network.
 */
internal object ReaderOrigin {
    const val HOST = WebViewAssetLoader.DEFAULT_DOMAIN
    const val BOOK_PATH = "/book/"
    const val FONTS_PATH = "/fonts/"

    /** The URL of a file in the open book, given its path inside the book. */
    fun bookUrl(path: String): String =
        "https://$HOST$BOOK_PATH" + path.split('/').joinToString("/") { Uri.encode(it) }

    /** The open book's files and the reading faces; nothing else, from anywhere. */
    fun assetLoader(context: Context, bookDir: File): WebViewAssetLoader =
        WebViewAssetLoader.Builder()
            .setDomain(HOST)
            .addPathHandler(BOOK_PATH, WebViewAssetLoader.InternalStoragePathHandler(context, bookDir))
            .addPathHandler(FONTS_PATH, ReaderFontPathHandler(context.resources))
            .build()
}

/** A chapter ready to show: its markup and the URL it lives at inside the book. */
data class ChapterDocument(val html: String, val url: String)

/** Page behaviour that is not styling, pushed to the page script as it changes. */
internal data class PageConfig(
    val swipe: Boolean,
    val smooth: Boolean,
    val language: String?,
) {
    fun toJson(): String = JSONObject()
        .put("swipe", swipe)
        .put("smooth", smooth)
        .put("lang", language ?: JSONObject.NULL)
        .toString()
}

/** What a page reports back. Only the page on screen is listened to. */
internal interface PageEvents {
    /** See [ReaderScript]: true when the tap should turn the page. Any thread. */
    fun onTap(zone: Int): Boolean
    fun onEdge(direction: Int)
    fun onSwipe(direction: Int)
    fun onScroll(fraction: Float)
    fun onInternalLink(path: String, fragment: String?)
    fun onPositionApplied(requestId: Long)
}

/**
 * A hidden neighbour takes a new stylesheet once the taps have stopped, so restyling it
 * never competes with the page being looked at.
 */
private const val HIDDEN_RESTYLE_DELAY_MS = 700L

/**
 * One chapter in its own WebView. The reader keeps the chapters either side of the open
 * one loaded but hidden, so turning into them is a swap rather than a reload.
 *
 * The document is loaded once. Later changes to the reading theme or typography are
 * applied by swapping the reader's stylesheet in place, which keeps the reader's place
 * in the text and does not flash the page.
 *
 * @param position applied once the page has loaded; only ever given to the page on screen.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun ChapterPage(
    document: ChapterDocument,
    active: Boolean,
    css: String,
    config: PageConfig,
    position: PositionRequest?,
    assets: WebViewAssetLoader,
    paper: Color,
    events: PageEvents,
    onReady: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var ready by remember { mutableStateOf(false) }
    // The stylesheet the page is showing, so a change is pushed once and only once.
    var shownCss by remember { mutableStateOf(css) }

    val isActive by rememberUpdatedState(active)
    val latestEvents by rememberUpdatedState(events)
    val latestOnReady by rememberUpdatedState(onReady)

    LaunchedEffect(ready, css, active) {
        val wv = webView ?: return@LaunchedEffect
        if (!ready || css == shownCss) return@LaunchedEffect
        if (!active) delay(HIDDEN_RESTYLE_DELAY_MS)
        wv.evaluateJavascript("__reader.setStyle(${JSONObject.quote(css)})", null)
        shownCss = css
    }
    // Declared before the position effect, so a page is configured (swiping, language)
    // before it is first scrolled.
    LaunchedEffect(ready, config) {
        if (ready) webView?.evaluateJavascript("__reader.configure(${config.toJson()})", null)
    }
    LaunchedEffect(ready, position) {
        val wv = webView ?: return@LaunchedEffect
        if (!ready || position == null) return@LaunchedEffect
        val js = when (val p = position.position) {
            is PagePosition.Fraction -> "__reader.goTo(${p.value.coerceIn(0f, 1f)})"
            is PagePosition.Anchor -> "__reader.goToAnchor(${JSONObject.quote(p.id)})"
        }
        wv.evaluateJavascript(js, null)
        latestEvents.onPositionApplied(position.id)
    }

    AndroidView(
        factory = { context ->
            WebView(context).also { wv ->
                webView = wv
                wv.settings.applyReaderPolicy()
                wv.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView, request: WebResourceRequest
                    ): WebResourceResponse? = when (request.url.scheme) {
                        // Any web request that is not the book or a bundled font is
                        // refused, so a chapter cannot reach the network.
                        "http", "https" -> assets.shouldInterceptRequest(request.url) ?: refused()
                        // The chapter itself arrives as a data: URL. file: and content:
                        // are switched off in the settings below.
                        else -> null
                    }

                    override fun shouldOverrideUrlLoading(
                        view: WebView, request: WebResourceRequest
                    ): Boolean {
                        val url = request.url
                        val path = url.path
                        if (url.host == ReaderOrigin.HOST && path != null &&
                            path.startsWith(ReaderOrigin.BOOK_PATH)
                        ) {
                            val inBook = path.removePrefix(ReaderOrigin.BOOK_PATH)
                            wv.post { latestEvents.onInternalLink(inBook, url.fragment) }
                        }
                        // External links go nowhere: the app is offline by design.
                        return true
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        view.evaluateJavascript(ReaderScript + "\n__reader.tagAlignment();", null)
                        if (!ready) {
                            ready = true
                            latestOnReady()
                        }
                    }
                }
                wv.addJavascriptInterface(object : Any() {
                    // Called on the WebView's bridge thread. Everything but tap() is
                    // handed to the main thread, where the reader's state lives.
                    @JavascriptInterface
                    fun tap(zone: Int): Boolean = isActive && latestEvents.onTap(zone)

                    @JavascriptInterface
                    fun edge(direction: Int) {
                        wv.post { if (isActive) latestEvents.onEdge(direction) }
                    }

                    @JavascriptInterface
                    fun swipe(direction: Int) {
                        wv.post { if (isActive) latestEvents.onSwipe(direction) }
                    }

                    @JavascriptInterface
                    fun scrollProgress(fraction: Float) {
                        wv.post { if (isActive) latestEvents.onScroll(fraction) }
                    }
                }, "Android")
                wv.loadDataWithBaseURL(
                    document.url, chapterDocument(document.html, css), "text/html", "UTF-8", null
                )
            }
        },
        update = { wv ->
            // Paint the view itself in the page colour, so the gap while a chapter loads
            // is the page and not a white flash.
            wv.setBackgroundColor(paper.toArgb())
            wv.visibility = if (active) View.VISIBLE else View.INVISIBLE
            wv.importantForAccessibility = if (active) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        },
        onRelease = { wv ->
            wv.stopLoading()
            wv.destroy()
        },
        modifier = modifier,
    )
}

private fun refused() = WebResourceResponse(
    "text/plain", "utf-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0))
)

/**
 * A chapter is untrusted HTML running with JavaScript enabled and a JavascriptInterface
 * attached. It reaches its own files only through [ReaderOrigin], so:
 *
 *  - it gets no file:// or content:// access at all, which would let a malicious book
 *    read the rest of the library and hand it to a script;
 *  - it must not reach the network. The app is offline by design, and a remote image or
 *    beacon in a chapter would otherwise report back what is being read, and when. The
 *    request interceptor refuses everything that is not the book; blocking network loads
 *    as well keeps that true should the interceptor ever be bypassed.
 *
 * The two file-URL flags already default to false at this minSdk; they are set explicitly
 * so the intent survives someone loosening the settings above.
 */
@Suppress("DEPRECATION")
private fun WebSettings.applyReaderPolicy() {
    javaScriptEnabled = true
    // Every book is served from the same origin, so DOM storage would be one store
    // shared by all of them. Under file:// it was never available to a book anyway.
    domStorageEnabled = false
    allowFileAccess = false
    allowContentAccess = false
    allowFileAccessFromFileURLs = false
    allowUniversalAccessFromFileURLs = false
    blockNetworkLoads = true
    setSupportZoom(true)
    builtInZoomControls = true
    displayZoomControls = false
    // Lay chapters out at the screen's width. The wide viewport made the WebView render a
    // ~980px desktop page and zoom it out, so the chosen size came out a fraction of itself.
    useWideViewPort = false
    // The reader applies the system font scale itself, to the body text only.
    textZoom = 100
}
