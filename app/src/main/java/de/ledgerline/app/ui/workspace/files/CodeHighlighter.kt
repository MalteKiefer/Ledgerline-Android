package de.ledgerline.app.ui.workspace.files

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle

/**
 * Self-contained, fully-offline syntax highlighter for the in-app code editor.
 *
 * There are NO third-party dependencies here and nothing touches the network:
 * this is a hand-rolled single-pass lexer that maps a subset of common tokens
 * (comments, strings, numbers, keywords) to [SpanStyle] colors and returns an
 * [AnnotatedString] whose `.text` is byte-for-byte identical to the input. That
 * identity is the contract the editor relies on: because we neither add nor
 * remove characters, [androidx.compose.ui.text.input.OffsetMapping.Identity] is
 * a correct mapping and the cursor/selection stay aligned while editing.
 *
 * The scan is deliberately robust: any unexpected state degrades to emitting the
 * remaining characters as plain (default-colored) text rather than throwing, and
 * [highlight] wraps the whole thing so a lexer bug can never crash the editor.
 */

/** Languages the highlighter understands, inferred from the file extension. */
enum class CodeLang {
    KOTLIN, JAVA, JS, TS, PYTHON, C_CPP, CSHARP, GO, RUST, PHP, RUBY, SWIFT,
    SQL, SHELL, JSON, XML_HTML, CSS, YAML, MARKDOWN, GENERIC,
}

/** Theme-derived token colors passed in from the composable (M3 color scheme). */
data class HighlightColors(
    val keyword: Color,
    val string: Color,
    val number: Color,
    val comment: Color,
    val default: Color,
)

/** Maps a file name to a [CodeLang] by its lowercased extension. */
fun langOf(name: String): CodeLang {
    val ext = name.trim().lowercase().substringAfterLast('.', missingDelimiterValue = "")
    return when (ext) {
        "kt", "kts" -> CodeLang.KOTLIN
        "java" -> CodeLang.JAVA
        "js", "mjs", "jsx" -> CodeLang.JS
        "ts", "tsx" -> CodeLang.TS
        "py" -> CodeLang.PYTHON
        "c", "h", "cpp", "hpp", "cc" -> CodeLang.C_CPP
        "cs" -> CodeLang.CSHARP
        "go" -> CodeLang.GO
        "rs" -> CodeLang.RUST
        "php" -> CodeLang.PHP
        "rb" -> CodeLang.RUBY
        "swift" -> CodeLang.SWIFT
        "sql" -> CodeLang.SQL
        "sh", "bash", "zsh" -> CodeLang.SHELL
        "json" -> CodeLang.JSON
        "xml", "html", "htm", "svg" -> CodeLang.XML_HTML
        "css", "scss", "sass", "less" -> CodeLang.CSS
        "yaml", "yml", "toml" -> CodeLang.YAML
        "md", "markdown" -> CodeLang.MARKDOWN
        else -> CodeLang.GENERIC
    }
}

// ---------------------------------------------------------------------------
// Keyword sets (whole-word matches). Kept reasonable: the common keywords per
// language plus common literals (true/false/null and friends).
// ---------------------------------------------------------------------------

private val KOTLIN_KW = setOf(
    "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if",
    "in", "interface", "is", "null", "object", "package", "return", "super", "this",
    "throw", "true", "try", "typealias", "typeof", "val", "var", "when", "while",
    "by", "catch", "constructor", "delegate", "dynamic", "field", "file", "finally",
    "get", "import", "init", "param", "property", "receiver", "set", "setparam",
    "value", "where", "abstract", "actual", "annotation", "companion", "const",
    "crossinline", "data", "enum", "expect", "external", "final", "infix", "inline",
    "inner", "internal", "lateinit", "noinline", "open", "operator", "out", "override",
    "private", "protected", "public", "reified", "sealed", "suspend", "tailrec", "vararg",
)

