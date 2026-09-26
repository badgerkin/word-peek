package com.wordpeek

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView

/** Launcher screen: turns on the instant pop-up and lets you try a lookup directly. */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val content = findViewById<View>(R.id.content)
        content.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(bars.left + dp(24), bars.top + dp(24), bars.right + dp(24), bars.bottom + dp(24))
            }
            insets
        }

        findViewById<Button>(R.id.btnInstant).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btnAppInfo).setOnClickListener {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }

        val prefs = getSharedPreferences("wordpeek", MODE_PRIVATE)
        val textFields = findViewById<Switch>(R.id.swTextFields)
        textFields.isChecked = prefs.getBoolean(WordPeekAccessibilityService.PREF_TEXT_FIELDS, false)
        textFields.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(WordPeekAccessibilityService.PREF_TEXT_FIELDS, checked).apply()
        }

        val input = findViewById<EditText>(R.id.etTry)
        findViewById<Button>(R.id.btnTry).setOnClickListener {
            val text = input.text.toString().trim()
            if (text.isEmpty()) return@setOnClickListener
            startActivity(
                Intent(this, ProcessTextActivity::class.java)
                    .setAction(Intent.ACTION_PROCESS_TEXT)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_PROCESS_TEXT, text)
                    .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
            )
        }
    }

    override fun onResume() {
        super.onResume()
        val enabled = isInstantPopupEnabled()
        findViewById<TextView>(R.id.tvInstantStatus).setText(if (enabled) R.string.instant_on else R.string.instant_off)
        findViewById<Button>(R.id.btnInstant).setText(if (enabled) R.string.instant_settings else R.string.instant_enable)
        // "Restricted settings" for sideloaded apps only exist from Android 13.
        val restrictedVisibility = if (enabled || Build.VERSION.SDK_INT < 33) View.GONE else View.VISIBLE
        findViewById<View>(R.id.tvRestricted).visibility = restrictedVisibility
        findViewById<View>(R.id.btnAppInfo).visibility = restrictedVisibility
        findViewById<View>(R.id.swTextFields).visibility = if (enabled) View.VISIBLE else View.GONE
    }

    private fun isInstantPopupEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(this, WordPeekAccessibilityService::class.java)
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        for (entry in splitter) {
            if (ComponentName.unflattenFromString(entry) == me) return true
        }
        return false
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
