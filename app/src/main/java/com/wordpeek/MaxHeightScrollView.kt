package com.wordpeek

import android.content.Context
import android.util.AttributeSet
import android.widget.ScrollView

/** ScrollView that never grows taller than 55% of the screen, so the sheet's buttons stay visible. */
class MaxHeightScrollView(context: Context, attrs: AttributeSet?) : ScrollView(context, attrs) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val max = (resources.displayMetrics.heightPixels * 0.55f).toInt()
        val size = MeasureSpec.getSize(heightMeasureSpec)
        val capped = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) max else minOf(size, max)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(capped, MeasureSpec.AT_MOST))
    }
}
