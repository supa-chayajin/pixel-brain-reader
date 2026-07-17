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
    private val title: String
) : LineBackgroundSpan, LeadingMarginSpan, LineHeightSpan {

    private val stripeWidth = 12
    private val padding = 40
    // Updated metrics as per request
    private val headerHeight = 70
    private val bottomMargin = 40

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

        // Draw Stripe ALWAYS (not just if first)
        p.style = Paint.Style.FILL
        p.color = stripeColor
        
        val left = x.toFloat()
        val right = (x + dir * stripeWidth).toFloat()
        // Draw stripe from top to bottom of the line
        c.drawRect(left, top.toFloat(), right, bottom.toFloat(), p)

        // Draw the header ONLY on the true first line of the whole callout span.
        // `first` is true for the first line of EVERY paragraph the span covers, so relying
        // on it repeated the icon+title at the top of each paragraph (covering the body).
        // chooseHeight() reserves the header's top space only when start == spanStart, so we
        // key the header draw off the exact same condition to keep them in lock-step.
        val spanStart = (text as? Spanned)?.getSpanStart(this) ?: -1
        if (start == spanStart) {
            // Draw Icon and Title ONLY on first line, shifted up into the reserved space
            p.isFakeBoldText = true
            p.textSize = 40f 
            
            // Calculate position: standard baseline minus the extra ascent we added?
            // "top" passed to drawLeadingMargin includes the space adjustment from chooseHeight?
            // Actually, 'top' IS the top of the line box.
            // We reserved 'headerHeight' ABOVE the text content.
            // So we want to draw the header inside that reserved top area.
            
            // Text baseline is 'baseline'. 
            // The content starts roughly at 'baseline + ascent' (standard).
            // We pushed 'ascent' up by 70. So 'top' is ~70px higher than normal.
            
            // Let's aim to center the header text vertically within the 'headerHeight' space?
            // Or just anchor it near the top.
            // top + headerHeight is roughly where the content starts.
            // We want to draw at Y = top + some_offset.
            
            // Using a heuristic based on previous value: top + headerHeight - 12f
            // If top is -100, and headerHeight is 70, then headerY is -42.
            // This places it above the content.
            
            val headerY = top + headerHeight - 20f // Tweaked for 70px height
            
            c.drawText("${icon}  ${title.uppercase()}", x + stripeWidth + 24f, headerY, p)
        }

        p.style = originalStyle
        p.color = originalColor
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
