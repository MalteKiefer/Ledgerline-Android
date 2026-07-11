package de.ledgerline.app.ui.workspace.files

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeHighlighterTest {

    private val colors = HighlightColors(
        keyword = Color(0xFF0000FF),
        string = Color(0xFF008000),
        number = Color(0xFFFF8800),
        comment = Color(0xFF888888),
        default = Color(0xFF000000),
    )

    // ---- langOf ----------------------------------------------------------

    @Test fun langOf_maps_extensions() {
        assertEquals(CodeLang.KOTLIN, langOf("Main.kt"))
        assertEquals(CodeLang.KOTLIN, langOf("build.gradle.kts"))
        assertEquals(CodeLang.JAVA, langOf("App.java"))
        assertEquals(CodeLang.JS, langOf("index.mjs"))
        assertEquals(CodeLang.JS, langOf("comp.jsx"))
        assertEquals(CodeLang.TS, langOf("main.ts"))
        assertEquals(CodeLang.TS, langOf("view.tsx"))
        assertEquals(CodeLang.PYTHON, langOf("script.py"))
        assertEquals(CodeLang.C_CPP, langOf("core.cpp"))
        assertEquals(CodeLang.C_CPP, langOf("header.h"))
        assertEquals(CodeLang.CSHARP, langOf("Program.cs"))
        assertEquals(CodeLang.GO, langOf("server.go"))
        assertEquals(CodeLang.RUST, langOf("lib.rs"))
        assertEquals(CodeLang.PHP, langOf("index.php"))
        assertEquals(CodeLang.RUBY, langOf("app.rb"))
        assertEquals(CodeLang.SWIFT, langOf("View.swift"))
        assertEquals(CodeLang.SQL, langOf("schema.sql"))
        assertEquals(CodeLang.SHELL, langOf("deploy.sh"))
        assertEquals(CodeLang.SHELL, langOf("run.bash"))
        assertEquals(CodeLang.JSON, langOf("data.json"))
        assertEquals(CodeLang.XML_HTML, langOf("page.html"))
        assertEquals(CodeLang.XML_HTML, langOf("pom.xml"))
        assertEquals(CodeLang.XML_HTML, langOf("icon.svg"))
        assertEquals(CodeLang.CSS, langOf("style.scss"))
        assertEquals(CodeLang.YAML, langOf("ci.yml"))
        assertEquals(CodeLang.YAML, langOf("Cargo.toml"))
        assertEquals(CodeLang.MARKDOWN, langOf("README.md"))
    }

    @Test fun langOf_generic_fallback() {
        assertEquals(CodeLang.GENERIC, langOf("notes.txt"))
        assertEquals(CodeLang.GENERIC, langOf("noextension"))
        assertEquals(CodeLang.GENERIC, langOf(""))
    }

    // ---- highlight text preservation (critical for OffsetMapping.Identity) --

    private fun assertPreserves(code: String, lang: CodeLang) {
        val out = highlight(code, lang, colors)
        assertEquals("highlight must preserve text exactly", code, out.text)
    }

    @Test fun highlight_preserves_kotlin() {
        val code = """
            // a comment
            fun main() {
                val x = 42       // number and keyword
                val s = "hello \" world"
                println(s + x)
            }
        """.trimIndent()
        assertPreserves(code, CodeLang.KOTLIN)
    }

    @Test fun highlight_preserves_json() {
        val code = """{ "name": "ledger", "count": 3, "ok": true, "nil": null }"""
        assertPreserves(code, CodeLang.JSON)
    }

    @Test fun highlight_preserves_python_hash_comment() {
        val code = "# a python comment\ndef f(x):\n    return x * 2  # inline\n"
        assertPreserves(code, CodeLang.PYTHON)
    }

    @Test fun highlight_preserves_xml() {
        val code = "<!-- comment -->\n<root attr=\"v\">\n  <child>text 12</child>\n</root>"
        assertPreserves(code, CodeLang.XML_HTML)
    }

    @Test fun highlight_preserves_empty_and_generic() {
        assertPreserves("", CodeLang.GENERIC)
        assertPreserves("plain text with no tokens", CodeLang.GENERIC)
    }

    @Test fun highlight_does_not_throw_on_unterminated_tokens() {
        // Unterminated string / block comment / tag must degrade gracefully.
        assertPreserves("val s = \"unterminated", CodeLang.KOTLIN)
        assertPreserves("/* unterminated block", CodeLang.KOTLIN)
        assertPreserves("<tag without close", CodeLang.XML_HTML)
    }

    @Test fun highlight_bigfile_guard_returns_plain() {
        val big = "a".repeat(100_001)
        val out = highlight(big, CodeLang.KOTLIN, colors)
        assertEquals(big, out.text)
        assertTrue(out.spanStyles.isEmpty())
    }

    // ---- keyword span assertion ------------------------------------------

    @Test fun highlight_applies_keyword_span_for_kotlin() {
        val code = "fun main() {}"
        val out = highlight(code, CodeLang.KOTLIN, colors)
        // "fun" is at [0,3) and must carry the keyword color.
        val funSpan = out.spanStyles.firstOrNull { it.start == 0 && it.end == 3 }
        assertTrue("expected a keyword span at the 'fun' keyword range", funSpan != null)
        assertEquals(colors.keyword, funSpan!!.item.color)
    }
}
