package com.sokolovskiy.cases

import com.sokolovskiy.cases.Matcher.Text
import org.json.JSONArray
import org.json.JSONObject

/**
 * Judge of the free-form answers to a case's control questions (case.json → questions[].fields[]):
 *  - `person`: the answer must name exactly that person and no other suspect;
 *  - `time`: every mentioned moment lies in `window` (and at least one is mentioned);
 *  - `text`: the answer is made of arguments (`parts`); a part counts by keywords (fuzzy) or semantically
 *    (`examples` against `wrong`).
 * Ported 1:1 from the Python prototype (`app/judge.py`).
 */
class AnswerJudge(
    private val persons: List<JSONObject>,
    private val suspects: Set<String>,
    private val semantic: () -> SemanticJudge?,
) {
    class Verdict(val good: Boolean, val points: Double)

    private fun namedPersons(text: Text): Set<String> =
        persons.filter { p -> p.strings("aliases").any { Matcher.keywordHit(it, text) } }.map { it.getString("id") }.toSet()

    private fun partHit(part: JSONObject, answer: String, text: Text, wrong: List<String>): Boolean {
        if (part.strings("kw").any { Matcher.keywordHit(it, text) }) return true
        val examples = part.strings("examples")
        if (examples.isNotEmpty()) return semantic()?.decide(answer, examples, wrong + GENERIC_WRONG) == true
        return false
    }

    fun field(f: JSONObject, rawAnswer: String): Verdict {
        val answer = rawAnswer.trim()
        if (answer.isEmpty()) return Verdict(false, 0.0)
        val text = Text(answer)
        val ok: Boolean
        when (f.getString("kind")) {
            "person" -> {
                val named = namedPersons(text)
                val who = f.getString("person")
                ok = who in named && (named intersect (suspects - who)).isEmpty()
            }
            "time" -> {
                val times = parseTimes(answer)
                val w = f.getJSONArray("window")
                val lo = minutes(w.getString(0))
                val hi = minutes(w.getString(1))
                ok = times.isNotEmpty() && times.all { it in lo..hi }
            }
            else -> {
                // "Соломія's car and Maksym's flat" — the places are named, but not of that person
                f.optString("only_person").takeIf { it.isNotEmpty() }?.let { only ->
                    if ((namedPersons(text) intersect (suspects - only)).isNotEmpty()) return Verdict(false, 0.0)
                }
                val wrong = f.strings("wrong")
                val parts = f.getJSONArray("parts")
                val hits = (0 until parts.length()).count { partHit(parts.getJSONObject(it), answer, text, wrong) }
                ok = hits >= f.optInt("min_parts", 1)
                if (ok && f.has("points_per_part")) {
                    val pts = minOf(hits * f.getDouble("points_per_part"), f.optDouble("max_points", hits.toDouble()))
                    return Verdict(true, pts)
                }
            }
        }
        val points = f.optDouble("points", if (f.optBoolean("required")) 0.0 else 1.0)
        return Verdict(ok, if (ok) points else 0.0)
    }

    companion object {
        private val GENERIC_WRONG = listOf(
            "не знаю", "важко сказати", "нещасний випадок", "він сам упав зі сходів", "пограбування",
            "сліди боротьби", "без відповіді",
        )

        // Ordinal hour words in spoken form ("о восьмій", "пів на восьму").
        private val HOUR_WORDS: List<Pair<String, Int>> = listOf(
            "першо" to 1, "друго" to 2, "третьо" to 3, "четверто" to 4, "п'ято" to 5, "шосто" to 6, "сьомо" to 7,
            "восьмо" to 8, "дев'ято" to 9, "десято" to 10, "одинадцято" to 11, "дванадцято" to 12,
            "першу" to 1, "другу" to 2, "третю" to 3, "четверту" to 4, "п'яту" to 5, "шосту" to 6, "сьому" to 7,
            "восьму" to 8, "дев'яту" to 9, "десяту" to 10, "одинадцяту" to 11, "дванадцяту" to 12,
            "перш" to 1, "друг" to 2, "трет" to 3, "четверт" to 4, "п'ят" to 5, "шост" to 6, "сьом" to 7, "восьм" to 8,
            "дев'ят" to 9, "десят" to 10, "одинадцят" to 11, "дванадцят" to 12,
        ).sortedByDescending { it.first.length }
        private const val EVENING = 12  // the story is about an evening: "о восьмій" = 20:00

        private fun hourWord(word: String): Int? = HOUR_WORDS.firstOrNull { word.startsWith(it.first) }?.second

        private val DATES = Regex("""(?U)\b\d{1,2}\.\d{1,2}\.\d{2,4}\b|\b1[0-5]\.11\b""")
        private val CLOCK = Regex("""(?U)\b([01]?\d|2[0-3])[:.]([0-5]\d)\b""")
        private val HALF_PAST = Regex("""(?U)пів\s*на\s*([\w']+)""")
        private val NUMBER = Regex("""(?U)\b(\d{1,2})\s*(вечора|веч\b|год|годин)?""")
        private val AT_HOUR = Regex("""(?U)\bо\s+([\w']+)""")

        /**
         * Minutes after 00:00 for every moment mentioned: "19:30", "19.30", "між 19 і 21", "пів на восьму", "о восьмій",
         * "7 вечора". Numbers that are clearly not hours (9 см, 1–2 години after a meal) are ignored.
         */
        fun parseTimes(text: String): List<Int> {
            var s = text.lowercase().replace('’', '\'').replace('ʼ', '\'')
            s = DATES.replace(s, " ")
            val times = mutableListOf<Int>()
            for (m in CLOCK.findAll(s)) times += m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt()
            s = CLOCK.replace(s, " ")
            for (m in HALF_PAST.findAll(s)) {
                hourWord(m.groupValues[1])?.let { h -> times += ((h - 1 + EVENING) % 24) * 60 + 30 }
            }
            s = HALF_PAST.replace(s, " ")
            for (m in NUMBER.findAll(s)) {
                val n = m.groupValues[1].toInt()
                val ev = m.groupValues[2]
                if (n in 17..23) times += n * 60
                else if (ev.startsWith("веч") && n in 1..11) times += (n + 12) * 60
            }
            for (m in AT_HOUR.findAll(s)) {
                hourWord(m.groupValues[1])?.let { h -> times += ((h + EVENING) % 24) * 60 }
            }
            return times
        }

        private fun minutes(hhmm: String): Int {
            val (h, m) = hhmm.split(":")
            return h.toInt() * 60 + m.toInt()
        }
    }
}

/** `["a","b"]` → list; absent → empty. */
internal fun JSONObject.strings(key: String): List<String> {
    val a: JSONArray = optJSONArray(key) ?: return emptyList()
    return List(a.length()) { a.getString(it) }
}
