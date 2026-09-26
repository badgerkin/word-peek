package com.wordpeek

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets

/**
 * Handles ACTION_PROCESS_TEXT from any app's text-selection menu and shows
 * definition, Wikipedia and translation results in a bottom panel over the host app.
 * This is the fallback for apps that don't report selections to WordPeekAccessibilityService.
 */
class ProcessTextActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val selectedText = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()?.trim()
        val readOnly = intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)

        if (selectedText.isNullOrBlank()) {
            finish()
            return
        }

        setContentView(R.layout.dialog_overlay)
        val root = findViewById<View>(R.id.root)
        val sheet = findViewById<View>(R.id.sheet)
        root.setOnClickListener { finish() }
        root.setOnApplyWindowInsetsListener { _, insets ->
            val bottom = if (Build.VERSION.SDK_INT >= 30) {
                insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime()).bottom
            } else {
                @Suppress("DEPRECATION") insets.systemWindowInsetBottom
            }
            sheet.setPadding(sheet.paddingLeft, sheet.paddingTop, sheet.paddingRight, bottom + dp(20))
            insets
        }

        val onReplace: ((String) -> Unit)? = if (readOnly) null else { text ->
            setResult(RESULT_OK, Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, text))
            finish()
        }
        OverlaySheet(this, sheet, onClose = { finish() }, onReplace = onReplace).show(selectedText)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
