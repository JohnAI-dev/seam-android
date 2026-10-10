package dev.johnai.seam

import android.os.Build
import android.view.View
import android.view.WindowInsets

/**
 * Android 15+ draws apps edge to edge, under the status and navigation bars. Pad the view by
 * the bars (and any camera cutout) so nothing ends up hidden behind them, keeping its own padding.
 */
fun View.padForSystemBars() {
    val base = intArrayOf(paddingLeft, paddingTop, paddingRight, paddingBottom)
    setOnApplyWindowInsetsListener { v, insets ->
        val (l, t, r, b) = if (Build.VERSION.SDK_INT >= 30) {
            val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            listOf(i.left, i.top, i.right, i.bottom)
        } else {
            @Suppress("DEPRECATION")
            listOf(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
        }
        v.setPadding(base[0] + l, base[1] + t, base[2] + r, base[3] + b)
        insets
    }
    requestApplyInsets()
}
