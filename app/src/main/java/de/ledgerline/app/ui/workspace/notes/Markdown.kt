package de.ledgerline.app.ui.workspace.notes

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/** A tiny, dependency-free subset of Markdown for read-only note rendering. */
sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class BulletList(val items: List<String>) : MdBlock
    data class NumberedList(val items: List<String>) : MdBlock
}

enum class MdStyle { PLAIN, BOLD, ITALIC, CODE }
data class MdSpan(val text: String, val style: MdStyle = MdStyle.PLAIN)

/** Split raw markdown into block elements (headings, paragraphs, bullet/numbered lists). */
fun markdownBlocks(raw: String): List<MdBlock> {
    val lines = raw.replace("\r\n", "\n").trimEnd().split("\n")
    val blocks = mutableListOf<MdBlock>()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        when {
            line.isBlank() -> i++
            line.startsWith("#") -> {
                val level = line.takeWhile { it == '#' }.length.coerceIn(1, 6)
                blocks += MdBlock.Heading(level, line.drop(level).trim())
                i++
            }
            line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") -> {
                val items = mutableListOf<String>()
                while (i < lines.size && (lines[i].trimStart().startsWith("- ") || lines[i].trimStart().startsWith("* "))) {
                    items += lines[i].trimStart().drop(2).trim(); i++
                }
                blocks += MdBlock.BulletList(items)
            }
            Regex("^\\s*\\d+\\. ").containsMatchIn(line) -> {
                val items = mutableListOf<String>()
                while (i < lines.size && Regex("^\\s*\\d+\\. ").containsMatchIn(lines[i])) {
                    items += lines[i].replaceFirst(Regex("^\\s*\\d+\\. "), "").trim(); i++
                }
                blocks += MdBlock.NumberedList(items)
            }
            else -> {
                val sb = StringBuilder()
                while (i < lines.size && lines[i].isNotBlank() && !lines[i].startsWith("#") &&
                    !lines[i].trimStart().startsWith("- ") && !lines[i].trimStart().startsWith("* ")) {
                    if (sb.isNotEmpty()) sb.append(' ')
                    sb.append(lines[i].trim()); i++
                }
                blocks += MdBlock.Paragraph(sb.toString())
            }
        }
    }
    return blocks
}

/** Parse inline **bold**, *italic*, `code` into styled spans (single level, no nesting). */
fun inlineSpans(text: String): List<MdSpan> {
    val spans = mutableListOf<MdSpan>()
    val token = Regex("\\*\\*(.+?)\\*\\*|\\*(.+?)\\*|`(.+?)`")
    var last = 0
    for (m in token.findAll(text)) {
        if (m.range.first > last) spans += MdSpan(text.substring(last, m.range.first))
        val style = when {
            m.groupValues[1].isNotEmpty() -> MdStyle.BOLD
            m.groupValues[2].isNotEmpty() -> MdStyle.ITALIC
            else -> MdStyle.CODE
        }
        val content = m.groupValues[1].ifEmpty { m.groupValues[2].ifEmpty { m.groupValues[3] } }
        spans += MdSpan(content, style)
        last = m.range.last + 1
    }
    if (last < text.length) spans += MdSpan(text.substring(last))
    return spans
}

@Composable
private fun inlineAnnotated(text: String) = buildAnnotatedString {
    for (span in inlineSpans(text)) when (span.style) {
        MdStyle.BOLD -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(span.text) }
        MdStyle.ITALIC -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(span.text) }
        MdStyle.CODE -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(span.text) }
        MdStyle.PLAIN -> append(span.text)
    }
}

@Composable
fun MarkdownText(raw: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        for (block in markdownBlocks(raw)) {
            when (block) {
                is MdBlock.Heading -> Text(
                    inlineAnnotated(block.text),
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.headlineSmall
                        2 -> MaterialTheme.typography.titleLarge
                        else -> MaterialTheme.typography.titleMedium
                    },
                    color = MaterialTheme.colorScheme.onBackground,
                )
                is MdBlock.Paragraph -> Text(
                    inlineAnnotated(block.text),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                is MdBlock.BulletList -> block.items.forEach { item ->
                    Row { Text("•  "); Text(inlineAnnotated(item), color = MaterialTheme.colorScheme.onSurface) }
                }
                is MdBlock.NumberedList -> block.items.forEachIndexed { idx, item ->
                    Row { Text("${idx + 1}.  "); Text(inlineAnnotated(item), color = MaterialTheme.colorScheme.onSurface) }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
