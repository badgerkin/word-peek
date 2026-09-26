package com.wordpeek

import android.os.Build
import android.text.Html
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.regex.Pattern

/** Blocking network lookups. Call from a background thread. */
object Lookup {

    private const val USER_AGENT = "WordPeek/1.3 (Android; +https://github.com/badgerkin)"

    /** A server answered with an HTTP error other than 404. */
    class HttpError(val code: Int, val host: String) : IOException("HTTP $code from $host")

    /**
     * Dictionary definitions, or null when no source knows the word.
     * Tries Wiktionary first, then dictionaryapi.dev. Throws only if every source failed.
     */
    fun define(word: String): String? {
        var firstError: Exception? = null
        for (source in listOf(::wiktionary, ::dictionaryApi)) {
            try {
                source(word)?.let { return it }
            } catch (e: Exception) {
                if (firstError == null) firstError = e
            }
        }
        firstError?.let { throw it }
        return null
    }

    /**
     * English definitions from Wiktionary's REST API. For inflected forms such as
     * "quantized", whose entry just says "past participle of quantize", the base word's
     * definitions are appended.
     */
    private fun wiktionary(word: String): String? {
        val candidates = linkedSetOf(word, word.lowercase())
        for (title in candidates) {
            val entries = wiktionaryEntries(title) ?: continue
            val out = StringBuilder()
            val lemmas = linkedSetOf<String>()
            appendWiktionary(entries, out, lemmas)
            // Follow at most one "form of" link, and only if it names a different word.
            lemmas.firstOrNull { !it.equals(title, ignoreCase = true) }?.let { lemma ->
                val lemmaEntries = try {
                    wiktionaryEntries(lemma)
                } catch (e: IOException) {
                    null
                }
                if (lemmaEntries != null) {
                    out.append("— ").append(lemma).append(" —\n")
                    appendWiktionary(lemmaEntries, out, null)
                }
            }
            val text = out.toString().trim()
            if (text.isNotEmpty()) return text
        }
        return null
    }

    private fun wiktionaryEntries(title: String): JSONArray? {
        val body = get("https://en.wiktionary.org/api/rest_v1/page/definition/" + encodePath(title)) ?: return null
        return JSONObject(body).optJSONArray("en")
    }

    // java.util.regex rather than kotlin.text.Regex: the latter pulls in stdlib lambdas
    // compiled to invokedynamic, which dx can't convert for Android 6-7.
    private val FORM_OF_LINK = Pattern.compile("form-of-definition-link.*?title=\"([^\"#]+)")
    private val WHITESPACE = Pattern.compile("\\s+")

    private fun appendWiktionary(entries: JSONArray, out: StringBuilder, lemmas: MutableSet<String>?) {
        for (i in 0 until entries.length()) {
            val entry = entries.getJSONObject(i)
            val defs = entry.optJSONArray("definitions") ?: continue
            val lines = ArrayList<String>()
            for (d in 0 until defs.length()) {
                val html = defs.getJSONObject(d).optString("definition")
                if (lemmas != null) {
                    val m = FORM_OF_LINK.matcher(html)
                    if (m.find()) lemmas.add(m.group(1)!!)
                }
                val text = stripHtml(html)
                if (text.isNotEmpty()) lines.add(text)
                if (lines.size == 3) break
            }
            if (lines.isEmpty()) continue
            out.append(entry.optString("partOfSpeech").lowercase()).append('\n')
            lines.forEachIndexed { n, line -> out.append(n + 1).append(". ").append(line).append('\n') }
            out.append('\n')
            if (out.length > 1500) break
        }
    }

    private fun stripHtml(html: String): String {
        val spanned = if (Build.VERSION.SDK_INT >= 24) {
            Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY)
        } else {
            @Suppress("DEPRECATION") Html.fromHtml(html)
        }
        return WHITESPACE.matcher(spanned).replaceAll(" ").trim()
    }

    /** Fallback source: dictionaryapi.dev. */
    private fun dictionaryApi(word: String): String? {
        val body = get("https://api.dictionaryapi.dev/api/v2/entries/en/" + encodePath(word.lowercase())) ?: return null
        val entries = JSONArray(body)
        val out = StringBuilder()
        for (i in 0 until entries.length()) {
            val entry = entries.getJSONObject(i)
            if (i == 0) {
                val phonetic = entry.optString("phonetic")
                if (phonetic.isNotEmpty()) out.append(phonetic).append("\n\n")
            }
            val meanings = entry.optJSONArray("meanings") ?: continue
            for (m in 0 until meanings.length()) {
                val meaning = meanings.getJSONObject(m)
                val defs = meaning.optJSONArray("definitions") ?: continue
                out.append(meaning.optString("partOfSpeech")).append('\n')
                for (d in 0 until minOf(defs.length(), 3)) {
                    out.append(d + 1).append(". ").append(defs.getJSONObject(d).optString("definition")).append('\n')
                }
                out.append('\n')
            }
            if (out.length > 1500) break
        }
        return out.toString().trim().ifEmpty { null }
    }

    /** Wikipedia page summary, or null when there is no article. */
    fun wikipedia(query: String, lang: String): String? {
        val host = "https://" + lang + ".wikipedia.org/api/rest_v1/page/summary/"
        val body = get(host + encodePath(query.replace(' ', '_'))) ?: return null
        val json = JSONObject(body)
        val extract = json.optString("extract")
        return extract.ifEmpty { null }
    }

    /** Machine translation via MyMemory. Returns null on failure. */
    fun translate(text: String, target: String): String? {
        val q = URLEncoder.encode(text, "UTF-8")
        for (source in arrayOf("Autodetect", "en")) {
            val body = get("https://api.mymemory.translated.net/get?q=$q&langpair=$source%7C$target") ?: continue
            val json = JSONObject(body)
            if (json.optInt("responseStatus") != 200) continue
            val translated = json.optJSONObject("responseData")?.optString("translatedText").orEmpty()
            if (translated.isNotEmpty()) return translated
        }
        return null
    }

    private fun encodePath(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** GET a URL and return the body, or null on HTTP 404. Other HTTP errors throw [HttpError]. */
    private fun get(url: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.setRequestProperty("Accept", "application/json")
        try {
            val code = conn.responseCode
            if (code == 404) return null
            if (code !in 200..299) throw HttpError(code, conn.url.host)
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
