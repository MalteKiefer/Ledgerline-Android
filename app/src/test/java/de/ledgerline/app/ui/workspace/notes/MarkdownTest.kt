package de.ledgerline.app.ui.workspace.notes

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownTest {
    @Test fun splits_blocks() {
        val blocks = markdownBlocks("# Title\n\nHello **world**\n\n- a\n- b")
        assertEquals(3, blocks.size)
        assertEquals(MdBlock.Heading(1, "Title"), blocks[0])
        assertEquals(MdBlock.Paragraph("Hello **world**"), blocks[1])
        assertEquals(MdBlock.BulletList(listOf("a", "b")), blocks[2])
    }

    @Test fun parses_inline_spans() {
        val spans = inlineSpans("a **b** c *d* `e`")
        // Expect literal 'a ', bold 'b', ' c ', italic 'd', ' ', code 'e'
        assertEquals("a ", spans[0].text)
        assertEquals(MdStyle.BOLD, spans[1].style)
        assertEquals("b", spans[1].text)
        assertEquals(MdStyle.ITALIC, spans[3].style)
        assertEquals(MdStyle.CODE, spans[5].style)
    }
}
