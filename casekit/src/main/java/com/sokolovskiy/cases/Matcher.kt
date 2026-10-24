package com.sokolovskiy.cases

/**
 * Recognising what a player typed: fuzzy keywords (normalisation, stop words, rough stemming, typos by Levenshtein
 * distance, exact numbers). Always available; an optional [SemanticJudge] adds paraphrases on top.
 * Ported 1:1 from the Python prototype (`app/matcher.py`), so the same `case.json` behaves the same way.
 */
object Matcher {

    private val STOP_WORDS = setOf(
        "в", "у", "на", "з", "із", "зі", "до", "по", "під", "за", "біля", "коло", "від", "для", "про", "через", "при",
        "над", "між", "без", "о", "об", "і", "й", "та", "а", "це", "то", "ну", "що", "як", "чи", "ви", "вас", "вам",
        "ваш", "ваша", "ваше", "ваші", "він", "вона", "вони", "його", "її", "їх", "ми", "нас", "мені", "я", "не",
        "вул", "вулиця", "пан", "пані", "гр", "громадянин", "громадянка",
    )

    /** Longest first. */
    private val ENDINGS = listOf(
        "ами", "ями", "ові", "еві", "єві", "ого", "ому", "ими", "іми", "ях", "ах", "ою", "ею", "єю", "ом", "ем",
        "ам", "ям", "ів", "їв", "ий", "ій", "ія", "ая", "яя", "ое", "ее", "ые",
        "а", "я", "у", "ю", "і", "и", "е", "є", "о", "ь", "ї", "й", "ы",
    )

    /** Latin letters that look like Cyrillic ones (licence plates get typed any way). */
    private val LOOKALIKE = mapOf(
        'a' to 'а', 'b' to 'в', 'c' to 'с', 'e' to 'е', 'h' to 'н', 'i' to 'і', 'k' to 'к', 'm' to 'м',
        'o' to 'о', 'p' to 'р', 't' to 'т', 'x' to 'х', 'y' to 'у',
    )

    private val NOT_COMPACT = Regex("""[^\p{L}\p{M}\p{N}_':№-]""")
    private val NOT_WORD = Regex("""[^\p{L}\p{M}\p{N}_']""")

    private fun base(text: String) = text.lowercase()
        .replace('’', '\'').replace('ʼ', '\'').replace('`', '\'').replace('ё', 'е').replace('ы', 'и')

    /** Without spaces and punctuation (except ' : - №) — for multi-word keys and numbers. */
    fun compact(text: String) = NOT_COMPACT.replace(base(text), "")

    fun digits(text: String) = text.filter { it.isDigit() }

    fun tokens(text: String): List<String> {
        val words = NOT_WORD.replace(base(text), " ").split(' ').filter { it.isNotEmpty() }
        return words.filter { it !in STOP_WORDS }.ifEmpty { words }
    }

    fun stem(token: String): String {
        if (token.isNotEmpty() && token.all { it.isDigit() }) return token
        for (e in ENDINGS) if (token.endsWith(e) && token.length - e.length >= 3) return token.dropLast(e.length)
        return token
    }

    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] != b[j - 1]) 1 else 0)
            }
            prev = cur
        }
        return prev[b.length]
    }

    private fun allowedTypos(n: Int) = if (n < 5) 0 else if (n < 9) 1 else 2

    /** A player's query, processed once. */
    class Text(val raw: String) {
        val compact = compact(raw)
        val translit = compact(raw.lowercase().map { LOOKALIKE[it] ?: it }.joinToString(""))
        val digits = digits(raw)
        val tokens = tokens(raw)
        val stems = tokens.map(::stem)
    }

    /** A key is a word stem ("кульчицьк"), a phrase ("номер авто"), a number ("#672140851") or an id ("112487"). */
    fun keywordHit(keyword: String, text: Text): Boolean {
        if (keyword.startsWith("#")) return keyword.substring(1) in text.digits
        val kc = compact(keyword)
        // Inside the text only for long or compound keys: a short one hides in other words ("віка" in "чоловіка")
        val inside = kc.length >= 5 || kc.any { !it.isLetter() }
        if (inside && (kc in text.compact || kc in text.translit)) return true
        if (kc.any { it.isDigit() } || ' ' in keyword.trim()) return false  // numbers and phrases: exact only
        val allowed = allowedTypos(kc.length)
        // The key's stem counts only while it stays long: "кравець"→"кравец" ≈ "кравц(я)", but "гнатів"→"гнат" would
        // already match "Гнатюк".
        val kstem = stem(kc).takeIf { it.length >= 5 }
        for (i in text.tokens.indices) {
            val tok = text.tokens[i]
            val st = text.stems[i]
            if (st.startsWith(kc) || tok.startsWith(kc)) return true
            if (allowed > 0 && (levenshtein(tok.take(kc.length), kc) <= allowed || levenshtein(st, kc) <= allowed)) return true
            if (kstem != null && levenshtein(st, kstem) <= allowedTypos(kstem.length)) return true
        }
        return false
    }

    fun groupHit(group: List<String>, text: Text) = group.any { keywordHit(it, text) }
}

/**
 * Optional second layer: does the phrase mean what the rule's examples say, rather than what the counter-examples say?
 * Returns null when no model is available (then only keywords decide).
 */
fun interface SemanticJudge {
    fun decide(text: String, examples: List<String>, counters: List<String>): Boolean?
}
