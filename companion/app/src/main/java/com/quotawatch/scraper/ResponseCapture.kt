package com.quotawatch.scraper

/**
 * Captures the JSON the usage page loads for itself, so the scrapers can read the page's own
 * structured numbers instead of matching rendered text that changes with every redesign.
 *
 * [hookScript] is installed as a document-start script (it runs before any page script) in the
 * scraper's WebView only. It wraps `window.fetch` and `XMLHttpRequest` so that when the PAGE
 * requests a same-origin URL whose path matches one of the given patterns and gets a 2xx JSON
 * response, a copy of the parsed body is kept in `window.__qwCapture[key]`. The scrapers' poll
 * JS then reads only the few numeric fields it needs from there.
 *
 * Design constraints:
 * - We never make a request ourselves and never touch auth headers or tokens: the page sends
 *   whatever it sends, we only read a clone of the response it already received.
 * - The wrappers always hand the page its original promise/response; any failure inside the
 *   capture path is swallowed so it cannot change the page's behavior.
 * - Cross-origin URLs are never captured, even if the path matches; the check is made on the
 *   requested URL and again on the response's final URL, so a redirect elsewhere is dropped. A
 *   response with no final URL (e.g. one a script constructed) is not captured.
 * - Each request is numbered when it starts; an older response finishing after a newer one has
 *   been kept is dropped, so overlapping requests cannot leave stale numbers behind.
 * - A key that matched more than one distinct path keeps no body (`ambiguous`), so the
 *   scrapers fall back to the rendered text rather than guess which response the page shows.
 *   Paths are noted when a request starts, but a second path the page requests only AFTER the
 *   scrape has settled on the first cannot be seen; that window is accepted (no page has been
 *   observed to load more than one organization's usage).
 * - The wrapped `window.fetch` is a plain function, so `fetch.toString()` no longer reports
 *   `[native code]`. A page that checks for that could behave differently; none observed.
 * - Raw bodies stay in page memory. They contain account identifiers, so the poll JS must
 *   return only numeric fields and fixed labels — never a raw body — because the poll result
 *   is logged.
 */
object ResponseCapture {

    const val GLOBAL = "__qwCapture"

    /**
     * @param patterns capture key -> JavaScript regex source tested against the URL's pathname
     *   (e.g. `"^/backend-api/wham/usage$"`). Keys and patterns are compile-time constants
     *   supplied by the scrapers, never page data.
     */
    fun hookScript(patterns: Map<String, String>): String {
        require(patterns.isNotEmpty()) { "no capture patterns" }
        val pats = patterns.entries.joinToString(",") { (key, src) ->
            "[${jsString(key)},new RegExp(${jsString(src)})]"
        }
        return """
            (function() {
                if (window.$GLOBAL) return;
                var cap = {};
                Object.defineProperty(window, '$GLOBAL', {value: cap});
                var pats = [$pats];
                function keyFor(u) {
                    try {
                        var p = new URL(u, location.href);
                        if (p.origin !== location.origin) return null;
                        for (var i = 0; i < pats.length; i++) {
                            if (pats[i][1].test(p.pathname)) return pats[i][0];
                        }
                    } catch (e) {}
                    return null;
                }
                // Requests are numbered when they start. A response is kept only if (a) the URL
                // it finally came from (after any redirect) is still same-origin and maps to the
                // same key, and (b) no newer request for that key has already been kept, so an
                // older response that finishes last cannot replace newer numbers.
                // A key whose pattern has matched more than one distinct path (e.g. the usage of
                // two different organizations) is ambiguous: which one the panel shows is
                // unknown, so the key keeps no body at all and the scraper falls back to its
                // label-anchored DOM extraction. Once ambiguous, it stays ambiguous.
                // Paths are noted when a request STARTS (and again for its final URL), so a second
                // path makes the key ambiguous even if that request then fails or returns no JSON.
                var seq = 0, paths = {};
                function note(key, u) {
                    var seen = paths[key] || (paths[key] = {});
                    seen[new URL(u, location.href).pathname] = true;
                    if (Object.keys(seen).length > 1) { cap[key] = {at: Date.now(), ambiguous: true}; return false; }
                    return true;
                }
                function keep(key, n, finalUrl, body) {
                    if (!finalUrl) return;
                    if (keyFor(finalUrl) !== key) return;
                    if (!body || typeof body !== 'object') return;
                    if (!note(key, finalUrl)) return;
                    var prev = cap[key];
                    if (prev && prev.n > n) return;
                    cap[key] = {at: Date.now(), n: n, body: body};
                }
                var origFetch = window.fetch;
                if (typeof origFetch === 'function') {
                    window.fetch = function(input) {
                        var pr = origFetch.apply(this, arguments);
                        try {
                            var u = typeof input === 'string' ? input : (input && input.url) || String(input);
                            var key = keyFor(u);
                            if (key) {
                                note(key, u);
                                var n = ++seq;
                                pr.then(function(r) {
                                    try {
                                        if (r && r.ok) r.clone().json().then(function(j) { try { keep(key, n, r.url, j); } catch (e) {} }, function() {});
                                    } catch (e) {}
                                }, function() {});
                            }
                        } catch (e) {}
                        return pr;
                    };
                }
                var X = window.XMLHttpRequest && window.XMLHttpRequest.prototype;
                if (X) {
                    var origOpen = X.open, origSend = X.send;
                    X.open = function(method, u) {
                        try { this.__qwUrl = String(u); this.__qwKey = keyFor(this.__qwUrl); } catch (e) {}
                        return origOpen.apply(this, arguments);
                    };
                    X.send = function() {
                        var x = this;
                        try {
                            if (x.__qwKey) {
                                var key = x.__qwKey, n = ++seq;
                                note(key, x.__qwUrl);
                                x.addEventListener('load', function() {
                                    try {
                                        if (x.status >= 200 && x.status < 300) {
                                            var b = x.responseType === 'json' ? x.response : JSON.parse(x.responseText);
                                            keep(key, n, x.responseURL, b);
                                        }
                                    } catch (e) {}
                                });
                            }
                        } catch (e) {}
                        return origSend.apply(this, arguments);
                    };
                }
            })();
        """.trimIndent()
    }

    /** Single-quoted JS string literal for a trusted constant (escapes backslash and quote). */
    internal fun jsString(s: String): String =
        "'" + s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r")
            .replace("\u2028", "\\u2028").replace("\u2029", "\\u2029") + "'"
}
