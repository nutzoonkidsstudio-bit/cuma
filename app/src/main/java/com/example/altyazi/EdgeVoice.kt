package com.example.altyazi

import android.media.AudioAttributes
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.media.PlaybackParams
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Edge "Sesli Oku" nöral Türkçe sesleri. Ücretsiz, anahtar gerekmez, internet gerekir.
 * Resmî bir API değil: Microsoft protokolü değiştirirse çalışmayabilir, o durumda
 * çağıran taraf telefon sesine geçer.
 */
class EdgeVoice {

    companion object {
        val VOICES = listOf(
            "tr-TR-EmelNeural" to "Emel (kadın)",
            "tr-TR-AhmetNeural" to "Ahmet (erkek)"
        )
        const val DEFAULT_VOICE = "tr-TR-EmelNeural"

        private const val TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
        private const val CHROMIUM_FULL = "143.0.3650.75"
        private const val WSS =
            "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1"
        private const val TIMEOUT_MS = 8000L
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val executor = Executors.newSingleThreadExecutor()
    private val generation = AtomicInteger(0)
    @Volatile private var player: MediaPlayer? = null

    // Aynı cümle tekrar gelirse sesi yeniden üretme (en son 40 cümle hatırlanır)
    private val audioCache: MutableMap<String, ByteArray> = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, ByteArray>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean =
                size > 40
        }
    )

    /** Ses gerçekten çalmaya başlarken çağrılır (gecikme ölçümü için). */
    @Volatile var onStart: (() -> Unit)? = null

    fun speak(
        voice: String,
        text: String,
        rate: Float,
        flush: Boolean,
        onError: (String) -> Unit
    ) {
        if (flush) stop()
        val gen = generation.get()
        executor.execute {
            if (gen != generation.get()) return@execute
            try {
                val ck = "$voice|$text"
                val mp3 = audioCache[ck] ?: fetch(voice, text).also { audioCache[ck] = it }
                if (gen != generation.get()) return@execute
                play(mp3, rate, gen)
            } catch (e: Exception) {
                if (gen == generation.get()) onError(e.message ?: "bilinmeyen hata")
            }
        }
    }

    fun stop() {
        generation.incrementAndGet()
        try {
            player?.stop()
        } catch (e: Exception) {
            // yok say
        }
    }

    fun release() {
        stop()
        executor.shutdownNow()
    }

    private fun secMsGec(): String {
        // Windows FILETIME: 1601'den beri 100 ns, 5 dakikaya yuvarlanmış
        var ticks = System.currentTimeMillis() / 1000.0 + 11644473600.0
        ticks -= ticks % 300.0
        val t100ns = (ticks * 10_000_000.0).toLong()
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$t100ns$TOKEN".toByteArray(Charsets.US_ASCII))
        return digest.joinToString("") { "%02X".format(it) }
    }

    private fun escape(s: String): String =
        s.replace(Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]"), " ")
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

    private fun timestamp(): String {
        val f = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(Date())
    }

    private fun fetch(voice: String, text: String): ByteArray {
        val connId = UUID.randomUUID().toString().replace("-", "")
        val major = CHROMIUM_FULL.substringBefore('.')
        val url = "$WSS?TrustedClientToken=$TOKEN&ConnectionId=$connId" +
            "&Sec-MS-GEC=${secMsGec()}&Sec-MS-GEC-Version=1-$CHROMIUM_FULL"
        val muid = UUID.randomUUID().toString().replace("-", "").uppercase(Locale.US)

        val req = Request.Builder()
            .url(url)
            .header("Pragma", "no-cache")
            .header("Cache-Control", "no-cache")
            .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
            .header(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/$major.0.0.0 Safari/537.36 Edg/$major.0.0.0"
            )
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Cookie", "muid=$muid;")
            .build()

        val audio = ByteArrayOutputStream()
        val done = CountDownLatch(1)
        val failure = java.util.concurrent.atomic.AtomicReference<String?>(null)

        val ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val ts = timestamp()
                webSocket.send(
                    "X-Timestamp:$ts\r\nContent-Type:application/json; charset=utf-8\r\n" +
                        "Path:speech.config\r\n\r\n" +
                        "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":" +
                        "{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"}," +
                        "\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}\r\n"
                )
                val ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' " +
                    "xml:lang='tr-TR'><voice name='$voice'>" +
                    "<prosody pitch='+0Hz' rate='+0%' volume='+0%'>${escape(text)}</prosody>" +
                    "</voice></speak>"
                webSocket.send(
                    "X-RequestId:$connId\r\nContent-Type:application/ssml+xml\r\n" +
                        "X-Timestamp:${ts}Z\r\nPath:ssml\r\n\r\n$ssml"
                )
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.contains("Path:turn.end")) done.countDown()
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val b = bytes.toByteArray()
                if (b.size < 2) return
                val headerLen = ((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)
                val start = 2 + headerLen
                if (start > b.size) return
                val header = String(b, 2, headerLen, Charsets.UTF_8)
                if (header.contains("Path:audio")) audio.write(b, start, b.size - start)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                failure.set(response?.let { "HTTP ${it.code}" } ?: (t.message ?: "bağlantı hatası"))
                done.countDown()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                done.countDown()
            }
        })

        try {
            if (!done.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) throw Exception("zaman aşımı")
        } finally {
            ws.cancel()
        }
        failure.get()?.let { throw Exception(it) }
        val bytes = audio.toByteArray()
        if (bytes.isEmpty()) throw Exception("ses servisi boş yanıt verdi")
        return bytes
    }

    private class BytesSource(private val data: ByteArray) : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= data.size) return -1
            val n = minOf(size, (data.size - position).toInt())
            System.arraycopy(data, position.toInt(), buffer, offset, n)
            return n
        }

        override fun getSize(): Long = data.size.toLong()
        override fun close() {}
    }

    private fun play(mp3: ByteArray, rate: Float, gen: Int) {
        val done = CountDownLatch(1)
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mp.setDataSource(BytesSource(mp3))
            mp.setOnCompletionListener { done.countDown() }
            mp.setOnErrorListener { _, _, _ -> done.countDown(); true }
            mp.prepare()
            player = mp
            onStart?.invoke()
            mp.start()
            try {
                mp.playbackParams = PlaybackParams().setSpeed(rate)
            } catch (e: Exception) {
                // hız ayarlanamazsa normal hızda çal
            }
            while (!done.await(40, TimeUnit.MILLISECONDS)) {
                if (gen != generation.get()) break
            }
        } finally {
            try {
                mp.stop()
            } catch (e: Exception) {
                // yok say
            }
            mp.release()
            player = null
        }
    }
}
