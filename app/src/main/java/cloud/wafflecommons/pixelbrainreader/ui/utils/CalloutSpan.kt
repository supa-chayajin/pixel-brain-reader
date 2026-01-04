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
    private val headerHeight = 60
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
        if (start == spanStart) {
            fm.ascent -= headerHeight
            fm.top -= headerHeight
        }

        // 2. Add Bottom Margin ONLY for the last line
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
        c.drawRect(left, top.toFloat(), right, bottom.toFloat(), p)

        if (first) {
            // Draw Icon and Title ONLY on first line, shifted up into the reserved space
            p.isFakeBoldText = true
            p.textSize = 40f 
            
            // Calculate position: standard baseline minus the extra ascent we added
            // But 'baseline' passed here is the text baseline. 
            // We want to draw in the "header" area above the text.
            // Approximate Y: top (which is now higher) + headerHeight - small offset
            val headerY = top + headerHeight - 12f
            
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
