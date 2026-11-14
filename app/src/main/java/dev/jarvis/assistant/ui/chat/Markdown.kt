package dev.jarvis.assistant.ui.chat

// Pure Kotlin on purpose (no android or compose imports) so it can be unit-tested on the build server.
// A small Markdown subset for chat bubbles: **bold**, *italic*, `code`, headings, bullet and numbered lists,
// fenced code blocks. It never throws and never drops text: anything it does not understand is shown as typed.

/** One run of text with the same style. */
data class Span(val text: String, val bold: Boolean = false, val italic: Boolean = false, val code: Boolean = false)

sealed interface Block {
    data class Paragraph(val spans: List<Span>) : Block
    data class Heading(val level: Int, val spans: List<Span>) : Block
    data class Bullet(val indent: Int, val spans: List<Span>) : Block
    data class Numbered(val indent: Int, val number: String, val spans: List<Span>) : Block
    data class CodeBlock(val text: String) : Block
}

object Markdown {
    private val HEADING = Regex("""^\s{0,3}(#{1,6})\s+(.*)$""")
    private val BULLET = Regex("""^(\s*)[-*+]\s+(.*)$""")
    private val NUMBERED = Regex("""^(\s*)(\d{1,3})[.)]\s+(.*)$""")
    private val FENCE = Regex("""^\s*```.*$""")

    /**
     * [streaming] = the text is still arriving. An opening marker with no closing one yet (for example a lone `**`)
     * then styles the rest of the line right away, so the user sees bold appear instead of stray asterisks.
     * When the reply is finished, an unmatched marker is shown literally.
     */
    fun parse(text: String, streaming: Boolean = false): List<Block> {
        val blocks = mutableListOf<Block>()
        val para = mutableListOf<String>()
        var inCode = false
        val code = StringBuilder()

        fun flushPara() {
            if (para.isEmpty()) return
            blocks += Block.Paragraph(inlineLines(para.toList(), streaming))
            para.clear()
        }

        for (raw in text.replace("\r\n", "\n").split('\n')) {
            if (inCode) {
                if (FENCE.matches(raw) && raw.trim() == "```") {
                    blocks += Block.CodeBlock(code.toString().trimEnd('\n'))
                    code.clear()
                    inCode = false
                } else {
                    code.appendLine(raw)
                }
                continue
            }
            if (FENCE.matches(raw)) {
                flushPara()
                inCode = true
                continue
            }
            val heading = HEADING.matchEntire(raw)
            val bullet = BULLET.matchEntire(raw)
            val numbered = NUMBERED.matchEntire(raw)
            when {
                raw.isBlank() -> flushPara()
                heading != null -> {
                    flushPara()
                    blocks += Block.Heading(heading.groupValues[1].length, inline(heading.groupValues[2], streaming))
                }
                bullet != null -> {
                    flushPara()
                    blocks += Block.Bullet(bullet.groupValues[1].length / 2, inline(bullet.groupValues[2], streaming))
                }
                numbered != null -> {
                    flushPara()
                    blocks += Block.Numbered(
                        numbered.groupValues[1].length / 2, numbered.groupValues[2], inline(numbered.groupValues[3], streaming),
                    )
                }
                else -> para += raw
            }
        }
        // An unfinished code fence (still streaming, or never closed) is shown as code, not lost.
        if (inCode) blocks += Block.CodeBlock(code.toString().trimEnd('\n'))
        flushPara()
        return blocks
    }

    private fun inlineLines(lines: List<String>, streaming: Boolean): List<Span> {
        val out = mutableListOf<Span>()
        lines.forEachIndexed { i, line ->
            if (i > 0) out += Span("\n")
            out += inline(line, streaming)
        }
        return merge(out)
    }

    /** Styles one line. Markers must hug the text they style, so `2 * 3 * 4` and `a ** b` stay literal. */
    fun inline(line: String, streaming: Boolean = false): List<Span> {
        val out = mutableListOf<Span>()
        val buf = StringBuilder()
        var bold = false
        var italic = false
        var i = 0

        fun flush() {
            if (buf.isNotEmpty()) {
                out += Span(buf.toString(), bold, italic)
                buf.clear()
            }
        }

        fun prevIsWordChar() = i > 0 && line[i - 1].isLetterOrDigit()
        fun nextNonSpace(at: Int) = at < line.length && !line[at].isWhitespace()
        fun prevNonSpace() = i > 0 && !line[i - 1].isWhitespace()

        while (i < line.length) {
            val c = line[i]
            when {
                c == '`' -> {
                    val end = line.indexOf('`', i + 1)
                    if (end > i + 1) {
                        flush()
                        out += Span(line.substring(i + 1, end), code = true)
                        i = end + 1
                    } else if (end < 0 && streaming && nextNonSpace(i + 1)) {
                        flush()
                        out += Span(line.substring(i + 1), code = true)
                        i = line.length
                    } else {
                        buf.append(c); i++
                    }
                }
                line.startsWith("**", i) -> {
                    if (bold && prevNonSpace()) {
                        flush(); bold = false; i += 2
                    } else if (!bold && nextNonSpace(i + 2) && (line.indexOf("**", i + 2) > 0 || streaming)) {
                        flush(); bold = true; i += 2
                    } else {
                        buf.append("**"); i += 2
                    }
                }
                c == '*' -> {
                    if (italic && prevNonSpace()) {
                        flush(); italic = false; i++
                    } else if (!italic && !prevIsWordChar() && nextNonSpace(i + 1) &&
                        (hasSingleStarAfter(line, i + 1) || streaming)
                    ) {
                        flush(); italic = true; i++
                    } else {
                        buf.append(c); i++
                    }
                }
                c == '\\' && i + 1 < line.length && line[i + 1] in "*`\\" -> {
                    buf.append(line[i + 1]); i += 2
                }
                else -> {
                    buf.append(c); i++
                }
            }
        }
        flush()
        return merge(out)
    }

    private fun hasSingleStarAfter(line: String, from: Int): Boolean {
        var j = from
        while (j < line.length) {
            if (line[j] == '*') {
                if (j + 1 < line.length && line[j + 1] == '*') j += 2 else return true
            } else j++
        }
        return false
    }

    private fun merge(spans: List<Span>): List<Span> {
        val out = mutableListOf<Span>()
        for (s in spans) {
            if (s.text.isEmpty()) continue
            val last = out.lastOrNull()
            if (last != null && last.bold == s.bold && last.italic == s.italic && last.code == s.code) {
                out[out.size - 1] = last.copy(text = last.text + s.text)
            } else out += s
        }
        return out
    }
}
