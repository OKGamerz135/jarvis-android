package dev.jarvis.assistant.ui

import dev.jarvis.assistant.ui.chat.Block
import dev.jarvis.assistant.ui.chat.Markdown
import dev.jarvis.assistant.ui.chat.Span
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTest {
    private fun plain(spans: List<Span>) = spans.joinToString("") { it.text }

    @Test
    fun boldIsRenderedNotStripped() {
        val b = Markdown.parse("**Short answer:** No, a chicken would not survive in space.")
        val p = b.single() as Block.Paragraph
        assertEquals(Span("Short answer:", bold = true), p.spans[0])
        assertEquals(Span(" No, a chicken would not survive in space."), p.spans[1])
        assertFalse(plain(p.spans).contains("*"))
    }

    @Test
    fun italicAndBoldAndCodeInOneLine() {
        val spans = Markdown.inline("a *b* **c** `d` e")
        assertEquals(
            listOf(
                Span("a "), Span("b", italic = true), Span(" "), Span("c", bold = true),
                Span(" "), Span("d", code = true), Span(" e"),
            ),
            spans,
        )
    }

    @Test
    fun boldAndItalicNest() {
        val spans = Markdown.inline("**bold *both* bold**")
        assertEquals(listOf(Span("bold ", bold = true), Span("both", bold = true, italic = true), Span(" bold", bold = true)), spans)
    }

    @Test
    fun headingsAreRecognisedWithLevel() {
        val b = Markdown.parse("## A **big** title\nbody")
        val h = b[0] as Block.Heading
        assertEquals(2, h.level)
        assertEquals("A big title", plain(h.spans))
        assertTrue(h.spans.any { it.bold && it.text == "big" })
        assertEquals("body", plain((b[1] as Block.Paragraph).spans))
    }

    @Test
    fun bulletAndNumberedListsKeepTheirItemsAndNumbers() {
        val b = Markdown.parse("- one\n* two **x**\n  - nested\n1. first\n2) second")
        assertEquals(5, b.size)
        assertEquals("one", plain((b[0] as Block.Bullet).spans))
        assertEquals("two x", plain((b[1] as Block.Bullet).spans))
        assertEquals(1, (b[2] as Block.Bullet).indent)
        val n = b[3] as Block.Numbered
        assertEquals("1", n.number)
        assertEquals("first", plain(n.spans))
        assertEquals("2", (b[4] as Block.Numbered).number)
    }

    @Test
    fun paragraphsAreSeparatedAndSingleNewlinesAreKept() {
        val b = Markdown.parse("line one\nline two\n\nnext paragraph")
        assertEquals(2, b.size)
        assertEquals("line one\nline two", plain((b[0] as Block.Paragraph).spans))
        assertEquals("next paragraph", plain((b[1] as Block.Paragraph).spans))
    }

    @Test
    fun fencedCodeBlocksKeepTheirContentVerbatim() {
        val b = Markdown.parse("before\n```kotlin\nval a = **not bold**\n  indented\n```\nafter")
        assertEquals(3, b.size)
        assertEquals("val a = **not bold**\n  indented", (b[1] as Block.CodeBlock).text)
        assertEquals("after", plain((b[2] as Block.Paragraph).spans))
    }

    // ------------------------------------------------------------------ streaming and malformed input

    @Test
    fun anOpeningBoldWithoutItsCloserStylesTheRestWhileStreaming() {
        val s = Markdown.inline("Hello **wor", streaming = true)
        assertEquals(listOf(Span("Hello "), Span("wor", bold = true)), s)
        assertFalse(plain(s).contains("*"))
    }

    @Test
    fun theSameTextWhenFinishedShowsTheMarkerLiterally() {
        val s = Markdown.inline("Hello **wor", streaming = false)
        assertEquals("Hello **wor", plain(s))
        assertTrue(s.none { it.bold })
    }

    @Test
    fun aLoneTrailingMarkerWhileStreamingDoesNotCrashOrLeakStyle() {
        // a marker with nothing after it yet is kept as typed; it becomes styling once real text follows
        assertEquals("Hello **", plain(Markdown.inline("Hello **", streaming = true)))
        assertEquals("a *", plain(Markdown.inline("a *", streaming = true)))
        assertTrue(Markdown.inline("Hello **", streaming = true).none { it.bold })
    }

    @Test
    fun streamingGrowsFromPartialToFullWithoutLosingText() {
        val full = "**Short answer:** No. *Really* `x`."
        for (n in 1..full.length) {
            val prefix = full.substring(0, n)
            val blocks = Markdown.parse(prefix, streaming = true)
            val text = blocks.joinToString("") { b ->
                when (b) {
                    is Block.Paragraph -> plain(b.spans)
                    else -> ""
                }
            }
            // never throws; letters are never lost (only marker characters may disappear)
            assertEquals(prefix.filter { it.isLetterOrDigit() }, text.filter { it.isLetterOrDigit() })
        }
        assertEquals("Short answer: No. Really x.", plain((Markdown.parse(full).single() as Block.Paragraph).spans))
    }

    @Test
    fun mathAndWildcardStarsStayLiteral() {
        assertEquals("2 * 3 * 4 = 24", plain(Markdown.inline("2 * 3 * 4 = 24")))
        assertEquals("a ** b", plain(Markdown.inline("a ** b")))
        assertEquals("*.txt files", plain(Markdown.inline("*.txt files")))
        assertEquals("snake_case_name and 5*6", plain(Markdown.inline("snake_case_name and 5*6")))
    }

    @Test
    fun anUnclosedCodeFenceIsShownAsCodeAndNotLost() {
        val b = Markdown.parse("intro\n```\nfun main() {", streaming = true)
        assertEquals("fun main() {", (b.last() as Block.CodeBlock).text)
    }

    @Test
    fun backslashEscapesAndEmptyInput() {
        assertEquals("*not italic*", plain(Markdown.inline("\\*not italic\\*")))
        assertTrue(Markdown.parse("").isEmpty())
        assertTrue(Markdown.parse("\n\n  \n").isEmpty())
    }

    @Test
    fun windowsLineEndingsWork() {
        val b = Markdown.parse("- a\r\n- b")
        assertEquals(2, b.size)
    }
}
