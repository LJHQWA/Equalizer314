package com.bearinmind.equalizer314.ui

import android.content.Context
import android.text.TextPaint
import android.util.AttributeSet
import android.util.TypedValue
import com.google.android.material.textview.MaterialTextView
import java.text.BreakIterator
import kotlin.math.abs

/** Multi-line label that only wraps where a line may break (spaces, hyphens, between CJK characters): shrinks, down to 8sp, until its widest unbreakable piece fits one line, so long translations never split mid-word. */
class WordFitTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle,
) : MaterialTextView(context, attrs, defStyleAttr) {

    private val baseSizePx = textSize
    private val minSizePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 8f, resources.displayMetrics)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            // 3% slack: the line breaker's widths land a hair above measureText's.
            val avail = (MeasureSpec.getSize(widthMeasureSpec) - compoundPaddingLeft - compoundPaddingRight) * 0.97f
            val widest = widestPiece(TextPaint(paint).apply { textSize = baseSizePx })
            val fitted = if (avail > 0 && widest > avail) (baseSizePx * avail / widest).coerceAtLeast(minSizePx) else baseSizePx
            if (abs(fitted - textSize) > 0.5f) setTextSize(TypedValue.COMPLEX_UNIT_PX, fitted)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    /** Width of the longest run between two line-break opportunities, at [p]'s size. */
    private fun widestPiece(p: TextPaint): Float {
        val s = text?.toString().orEmpty()
        val it = BreakIterator.getLineInstance(textLocale).apply { setText(s) }
        var widest = 0f
        var start = it.first()
        var end = it.next()
        while (end != BreakIterator.DONE) {
            // TextView keeps hyphenated compounds ("Пост-підсилення") together, so a break right after '-' doesn't count.
            if (end < s.length && s[end - 1] == '-') { end = it.next(); continue }
            val piece = s.substring(start, end).trimEnd()
            if (piece.isNotEmpty()) widest = maxOf(widest, p.measureText(piece))
            start = end
            end = it.next()
        }
        return widest
    }
}
