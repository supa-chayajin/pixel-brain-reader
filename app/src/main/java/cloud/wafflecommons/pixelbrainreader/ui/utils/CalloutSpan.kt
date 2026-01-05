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

    override fun chooseHeight(
        text: CharSequence,
        start: Int, end: Int,
        spanstartv: Int, v: Int,
        fm: Paint.FontMetricsInt
    ) {
        val spanned = text as Spanned
        val spanStart = spanned.getSpanStart(this)
        val spanEnd = spanned.getSpanEnd(this)

        // 1. Add Top Padding ONLY for the first line (Header space)
        // Check if the current line's start matches the span's start
        if (start == spanStart) {
            // Push the ascent up (negative direction) to create space above the baseline
            fm.ascent -= headerHeight 
            fm.top -= headerHeight
        }
        
        // 2. Add Bottom Margin ONLY for the last line
        // Check if the current line's end matches the span's end
        if (end >= spanEnd) {
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

        if (first) {
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
