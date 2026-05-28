package com.quotawatch.scraper

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class UsageScraper(private val context: Context) {

    companion object {
        const val TAG = "UsageScraper"
        const val INJECT_DELAY_MS = 4000L
        const val TIMEOUT_MS = 45000L
        const val CHROME_UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    }

    private fun getActivityContext(): Context {
        var ctx = context
        while (ctx is ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return context
    }

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun scrape(url: String, js: String): String? = withContext(Dispatchers.Main) {
        val result = CompletableDeferred<String?>()
        val handler = Handler(Looper.getMainLooper())

        Log.d(TAG, "=== Scraping $url ===")

        val webContext = getActivityContext()
        val webView = WebView(webContext).apply {
            layoutParams = ViewGroup.LayoutParams(1, 1)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.userAgentString = CHROME_UA
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        }

        // Debounce: each onPageFinished resets the inject timer.
        // This way we wait for the LAST page load (after redirects).
        var pendingInject: Runnable? = null

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, finishedUrl: String) {
                Log.d(TAG, "onPageFinished: $finishedUrl")

                // Cancel any pending inject from a previous onPageFinished
                pendingInject?.let { handler.removeCallbacks(it) }

                // Schedule new inject
                val inject = Runnable {
                    Log.d(TAG, "Injecting JS into $finishedUrl")
                    view.evaluateJavascript(js) { jsResult ->
                        Log.d(TAG, "JS callback: ${jsResult?.take(300)}")
                        if (!result.isCompleted) result.complete(jsResult)
                    }
                }
                pendingInject = inject
                handler.postDelayed(inject, INJECT_DELAY_MS)
            }

            override fun onReceivedError(
                view: WebView, request: WebResourceRequest, error: WebResourceError
            ) {
                if (request.isForMainFrame) {
                    Log.e(TAG, "Error: ${error.errorCode} ${error.description} at ${request.url}")
                    if (!result.isCompleted) result.complete(null)
                }
            }
        }

        webView.loadUrl(url)

        val scraped = withTimeoutOrNull(TIMEOUT_MS) { result.await() }
        if (scraped == null) Log.w(TAG, "Timed out for $url")
        pendingInject?.let { handler.removeCallbacks(it) }
        webView.stopLoading()
        webView.destroy()
        scraped
    }

    fun hasSession(url: String): Boolean {
        val cookies = CookieManager.getInstance().getCookie(url)
        return !cookies.isNullOrBlank()
    }
}
