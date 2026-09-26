package com.wordpeek

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import java.util.regex.Pattern

/**
 * Shows the Word Peek sheet as soon as the user selects text (long-press) in another app,
 * without going through the selection menu. It listens for accessibility text-selection
 * events, waits for the selection to settle, then draws the sheet as an accessibility overlay.
 *
 * Only works in apps that report selections to accessibility services; the PROCESS_TEXT
 * menu entry remains as the fallback everywhere else.
 */
class WordPeekAccessibilityService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var sheet: OverlaySheet? = null
    private var shown = false
    private var shownPackage: CharSequence? = null
    private var shownText: String? = null
    private var pending: Runnable? = null

    override fun onServiceConnected() {
        windowManager = getSystemService(WindowManager::class.java)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName ?: return
        if (pkg == packageName) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> onSelectionChanged(event, pkg)
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ->
                // Another app came to the front.
                if (shown && pkg != shownPackage && pkg != "com.android.systemui") hide()
        }
    }

    private fun onSelectionChanged(event: AccessibilityEvent, pkg: CharSequence) {
        cancelPending()
        val text = selectedText(event)
        if (text == null) {
            // Selection collapsed or cleared in the app we're showing over.
            if (shown && pkg == shownPackage) hide()
            return
        }
        if (event.isPassword) return
        if (!isLookupSized(text)) return
        val prefs = getSharedPreferences("wordpeek", Context.MODE_PRIVATE)
        if (!prefs.getBoolean(PREF_TEXT_FIELDS, false) && event.source?.isEditable == true) return
        if (shown && text == shownText) return

        // Selection handles fire a stream of events while dragging; wait for it to settle.
        val show = Runnable { show(text, pkg) }
        pending = show
        main.postDelayed(show, SETTLE_DELAY_MS)
    }

    private fun selectedText(event: AccessibilityEvent): String? {
        val from = minOf(event.fromIndex, event.toIndex)
        val to = maxOf(event.fromIndex, event.toIndex)
        if (from < 0 || from == to) return null

        var full: CharSequence? = if (event.text.isNotEmpty()) event.text.joinToString("") else null
        if (full == null || to > full.length) full = event.source?.text
        if (full == null || to > full.length) return null
        return full.subSequence(from, to).toString().trim().ifEmpty { null }
    }

    /** Auto pop-up is for words and short phrases; longer selections are usually for copying. */
    private fun isLookupSized(text: String) =
        text.length <= MAX_CHARS && WHITESPACE.split(text).size <= MAX_WORDS

    private fun show(text: String, pkg: CharSequence) {
        pending = null
        val wm = windowManager ?: return
        val current = sheet ?: createSheet().also { sheet = it }
        current.show(text)
        shownText = text
        shownPackage = pkg
        if (!shown) {
            try {
                wm.addView(current.view, layoutParams())
                shown = true
            } catch (e: RuntimeException) {
                // The window token can be refused while the service is being torn down.
                shown = false
            }
        }
    }

    private fun createSheet(): OverlaySheet {
        val themed = ContextThemeWrapper(this, R.style.AppTheme)
        val view = LayoutInflater.from(themed).inflate(R.layout.overlay_sheet, null)
        val basePadding = view.paddingBottom
        view.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_OUTSIDE) hide()
            false
        }
        view.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bottom = insets.getInsets(WindowInsets.Type.navigationBars()).bottom
                v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, basePadding + bottom)
            }
            insets
        }
        return OverlaySheet(themed, view, onClose = { hide() })
    }

    private fun layoutParams(): WindowManager.LayoutParams {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // Not focusable, and touches outside the sheet go to the app underneath.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        params.gravity = Gravity.BOTTOM
        params.windowAnimations = android.R.style.Animation_InputMethod
        if (Build.VERSION.SDK_INT >= 30) {
            // Draw under the navigation bar; the insets listener pads the buttons clear of it.
            params.fitInsetsTypes = 0
        }
        return params
    }

    private fun hide() {
        cancelPending()
        if (!shown) return
        shown = false
        shownText = null
        shownPackage = null
        sheet?.view?.let { v ->
            try {
                windowManager?.removeView(v)
            } catch (e: RuntimeException) {
                // Already detached.
            }
        }
    }

    private fun cancelPending() {
        pending?.let { main.removeCallbacks(it) }
        pending = null
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        hide()
        return super.onUnbind(intent)
    }

    companion object {
        const val PREF_TEXT_FIELDS = "popup_in_text_fields"
        private const val SETTLE_DELAY_MS = 450L
        private const val MAX_CHARS = 80
        private const val MAX_WORDS = 6
        private val WHITESPACE = Pattern.compile("\\s+")
    }
}
