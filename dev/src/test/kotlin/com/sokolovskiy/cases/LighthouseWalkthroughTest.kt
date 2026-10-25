package com.sokolovskiy.cases

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** «Проблиск» played through the engine, keywords only (no model). */
class LighthouseWalkthroughTest {
    private val path = "cases/lighthouse-ledger/case.json"
    private fun engine() = CaseEngine(JSONObject(File(path).readText()))
    private fun CaseEngine.req(t: String, target: String, details: String = "") = request("тест", t, target, details)
    private fun CaseEngine.ans(q: String, vararg a: Pair<String, String>) = answer("тест", q, mapOf(*a))

    @Test fun everyDocumentHasAFile() {
        val docs = JSONObject(File(path).readText()).getJSONArray("documents")
        for (i in 0 until docs.length()) {
            val id = docs.getJSONObject(i).getString("id")
            assertTrue("no documents/$id.html", File("cases/lighthouse-ledger/documents/$id.html").exists())
        }
    }

    @Test fun everyUnlockExists() {
        val case = JSONObject(File(path).readText())
        val ids = case.getJSONArray("documents").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("id") } }.toSet()
        val rules = case.getJSONArray("rules")
        for (i in 0 until rules.length()) {
            val u = rules.getJSONObject(i).optJSONArray("unlock") ?: continue
            for (j in 0 until u.length()) assertTrue(u.getString(j), u.getString(j) in ids)
        }
    }

    @Test fun fullWalkthrough() {
        val g = engine()
        // stage 1: the light
        assertFalse(g.req("INT", "Пастух").ok)                         // crew waits
        assertFalse(g.req("FAX", "UMC", "деталізація дзвінків капітана").ok)
        assertEquals(listOf("B01"), g.req("FAX", "диспетчер порту Очаків", "журнал радіообміну УКХ").new)
        assertEquals(listOf("B02", "B03"), g.req("FAX", "Очаківський район гідрографії", "Дубовик, вахтовий журнал").new)
        assertEquals(listOf("B04"), g.req("INT", "Мартинюк, начальник маяка").new)
        assertFalse(g.req("INT", "Кравець", "ніч").ok)                // radar first
        assertEquals(listOf("B05"), g.req("FAX", "ПТС Вовча коса", "журнал радара").new)
        assertEquals(listOf("B06"), g.req("INT", "Кравець", "були вночі в морі, бачили маяк?").new)
        assertEquals(listOf("B07"), g.req("FAX", "Очаківський РВ, слідчий Дьомін", "тіло Савчука").new)
        assertEquals(listOf("B08"), g.req("OGL", "аркуші з речей Савчука").new)
        assertEquals(listOf("B10"), g.req("FAX", "метеостанція", "погода і видимість").new)
        assertEquals(listOf("B11"), g.req("OGL", "маяк", "механізм і башта").new)
        assertEquals(listOf("B09"), g.req("ARCH", "газета Чорноморський вісник").new)

        g.ans("Q1", "from" to "01:10", "to" to "01:33", "what" to "постійний тьмяний вогонь").let { assertFalse(it.text, it.ok) }
        g.ans("Q1", "from" to "01:10", "to" to "01:52", "what" to "постійний тьмяний вогонь").let { assertFalse(it.text, it.ok) }
        g.ans("Q1", "from" to "01:10", "to" to "01:31", "what" to "маяк не світив зовсім").let { assertFalse(it.text, it.ok) }
        g.ans("Q1", "from" to "близько першої", "to" to "пів на другу", "what" to "стояв тьмяним постійним, бо проспали завод механізму")
            .let { assertTrue(it.text, it.ok) }
        assertEquals(2, g.state.getInt("stage"))

        // stage 2: the course
        assertFalse(g.ans("Q2", "who" to "Гаврилюк", "why" to "бо так").ok)
        assertEquals(listOf("C01"), g.req("INT", "рульовий Пастух").new)
        assertEquals(listOf("C02"), g.req("INT", "Оліфіренко стармех").new)
        assertEquals(listOf("C03", "C04"), g.req("FAX", "інспекція портового нагляду", "судновий журнал").new)
        assertEquals(listOf("C05"), g.req("TRANS", "судновий журнал", "англійська").new)
        assertEquals(listOf("C06"), g.req("PLOT", "журнал ПТС", "позиції цілі").new)
        assertEquals(listOf("C07"), g.req("ARCH", "реєстр суден і ЄДРПОУ", "Акватрейд").new)
        assertEquals(listOf("C08"), g.req("FAX", "Чорноморська гарантія", "поліс").new)
        assertEquals(listOf("C09"), g.req("INT", "Пасічник").new)
        assertEquals(listOf("C10"), g.req("ARCH", "Гордієнко і Гаврилюк", "однокурсники, ціни на судна").new)
        assertEquals(listOf("C11"), g.req("INT", "Гордієнко").new)
        assertEquals(listOf("C12"), g.req("INT", "кухарка Руденко").new)
        assertEquals(listOf("C13"), g.req("INT", "старпом Бойко").new)
        assertEquals(listOf("C14"), g.req("OGL", "судно Ліман Стар", "місток і GPS").new)
        assertEquals(listOf("C15"), g.req("FAX", "UMC", "повна деталізація мобільного капітана").new)
        assertFalse(g.req("INT", "Ткач").ok)                           // stage 3

        g.ans("Q2", "who" to "Оліфіренко", "why" to "поставив на банку, страховка 900 тисяч").let { assertFalse(it.text, it.ok) }
        g.ans("Q2", "who" to "Гаврилюк і Бойко", "why" to "повернув на 105 заради страховки 900 тисяч").let { assertFalse(it.text, it.ok) }
        g.ans("Q2", "who" to "капітан Гаврилюк", "why" to "Маяк уже давно блимав, а він повернув на 105 і посадив судно заради страховки власника Гордієнка")
            .let { assertTrue(it.text, it.ok); assertEquals(listOf("D01", "D02", "D03"), it.new) }
        assertEquals(3, g.state.getInt("stage"))

        // stage 3: the cassette
        assertTrue(g.req("FAX", "Дьомін", "касета і дзвінок вдови").new.isEmpty())
        assertEquals(listOf("D04"), g.req("FAX", "Укртелеком", "розшифровка міжміських розмов абонента #217").new)
        assertEquals(listOf("D05"), g.req("FAX", "слідчий Дьомін", "висновок судмедексперта").new)
        assertEquals(listOf("D06"), g.req("OGL", "шлюпка на березі").new)
        assertEquals(listOf("D07"), g.req("INT", "Бойко").new)
        assertEquals(listOf("D08"), g.req("INT", "капітан Гаврилюк").new)
        assertEquals(listOf("D09"), g.req("INT", "Оксана Бондар").new)
        assertEquals(listOf("D10"), g.req("INT", "боцман Ткач").new)
        assertFalse(g.req("INT", "Мартинюк", "другий раз").ok)         // not before stage 4

        g.ans("Q3", "when" to "03:05", "where" to "у прибої", "how" to "втопився").let { assertFalse(it.text, it.ok) }
        g.ans("Q3", "when" to "03:51", "where" to "на маяку, дзвонив із башти", "how" to "вдарили по голові й кинули в море живим")
            .let { assertTrue(it.text, it.ok) }
        assertEquals(4, g.state.getInt("stage"))

        // stage 4: the night watch
        assertEquals(listOf("E01"), g.req("OGL", "дизельна на маяку", "жилети").new)
        assertEquals(listOf("E02"), g.req("OGL", "будинок доглядачів", "кухня і холодильник").new)
        assertEquals(listOf("E03"), g.req("FAX", "слідчий Дьомін", "експертиза рукоятки заводу").new)
        assertEquals(listOf("E04"), g.req("INT", "Кравець", "що бачили о четвертій, вогник").new)
        assertEquals(listOf("E05"), g.req("INT", "Оксана", "дзвінок і трубка").new)
        assertEquals(listOf("E06"), g.req("INT", "Мартинюк").new)
        assertEquals(listOf("E07"), g.req("FAX", "Дубовик", "особова справа, догана").new)

        g.ans("Q4", "who" to "Гаврилюк", "proof" to "рукоятка і жилет").let { assertFalse(it.text, it.ok) }
        g.ans("Q4", "who" to "Мартинюк і Оксана", "proof" to "рукоятка і жилет").let { assertFalse(it.text, it.ok) }
        g.ans("Q4", "who" to "Мартинюк", "proof" to "Савчук дзвонив з башти, де був він; кров на рукоятці; жилет у дизельній")
            .let { assertTrue(it.text, it.ok); assertEquals(listOf("F01", "F02", "F03"), it.new) }
        println("score=${g.score()} rank=${g.rank()}")
        assertTrue(g.rank() != null)
    }

    @Test fun nightTimes() {
        assertEquals(listOf(90), AnswerJudge.parseTimes("о пів на другу", 0))
        assertEquals(listOf(60), AnswerJudge.parseTimes("близько першої", 0))
        assertEquals(listOf(3 * 60), AnswerJudge.parseTimes("3 години ночі", 0).ifEmpty { listOf(3 * 60) })
        assertEquals(listOf(71), AnswerJudge.parseTimes("01:11", 0))
    }

    @Test fun wrongPathsAndPhrasing() {
        val g = engine()
        // guessing before evidence
        assertFalse(g.ans("Q1", "from" to "01:10", "to" to "01:31", "what" to "постійний тьмяний вогонь").ok)
        assertEquals(1, g.state.getInt("stage"))
        // typos, cases, latin look-alikes
        assertEquals(listOf("B04"), g.req("INT", "начальника маяка Мартинюка").new)
        assertEquals(listOf("B05"), g.req("FAX", "прикордонники, ПТС", "радар").new)
        assertTrue(g.req("INT", "Мартинюк").new.isEmpty())             // repeats do not open anything
        assertTrue(g.req("FAX", "Дьомін", "розтин").new.let { it == listOf("B07") })   // B07 first, the autopsy waits
        assertTrue(g.req("FAX", "Дьомін", "розтин").new.isEmpty())
        assertTrue(g.req("INT", "Савчук").text.isNotEmpty())
        assertFalse(g.req("INT", "Невідомий Іван").ok)
    }
}
