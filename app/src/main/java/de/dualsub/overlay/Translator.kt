package de.dualsub.overlay

import android.util.LruCache
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * DeepL, one subtitle at a time, with a cache for lines that recur.
 *
 * The key is read from files/deepl_key in the app's private storage, put there
 * over `adb shell run-as` so it never travels as a command-line argument.
 */
class Translator(filesDir: File, private val targetLang: String = "EN-GB") {

    private val key: String? = File(filesDir, "deepl_key").takeIf { it.exists() }
        ?.readText()?.trim()?.takeIf { it.isNotEmpty() }
    private val cache = LruCache<String, String>(500)
    var charsSent = 0
        private set

    val ready get() = key != null

    fun cached(text: String): String? = cache.get(text)

    /** Blocking; call off the main thread. Returns null on failure. */
    fun translate(text: String): String? {
        cache.get(text)?.let { return it }
        val k = key ?: return null
        val host = if (k.endsWith(":fx")) "api-free.deepl.com" else "api.deepl.com"
        val body = "text=" + URLEncoder.encode(text, "UTF-8") +
                "&source_lang=DE&target_lang=" + targetLang +
                "&preserve_formatting=1"
        val conn = URL("https://$host/v2/translate").openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 4000
            conn.readTimeout = 6000
            conn.doOutput = true
            conn.setRequestProperty("Authorization", "DeepL-Auth-Key $k")
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.outputStream.use { it.write(body.toByteArray()) }
            if (conn.responseCode != 200) {
                Diag.error("DeepL refused (HTTP ${conn.responseCode}${explain(conn.responseCode)})")
                conn.errorStream?.use { it.readBytes() }
                return null
            }
            // Reading the body to the end (and not calling disconnect) lets the
            // connection go back to the keep-alive pool: no TLS handshake per line.
            val json = conn.inputStream.bufferedReader().use { it.readText() }
            val out = JSONObject(json).getJSONArray("translations")
                .getJSONObject(0).getString("text")
            charsSent += text.length
            cache.put(text, out)
            out
        } catch (e: Exception) {
            Diag.error("DeepL unreachable", e)
            null
        }
    }

    private fun explain(code: Int) = when (code) {
        403 -> ": key rejected"
        429 -> ": too many requests"
        456 -> ": monthly quota used up"
        else -> ""
    }

    /** Opens the connection early and logs this month's usage. */
    fun warmUp() {
        val k = key ?: return
        val host = if (k.endsWith(":fx")) "api-free.deepl.com" else "api.deepl.com"
        try {
            val conn = URL("https://$host/v2/usage").openConnection() as HttpURLConnection
            conn.connectTimeout = 4000
            conn.readTimeout = 6000
            conn.setRequestProperty("Authorization", "DeepL-Auth-Key $k")
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val used = json.optLong("character_count")
            val limit = json.optLong("character_limit")
            Diag.deeplUsage = "%,d of %,d".format(used, limit)
            Diag.info("DeepL: ${Diag.deeplUsage} characters used this month")
        } catch (e: Exception) {
            Diag.error("DeepL unreachable at start", e)
        }
    }
}
