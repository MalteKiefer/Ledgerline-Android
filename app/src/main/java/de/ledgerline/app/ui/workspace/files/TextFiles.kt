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
)

/** Extensionless filenames (matched whole, lowercased) that are text/code. */
private val TEXT_FILENAMES = setOf(
    "dockerfile", "makefile", ".gitignore", ".env",
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
