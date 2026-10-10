package dev.johnai.seam

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/**
 * Share target: the person picks Share to Seam. Sends the text and closes.
 * Android will not let a background app read the clipboard, but a share intent can.
 */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val text = sharedText(intent)
            val message = if (text == null) {
                getString(R.string.clipboard_nothing)
            } else {
                LinkHub.startService(this)
                val name = LinkHub.sendClipboard(this, text)
                if (name == null) getString(R.string.clipboard_not_connected)
                else getString(R.string.clipboard_sent, name)
            }
            Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        } finally {
            finish()
        }
    }

    private fun sharedText(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_SEND) return null
        val type = intent.type?.substringBefore(';')?.trim() ?: return null
        if (type != "text/plain") return null
        val extra = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        if (!extra.isNullOrEmpty()) return extra
        val item = intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0) ?: return null
        return item.text?.toString()?.takeIf { it.isNotEmpty() }
            ?: item.coerceToText(this).toString().takeIf { it.isNotEmpty() }
    }
}
