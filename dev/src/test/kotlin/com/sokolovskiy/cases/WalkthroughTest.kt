package com.sokolovskiy.cases

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The whole case from the authoring project played through the Kotlin engine, keywords only (no model). */
class WalkthroughTest {
    private fun engine() = CaseEngine(JSONObject(File("cases/stairs/case.json").readText()))
    private fun CaseEngine.req(t: String, target: String, details: String = "") = request("тест", t, target, details)

    @Test fun fullWalkthrough() {
        val g = engine()
        assertEquals(listOf("B02"), g.req("ORG", "Смакуй").new)
        assertEquals(listOf("B03"), g.req("INT", "Оліфіренко Денис").new)
        assertEquals(listOf("B09"), g.req("VEH", "BC 77").new)
        assertEquals(setOf("B10", "B10b"), g.req("INT", "Кульчицький Тарас").new.toSet())
        assertEquals(setOf("B11", "B11a", "B12"), g.req("INT", "Кульчицький", "номер авто ВС 7741 НК").new.toSet())
        assertEquals(listOf("B05"), g.req("INT", "Дзюба Соломія", "Кравець бачив вас о 8:30").new)
        assertEquals(listOf("B19"), g.req("INT", "Кравець", "жінка в пальті о 19:58, підбори").new)
        assertEquals(listOf("B15a"), g.req("DB", "Сенишин Максим Андрійович").new)
        assertEquals(listOf("B15e"), g.req("DB", "Гнатів Вікторія").new)
        assertEquals(listOf("B13"), g.req("INT", "Максим Сенишин", "протокол комендантської 00:03").new)

        val bad = g.answer("тест", "Q1", mapOf("what" to "Його вбили", "weapon" to "статуетка Феміди",
            "why" to "Перекинутий стілець — були сліди боротьби"))
        assertFalse(bad.ok)
        assertTrue(bad.text, "✗ Які факти" in bad.text && "✓ Чим" in bad.text)
        assertTrue(g.answer("тест", "Q1", mapOf("what" to "Вбивство, а не падіння", "weapon" to "бронзовою фігуркою Феміди зі столу",
            "why" to "На столі пилове коло від статуетки, а капець лежить біля столу за 3 метри від тіла")).ok)
        assertEquals(2, g.state.getInt("stage"))

        assertEquals(listOf("B01"), g.req("TEL", "067-214-08-51").new)
        assertEquals(listOf("B08"), g.req("TEL", "+380677007741").new)
        assertEquals(listOf("B20"), g.req("TEL", "0639054418").new)
        assertEquals(listOf("B21"), g.req("TEL", "+380 98 512 30 07").new)
        assertFalse(g.req("TEL", "+380 50 377 19 62").ok)
        assertEquals(listOf("B04"), g.req("EXP", "судово-медична: уточнити час смерті").new)
        assertEquals(listOf("B07"), g.req("EXP", "комп'ютерно-технічна, ПК контори").new)
        assertEquals(listOf("B18"), g.req("EXP", "дактилоскопічна").new)
        assertEquals(listOf("B14"), g.req("ORG", "Рушай", "поїздки Сенишина Максима").new)
        assertEquals(listOf("B17"), g.req("ADR", "вул. Мулярська, 3").new)
        assertEquals(listOf("B16"), g.req("INT", "Максим", "бар Дим, покер у Бойка").new)
        assertEquals(listOf("B06"), g.req("ORG", "Лікарня №2", "Дзюба Ганна, швидка").new)
        assertEquals(listOf("B05b"), g.req("INT", "Соломія Дзюба", "довіреність Петренка, бланк НОН 112487").new)
        assertFalse(g.req("ORG", "Лікарня № 2", "Гнатів, СКУД").ok)

        assertFalse(g.answer("тест", "Q2", mapOf("time" to "22:00–02:00", "sms" to "Ні", "where" to "лікарня")).ok)
        assertTrue(g.answer("тест", "Q2", mapOf("time" to "між 19:15 і 21:00", "sms" to "Ні, він на той час уже був мертвий",
            "where" to "у лікарні на Буковецькій", "knock" to "син, Максим")).ok)
        assertEquals(3, g.state.getInt("stage"))

        assertEquals(listOf("C01"), g.req("ORG", "ЛМЛШД №2", "Гнатів В.О., СКУД і паркінг").new)
        assertEquals(listOf("C02"), g.req("INT", "Білик Оксана").new)
        assertEquals(listOf("C03"), g.req("TEL", "+380 50 377 19 62").new)
        assertEquals(listOf("C04"), g.req("ORG", "Спадковий реєстр", "заповіт Сенишина").new)
        assertEquals(listOf("C05"), g.req("INT", "Мацюк Ірина").new)
        assertEquals(listOf("C06"), g.req("INT", "Гнатів Вікторія", "білінг: Центр-14 18:20–19:58, пальто на реєстраторі").new)
        assertFalse(g.req("INT", "Гнатів Вікторія", "білінг").ok)

        assertFalse(g.answer("тест", "Q3", mapOf("who" to "Максим Сенишин")).ok)
        assertFalse(g.answer("тест", "Q3", mapOf("who" to "Гнатів разом із Кульчицьким")).ok)
        val r = g.answer("тест", "Q3", mapOf("who" to "Вікторія Гнатів", "weapon" to "Феміда", "time" to "близько пів на восьму",
            "motive" to "Новий заповіт 14.11 позбавляв її квартири, а ще борг Кульчицькому",
            "sms" to "Гнатів, з лікарні", "search" to "Багажник її Škoda і шафка в ординаторській",
            "money" to "Максим", "heater" to "помічниця Соломія", "window" to "Кульчицький"))
        assertTrue(r.text, r.ok)
        assertEquals(setOf("D01", "D02", "D03", "D04"), r.new.toSet())
        assertEquals(5.0, g.score(), 0.001)
        assertEquals("Суд повернув справу на доопрацювання", g.rank())
        assertFalse(g.publicState().toString().contains("\"rules\""))
    }

    @Test fun fuzzyAndTimes() {
        fun hit(k: String, t: String) = Matcher.keywordHit(k, Matcher.Text(t))
        assertTrue(hit("кульчицьк", "Кульчицкий Тарас")); assertTrue(hit("дзюба", "допитати Дзюбу"))
        assertTrue(hit("оліфіренк", "Олефіренко")); assertTrue(hit("гнатів", "Гнатов"))
        assertFalse(hit("гнатів", "Гнатюк П.")); assertFalse(hit("#672140851", "067 214 08 50"))
        assertEquals(listOf(19 * 60, 21 * 60), AnswerJudge.parseTimes("між 19 і 21").sorted())
        assertEquals(listOf(19 * 60 + 30), AnswerJudge.parseTimes("пів на восьму"))
        assertEquals(listOf(20 * 60), AnswerJudge.parseTimes("о восьмій вечора"))
        assertEquals(listOf(19 * 60 + 30), AnswerJudge.parseTimes("12.11 о 19.30, через 1–2 години після вечері, рана 9 см"))
    }
}

class Q1WordingTest {
    @Test fun shortWordingsAreAccepted() {
        val g = CaseEngine(JSONObject(File("cases/stairs/case.json").readText()))
        val r = g.answer("тест", "Q1", mapOf("what" to "Вбили", "weapon" to "Статуєткою", "why" to "Фото стола, СМЕ"))
        // the first two are right; the reasons are too thin, so only "why" may be marked wrong
        assertTrue(r.verdicts[0].ok)
        assertTrue(r.verdicts[1].ok)
    }
}