private val JAVA_KW = setOf(
    "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
    "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
    "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
    "interface", "long", "native", "new", "package", "private", "protected", "public",
    "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
    "throw", "throws", "transient", "try", "void", "volatile", "while", "true", "false",
    "null", "var", "record", "sealed", "permits", "yield",
)

private val JS_KW = setOf(
    "await", "break", "case", "catch", "class", "const", "continue", "debugger",
    "default", "delete", "do", "else", "export", "extends", "finally", "for", "function",
    "if", "import", "in", "instanceof", "let", "new", "return", "super", "switch", "this",
    "throw", "try", "typeof", "var", "void", "while", "with", "yield", "async", "static",
    "get", "set", "of", "true", "false", "null", "undefined", "NaN", "Infinity",
)

private val TS_KW = JS_KW + setOf(
    "any", "as", "asserts", "boolean", "declare", "enum", "implements", "interface",
    "is", "keyof", "namespace", "never", "number", "object", "private", "protected",
    "public", "readonly", "string", "symbol", "type", "unknown", "abstract", "override",
    "satisfies", "infer",
)

private val PYTHON_KW = setOf(
    "False", "None", "True", "and", "as", "assert", "async", "await", "break", "class",
    "continue", "def", "del", "elif", "else", "except", "finally", "for", "from", "global",
    "if", "import", "in", "is", "lambda", "nonlocal", "not", "or", "pass", "raise",
    "return", "try", "while", "with", "yield", "match", "case", "self", "cls",
)

private val C_CPP_KW = setOf(
    "auto", "break", "case", "char", "const", "continue", "default", "do", "double",
    "else", "enum", "extern", "float", "for", "goto", "if", "inline", "int", "long",
    "register", "restrict", "return", "short", "signed", "sizeof", "static", "struct",
    "switch", "typedef", "union", "unsigned", "void", "volatile", "while", "bool", "true",
    "false", "class", "namespace", "template", "typename", "public", "private", "protected",
    "virtual", "override", "new", "delete", "this", "nullptr", "using", "try", "catch",
    "throw", "constexpr", "friend", "operator", "explicit", "mutable", "static_cast",
    "dynamic_cast", "reinterpret_cast", "const_cast",
)

private val CSHARP_KW = setOf(
    "abstract", "as", "base", "bool", "break", "byte", "case", "catch", "char", "checked",
    "class", "const", "continue", "decimal", "default", "delegate", "do", "double", "else",
    "enum", "event", "explicit", "extern", "false", "finally", "fixed", "float", "for",
    "foreach", "goto", "if", "implicit", "in", "int", "interface", "internal", "is", "lock",
    "long", "namespace", "new", "null", "object", "operator", "out", "override", "params",
    "private", "protected", "public", "readonly", "ref", "return", "sbyte", "sealed",
    "short", "sizeof", "stackalloc", "static", "string", "struct", "switch", "this",
    "throw", "true", "try", "typeof", "uint", "ulong", "unchecked", "unsafe", "ushort",
    "using", "virtual", "void", "volatile", "while", "var", "async", "await", "record",
    "get", "set", "value", "nameof", "when", "yield",
)

private val GO_KW = setOf(
    "break", "case", "chan", "const", "continue", "default", "defer", "else", "fallthrough",
    "for", "func", "go", "goto", "if", "import", "interface", "map", "package", "range",
    "return", "select", "struct", "switch", "type", "var", "true", "false", "nil", "iota",
    "bool", "byte", "int", "int8", "int16", "int32", "int64", "uint", "uint8", "uint16",
    "uint32", "uint64", "float32", "float64", "string", "error", "rune", "make", "new",
    "len", "cap", "append", "panic", "recover",
)

private val RUST_KW = setOf(
    "as", "async", "await", "break", "const", "continue", "crate", "dyn", "else", "enum",
    "extern", "false", "fn", "for", "if", "impl", "in", "let", "loop", "match", "mod",
    "move", "mut", "pub", "ref", "return", "self", "Self", "static", "struct", "super",
    "trait", "true", "type", "union", "unsafe", "use", "where", "while", "bool", "char",
    "str", "u8", "u16", "u32", "u64", "u128", "usize", "i8", "i16", "i32", "i64", "i128",
    "isize", "f32", "f64", "String", "Vec", "Option", "Result", "Some", "None", "Ok", "Err",
)

