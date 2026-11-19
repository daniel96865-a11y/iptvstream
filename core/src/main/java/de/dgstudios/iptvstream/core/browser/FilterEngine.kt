package de.dgstudios.iptvstream.core.browser

/**
 * Werbeblocker auf Basis echter Adblock-Plus-Filterlisten (EasyList, EasyList Germany, Peter Lowe).
 *
 * Unterstützt:
 * - Host-Regeln `||werbung.example^` (auch mit Optionen wie `$third-party`, `$popup`)
 * - Muster-Regeln mit `*`, `^`, `|`, `||` (über ein Token-Verzeichnis schnell gefunden)
 * - Ausnahmen `@@||host^` und `@@`-Muster ohne `domain=`
 * - Element-Ausblendung `##selektor` (allgemein) und `seite.de##selektor` (pro Seite)
 *
 * Bewusst ausgelassen (lieber zu wenig als zu viel blockieren): Regeln mit `domain=`, reguläre
 * Ausdrücke `/…/`, `csp=`, `redirect=`, `removeparam`, `$document`, Erweiterungen `#?#`, `#$#`.
 */
class FilterEngine private constructor(
    private val hosts: Map<String, Boolean>, // Host -> nur Drittanbieter
    private val popupHosts: Map<String, Boolean>,
    private val allowHosts: Map<String, Boolean>,
    private val patterns: TokenIndex,
    private val popupPatterns: TokenIndex,
    private val allowPatterns: TokenIndex,
    private val genericHide: List<String>,
    private val siteHide: Map<String, List<String>>,
) {
    val hostRuleCount get() = hosts.size
    val patternRuleCount get() = patterns.size
    val hideRuleCount get() = genericHide.size + siteHide.values.sumOf { it.size }
    val ruleCount get() = hostRuleCount + patternRuleCount + popupHosts.size + popupPatterns.size + hideRuleCount
    val untokenizedCount get() = patterns.untokenized

    /** Unterressource (Skript, Bild, iframe, XHR …) auf [pageHost] blockieren? */
    fun shouldBlock(url: String, pageHost: String?): Boolean {
        val u = url.lowercase()
        val host = hostOf(u) ?: return false
        val third = isThirdParty(host, pageHost)
        if (allowed(u, host, third)) return false
        val h = hostMatch(hosts, host)
        if (h != null && (!h || third)) return true
        return patterns.matches(u, third)
    }

    /** Neues Fenster / Weiterleitung auf [url] blockieren (Popup- und Werbeziele). */
    fun shouldBlockPopup(url: String, pageHost: String?): Boolean {
        val u = url.lowercase()
        val host = hostOf(u) ?: return false
        val third = isThirdParty(host, pageHost)
        if (allowed(u, host, third)) return false
        if (hostMatch(popupHosts, host) != null) return true
        val h = hostMatch(hosts, host)
        if (h != null && (!h || third)) return true
        return popupPatterns.matches(u, third)
    }

    private fun allowed(u: String, host: String, third: Boolean): Boolean =
        hostMatch(allowHosts, host) != null || allowPatterns.matches(u, third)

    /** Allgemeines Ausblende-CSS (für alle Seiten gleich, einmal gebaut). */
    val genericCss: String by lazy { cssOf(genericHide) }

    /** Nur die seitenspezifischen Ausblende-Regeln für [host]. */
    fun siteCss(host: String?): String = hideCss(host, includeGeneric = false)

    private fun cssOf(sel: List<String>): String =
        sel.chunked(200).joinToString("\n") { it.joinToString(",") + "{display:none!important}" }

    /** CSS zum Ausblenden von Werbeflächen auf [host]. */
    fun hideCss(host: String?, includeGeneric: Boolean = true): String {
        val sel = ArrayList<String>()
        if (includeGeneric) sel.addAll(genericHide)
        var h = host?.lowercase()?.removePrefix("www.")
        while (!h.isNullOrEmpty()) {
            siteHide[h]?.let { sel.addAll(it) }
            val i = h.indexOf('.')
            h = if (i < 0) null else h.substring(i + 1)
        }
        if (sel.isEmpty()) return ""
        // In Blöcken, damit ein ungültiger Selektor nicht die ganze Regel kippt.
        return cssOf(sel)
    }

    companion object {
        private val HOST_RULE = Regex("^\\|\\|([a-z0-9.\\-_]+)\\^\\|?$")
        private val SKIP_OPTIONS = listOf(
            "domain=", "csp", "redirect", "removeparam", "rewrite", "header", "document", "elemhide",
            "generichide", "genericblock", "specifichide", "permissions", "urltransform", "replace", "badfilter",
            "to=", "from=", "method=", "denyallow", "1p", "first-party", "~third-party", "strict",
        )

        fun parse(texts: List<String>): FilterEngine {
            val hosts = HashMap<String, Boolean>(80_000)
            val popupHosts = HashMap<String, Boolean>()
            val allowHosts = HashMap<String, Boolean>()
            val patterns = TokenIndex()
            val popupPatterns = TokenIndex()
            val allowPatterns = TokenIndex()
            val generic = LinkedHashSet<String>()
            val site = HashMap<String, MutableList<String>>()

            for (text in texts) {
                for (raw in text.lineSequence()) {
                    val line = raw.trim()
                    if (line.isEmpty() || line[0] == '!' || line[0] == '[') continue
                    val hideAt = line.indexOf("##")
                    if (hideAt >= 0) {
                        parseHide(line, hideAt, generic, site)
                        continue
                    }
                    if (line.contains("#@#") || line.contains("#?#") || line.contains("#$#") || line.contains("#%#")) continue
                    parseNetwork(line, hosts, popupHosts, allowHosts, patterns, popupPatterns, allowPatterns)
                }
            }
            return FilterEngine(hosts, popupHosts, allowHosts, patterns, popupPatterns, allowPatterns, generic.toList(), site)
        }

        private fun parseHide(line: String, at: Int, generic: MutableSet<String>, site: MutableMap<String, MutableList<String>>) {
            val sel = line.substring(at + 2).trim()
            if (sel.isEmpty() || sel.contains(":-abp-") || sel.contains(":has-text") || sel.contains(":contains") ||
                sel.contains(":xpath") || sel.contains(":upward") || sel.contains(":remove") || sel.contains(":style") ||
                sel.contains(":matches") || sel.contains("{")
            ) return
            if (at == 0) {
                generic.add(sel)
                return
            }
            val domains = line.substring(0, at).split(',').map { it.trim().lowercase() }
                .filter { it.isNotEmpty() && !it.startsWith("~") && !it.contains('*') }
            for (d in domains) site.getOrPut(d.removePrefix("www.")) { ArrayList() }.add(sel)
        }

        private fun parseNetwork(
            line0: String,
            hosts: MutableMap<String, Boolean>,
            popupHosts: MutableMap<String, Boolean>,
            allowHosts: MutableMap<String, Boolean>,
            patterns: TokenIndex,
            popupPatterns: TokenIndex,
            allowPatterns: TokenIndex,
        ) {
            var line = line0
            val exception = line.startsWith("@@")
            if (exception) line = line.substring(2)
            if (line.startsWith("/") && line.length > 2 && line.endsWith("/")) return // Regex-Regel
            var options = emptyList<String>()
            val dollar = line.lastIndexOf('$')
            if (dollar > 0 && !line.substring(dollar).contains('/')) {
                options = line.substring(dollar + 1).lowercase().split(',').map { it.trim() }
                line = line.substring(0, dollar)
            }
            if (line.isEmpty() || line == "|" || line == "||" || line == "*") return
            if (options.any { o -> SKIP_OPTIONS.any { o == it || (it.endsWith("=") && o.startsWith(it)) } }) return
            val popup = "popup" in options
            val popupOnly = popup && options.size == 1
            val thirdOnly = "third-party" in options || "3p" in options
            val matchCase = "match-case" in options
            val p = if (matchCase) line else line.lowercase()

            val hostMatch = HOST_RULE.find(p)
            if (hostMatch != null) {
                val host = hostMatch.groupValues[1].removePrefix(".")
                when {
                    exception -> allowHosts[host] = false
                    popupOnly -> popupHosts[host] = false
                    else -> {
                        val prev = hosts[host]
                        hosts[host] = if (prev == null) thirdOnly else prev && thirdOnly
                        if (popup) popupHosts[host] = false
                    }
                }
                return
            }
            val rule = PatternRule(p, thirdOnly)
            when {
                exception -> allowPatterns.add(rule)
                popupOnly -> popupPatterns.add(rule)
                else -> {
                    patterns.add(rule)
                    if (popup) popupPatterns.add(rule)
                }
            }
        }

        fun hostOf(url: String): String? {
            val s = url.indexOf("://")
            if (s < 0) return null
            var start = s + 3
            var end = start
            while (end < url.length && url[end] != '/' && url[end] != '?' && url[end] != '#') end++
            val at = url.lastIndexOf('@', end - 1)
            if (at >= start) start = at + 1
            var host = url.substring(start, end)
            if (host.startsWith("[")) return host
            val colon = host.indexOf(':')
            if (colon >= 0) host = host.substring(0, colon)
            return host.trimEnd('.').ifEmpty { null }
        }

        /** Grobe „registrierbare Domain“ (letzte zwei Teile, bei co.uk & Co. drei). */
        fun siteOf(host: String): String {
            val parts = host.split('.')
            if (parts.size <= 2) return host
            val two = parts.takeLast(2).joinToString(".")
            val sld = parts[parts.size - 2]
            return if (sld.length <= 3 && parts.last().length == 2 && sld in setOf("co", "com", "org", "net", "gov", "ac", "or", "ne", "edu")) {
                parts.takeLast(3).joinToString(".")
            } else {
                two
            }
        }

        fun isThirdParty(host: String, pageHost: String?): Boolean =
            pageHost.isNullOrEmpty() || siteOf(host) != siteOf(pageHost.lowercase())

        /** Host oder eine übergeordnete Domain steht in der Liste: Wert, sonst null. */
        fun hostMatch(map: Map<String, Boolean>, host: String): Boolean? {
            var h = host
            while (true) {
                map[h]?.let { return it }
                val i = h.indexOf('.')
                if (i < 0) return null
                h = h.substring(i + 1)
            }
        }
    }
}

