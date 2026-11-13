package com.example.altyazi

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Anahtarsız çevrimiçi çeviri (Google Translate'in herkese açık web ucu).
 * ML Kit'ten belirgin şekilde daha doğru. Resmî bir API değil, ileride değişirse
 * ya da kısıtlanırsa çağıran taraf otomatik olarak ML Kit'e geçer.
 */
class FreeTranslate {

    private val executor = Executors.newSingleThreadExecutor()
    private val generation = AtomicInteger(0)

    fun translate(source: String, text: String, onResult: (String?, String?) -> Unit) {
        val gen = generation.incrementAndGet()
        executor.execute {
            if (gen != generation.get()) {
                onResult(null, "iptal")
                return@execute
            }
            try {
                onResult(call(source, text), null)
            } catch (e: Exception) {
                onResult(null, e.message ?: "bilinmeyen hata")
            }
        }
    }

    fun release() {
        generation.incrementAndGet()
        executor.shutdownNow()
    }

    private fun call(source: String, text: String): String {
        val url = URL(
            "https://translate.googleapis.com/translate_a/single?client=gtx" +
                "&sl=$source&tl=tr&dt=t&q=" + URLEncoder.encode(text, "UTF-8")
        )
        val c = url.openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 4000
            c.readTimeout = 4000
            c.setRequestProperty("User-Agent", "Mozilla/5.0")
            val code = c.responseCode
            if (code !in 200..299) throw Exception("HTTP $code")
            val resp = c.inputStream.bufferedReader().use { it.readText() }
            val segs: JSONArray = JSONArray(resp).getJSONArray(0)
            val sb = StringBuilder()
            for (i in 0 until segs.length()) {
                val part = segs.optJSONArray(i)?.optString(0, "") ?: ""
                sb.append(part)
            }
            val out = sb.toString().trim()
            if (out.isEmpty()) throw Exception("boş yanıt")
            return out
        } finally {
            c.disconnect()
        }
    }
}
