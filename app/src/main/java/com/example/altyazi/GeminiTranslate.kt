package com.example.altyazi

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Oyun altyazısını Gemini (metin modeli) ile Türkçeye çevirir.
 * Cihaz içi ML Kit çevirisinden çok daha doğal sonuç verir, özellikle diyalog ve deyimlerde.
 * Başarısız olursa (kota, internet yok vb.) çağıran taraf ML Kit'e geçer.
 */
class GeminiTranslate {

    companion object {
        // Düşünme (thinking) kapalı, hızlı hafif model takma adı
        const val DEFAULT_MODEL = "gemini-flash-lite-latest"
        private const val TIMEOUT_MS = 6000
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val generation = AtomicInteger(0)

    /**
     * @param sourceName kaynak dil adı (ör. "İngilizce")
     * @param context bir önceki satırın çevirisi (anlam bütünlüğü için), boş olabilir
     * @param onResult çeviri metni, başarısızsa null ve hata mesajı
     */
    fun translate(
        apiKey: String,
        model: String,
        sourceName: String,
        text: String,
        context: String,
        onResult: (String?, String?) -> Unit
    ) {
        val gen = generation.incrementAndGet()
        executor.execute {
            if (gen != generation.get()) {
                onResult(null, "iptal")
                return@execute
            }
            try {
                val out = call(apiKey, model, sourceName, text, context)
                onResult(out, null)
            } catch (e: Exception) {
                onResult(null, e.message ?: "bilinmeyen hata")
            }
        }
    }

    fun release() {
        generation.incrementAndGet()
        executor.shutdownNow()
    }

    private fun call(
        apiKey: String,
        model: String,
        sourceName: String,
        text: String,
        context: String
    ): String {
        val url = URL(
            "https://generativelanguage.googleapis.com/v1beta/models/" +
                "${model.ifBlank { DEFAULT_MODEL }}:generateContent"
        )

        val system = "Sen bir video oyunu altyazı çevirmenisin. $sourceName dilindeki altyazıyı " +
            "doğal, akıcı, günlük Türkçeye çevir. Kurallar: " +
            "1) Yalnızca çeviriyi yaz; açıklama, tırnak, not, parantez ekleme. " +
            "2) Metinde olmayan kelime veya cümle ekleme, hiçbir şeyi tamamlama. " +
            "3) Metin OCR ile okunduğu için hatalı harfler olabilir; açıkça bozuk parçaları " +
            "yok say, anlamlı kısmı çevir. " +
            "4) Ünlemleri ('ohhh', 'ahh', 'hmm') kısa tut ve harf harf yazma ('oh', 'ah' gibi). " +
            "5) Anlamlı hiçbir şey yoksa boş bırak. " +
            "6) Özel isimleri (karakter, yer) çevirme."

        val userText = buildString {
            if (context.isNotBlank()) {
                append("[Önceki satırın çevirisi, yalnızca bağlam için, tekrar çevirme]\n")
                append(context.take(200)).append("\n\n")
            }
            append("[Çevrilecek altyazı]\n").append(text)
        }

        val body = JSONObject()
            .put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system)))
            )
            .put(
                "contents",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", userText)))
                )
            )
            .put(
                "generationConfig",
                JSONObject()
                    .put("temperature", 0.2)
                    .put("maxOutputTokens", 300)
            )

        val c = url.openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = TIMEOUT_MS
            c.readTimeout = TIMEOUT_MS
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("x-goog-api-key", apiKey)
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val resp = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw Exception(describeError(code, resp))

            val parts = JSONObject(resp)
                .getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
            val sb = StringBuilder()
            for (i in 0 until parts.length()) sb.append(parts.getJSONObject(i).optString("text"))
            return sb.toString().trim().trim('"', '“', '”', '«', '»').trim()
        } finally {
            c.disconnect()
        }
    }

    private fun describeError(code: Int, resp: String): String {
        val detail = try {
            JSONObject(resp).getJSONObject("error").getString("message")
        } catch (e: Exception) {
            ""
        }
        val hint = when (code) {
            400 -> "API anahtarı ya da istek geçersiz"
            401, 403 -> "API anahtarı reddedildi"
            404 -> "Model bulunamadı"
            429 -> "Kota doldu"
            else -> "Sunucu hatası"
        }
        return "$hint (HTTP $code)" + if (detail.isNotEmpty()) ": " + detail.take(100) else ""
    }
}
