package de.dualsub.overlay

import android.content.Context

/**
 * Which way to translate: German subtitles get English, English ones German.
 *
 * In Auto the language is told from the line itself. Subtitles are short, but
 * German ones are thick with der/die/und/ist/nicht/ich and umlauts, English
 * ones with the/and/you/is/it -- counting those is free next to the OCR. A
 * line with neither ("Okay.", "Hm?") keeps the previous line's language,
 * since a programme rarely switches mid-scene.
 */
object Direction {

    const val AUTO = 0
    const val DE_TO_EN = 1
    const val EN_TO_DE = 2

    private const val PREFS = "settings"
    private const val KEY = "direction"

    @Volatile var mode = AUTO
        private set

    fun load(context: Context) {
        mode = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY, AUTO)
    }

    /** Auto -> German to English -> English to German -> Auto. */
    fun next(context: Context): Int {
        mode = (mode + 1) % 3
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY, mode).apply()
        return mode
    }

    fun label(m: Int = mode) = when (m) {
        DE_TO_EN -> "German → English"
        EN_TO_DE -> "English → German"
        else -> "Auto-detect"
    }

    /** "DE" or "EN": the language of [text] under the current mode. */
    fun sourceOf(text: String, previous: String): String = when (mode) {
        DE_TO_EN -> "DE"
        EN_TO_DE -> "EN"
        else -> detect(text) ?: previous
    }

    /** "DE", "EN", or null when the line gives no clue. */
    fun detect(text: String): String? {
        var de = 0
        var en = 0
        if (text.any { it in "äöüßÄÖÜ" }) de += 2
        for (w in text.lowercase().split(NON_LETTERS)) {
            if (w in GERMAN) de++
            if (w in ENGLISH) en++
        }
        return when {
            de > en -> "DE"
            en > de -> "EN"
            else -> null
        }
    }

    /** DeepL target for a line in [source]. */
    fun targetFor(source: String) = if (source == "EN") "DE" else "EN-GB"

    private val NON_LETTERS = Regex("[^\\p{L}']+")

    // Short, frequent and not shared between the two languages ("in", "so",
    // "was", "will", "man", "her", "okay" are left out: both use them).
    private val GERMAN = setOf(
        "der", "die", "das", "und", "ist", "nicht", "ich", "du", "sie", "wir", "ihr",
        "ein", "eine", "einen", "zu", "mit", "auf", "wie", "ja", "nein", "auch", "noch",
        "mal", "hier", "dann", "schon", "den", "dem", "des", "sich", "es", "bin", "bist",
        "sind", "habe", "hast", "hat", "haben", "kann", "mich", "dich", "mir", "dir",
        "aber", "oder", "wenn", "doch", "jetzt", "nur", "gut", "kein", "keine", "mein",
        "dein", "warum", "wer", "wo", "nichts", "alles", "sehr", "danke", "bitte", "komm",
        "kommt", "wieso", "gleich", "weiß", "los", "eben", "genau", "immer"
    )
    private val ENGLISH = setOf(
        "the", "and", "is", "are", "you", "i", "it", "that", "to", "of", "what", "this",
        "not", "no", "yes", "we", "they", "he", "she", "my", "your", "have", "be", "do",
        "don't", "it's", "i'm", "you're", "just", "with", "for", "on", "at", "can", "know",
        "there", "here", "about", "get", "got", "would", "could", "should", "been", "were",
        "if", "but", "or", "me", "him", "them", "our", "how", "why", "who", "where",
        "when", "all", "one", "did", "does", "going", "want", "right", "thank"
    )
}
