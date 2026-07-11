package de.ledgerline.app.ui.workspace.files

/**
 * Pure text/code detection for the in-app editor. Mirrors the web `vaultFiles`
 * editor gate: a file opens editable when its MIME or filename looks textual.
 * Kept side-effect free so it can be unit-tested directly.
 */

/** MIME types (besides the `text/` prefix) that are text/code payloads. */
private val TEXT_MIMES = setOf(
    "application/json",
    "application/xml",
    "application/javascript",
    "application/x-yaml",
    "application/toml",
    "application/x-sh",
    "application/sql",
)

/** Lowercased file extensions we treat as text/code. */
private val TEXT_EXTENSIONS = setOf(
    "txt", "md", "markdown", "json", "xml", "yaml", "yml", "toml", "ini", "cfg",
    "conf", "properties", "env", "gitignore", "dockerfile", "csv", "tsv", "log",
    "js", "mjs", "ts", "tsx", "jsx", "kt", "kts", "java", "gradle", "groovy",
    "py", "rb", "go", "rs", "c", "h", "cpp", "hpp", "cc", "cs", "php", "html",
    "htm", "css", "scss", "sass", "less", "sh", "bash", "zsh", "sql", "swift",
    "m", "mm", "lua", "pl", "r", "dart", "vue", "svelte", "tex", "bib",
    "makefile", "mk", "cmake",
    // Keys / certs / signatures in ASCII armor / PEM (all plain text).
    "asc", "pub", "pem", "key", "crt", "cer", "csr", "sig", "gpg",
    // Misc text formats.
    "diff", "patch", "rst", "adoc", "asciidoc", "srt", "vtt", "ics", "vcf",
    "plist", "nix", "proto", "graphql", "gql", "tf", "hcl", "ps1", "psm1",
    "bat", "cmd", "awk", "sed", "erb", "ejs", "twig", "haml", "sln",
    "gitattributes", "editorconfig", "npmrc", "eslintrc", "prettierrc",
    "babelrc", "dockerignore", "readme", "license", "changelog", "authors",
)

/** Extensionless filenames (matched whole, lowercased) that are text/code. */
private val TEXT_FILENAMES = setOf(
    "dockerfile", "makefile", ".gitignore", ".env", "license", "readme",
    "changelog", "authors", "notice", "copying", "install", "todo",
    ".gitattributes", ".editorconfig", ".npmrc", ".dockerignore",
)

/**
 * True when [mime]/[name] identify a text or source-code file that the in-app
 * editor should open editable. Additive/tolerant: anything not recognized here
 * keeps the read-only preview behavior.
 */
fun isTextFile(mime: String?, name: String): Boolean {
    // Strip any parameters (e.g. "; charset=utf-8") before matching the media type.
    val base = mime?.trim()?.lowercase()?.substringBefore(';')?.trim().orEmpty()
    if (base.isNotEmpty()) {
        if (base.startsWith("text/")) return true
        if (base in TEXT_MIMES) return true
        // Structured suffixes: application/vnd.foo+json, image/svg+xml, ...
        if (base.endsWith("+json") || base.endsWith("+xml")) return true
    }

    val lower = name.trim().lowercase()
    if (lower.isEmpty()) return false
    if (lower in TEXT_FILENAMES) return true

    // Bare name equal to a known extensionless type (e.g. "Dockerfile", "Makefile").
    if (!lower.contains('.') && lower in TEXT_EXTENSIONS) return true

    val ext = lower.substringAfterLast('.', missingDelimiterValue = "")
    return ext.isNotEmpty() && ext in TEXT_EXTENSIONS
}

/**
 * Content-based text sniff for files the name/MIME allowlist misses (`.asc`, `.pub`,
 * PEM keys, extensionless configs, unknown types). Heuristic (like git's): a leading
 * sample with NO NUL byte and a high ratio of printable/whitespace bytes is text.
 * Binary files (images, PDFs, video, archives) contain NUL bytes → rejected.
 *
 * The bytes are already decrypted in memory when viewing, so this is cheap + reliable.
 */
fun looksLikeText(bytes: ByteArray): Boolean {
    if (bytes.isEmpty()) return true // empty file → editable
    val sample = minOf(bytes.size, 8192)
    var suspicious = 0
    for (i in 0 until sample) {
        val b = bytes[i].toInt() and 0xFF
        when {
            b == 0 -> return false // NUL → binary
            b == 9 || b == 10 || b == 13 -> {} // tab / LF / CR
            b in 32..126 -> {} // printable ASCII
            b >= 0x80 -> {} // assume UTF-8 multibyte / extended — allow
            else -> suspicious++ // other control chars
        }
    }
    // Allow a small fraction of stray control chars (e.g. ANSI escapes in logs).
    return suspicious * 100 <= sample * 5
}

/** Editor gate combining the name/MIME allowlist with a content sniff of [bytes]. */
fun isEditableText(mime: String?, name: String, bytes: ByteArray): Boolean =
    isTextFile(mime, name) || looksLikeText(bytes)