private val PHP_KW = setOf(
    "abstract", "and", "array", "as", "break", "callable", "case", "catch", "class",
    "clone", "const", "continue", "declare", "default", "do", "echo", "else", "elseif",
    "empty", "enddeclare", "endfor", "endforeach", "endif", "endswitch", "endwhile",
    "enum", "extends", "final", "finally", "fn", "for", "foreach", "function", "global",
    "goto", "if", "implements", "include", "instanceof", "insteadof", "interface", "isset",
    "list", "match", "namespace", "new", "or", "print", "private", "protected", "public",
    "readonly", "require", "return", "static", "switch", "throw", "trait", "try", "unset",
    "use", "var", "while", "xor", "yield", "true", "false", "null", "self", "parent",
)

private val RUBY_KW = setOf(
    "BEGIN", "END", "alias", "and", "begin", "break", "case", "class", "def", "defined?",
    "do", "else", "elsif", "end", "ensure", "false", "for", "if", "in", "module", "next",
    "nil", "not", "or", "redo", "rescue", "retry", "return", "self", "super", "then", "true",
    "undef", "unless", "until", "when", "while", "yield", "require", "require_relative",
    "attr_accessor", "attr_reader", "attr_writer", "puts", "lambda", "proc",
)

private val SWIFT_KW = setOf(
    "associatedtype", "class", "deinit", "enum", "extension", "fileprivate", "func",
    "import", "init", "inout", "internal", "let", "open", "operator", "private", "protocol",
    "public", "static", "struct", "subscript", "typealias", "var", "break", "case",
    "continue", "default", "defer", "do", "else", "fallthrough", "for", "guard", "if", "in",
    "repeat", "return", "switch", "where", "while", "as", "catch", "false", "is", "nil",
    "rethrows", "super", "self", "Self", "throw", "throws", "true", "try", "any", "some",
    "Int", "String", "Double", "Bool", "Float", "Void", "Optional", "async", "await", "actor",
)

private val SQL_KW = setOf(
    "select", "from", "where", "insert", "into", "values", "update", "set", "delete",
    "create", "table", "drop", "alter", "add", "column", "primary", "key", "foreign",
    "references", "index", "unique", "not", "null", "default", "and", "or", "in", "like",
    "between", "is", "join", "inner", "left", "right", "outer", "full", "on", "as", "group",
    "by", "order", "having", "limit", "offset", "distinct", "count", "sum", "avg", "min",
    "max", "union", "all", "case", "when", "then", "else", "end", "asc", "desc", "true",
    "false", "int", "integer", "varchar", "text", "boolean", "date", "timestamp", "with",
)

private val SHELL_KW = setOf(
    "if", "then", "else", "elif", "fi", "case", "esac", "for", "while", "until", "do",
    "done", "in", "function", "select", "time", "return", "break", "continue", "exit",
    "export", "local", "readonly", "declare", "unset", "echo", "printf", "read", "cd",
    "source", "alias", "set", "test", "true", "false",
)

private val JSON_KW = setOf("true", "false", "null")

private val CSS_KW = setOf(
    "important", "inherit", "initial", "unset", "auto", "none", "block", "inline", "flex",
    "grid", "absolute", "relative", "fixed", "sticky", "static", "hidden", "visible",
    "bold", "italic", "normal", "center", "left", "right", "solid", "dashed", "dotted",
)

private val YAML_KW = setOf("true", "false", "null", "yes", "no", "on", "off", "~")

private val MARKDOWN_KW = emptySet<String>()

