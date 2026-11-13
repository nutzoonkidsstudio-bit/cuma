package com.example.altyazi

/**
 * OCR çıktısını çeviriye girmeden önce, çeviriyi de seslendirmeden önce temizler.
 * Amaç: gürültü ("a a j j y y"), harf harf okunan ünlemler ("ohhh") ve anlamsız parçalar
 * ne çeviriye ne de sese gitsin.
 */
object TextCleaner {

    // Çevrilmeden bırakılacak ünlemler (kökleri). Sese gitmeden önce kısaltılır.
    private val INTERJECTIONS = setOf(
        "oh", "ooh", "ah", "aah", "eh", "hm", "hmm", "mm", "mmm", "uh", "um", "ugh",
        "huh", "ha", "haha", "heh", "hey", "yeah", "wow", "whoa", "oof", "ow", "argh", "aw"
    )

    // Ünlem -> Türkçe karşılık (sesli okunurken doğal duyulsun)
    private val SPOKEN = mapOf(
        "oh" to "oh", "ooh" to "vay", "ah" to "ah", "aah" to "ah", "eh" to "ee",
        "hm" to "hmm", "hmm" to "hmm", "mm" to "hmm", "mmm" to "hmm",
        "uh" to "ıı", "um" to "ıı", "ugh" to "off", "huh" to "ha",
        "ha" to "ha", "haha" to "ha ha", "heh" to "he", "hey" to "hey",
        "yeah" to "evet", "wow" to "vay be", "whoa" to "vay", "oof" to "off",
        "ow" to "ah", "argh" to "ahh", "aw" to "ah"
    )

    private val VOWELS = "aeıioöuüAEIİOÖUÜyY"

    /** "ohhh" -> "oh", "aaaa" -> "a" (3+ aynı harfi 1'e, 2 aynı harfi olduğu gibi bırak) */
    private fun squeeze(word: String): String =
        word.replace(Regex("(\\p{L})\\1{2,}"), "$1")

    /**
     * OCR metnini temizler. Çeviriye gitmeye değmeyecek (gürültü) metinde null döner.
     */
    fun cleanOcr(raw: String): String? {
        var s = raw
            .replace(Regex("[\\r\\n]+"), " ")
            .replace(Regex("[|_~`^*#<>{}\\[\\]\\\\]+"), " ") // OCR'ın uydurduğu simgeler
            .replace(Regex("\\s+"), " ")
            .trim()
        if (s.length < 3) return null

        val tokens = s.split(' ').filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null

        // Tek harfli parçaların oranı yüksekse (a a j j y y) bu bir yazı değil, gürültü
        val single = tokens.count { t -> t.count { it.isLetter() } == 1 && t.length <= 2 }
        val realWords = tokens.count { t ->
            val letters = t.filter { it.isLetter() }
            letters.length >= 3 && letters.any { it in VOWELS }
        }
        if (tokens.size >= 3 && single.toFloat() / tokens.size > 0.5f) return null
        if (realWords == 0) {
            // "Oh", "No", "Go" gibi kısa ama gerçek ifadelere izin ver (tek-iki token, 2+ harf)
            val ok = tokens.size <= 2 && tokens.all { t -> t.count { it.isLetter() } >= 2 }
            if (!ok) return null
        }

        // Harf oranı düşükse (rakam/simge dolu arayüz metni) at
        val letters = s.count { it.isLetter() }
        if (letters.toFloat() / s.length < 0.55f) return null

        // Aynı karakterin çok uzun tekrarını kısalt (OCR "lllllll" üretebilir)
        s = s.replace(Regex("(.)\\1{4,}"), "$1$1$1")
        return s
    }

    /**
     * Seslendirmeden önce metni temizler. Boş dönerse hiç konuşma.
     * - "ohhh" -> "oh" (heceleme olmasın), ünlemler Türkçe okunuşa çevrilir
     * - Sesli harfi olmayan 1-2 harflik parçalar atılır
     * - Çoklu noktalama/tire sadeleştirilir
     */
    fun forSpeech(text: String): String {
        val cleaned = text
            .replace(Regex("[\\r\\n]+"), " ")
            .replace(Regex("[*_#~`<>{}\\[\\]|\\\\]+"), " ")
            .replace(Regex("[-–—]{2,}"), " ")
            .replace(Regex("\\.{4,}"), "...")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (cleaned.isEmpty()) return ""

        val out = ArrayList<String>()
        for (tok in cleaned.split(' ')) {
            val core = tok.trim { !it.isLetterOrDigit() }
            if (core.isEmpty()) {
                out.add(tok)
                continue
            }
            val lead = tok.substring(0, tok.indexOf(core))
            val tail = tok.substring(tok.indexOf(core) + core.length)
            val squeezed = squeeze(core)
            val key = squeezed.lowercase()

            when {
                key in SPOKEN -> out.add(lead + SPOKEN.getValue(key) + tail)
                // Sesli harf içermeyen 2-3 harfli parça: harf harf okunur, at
                // (BÜYÜK HARFLİ kısaltmalar -TV, DNA- doğru okunur, onlara dokunma)
                core.length <= 3 && core.all { it.isLetter() } &&
                    core.any { it.isLowerCase() } &&
                    core.none { it in VOWELS } -> { /* at */ }
                // Tek harfli parça ("a", "j") konuşmayı bozar, at; Türkçedeki "o" kalır
                core.length == 1 && core[0].isLetter() &&
                    core[0].lowercaseChar() != 'o' -> { /* at */ }
                else -> out.add(lead + squeezed + tail)
            }
        }
        val result = out.joinToString(" ").replace(Regex("\\s+"), " ").trim()
        // Geriye hiç harf kalmadıysa konuşma
        return if (result.any { it.isLetter() }) result else ""
    }

    /** Ünlemin tek başına geldiği satır mı ("Ohhh!", "Hmm...")? */
    fun isOnlyInterjection(text: String): Boolean {
        val toks = text.lowercase().split(Regex("[^\\p{L}]+")).filter { it.isNotEmpty() }
        return toks.isNotEmpty() && toks.all { squeeze(it) in INTERJECTIONS }
    }
}
