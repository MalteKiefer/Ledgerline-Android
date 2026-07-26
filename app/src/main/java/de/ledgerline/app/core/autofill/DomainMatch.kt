package de.ledgerline.app.core.autofill

import de.ledgerline.app.domain.model.SecretFields
import de.ledgerline.app.domain.model.SecretItem

/**
 * Matches vault secrets to the app/website requesting autofill. Matching is deliberately
 * conservative: a registrable-domain (eTLD+1) match on a stored URL, or a package-name token
 * match, so we never suggest a credential for an unrelated site. Mirrors the intent of iOS
 * `DomainMatch` without pulling in a public-suffix list — a simple last-two-labels heuristic.
 */
object DomainMatch {

    /** Lowercase, strip scheme/path/port/`www.`; return bare host or null. */
    fun normalizeHost(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        var s = raw.trim().lowercase()
        s = s.substringAfter("://", s)
        s = s.substringBefore('/')
        s = s.substringBefore('?')
        s = s.substringBefore(':')
        if (s.startsWith("www.")) s = s.removePrefix("www.")
        return s.ifBlank { null }
    }

    /** eTLD+1 approximation: the last two dot-labels (last three for common two-part TLDs). */
    fun registrableDomain(host: String?): String? {
        val h = normalizeHost(host) ?: return null
        val parts = h.split('.').filter { it.isNotBlank() }
        if (parts.size <= 2) return h
        val twoPartTlds = setOf("co", "com", "org", "net", "gov", "ac", "edu")
        val last = parts[parts.size - 1]
        val secondLast = parts[parts.size - 2]
        return if (last.length <= 3 && secondLast in twoPartTlds && parts.size >= 3) {
            parts.takeLast(3).joinToString(".")
        } else {
            parts.takeLast(2).joinToString(".")
        }
    }

    /** All hosts referenced by a secret's `urls[]` list plus `host`/`url`/`website` fields. */
    fun hostsOf(item: SecretItem): List<String> {
        val singles = listOf("host", "url", "uri", "website", "domain")
            .map { SecretFields.str(item, it) }
        return (SecretFields.urls(item) + singles)
            .filter { it.isNotBlank() }
            .mapNotNull { normalizeHost(it) }
            .distinct()
    }

    /**
     * True when [item] plausibly belongs to the requesting [webDomain] and/or [packageName].
     * Web domain match wins; otherwise fall back to a package-token appearing in a stored host
     * or the title (covers native apps that expose no web domain).
     */
    fun matches(item: SecretItem, webDomain: String?, packageName: String?): Boolean {
        if (item.type !in FILLABLE_TYPES) return false
        val reqDomain = registrableDomain(webDomain)
        val hosts = hostsOf(item)
        if (reqDomain != null && hosts.any { registrableDomain(it) == reqDomain }) return true

        if (!packageName.isNullOrBlank()) {
            // e.g. "com.reddit.frontpage" -> tokens {reddit, frontpage}; ignore tld-ish parts.
            val tokens = packageName.lowercase().split('.', '_', '-')
                .filter { it.length >= 3 && it !in PACKAGE_STOPWORDS }
            val title = item.title.lowercase()
            if (tokens.any { t -> hosts.any { it.contains(t) } || title.contains(t) }) return true
        }
        return false
    }

    private val FILLABLE_TYPES = setOf("login", "password", "server")
    private val PACKAGE_STOPWORDS = setOf("com", "org", "net", "www", "app", "android", "mobile", "www2")
}
