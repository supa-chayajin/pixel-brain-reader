package cloud.wafflecommons.pixelbrainreader.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ComposeCortexEditor(
    content: String,
    onContentChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    useMonospace: Boolean = true,
    readOnly: Boolean = false,
    enabled: Boolean = true
) {
    val textColor = MaterialTheme.colorScheme.onSurface
    val primaryColor = MaterialTheme.colorScheme.primary
    val codeBackgroundColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f)

    val visualTransformation = remember(textColor, primaryColor) {
        MarkdownVisualTransformation(textColor, primaryColor, codeBackgroundColor)
    }

    // Caret-follow is handled natively by BasicTextField: hosts give this editor
    // a height-bounded, IME-inset container (FileDetailPane / PrivateJournalActivity),
    // so the field's built-in vertical scroll keeps the cursor above the keyboard.
    // The previous whole-field BringIntoView + fixed-delay hack scrolled the field's
    // bounding box (never the caret) and raced adjustResize, so it's removed.
    BasicTextField(
        value = content,
        onValueChange = onContentChange,
        modifier = modifier,
        readOnly = readOnly,
        enabled = enabled,
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
 * Selection-aware overload. Same styling as the [String] variant but backed by a [TextFieldValue],
 * so callers can read/drive the cursor and selection (e.g. to reformat only the selected text).
 * Offsets stay 1:1 because [MarkdownVisualTransformation] uses [OffsetMapping.Identity].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ComposeCortexEditor(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    useMonospace: Boolean = true,
    readOnly: Boolean = false,
    enabled: Boolean = true
) {
    val textColor = MaterialTheme.colorScheme.onSurface
    val primaryColor = MaterialTheme.colorScheme.primary
    val codeBackgroundColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f)

    val visualTransformation = remember(textColor, primaryColor) {
        MarkdownVisualTransformation(textColor, primaryColor, codeBackgroundColor)
    }

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        readOnly = readOnly,
        enabled = enabled,
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
 *
 * `filter` runs on every recomposition of the text field, not only when the text changes, so the
 * result for the last text is reused and the patterns are compiled once — they used to be compiled
 * and run over the whole document several times per keystroke.
 */
class MarkdownVisualTransformation(
    private val defaultColor: Color,
    private val highlightColor: Color,
    private val codeBackground: Color
) : VisualTransformation {

    private var lastText: String? = null
    private var lastStyled: AnnotatedString? = null

    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val styled = lastStyled?.takeIf { raw == lastText }
            ?: parseMarkdown(raw).also {
                lastText = raw
                lastStyled = it
            }
        return TransformedText(
            text = styled,
            offsetMapping = OffsetMapping.Identity
        )
    }

    private fun parseMarkdown(text: String): AnnotatedString {
        return buildAnnotatedString {
            append(text)

            // 1. Headers (# H1, ## H2...)
            HEADER.findAll(text).forEach { match ->
                val hashtags = match.groups[1]!!
                val content = match.groups[2]!!

                // Style hashtags (Subtle)
                addStyle(
                    SpanStyle(color = highlightColor.copy(alpha = 0.4f), fontWeight = FontWeight.Normal),
                    hashtags.range.first,
                    hashtags.range.last + 1
                )

                // Style content (Bold & Large)
                val fontSize = when (hashtags.value.length) {
                    1 -> 22.sp
                    2 -> 20.sp
                    3 -> 18.sp
                    else -> 16.sp
                }
                addStyle(
                    SpanStyle(
                        color = highlightColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = fontSize
                    ),
                    content.range.first,
                    content.range.last + 1
                )
            }

            // 2. Bold (**text**)
            BOLD.findAll(text).forEach { match ->
                val content = match.groups[2]!!

                // Symbols (Subtle)
                addStyle(SpanStyle(color = defaultColor.copy(alpha = 0.4f)), match.range.first, content.range.first)
                addStyle(SpanStyle(color = defaultColor.copy(alpha = 0.4f)), content.range.last + 1, match.range.last + 1)

                // Content (Bold)
                addStyle(
                    SpanStyle(fontWeight = FontWeight.Bold, color = highlightColor),
                    content.range.first,
                    content.range.last + 1
                )
            }

            // 3. Italic (*text*)
            ITALIC.findAll(text).forEach { match ->
                val content = match.groups[2]!!

                // Symbols (Subtle)
                addStyle(SpanStyle(color = defaultColor.copy(alpha = 0.4f)), match.range.first, content.range.first)
                addStyle(SpanStyle(color = defaultColor.copy(alpha = 0.4f)), content.range.last + 1, match.range.last + 1)

                addStyle(
                    SpanStyle(fontStyle = FontStyle.Italic),
                    content.range.first,
                    content.range.last + 1
                )
            }

            // 4. Bullets (- item or * item)
            BULLET.findAll(text).forEach { match ->
                val symbol = match.groups[1]!!
                // Style bullet (Muted Gray / highlightColor dimmed)
                addStyle(
                    SpanStyle(color = defaultColor.copy(alpha = 0.5f), fontWeight = FontWeight.Bold),
                    symbol.range.first,
                    symbol.range.last + 1
                )
            }

            // 5. Code (`text`)
            CODE.findAll(text).forEach { match ->
                addStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = codeBackground,
                        color = highlightColor
                    ),
                    match.range.first,
                    match.range.last + 1
                )
            }

            // 6. WikiLinks ([[target]] or [[target|label]]) and Markdown links ([label](url))
            WIKI_LINK.findAll(text).forEach { match ->
                addStyle(
                    SpanStyle(
                        color = highlightColor,
                        fontWeight = FontWeight.Medium
                    ),
                    match.range.first,
                    match.range.last + 1
                )
            }
            MD_LINK.findAll(text).forEach { match ->
                val label = match.groups[1]
                val url = match.groups[2]
                if (label != null) {
                    addStyle(
                        SpanStyle(color = highlightColor, fontWeight = FontWeight.Medium),
                        label.range.first,
                        label.range.last + 1
                    )
                }
                if (url != null) {
                    addStyle(
                        SpanStyle(color = defaultColor.copy(alpha = 0.5f)),
                        url.range.first,
                        url.range.last + 1
                    )
                }
            }
        }
    }

    private companion object {
        val HEADER = Regex("^(#{1,6})\\s+(.*)", RegexOption.MULTILINE)
        val BOLD = Regex("(\\*\\*|__)(.*?)\\1")
        val ITALIC = Regex("(?<!\\*)(\\*|_)(?!\\s)(.*?)(?<!\\s)\\1(?!\\*)")
        val BULLET = Regex("^\\s*([-*])\\s+(.*)", RegexOption.MULTILINE)
        val CODE = Regex("`([^`]+)`")
        val WIKI_LINK = Regex("\\[\\[([^\\]]+)\\]\\]")
        val MD_LINK = Regex("\\[([^\\]]+)\\]\\(([^)]+)\\)")
    }
}
