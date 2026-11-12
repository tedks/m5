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
 * - Cross-origin URLs are never captured, even if the path matches.
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
                function keep(key, body) {
                    if (body && typeof body === 'object') cap[key] = {at: Date.now(), body: body};
                }
                var origFetch = window.fetch;
                if (typeof origFetch === 'function') {
                    window.fetch = function(input) {
                        var pr = origFetch.apply(this, arguments);
                        try {
                            var u = typeof input === 'string' ? input : (input && input.url) || String(input);
                            var key = keyFor(u);
                            if (key) {
                                pr.then(function(r) {
                                    if (r && r.ok) r.clone().json().then(function(j) { keep(key, j); }, function() {});
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
                        try { this.__qwKey = keyFor(String(u)); } catch (e) {}
                        return origOpen.apply(this, arguments);
                    };
                    X.send = function() {
                        var x = this;
                        try {
                            if (x.__qwKey) x.addEventListener('load', function() {
                                try {
                                    if (x.status >= 200 && x.status < 300) {
                                        var b = x.responseType === 'json' ? x.response : JSON.parse(x.responseText);
                                        keep(x.__qwKey, b);
                                    }
                                } catch (e) {}
                            });
                        } catch (e) {}
                        return origSend.apply(this, arguments);
                    };
                }
            })();
        """.trimIndent()
    }

    /** Single-quoted JS string literal for a trusted constant (escapes backslash and quote). */
    internal fun jsString(s: String): String =
        "'" + s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n") + "'"
}
