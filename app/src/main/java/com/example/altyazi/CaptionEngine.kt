package com.example.altyazi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Matrix
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.camera.core.ImageProxy
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class HistoryItem(val time: Long, val original: String, val translated: String)

/**
 * Kamera karesi -> altyazı kutusunu kes -> OCR -> Türkçeye çevir -> seslendir.
 * Ayarlar ve geçmiş SharedPreferences'ta saklanır.
 */
class CaptionEngine(context: Context) {

    companion object {
        // Yazı tanıma Latin alfabesini destekler
        val LANGUAGES = listOf(
            TranslateLanguage.ENGLISH to "İngilizce",
            TranslateLanguage.GERMAN to "Almanca",
            TranslateLanguage.FRENCH to "Fransızca",
            TranslateLanguage.SPANISH to "İspanyolca",
            TranslateLanguage.ITALIAN to "İtalyanca",
            TranslateLanguage.PORTUGUESE to "Portekizce"
        )
        // OCR: bu değerin altındaki güvenle okunan satırlar atılır (0..1)
        const val MIN_CONFIDENCE = 0.6f
        const val CONTRAST = 1.5f
        const val DEF_LEFT = 0.15f
        const val DEF_TOP = 0.72f
        const val DEF_WIDTH = 0.70f
        const val DEF_HEIGHT = 0.24f
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    // ---------- Ayarlar ----------
    var speak by mutableStateOf(prefs.getBoolean("speak", true))
    var speechRate by mutableFloatStateOf(prefs.getFloat("rate", 1.0f))
    var pitch by mutableFloatStateOf(prefs.getFloat("pitch", 1.0f))
    var flushSpeech by mutableStateOf(prefs.getBoolean("flush", true))
    var textSize by mutableFloatStateOf(prefs.getFloat("size", 24f))
    var overlay by mutableStateOf(prefs.getBoolean("overlay", true))
    var scanSeconds by mutableFloatStateOf(prefs.getFloat("scan", 0.7f))
    var themeMode by mutableIntStateOf(prefs.getInt("theme", 0)) // 0 sistem, 1 koyu, 2 açık
    var keepAwake by mutableStateOf(prefs.getBoolean("awake", true))
    var keepHistory by mutableStateOf(prefs.getBoolean("keephist", true))
    var tipSeen by mutableStateOf(prefs.getBoolean("tip", false))

    // ---------- Ses motoru (0 telefon sesi, 1 Gemini, 2 ücretsiz doğal ses) ----------
    var voiceEngine by mutableIntStateOf(prefs.getInt("veng", 3))
    var azureKey by mutableStateOf(prefs.getString("akey", "") ?: "")
    var azureRegion by mutableStateOf(
        prefs.getString("aregion", AzureVoice.DEFAULT_REGION) ?: AzureVoice.DEFAULT_REGION
    )
    var azureVoiceName by mutableStateOf(
        prefs.getString("avoice", AzureVoice.DEFAULT_VOICE) ?: AzureVoice.DEFAULT_VOICE
    )
    var azureError by mutableStateOf<String?>(null)
    private val azure = AzureVoice()
    var edgeVoiceName by mutableStateOf(
        prefs.getString("evoice", EdgeVoice.DEFAULT_VOICE) ?: EdgeVoice.DEFAULT_VOICE
    )
    var edgeError by mutableStateOf<String?>(null)
    private val edge = EdgeVoice()
    var geminiKey by mutableStateOf(prefs.getString("gkey", "") ?: "")
    var geminiVoice by mutableStateOf(prefs.getString("gvoice", "Kore") ?: "Kore")
    var geminiModel by mutableStateOf(
        prefs.getString("gmodel", GeminiVoice.DEFAULT_MODEL) ?: GeminiVoice.DEFAULT_MODEL
    )
    var geminiError by mutableStateOf<String?>(null)
    private val gemini = GeminiVoice()

    // ---------- Çeviri motoru: Gemini (doğal, internet) ya da ML Kit (cihaz içi) ----------
    // 0 anahtarsız çevrimiçi (varsayılan), 1 Gemini (anahtar gerekir), 2 yalnızca ML Kit (cihaz içi)
    var translateMode by mutableIntStateOf(prefs.getInt("trmode", 0))
    var translateError by mutableStateOf<String?>(null)
    private val geminiTr = GeminiTranslate()
    private val freeTr = FreeTranslate()

    // Aynı satır tekrar gelirse yeniden çevirme (en son 300 satır hatırlanır)
    private val trCache: MutableMap<String, String> = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, String>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
                size > 300
        }
    )

    // ---------- Gecikme ölçümü (ekranda küçük yazıyla görünür) ----------
    var timing by mutableStateOf("")
    private var cycleStart = 0L
    private var timingBase = ""
    var lang by mutableStateOf(
        prefs.getString("lang", TranslateLanguage.ENGLISH) ?: TranslateLanguage.ENGLISH
    )

    // ---------- Altyazı kutusu (0..1, kamera görüntüsüne göre) ----------
    var boxLeft by mutableFloatStateOf(prefs.getFloat("bl2", DEF_LEFT))
    var boxTop by mutableFloatStateOf(prefs.getFloat("bt2", DEF_TOP))
    var boxWidth by mutableFloatStateOf(prefs.getFloat("bw2", DEF_WIDTH))
    var boxHeight by mutableFloatStateOf(prefs.getFloat("bh2", DEF_HEIGHT))

    // ---------- Çalışma durumu ----------
    var running by mutableStateOf(false)
    var original by mutableStateOf("")
    var translated by mutableStateOf("")
    var modelReady by mutableStateOf(false)
    var modelError by mutableStateOf(false)
    var ttsMissing by mutableStateOf(false)
    private var status by mutableStateOf("Yeşil kutuyu altyazının üstüne getir")

    val history = mutableStateListOf<HistoryItem>()

    val statusText: String
        get() = when {
            modelError -> "Çeviri modeli indirilemedi, internet bağlantını kontrol et"
            !modelReady -> "Çeviri modeli indiriliyor…"
            !running -> "Duraklatıldı"
            else -> status
        }

    val languageName: String
        get() = LANGUAGES.firstOrNull { it.first == lang }?.second ?: lang

    private val recognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private fun buildTranslator(code: String) = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(code)
            .setTargetLanguage(TranslateLanguage.TURKISH)
            .build()
    )

    private var translator = buildTranslator(lang)
    private var tts: TextToSpeech? = null

    @Volatile private var busy = false
    private var lastRun = 0L
    private var lastWords: Set<String> = emptySet()

    init {
        tts = TextToSpeech(appContext) { code ->
            if (code == TextToSpeech.SUCCESS) {
                val r = tts?.setLanguage(Locale("tr", "TR"))
                ttsMissing = r == TextToSpeech.LANG_MISSING_DATA ||
                    r == TextToSpeech.LANG_NOT_SUPPORTED
                // Telefonda kurulu en kaliteli Türkçe sesi seç
                try {
                    tts?.voices
                        ?.filter { it.locale.language == "tr" && !it.features.contains("notInstalled") }
                        ?.maxByOrNull { it.quality }
                        ?.let { tts?.voice = it }
                } catch (e: Exception) {
                    // ses seçilemezse varsayılan kalır
                }
            }
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = markVoiceStart()
            override fun onDone(utteranceId: String?) {}
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {}
        })
        azure.onStart = { markVoiceStart() }
        edge.onStart = { markVoiceStart() }
        gemini.onStart = { markVoiceStart() }
        loadHistory()
        prepareModel()
    }

    private fun markVoiceStart() {
        if (cycleStart == 0L) return
        val total = SystemClock.elapsedRealtime() - cycleStart
        timing = "$timingBase · ses başladı: toplam $total ms"
        cycleStart = 0L
    }

    // ---------- Model ----------
    private fun prepareModel() {
        modelReady = false
        modelError = false
        val t = translator
        t.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener { if (t === translator) modelReady = true }
            .addOnFailureListener { if (t === translator) modelError = true }
    }

    fun retryModel() = prepareModel()

    fun changeLanguage(code: String) {
        if (code == lang) return
        translator.close()
        lang = code
        lastWords = emptySet()
        original = ""
        translated = ""
        translator = buildTranslator(code)
        prefs.edit().putString("lang", code).apply()
        prepareModel()
    }

    // ---------- Ayarları kaydet ----------
    fun persist() {
        prefs.edit()
            .putBoolean("speak", speak)
            .putFloat("rate", speechRate)
            .putFloat("pitch", pitch)
            .putBoolean("flush", flushSpeech)
            .putFloat("size", textSize)
            .putBoolean("overlay", overlay)
            .putFloat("scan", scanSeconds)
            .putInt("theme", themeMode)
            .putBoolean("awake", keepAwake)
            .putBoolean("keephist", keepHistory)
            .putBoolean("tip", tipSeen)
            .putInt("trmode", translateMode)
            .putInt("veng", voiceEngine)
            .putString("gkey", geminiKey)
            .putString("gvoice", geminiVoice)
            .putString("gmodel", geminiModel)
            .putString("akey", azureKey)
            .putString("aregion", azureRegion)
            .putString("avoice", azureVoiceName)
            .putString("evoice", edgeVoiceName)
            .putFloat("bl2", boxLeft)
            .putFloat("bt2", boxTop)
            .putFloat("bw2", boxWidth)
            .putFloat("bh2", boxHeight)
            .apply()
        if (!speak) {
            tts?.stop()
            gemini.stop()
            azure.stop()
            edge.stop()
        }
    }

    // ---------- Kutu ----------
    fun moveBox(dx: Float, dy: Float) {
        boxLeft = (boxLeft + dx).coerceIn(0f, 1f - boxWidth)
        boxTop = (boxTop + dy).coerceIn(0f, 1f - boxHeight)
    }

    fun resizeBox(dx: Float, dy: Float) {
        boxWidth = (boxWidth + dx).coerceIn(0.1f, 1f - boxLeft)
        boxHeight = (boxHeight + dy).coerceIn(0.05f, 1f - boxTop)
    }

    fun resetBox() {
        boxLeft = DEF_LEFT
        boxTop = DEF_TOP
        boxWidth = DEF_WIDTH
        boxHeight = DEF_HEIGHT
        persist()
    }

    // ---------- Geçmiş ----------
    private fun loadHistory() {
        try {
            val arr = JSONArray(prefs.getString("history", "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                history.add(HistoryItem(o.getLong("t"), o.getString("o"), o.getString("r")))
            }
        } catch (e: Exception) {
            // bozuk kayıt varsa yok say
        }
    }

    private fun saveHistory() {
        val arr = JSONArray()
        history.forEach {
            arr.put(JSONObject().put("t", it.time).put("o", it.original).put("r", it.translated))
        }
        prefs.edit().putString("history", arr.toString()).apply()
    }

    private fun addHistory(o: String, r: String) {
        history.add(0, HistoryItem(System.currentTimeMillis(), o, r))
        while (history.size > 200) history.removeAt(history.size - 1)
        saveHistory()
    }

    fun clearHistory() {
        history.clear()
        saveHistory()
    }

    // ---------- Seslendirme ----------
    fun speakText(raw: String) {
        // Heceleme ve gürültü sese gitmesin: ünlemleri kısalt, anlamsız parçaları at
        val text = TextCleaner.forSpeech(raw)
        if (text.isEmpty()) return
        when (voiceEngine) {
            1 -> {
                if (geminiKey.isBlank()) {
                    geminiError = "API anahtarı girilmedi, Azure sesi veya telefon sesi kullanılıyor."
                    speakAzure(text)
                    return
                }
                geminiError = null
                gemini.speak(
                    geminiKey.trim(),
                    geminiModel.trim().ifEmpty { GeminiVoice.DEFAULT_MODEL },
                    geminiVoice,
                    text,
                    speechRate,
                    flushSpeech
                ) { msg ->
                    // Gemini çalışmazsa (ör. kota doldu) önce ücretsiz doğal sese, o da olmazsa telefon sesine geç
                    geminiError = "Gemini sesi çalışmadı: $msg. Azure sesi veya telefon sesi kullanıldı."
                    speakAzure(text)
                }
            }
            2 -> speakAzure(text)
            3 -> speakEdge(text)
            else -> speakLocal(text)
        }
    }

    private fun speakEdge(text: String) {
        edge.speak(edgeVoiceName, text, speechRate, flushSpeech) { msg ->
            edgeError = "Edge sesi çalışmadı: $msg. Telefon sesi kullanıldı."
            speakLocal(text)
        }
        edgeError = null
    }

    private fun speakAzure(text: String) {
        if (azureKey.isBlank()) {
            azureError = "Azure anahtarı girilmedi, telefon sesi kullanılıyor."
            speakLocal(text)
            return
        }
        azureError = null
        azure.speak(
            azureKey.trim(),
            azureRegion,
            azureVoiceName,
            text,
            speechRate,
            flushSpeech
        ) { msg ->
            azureError = "Azure sesi çalışmadı: $msg. Telefon sesi kullanıldı."
            speakLocal(text)
        }
    }

    private fun speakLocal(text: String) {
        tts?.setSpeechRate(speechRate)
        tts?.setPitch(pitch)
        tts?.speak(
            text,
            if (flushSpeech) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
            null,
            "caption"
        )
    }

    // ---------- Kare işleme ----------
    /** CameraX her kare için bunu çağırır. */
    fun analyze(proxy: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        if (!running || !modelReady || busy || now - lastRun < (scanSeconds * 1000).toLong()) {
            proxy.close()
            return
        }
        busy = true
        lastRun = now

        val crop: Bitmap? = try {
            val src = proxy.toBitmap()
            val m = Matrix().apply { postRotate(proxy.imageInfo.rotationDegrees.toFloat()) }
            val full = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
            val x = (boxLeft.coerceIn(0f, 0.95f) * full.width).toInt().coerceAtMost(full.width - 1)
            val y = (boxTop.coerceIn(0f, 0.95f) * full.height).toInt().coerceAtMost(full.height - 1)
            val w = (boxWidth * full.width).toInt().coerceIn(1, full.width - x)
            val h = (boxHeight * full.height).toInt().coerceIn(1, full.height - y)
            Bitmap.createBitmap(full, x, y, w, h)
        } catch (e: Exception) {
            null
        } finally {
            proxy.close()
        }

        if (crop == null) {
            busy = false
            return
        }

        val t0 = SystemClock.elapsedRealtime()
        recognizer.process(InputImage.fromBitmap(enhance(crop), 0))
            .addOnSuccessListener { res ->
                // Düşük güvenli satırları ele: yanlış okunan satır hiç çeviriye girmesin
                val text = res.textBlocks
                    .flatMap { it.lines }
                    .filter { it.confidence >= MIN_CONFIDENCE }
                    .joinToString(" ") { it.text }
                handle(text, t0)
            }
            .addOnFailureListener { busy = false }
    }

    /** Gri tonlama + kontrast artırma: açık renkli altyazıyı arka plandan ayırır, OCR hatasını azaltır. */
    private fun enhance(src: Bitmap): Bitmap {
        return try {
            val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
            val gray = ColorMatrix().apply { setSaturation(0f) }
            val c = CONTRAST
            val t = (-0.5f * c + 0.5f) * 255f
            val contrast = ColorMatrix(
                floatArrayOf(
                    c, 0f, 0f, 0f, t,
                    0f, c, 0f, 0f, t,
                    0f, 0f, c, 0f, t,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            gray.postConcat(contrast)
            val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(gray) }
            Canvas(out).drawBitmap(src, 0f, 0f, paint)
            out
        } catch (e: Exception) {
            src
        }
    }

    private fun handle(raw: String, t0: Long) {
        // OCR gürültüsünü ele (tek harfli parçalar, simge dolu metin, çok kısa metin)
        val clean = TextCleaner.cleanOcr(raw)
        if (clean == null) {
            status = "Kutuda yazı bulunamadı"
            busy = false
            return
        }
        val words = clean.lowercase()
            .split(Regex("[^\\p{L}0-9]+"))
            .filter { it.isNotEmpty() }
            .toSet()

        // Bir önceki satırla neredeyse aynıysa atla
        if (similar(words, lastWords)) {
            busy = false
            return
        }
        lastWords = words
        original = clean
        status = "Çevriliyor…"

        val tOcr = SystemClock.elapsedRealtime() - t0
        val prev = translated
        val cacheKey = "$lang|${clean.lowercase()}"

        fun finish(tr: String, by: String) {
            val out = tr.trim()
            val trMs = SystemClock.elapsedRealtime() - t0 - tOcr
            if (out.isEmpty()) {
                // Çevrilecek anlamlı bir şey yok: ekranda eski çeviriyi bırak, konuşma
                status = "Çalışıyor"
                busy = false
                return
            }
            // Zayıf yedek (ML Kit) çevirisi ve önbellekten gelenler tekrar kaydedilmez
            if (by != "ML Kit" && by != "önbellek") trCache[cacheKey] = out
            translated = out
            status = "Çalışıyor"
            timingBase = "OCR $tOcr ms · çeviri $trMs ms ($by)"
            timing = timingBase
            cycleStart = t0
            if (keepHistory) addHistory(clean, out)
            if (speak) speakText(out)
            busy = false
        }

        fun withMlKit() {
            translator.translate(clean)
                .addOnSuccessListener { finish(it, "ML Kit") }
                .addOnFailureListener { busy = false }
        }

        fun withFree() {
            freeTr.translate(lang, clean) { out, err ->
                if (out != null) {
                    translateError = null
                    finish(out, "Çevrimiçi")
                } else {
                    translateError = "Çevrimiçi çeviri çalışmadı ($err), ML Kit kullanıldı."
                    withMlKit()
                }
            }
        }

        val cached = trCache[cacheKey]
        if (cached != null) {
            finish(cached, "önbellek")
            return
        }

        val key = geminiKey.trim()
        when {
            translateMode == 1 && key.isNotEmpty() ->
                geminiTr.translate(key, GeminiTranslate.DEFAULT_MODEL, languageName, clean, prev) { out, err ->
                    if (out != null) {
                        translateError = null
                        finish(out, "Gemini")
                    } else {
                        translateError = "Gemini çalışmadı ($err), anahtarsız çeviri kullanıldı."
                        withFree()
                    }
                }
            translateMode == 2 -> withMlKit()
            else -> withFree()
        }
    }

    private fun similar(a: Set<String>, b: Set<String>): Boolean {
        if (b.isEmpty()) return false
        val inter = a.intersect(b).size.toFloat()
        return inter / a.union(b).size >= 0.7f
    }

    fun close() {
        recognizer.close()
        translator.close()
        tts?.stop()
        tts?.shutdown()
        gemini.release()
        azure.release()
        edge.release()
        geminiTr.release()
        freeTr.release()
    }
}
