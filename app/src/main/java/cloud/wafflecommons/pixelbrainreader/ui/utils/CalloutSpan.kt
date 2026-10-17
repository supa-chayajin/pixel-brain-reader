package cloud.wafflecommons.pixelbrainreader.ui.utils

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.Spanned
import android.text.style.LineBackgroundSpan
import android.text.style.LeadingMarginSpan
import android.text.style.LineHeightSpan

/**
 * A custom Span that renders an Obsidian Callout block natively.
 * It draws a rounded background and a thick left border.
 */
class CalloutSpan(
    private val backgroundColor: Int,
    private val stripeColor: Int,
    private val icon: String,
    private val title: String,
    density: Float = 1f
) : LineBackgroundSpan, LeadingMarginSpan, LineHeightSpan {

    private val scale = if (density > 0f) density / 2.625f else 1f
    private val stripeWidth = (12 * scale).toInt().coerceAtLeast(4)
    private val padding = (40 * scale).toInt().coerceAtLeast(16)
    private val headerHeight = (70 * scale).toInt().coerceAtLeast(28)
    private val bottomMargin = (40 * scale).toInt().coerceAtLeast(16)
    private val headerTextSize = 40f * scale
    private val headerBaselineInset = 20f * scale
    private val headerTextOffsetX = 24f * scale

    // StaticLayout reuses ONE FontMetricsInt across all lines of a paragraph, so a mutation
    // made on the first line persists onto every subsequent line. We snapshot the pristine
    // ascent/top on the first line and restore them on the body lines — otherwise the
    // first line's header inflation (headerHeight) leaks forward and stretches every line.
    private var baseAscent = 0
    private var baseTop = 0
    private var capturedBase = false

    override fun chooseHeight(
        text: CharSequence,
        start: Int, end: Int,
        spanstartv: Int, v: Int,
        fm: Paint.FontMetricsInt
    ) {
        val spanned = text as? Spanned ?: return
        val spanStart = spanned.getSpanStart(this)
        val spanEnd = spanned.getSpanEnd(this)

        // 1. Header space is reserved ONLY on the very first line of the callout.
        if (spanStart >= 0 && start == spanStart) {
            // Snapshot the untouched metrics, then push the ascent up for the header.
            baseAscent = fm.ascent
            baseTop = fm.top
            capturedBase = true
            fm.ascent -= headerHeight
            fm.top -= headerHeight
        } else if (capturedBase) {
            // Body line: undo the header inflation carried over in the shared fm object.
            fm.ascent = baseAscent
            fm.top = baseTop
        }

        // 2. Bottom breathing room ONLY on the exact last line of the callout.
        if (spanEnd >= 0 && end == spanEnd) {
            fm.descent += bottomMargin
            fm.bottom += bottomMargin
        }
    }

    override fun drawLeadingMargin(
        c: Canvas, p: Paint, x: Int, dir: Int,
        top: Int, baseline: Int, bottom: Int,
        text: CharSequence, start: Int, end: Int,
        first: Boolean, layout: android.text.Layout
    ) {
        val originalStyle = p.style
        val originalColor = p.color
        val originalTextSize = p.textSize

        // Draw Stripe ALWAYS (not just if first)
        p.style = Paint.Style.FILL
        p.color = stripeColor
        
        val left = x.toFloat()
        val right = (x + dir * stripeWidth).toFloat()
        // Draw stripe from top to bottom of the line
        c.drawRect(left, top.toFloat(), right, bottom.toFloat(), p)

        // Draw the header ONLY on the true first line of the whole callout span.
        val spanStart = (text as? Spanned)?.getSpanStart(this) ?: -1
        if (start == spanStart) {
            // Draw Icon and Title ONLY on first line, shifted up into the reserved space
            p.isFakeBoldText = true
            p.textSize = headerTextSize
            val headerY = top + headerHeight - headerBaselineInset
            c.drawText("${icon}  ${title.uppercase()}", x + stripeWidth + headerTextOffsetX, headerY, p)
        }

        p.style = originalStyle
        p.color = originalColor
        p.textSize = originalTextSize
        p.isFakeBoldText = false
    }

    override fun getLeadingMargin(first: Boolean): Int = padding

    override fun drawBackground(
        c: Canvas, p: Paint,
        left: Int, right: Int, top: Int, baseline: Int, bottom: Int,
        text: CharSequence, start: Int, end: Int, lnum: Int
    ) {
        val originalColor = p.color
        val rect = RectF(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
        
        p.color = backgroundColor
        c.drawRoundRect(rect, 0f, 0f, p)

        p.color = originalColor
    }
}
