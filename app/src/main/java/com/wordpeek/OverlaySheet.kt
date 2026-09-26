package com.wordpeek

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/**
 * Binds the definition/Wikipedia/translation sheet (layout/overlay_sheet) to a query.
 * Shared by ProcessTextActivity (menu entry) and WordPeekAccessibilityService (instant pop-up).
 *
 * [onReplace] is null when the selection can't be replaced, which hides the Replace button.
 */
class OverlaySheet(
    private val context: Context,
    val view: View,
    private val onClose: () -> Unit,
    private val onReplace: ((String) -> Unit)? = null,
) {
    private val main = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences("wordpeek", Context.MODE_PRIVATE)
    private val codes = context.resources.getStringArray(R.array.language_codes)
    private val names = context.resources.getStringArray(R.array.language_names)

    private var query = ""
    private var translation: String? = null
    // Bumped on every new query (translationGeneration also on language change) so late
    // results from older lookups are dropped.
    private var generation = 0
    private var translationGeneration = 0

    init {
        view.findViewById<Button>(R.id.btnClose).setOnClickListener { onClose() }
        view.findViewById<Button>(R.id.btnCopy).setOnClickListener {
            val text = translation ?: query
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Word Peek", text))
            if (Build.VERSION.SDK_INT < 33) Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
        }
        val replace = view.findViewById<Button>(R.id.btnReplace)
        replace.visibility = if (onReplace == null) View.GONE else View.VISIBLE
        replace.setOnClickListener { translation?.let { onReplace?.invoke(it) } }
        setUpLanguagePicker()
    }

    fun show(text: String) {
        query = text.trim().take(500)
        generation++
        translation = null
        view.findViewById<TextView>(R.id.tvWord).text = query
        view.findViewById<View>(R.id.scroll).scrollTo(0, 0)
        view.findViewById<View>(R.id.languageList).visibility = View.GONE

        val isSingleWord = query.none { it.isWhitespace() } && query.length <= 40
        setSectionVisible(R.id.lblDefinition, R.id.tvDefinition, isSingleWord)
        if (isSingleWord) {
            val q = query
            load(R.id.tvDefinition, R.string.no_definition) { Lookup.define(q) }
        }

        val wikiVisible = query.length <= 100
        setSectionVisible(R.id.lblWiki, R.id.tvWiki, wikiVisible)
        if (wikiVisible) {
            val q = query
            val wikiLang = Locale.getDefault().language.ifEmpty { "en" }
            load(R.id.tvWiki, R.string.no_wikipedia) {
                Lookup.wikipedia(q, wikiLang) ?: if (wikiLang != "en") Lookup.wikipedia(q, "en") else null
            }
        }
        translate()
    }

    private fun setSectionVisible(labelId: Int, bodyId: Int, visible: Boolean) {
        val v = if (visible) View.VISIBLE else View.GONE
        view.findViewById<View>(labelId).visibility = v
        view.findViewById<View>(bodyId).visibility = v
    }

    private fun targetLanguage(): String {
        val deviceLang = Locale.getDefault().language
        val fallback = if (deviceLang != "en" && deviceLang in codes) deviceLang else "es"
        val saved = prefs.getString("target_lang", fallback)
        return if (saved in codes) saved!! else fallback
    }

    private fun setUpLanguagePicker() {
        val button = view.findViewById<TextView>(R.id.btnLanguage)
        val list = view.findViewById<LinearLayout>(R.id.languageList)
        val density = context.resources.displayMetrics.density
        button.text = context.getString(R.string.language_button, names[codes.indexOf(targetLanguage())])
        button.setOnClickListener {
            list.visibility = if (list.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        for (i in codes.indices) {
            val item = TextView(context)
            item.text = names[i]
            item.textSize = 15f
            item.setTextColor(0xFFE2E8F0.toInt())
            val pad = (10 * density).toInt()
            item.setPadding(pad, pad, pad, pad)
            item.setOnClickListener {
                prefs.edit().putString("target_lang", codes[i]).apply()
                button.text = context.getString(R.string.language_button, names[i])
                list.visibility = View.GONE
                translate()
            }
            list.addView(item)
        }
    }

    private fun translate() {
        translation = null
        val q = query
        val code = targetLanguage()
        val gen = ++translationGeneration
        load(R.id.tvTranslation, R.string.no_translation, { gen == translationGeneration }, { translation = it }) {
            Lookup.translate(q, code)
        }
    }

    /**
     * Runs [fetch] off the main thread and shows the result (or [emptyRes]) in the given TextView,
     * unless [isCurrent] says a newer lookup has replaced this one.
     */
    private fun load(
        viewId: Int,
        emptyRes: Int,
        isCurrent: (() -> Boolean)? = null,
        onResult: (String) -> Unit = {},
        fetch: () -> String?,
    ) {
        val target = view.findViewById<TextView>(viewId)
        target.setText(R.string.loading)
        val gen = generation
        val current = isCurrent ?: { gen == generation }
        Thread {
            var error: Exception? = null
            val result = try {
                fetch()
            } catch (e: Exception) {
                error = e
                null
            }
            main.post {
                if (!current()) return@post
                target.text = result ?: error?.let { errorMessage(it) } ?: context.getString(emptyRes)
                if (result != null) onResult(result)
            }
        }.start()
    }

    private fun errorMessage(e: Exception): String = when (e) {
        is Lookup.HttpError -> context.getString(R.string.server_error, e.host, e.code)
        is java.io.IOException -> context.getString(R.string.network_error, e.javaClass.simpleName)
        else -> context.getString(R.string.parse_error, e.javaClass.simpleName)
    }
}