/** Eine Muster-Regel im Adblock-Plus-Format (bereits klein geschrieben). */
class PatternRule(val pattern: String, val thirdPartyOnly: Boolean) {
    private val regex: Regex by lazy { Regex(toRegex(pattern)) }

    fun matches(url: String, thirdParty: Boolean): Boolean {
        if (thirdPartyOnly && !thirdParty) return false
        return regex.containsMatchIn(url)
    }

    /** Ein Wortbaustein, der im Ziel garantiert als ganzes Token vorkommt, oder null. */
    fun token(): String? {
        var best: String? = null
        var i = 0
        val p = pattern
        while (i < p.length) {
            if (!isTokenChar(p[i])) {
                i++
                continue
            }
            val start = i
            while (i < p.length && isTokenChar(p[i])) i++
            val tok = p.substring(start, i)
            val before = if (start == 0) null else p[start - 1]
            val after = if (i >= p.length) null else p[i]
            val safeBefore = when {
                before == null -> false // Regel ohne Anker: davor kann noch mehr stehen
                before == '*' -> false
                before == '|' -> true
                else -> true
            }
            val safeAfter = when {
                after == null -> false
                after == '*' -> false
                else -> true
            }
            if (safeBefore && safeAfter && tok.length >= 3 && (best == null || tok.length > best.length)) best = tok
        }
        return best
    }

