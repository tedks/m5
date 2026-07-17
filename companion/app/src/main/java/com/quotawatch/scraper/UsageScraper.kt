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
import org.json.JSONObject
import org.json.JSONTokener

class UsageScraper(private val contextProvider: () -> Context) {

    companion object {
        const val TAG = "UsageScraper"
        // Delay before the first extraction attempt after the page settles (debounced past
        // redirects). Deliberately short — INITIAL_POLL_DELAY_MS + a few POLL_INTERVAL_MS ticks
        // covers the old fixed 4s/6s waits on a fast render, and unlike a fixed delay we keep
        // polling instead of extracting exactly once.
        const val INITIAL_POLL_DELAY_MS = 1000L
        const val POLL_INTERVAL_MS = 500L
        const val TIMEOUT_MS = 45000L
        const val MAX_RETRIES = 1
        const val CHROME_UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        // Login page patterns — if we land here, the session expired
        private val LOGIN_PATTERNS = listOf("/login", "/auth", "/signin", "/oauth")

        // evaluateJavascript's callback hands back a JSON-encoded representation of the JS
        // expression's value: for our JS (which always returns a string via JSON.stringify),
        // that's a quoted, escaped JSON string literal — e.g. `"{\"foo\":1}"` — or the bare
        // literal `null` if the JS threw before returning. JSONTokener does the actual JSON
        // string-unescaping (quotes, backslashes, \n, \uXXXX, ...) instead of the hand-rolled,
        // order-sensitive chained .replace() calls this used to do, which mishandled inputs
        // containing literal backslash-n sequences and never handled \u escapes at all.
        //
        // Deliberately pure (no logging) so it's cheaply unit-testable off-device; callers log
        // the decoded (or null) result themselves.
        fun decodeJsResult(raw: String?): String? {
            if (raw.isNullOrBlank()) return null
            return try {
                when (val value = JSONTokener(raw).nextValue()) {
                    is String -> value
                    JSONObject.NULL -> null
                    else -> null
                }
            } catch (e: Exception) {
                null
            }
        }
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
    suspend fun scrape(
        url: String,
        js: String,
        isSettled: (String) -> Boolean = { true },
        retryCount: Int = 0
    ): ScrapeResult =
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

                // Keep the renderer at IMPORTANT priority even with nothing visible. This WebView
                // is 1x1 and off-screen, and background scrapes run with no foreground UI at all;
                // without this the OS deprioritizes/pauses the renderer, so the SPA never finishes
                // rendering and the scrape hits the 45s timeout. waivedWhenNotVisible=false keeps
                // the priority pinned regardless of visibility.
                setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
            }

            var pendingPoll: Runnable? = null
            var currentUrl = url
            var lastDecoded: String? = null
            // Flipped right before teardown (removeCallbacks/destroy). A Handler runnable can't
            // be un-posted once it starts running, and an in-flight evaluateJavascript callback
            // can't be cancelled at all — this flag stops either of them from touching the
            // WebView or scheduling a new poll after we've torn down.
            var isTornDown = false

            // Runs isSettled defensively: a predicate that throws on a half-rendered/garbage
            // payload must not kill the scrape (the scrapers also guard their own predicates,
            // this is a second line of defense).
            fun safeIsSettled(decoded: String): Boolean =
                try {
                    isSettled(decoded)
                } catch (e: Exception) {
                    Log.w(TAG, "isSettled predicate threw", e)
                    false
                }

            // Poll the page every POLL_INTERVAL_MS. "Settled" = the decoded result is valid per
            // isSettled AND identical to the previous poll's result. That combination naturally
            // waits out progressive SPA rendering (one panel renders before another) without a
            // per-site fixed delay: an OR-style isSettled keeps polling until every field that
            // will show up on THIS poll's most-rendered pass has stopped changing.
            fun pollOnce(view: WebView) {
                if (isTornDown || result.isCompleted) return
                view.evaluateJavascript(js) { jsResult ->
                    if (isTornDown || result.isCompleted) return@evaluateJavascript
                    val decoded = decodeJsResult(jsResult)
                    Log.d(TAG, "Poll result: ${decoded?.take(200)}")
                    val settled = decoded != null && decoded == lastDecoded && safeIsSettled(decoded)
                    if (settled) {
                        result.complete(ScrapeResult(decoded))
                    } else {
                        lastDecoded = decoded
                        val next = Runnable { pollOnce(view) }
                        pendingPoll = next
                        handler.postDelayed(next, POLL_INTERVAL_MS)
                    }
                }
            }

            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, finishedUrl: String) {
                    Log.d(TAG, "onPageFinished: $finishedUrl")
                    currentUrl = finishedUrl

                    // Cancel any pending poll — reschedule from the latest finished page. This
                    // ensures redirects don't trigger extraction early, and login detection runs
                    // against the final settled URL rather than an intermediate redirect. Also
                    // reset lastDecoded: a redirect means we're on a different page now, so the
                    // previous poll's result is no longer a meaningful "did it change" baseline.
                    pendingPoll?.let { handler.removeCallbacks(it) }
                    lastDecoded = null

                    val start = Runnable {
                        if (LOGIN_PATTERNS.any { currentUrl.contains(it, ignoreCase = true) }) {
                            Log.w(TAG, "Settled at login URL — session expired: $currentUrl")
                            if (!result.isCompleted) {
                                result.complete(ScrapeResult(null, sessionExpired = true))
                            }
                            return@Runnable
                        }
                        pollOnce(view)
                    }
                    pendingPoll = start
                    handler.postDelayed(start, INITIAL_POLL_DELAY_MS)
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

            // On timeout, hand back the last decoded result we saw even though it never
            // stabilized — partial data (which the scrapers can still partially parse, or at
            // worst report alongside the raw page text) beats reporting nothing at all.
            val scraped = withTimeoutOrNull(TIMEOUT_MS) { result.await() }
                ?: ScrapeResult(lastDecoded)

            isTornDown = true
            pendingPoll?.let { handler.removeCallbacks(it) }
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
                return@withContext scrape(url, js, isSettled, retryCount + 1)
            }

            scraped
        }

    fun hasSession(url: String): Boolean {
        val cookies = CookieManager.getInstance().getCookie(url)
        return !cookies.isNullOrBlank()
    }
}
