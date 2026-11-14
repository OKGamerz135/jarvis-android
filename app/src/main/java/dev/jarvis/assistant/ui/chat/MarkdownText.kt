package dev.jarvis.assistant.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Renders assistant text. Parsed again only when [text] changes, so streaming stays smooth. */
@Composable
fun MarkdownText(text: String, streaming: Boolean, modifier: Modifier = Modifier) {
    val blocks = remember(text, streaming) { Markdown.parse(text, streaming) }
    val codeBackground = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (block in blocks) {
            when (block) {
                is Block.Paragraph ->
                    Text(styled(block.spans, codeBackground), style = MaterialTheme.typography.bodyLarge)
                is Block.Heading -> Text(
                    styled(block.spans, codeBackground),
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    },
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 4.dp),
                )
                is Block.Bullet -> ListRow("\u2022", block.indent, styled(block.spans, codeBackground))
                is Block.Numbered -> ListRow(block.number + ".", block.indent, styled(block.spans, codeBackground))
                is Block.CodeBlock -> Text(
                    block.text,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                    softWrap = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(codeBackground, RoundedCornerShape(8.dp))
                        .horizontalScroll(rememberScrollState())
                        .padding(8.dp),
                )
            }
        }
    }
}

@Composable
private fun ListRow(marker: String, indent: Int, content: AnnotatedString) {
    Row(Modifier.padding(start = (indent * 16).dp)) {
        Text(marker, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.widthIn(min = 20.dp))
        Text(content, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}

private fun styled(spans: List<Span>, codeBackground: androidx.compose.ui.graphics.Color): AnnotatedString = buildAnnotatedString {
    for (s in spans) {
        val style = SpanStyle(
            fontWeight = if (s.bold) FontWeight.Bold else null,
            fontStyle = if (s.italic) FontStyle.Italic else null,
            fontFamily = if (s.code) FontFamily.Monospace else null,
            background = if (s.code) codeBackground else androidx.compose.ui.graphics.Color.Unspecified,
        )
        withStyle(style) { append(s.text) }
    }
}
