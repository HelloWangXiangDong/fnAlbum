package com.fnalbum.tv

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup

/**
 * 固定行列的照片网格容器。
 * 由外部决定行列数（3x2 / 3x3 / 4x3），自行测算每个格子的尺寸并摆放。
 * 子 View 为 GONE 时跳过占位，INVISIBLE 时保留空位。
 */
class PhotoGridLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ViewGroup(context, attrs, defStyleAttr) {

    var cols = 3
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
            }
        }

    var rows = 2
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
            }
        }

    private val gap = (10 * resources.displayMetrics.density).toInt()

    /** 计算单个格子宽度，供图片按尺寸解码用 */
    fun cellWidth(): Int {
        val w = width
        if (w <= 0) return 0
        return ((w - gap * (cols + 1)) / cols).coerceAtLeast(1)
    }

    fun cellHeight(): Int {
        val h = height
        if (h <= 0) return 0
        return ((h - gap * (rows + 1)) / rows).coerceAtLeast(1)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val cw = ((w - gap * (cols + 1)) / cols).coerceAtLeast(1)
        val ch = ((h - gap * (rows + 1)) / rows).coerceAtLeast(1)
        for (i in 0 until childCount) {
            getChildAt(i).measure(
                MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY)
            )
        }
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        val h = b - t
        val cw = ((w - gap * (cols + 1)) / cols).coerceAtLeast(1)
        val ch = ((h - gap * (rows + 1)) / rows).coerceAtLeast(1)

        var slot = 0
        for (i in 0 until childCount) {
            val child: View = getChildAt(i)
            if (child.visibility == GONE) continue
            val row = slot / cols
            val col = slot % cols
            val x = gap + col * (cw + gap)
            val y = gap + row * (ch + gap)
            child.layout(x, y, x + cw, y + ch)
            slot++
        }
    }
}
