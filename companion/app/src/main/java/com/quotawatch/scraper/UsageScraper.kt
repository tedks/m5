package com.quotawatch.scraper

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Loads a URL in a hidden WebView, waits for render, injects JS, returns result.
 * Reuses cookies from prior WebView login sessions (CookieManager is global).
 */
class UsageScraper(private val context: Context) {

    companion object {
        const val TAG = "UsageScraper"
        const val RENDER_DELAY_MS = 4000L
        const val TIMEOUT_MS = 20000L
        // Chrome mobile UA — required because sites (especially Google OAuth
        // gates) block the default Android WebView user agent.
        const val CHROME_UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    }

    /**
     * Load [url] in a hidden WebView, wait for it to render,
     * then inject [js] and return the result string.
     * Must be called from a coroutine. Returns null on timeout/error.
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun scrape(url: String, js: String): String? = withContext(Dispatchers.Main) {
        val result = CompletableDeferred<String?>()

        val webView = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.userAgentString = CHROME_UA

            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, finishedUrl: String) {
                Log.d(TAG, "Page loaded: $finishedUrl")
                // Delay for SPA async rendering
                view.postDelayed({
                    view.evaluateJavascript(js) { jsResult ->
                        Log.d(TAG, "JS result: ${jsResult?.take(500)}")
                        result.complete(jsResult)
                    }
                }, RENDER_DELAY_MS)
            }

            override fun onReceivedError(
                view: WebView, errorCode: Int, description: String?, failingUrl: String?
            ) {
                Log.e(TAG, "WebView error $errorCode: $description at $failingUrl")
                if (!result.isCompleted) result.complete(null)
            }
        }

        Log.d(TAG, "Loading: $url")
        webView.loadUrl(url)

        val scraped = withTimeoutOrNull(TIMEOUT_MS) { result.await() }
        webView.destroy()
        scraped
    }

    /** Check if we have cookies for a domain (i.e., user has logged in before). */
    fun hasSession(url: String): Boolean {
        val cookies = CookieManager.getInstance().getCookie(url)
        return !cookies.isNullOrBlank()
    }
}