/** Broad common-keyword union used for GENERIC / unknown files. */
private val GENERIC_KW = (
    KOTLIN_KW + JAVA_KW + JS_KW + PYTHON_KW + C_CPP_KW + GO_KW + RUST_KW +
        SQL_KW + SHELL_KW + setOf("true", "false", "null", "none", "nil")
)

private fun keywordsFor(lang: CodeLang): Set<String> = when (lang) {
    CodeLang.KOTLIN -> KOTLIN_KW
    CodeLang.JAVA -> JAVA_KW
    CodeLang.JS -> JS_KW
    CodeLang.TS -> TS_KW
    CodeLang.PYTHON -> PYTHON_KW
    CodeLang.C_CPP -> C_CPP_KW
    CodeLang.CSHARP -> CSHARP_KW
    CodeLang.GO -> GO_KW
    CodeLang.RUST -> RUST_KW
    CodeLang.PHP -> PHP_KW
    CodeLang.RUBY -> RUBY_KW
    CodeLang.SWIFT -> SWIFT_KW
    CodeLang.SQL -> SQL_KW
    CodeLang.SHELL -> SHELL_KW
    CodeLang.JSON -> JSON_KW
    CodeLang.XML_HTML -> emptySet()
    CodeLang.CSS -> CSS_KW
    CodeLang.YAML -> YAML_KW
    CodeLang.MARKDOWN -> MARKDOWN_KW
    CodeLang.GENERIC -> GENERIC_KW
}

/** Whether `#` starts a line comment in this language. */
private fun usesHashComment(lang: CodeLang): Boolean = when (lang) {
    CodeLang.PYTHON, CodeLang.SHELL, CodeLang.YAML, CodeLang.RUBY -> true
    else -> false
}

