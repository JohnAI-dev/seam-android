package dev.johnai.seam

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Full-screen Found it shown while the phone is ringing. */
class FoundActivity : Activity() {
    private val onStopped = {
        if (!isFinishing) finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.hide()
        showOverLockScreen()
        setContentView(buildLayout())
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        showOverLockScreen()
    }

    override fun onStart() {
        super.onStart()
        PhoneFinder.addStopListener(onStopped)
        if (!PhoneFinder.isRinging()) {
            PhoneFinder.removeStopListener(onStopped)
            finish()
        }
    }

    override fun onStop() {
        PhoneFinder.removeStopListener(onStopped)
        super.onStop()
    }

    @Suppress("DEPRECATION")
    private fun showOverLockScreen() {
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
        )
    }

    private fun buildLayout(): LinearLayout {
        val pad = dp(24)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad, pad, pad)
        }
        column.addView(TextView(this).apply {
            tag = "find_title"
            text = getString(R.string.find_title)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            gravity = Gravity.CENTER
        })
        column.addView(TextView(this).apply {
            text = getString(R.string.find_text)
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(24))
        })
        column.addView(Button(this).apply {
            tag = "found"
            text = getString(R.string.found_it)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setOnClickListener {
                PhoneFinder.stop(this@FoundActivity)
                finish()
            }
        })
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER
            addView(column)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}

/** Notification action: the person found the phone. */
class FoundItReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        PhoneFinder.stop(context)
    }
}
