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

class UsageScraper(private val contextProvider: () -> Context) {

    companion object {
        const val TAG = "UsageScraper"
        const val INJECT_DELAY_MS = 4000L
        const val TIMEOUT_MS = 45000L
        const val MAX_RETRIES = 1
        const val CHROME_UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        // Login page patterns — if we land here, the session expired
        private val LOGIN_PATTERNS = listOf("/login", "/auth", "/signin", "/oauth")
    }

    // Resolve the context freshly per scrape: the provider hands back the live Activity when
    // one exists (WebViews empirically fail to render under an Application context on some
    // devices, see 046ec8c), unwrapping to the underlying Activity as a last line of defense.
    private fun getActivityContext(): Context {
        val resolved = contextProvider()
        var ctx: Context = resolved
        while (ctx is ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return resolved
    }

    data class ScrapeResult(
        val data: String?,
        val sessionExpired: Boolean = false
    )

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun scrape(url: String, js: String, injectDelayMs: Long = INJECT_DELAY_MS, retryCount: Int = 0): ScrapeResult =
        withContext(Dispatchers.Main) {
            val result = CompletableDeferred<ScrapeResult>()
            val handler = Handler(Looper.getMainLooper())

            Log.d(TAG, "Scraping $url (attempt ${retryCount + 1})")

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

            var pendingInject: Runnable? = null

            var currentUrl = url

            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, finishedUrl: String) {
                    Log.d(TAG, "onPageFinished: $finishedUrl")
                    currentUrl = finishedUrl

                    // Cancel any pending inject — reschedule from the latest finished page.
                    // This ensures redirects don't trigger injection early, and login detection
                    // runs against the final settled URL rather than an intermediate redirect.
                    pendingInject?.let { handler.removeCallbacks(it) }

                    val inject = Runnable {
                        if (LOGIN_PATTERNS.any { currentUrl.contains(it, ignoreCase = true) }) {
                            Log.w(TAG, "Settled at login URL — session expired: $currentUrl")
                            if (!result.isCompleted) {
                                result.complete(ScrapeResult(null, sessionExpired = true))
                            }
                            return@Runnable
                        }
                        view.evaluateJavascript(js) { jsResult ->
                            Log.d(TAG, "JS result: ${jsResult?.take(200)}")
                            if (!result.isCompleted) {
                                result.complete(ScrapeResult(jsResult))
                            }
                        }
                    }
                    pendingInject = inject
                    handler.postDelayed(inject, injectDelayMs)
                }

                override fun onReceivedError(
                    view: WebView, request: WebResourceRequest, error: WebResourceError
                ) {
                    if (request.isForMainFrame) {
                        Log.e(TAG, "Error: ${error.errorCode} ${error.description}")
                        if (!result.isCompleted) result.complete(ScrapeResult(null))
                    }
                }
            }

            webView.loadUrl(url)

            val scraped = withTimeoutOrNull(TIMEOUT_MS) { result.await() }
                ?: ScrapeResult(null)

            pendingInject?.let { handler.removeCallbacks(it) }
            webView.stopLoading()
            webView.destroy()

            // claude.ai/chatgpt.com rotate session tokens on some page loads; the new
            // cookie only lives in CookieManager's in-memory store until flushed. Flush
            // after every attempt (success, timeout, or error) so a rotated cookie
            // survives a process death — otherwise the next launch reuses the stale
            // pre-rotation cookie and the service looks logged out.
            CookieManager.getInstance().flush()

            // Retry on transient failure (not session expiry)
            if (scraped.data == null && !scraped.sessionExpired && retryCount < MAX_RETRIES) {
                Log.d(TAG, "Retrying $url...")
                return@withContext scrape(url, js, injectDelayMs, retryCount + 1)
            }

            scraped
        }

    fun hasSession(url: String): Boolean {
        val cookies = CookieManager.getInstance().getCookie(url)
        return !cookies.isNullOrBlank()
    }
}
