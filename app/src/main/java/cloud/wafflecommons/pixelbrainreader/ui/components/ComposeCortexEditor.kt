package cloud.wafflecommons.pixelbrainreader.ui.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ComposeCortexEditor(
    content: String,
    onContentChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    useMonospace: Boolean = true
) {
    val textColor = MaterialTheme.colorScheme.onSurface
    val primaryColor = MaterialTheme.colorScheme.primary
    val codeBackgroundColor = MaterialTheme.colorScheme.surfaceContainerHighest

    val visualTransformation = remember(textColor, primaryColor) {
        MarkdownVisualTransformation(textColor, primaryColor, codeBackgroundColor)
    }

    BasicTextField(
        value = content,
        onValueChange = onContentChange,
        modifier = modifier,
        textStyle = TextStyle(
            color = textColor,
            fontSize = 16.sp,
            fontFamily = if (useMonospace) FontFamily.Monospace else FontFamily.Default,
            lineHeight = 24.sp
        ),
        cursorBrush = SolidColor(primaryColor),
        visualTransformation = visualTransformation
    )
}

/**
 * A lightweight VisualTransformation for Markdown Syntax Highlighting.
 * Enhances:
 * - **Bold**
 * - *Italic*
 * - # Headers
 * - `Code`
 * - [Links]
 */
class MarkdownVisualTransformation(
    private val defaultColor: Color,
    private val highlightColor: Color,
    private val codeBackground: Color
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        return TransformedText(
            text = parseMarkdown(text.text),
            offsetMapping = OffsetMapping.Identity
        )
    }

    private fun parseMarkdown(text: String): AnnotatedString {
        return buildAnnotatedString {
            append(text) // Base text
            
            // We apply styles on top of the raw text. 
            // Note: VisualTransformation normally maps encoded text to visual.
            // Here we just style the raw text for "Highlighting" while editing (Syntax Highlighting).
            
            val raw = text
            
            // 1. Headers (# H1, ## H2...)
            val headerRegex = Regex("^(#{1,6})\\s+(.*)", RegexOption.MULTILINE)
            headerRegex.findAll(raw).forEach { match ->
                val range = match.range
                addStyle(
                    SpanStyle(
                        color = highlightColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = when (match.groupValues[1].length) {
                            1 -> 24.sp
                            2 -> 20.sp
                            3 -> 18.sp
                            else -> 16.sp
                        }
                    ),
                    range.first,
                    range.last + 1
                )
            }
            
            // 2. Bold (**text**)
            val boldRegex = Regex("(\\*\\*|__)(.*?)\\1")
            boldRegex.findAll(raw).forEach { match ->
                addStyle(
                    SpanStyle(fontWeight = FontWeight.Bold, color = highlightColor),
                    match.range.first,
                    match.range.last + 1
                )
            }
            
            // 3. Italic (*text*)
            val italicRegex = Regex("(\\*|_)(.*?)\\1")
            italicRegex.findAll(raw).forEach { match ->
                // Avoid matching empty or bold parts if regex overlaps (simple implementation)
                addStyle(
                    SpanStyle(fontStyle = FontStyle.Italic),
                    match.range.first,
                    match.range.last + 1
                )
            }
            
            // 4. Code (`text`)
            val codeRegex = Regex("(`[^`]+`)")
            codeRegex.findAll(raw).forEach { match ->
                 addStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = codeBackground
                    ),
                    match.range.first,
                    match.range.last + 1
                )
            }
            
            // 5. Links ([text])
            val linkRegex = Regex("\\[(.*?)\\]")
            linkRegex.findAll(raw).forEach { match ->
                 addStyle(
                    SpanStyle(
                        color = highlightColor,
                        textDecoration = TextDecoration.Underline
                    ),
                    match.range.first,
                    match.range.last + 1
                )
            }
        }
    }
}