/** Whether `//` starts a line comment and `/* */` a block comment. */
private fun usesSlashComment(lang: CodeLang): Boolean = when (lang) {
    CodeLang.KOTLIN, CodeLang.JAVA, CodeLang.JS, CodeLang.TS, CodeLang.C_CPP,
    CodeLang.CSHARP, CodeLang.GO, CodeLang.RUST, CodeLang.PHP, CodeLang.SWIFT,
    CodeLang.CSS, CodeLang.SQL, CodeLang.GENERIC,
    -> true
    else -> false
}

/** Whether the language supports backtick strings. */
private fun usesBacktickString(lang: CodeLang): Boolean = when (lang) {
    CodeLang.JS, CodeLang.TS, CodeLang.GO, CodeLang.GENERIC -> true
    else -> false
}

private const val MAX_HIGHLIGHT_CHARS = 100_000

/**
 * Highlights [code] for [lang] using [colors], returning an [AnnotatedString]
 * whose text equals [code] exactly. Never throws: any failure (or an
 * oversized buffer) degrades to a plain [AnnotatedString].
 */
fun highlight(code: String, lang: CodeLang, colors: HighlightColors): AnnotatedString {
    // Big-file guard: re-highlighting on every keystroke lags for huge files.
    if (code.length > MAX_HIGHLIGHT_CHARS) return AnnotatedString(code)
    return try {
        buildAnnotatedString {
            scan(code, lang, colors)
        }
    } catch (_: Throwable) {
        // A lexer bug must degrade to plain text, not crash the editor.
        AnnotatedString(code)
    }
}

/** Single left-to-right pass; appends every character exactly once. */
private fun androidx.compose.ui.text.AnnotatedString.Builder.scan(
    code: String,
    lang: CodeLang,
    colors: HighlightColors,
) {
    val n = code.length
    val kw = keywordsFor(lang)
    val hash = usesHashComment(lang)
    val slash = usesSlashComment(lang)
    val backtick = usesBacktickString(lang)
    val xml = lang == CodeLang.XML_HTML

    var i = 0
    // Buffer of pending default-colored characters, flushed lazily.
    val plain = StringBuilder()

    fun flushPlain() {
        if (plain.isNotEmpty()) {
            withStyle(SpanStyle(color = colors.default)) { append(plain.toString()) }
            plain.setLength(0)
        }
    }

    fun colored(text: String, color: Color) {
        flushPlain()
        withStyle(SpanStyle(color = color)) { append(text) }
    }

    while (i < n) {
        val c = code[i]

        // --- XML/HTML comments <!-- ... --> ---
        if (xml && c == '<' && code.startsWith("<!--", i)) {
            val end = code.indexOf("-->", i + 4)
            val stop = if (end < 0) n else end + 3
            colored(code.substring(i, stop), colors.comment)
            i = stop
            continue
        }

        // --- XML/HTML tags <tag ...> ---
        if (xml && c == '<') {
            val end = code.indexOf('>', i)
            val stop = if (end < 0) n else end + 1
            colored(code.substring(i, stop), colors.keyword)
            i = stop
            continue
        }

        // --- Block comment /* ... */ ---
        if (slash && c == '/' && i + 1 < n && code[i + 1] == '*') {
            val end = code.indexOf("*/", i + 2)
            val stop = if (end < 0) n else end + 2
            colored(code.substring(i, stop), colors.comment)
            i = stop
            continue
        }

        // --- Line comment // ... ---
        if (slash && c == '/' && i + 1 < n && code[i + 1] == '/') {
            val end = code.indexOf('\n', i)
            val stop = if (end < 0) n else end
            colored(code.substring(i, stop), colors.comment)
            i = stop
            continue
        }

        // --- Line comment # ... ---
        if (hash && c == '#') {
            val end = code.indexOf('\n', i)
            val stop = if (end < 0) n else end
            colored(code.substring(i, stop), colors.comment)
            i = stop
            continue
        }

        // --- Strings: " ' and (per-lang) ` ---
        if (c == '"' || c == '\'' || (backtick && c == '`')) {
            val allowNewline = c == '`'
            var j = i + 1
            while (j < n) {
                val d = code[j]
                if (d == '\\' && j + 1 < n) { j += 2; continue }
                if (d == c) { j++; break }
                if (!allowNewline && d == '\n') break // don't cross newline for '/"
                j++
            }
            colored(code.substring(i, minOf(j, n)), colors.string)
            i = minOf(j, n)
            continue
        }

        // --- Numbers: int / float / hex ---
        if (c.isDigit() || (c == '.' && i + 1 < n && code[i + 1].isDigit() &&
                (i == 0 || !isWordPart(code[i - 1])))
        ) {
            // Only start a number at a token boundary (not mid-identifier).
            if (i == 0 || !isWordPart(code[i - 1])) {
                var j = i
                if (c == '0' && i + 1 < n && (code[i + 1] == 'x' || code[i + 1] == 'X')) {
                    j = i + 2
                    while (j < n && (code[j].isDigit() || code[j] in 'a'..'f' ||
                            code[j] in 'A'..'F' || code[j] == '_')
                    ) j++
                } else {
                    while (j < n && (code[j].isDigit() || code[j] == '.' || code[j] == '_' ||
                            code[j] == 'e' || code[j] == 'E' ||
                            ((code[j] == '+' || code[j] == '-') && j > i &&
                                (code[j - 1] == 'e' || code[j - 1] == 'E')))
                    ) j++
                    // Trailing numeric type suffixes (f, F, L, l, d, D).
                    while (j < n && code[j] in "fFlLdDuU") j++
                }
                colored(code.substring(i, j), colors.number)
                i = j
                continue
            }
        }

        // --- Identifiers / keywords ---
        if (isWordStart(c)) {
            var j = i + 1
            while (j < n && isWordPart(code[j])) j++
            val word = code.substring(i, j)
            if (word in kw) {
                colored(word, colors.keyword)
            } else {
                plain.append(word)
            }
            i = j
            continue
        }

        // --- Anything else: default ---
        plain.append(c)
        i++
    }
    flushPlain()
}

private fun isWordStart(c: Char): Boolean = c.isLetter() || c == '_' || c == '$'
private fun isWordPart(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '$'