    companion object {
        fun isTokenChar(c: Char) = c in 'a'..'z' || c in '0'..'9' || c == '%'

        fun toRegex(p: String): String {
            val sb = StringBuilder()
            var s = p
            var anchoredEnd = false
            when {
                s.startsWith("||") -> {
                    sb.append("^[a-z][a-z0-9+.-]*://(?:[^/?#]*\\.)?")
                    s = s.substring(2)
                }
                s.startsWith("|") -> {
                    sb.append("^")
                    s = s.substring(1)
                }
            }
            if (s.endsWith("|") && s.length > 0) {
                anchoredEnd = true
                s = s.dropLast(1)
            }
            for (c in s) {
                when (c) {
                    '*' -> sb.append(".*")
                    '^' -> sb.append("(?:[^a-z0-9_.%-]|$)")
                    else -> {
                        if (c.isLetterOrDigit() || c == '_' || c == '-') sb.append(c) else sb.append('\\').append(c)
                    }
                }
            }
            if (anchoredEnd) sb.append('$')
            return sb.toString()
        }

        fun tokens(url: String): Set<String> {
            val out = HashSet<String>()
            var i = 0
            while (i < url.length) {
                if (!isTokenChar(url[i])) {
                    i++
                    continue
                }
                val start = i
                while (i < url.length && isTokenChar(url[i])) i++
                if (i - start >= 3) out.add(url.substring(start, i))
            }
            return out
        }
    }
}

/** Muster-Regeln nach Token sortiert; Regeln ohne brauchbares Token werden immer geprüft. */
class TokenIndex {
    private val byToken = HashMap<String, MutableList<PatternRule>>()
    private val rest = ArrayList<PatternRule>()
    var size = 0
        private set

    fun add(rule: PatternRule) {
        size++
        val t = rule.token()
        if (t == null) rest.add(rule) else byToken.getOrPut(t) { ArrayList(2) }.add(rule)
    }

    fun matches(url: String, thirdParty: Boolean): Boolean {
        if (size == 0) return false
        for (t in PatternRule.tokens(url)) {
            val list = byToken[t] ?: continue
            for (r in list) if (r.matches(url, thirdParty)) return true
        }
        for (r in rest) if (r.matches(url, thirdParty)) return true
        return false
    }

    val untokenized get() = rest.size
}
