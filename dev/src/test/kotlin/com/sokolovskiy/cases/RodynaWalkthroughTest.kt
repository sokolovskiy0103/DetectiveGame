package com.sokolovskiy.cases

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** «Родина» played through the engine, keywords only (no model). */
class RodynaWalkthroughTest {
    private fun engine() = CaseEngine(JSONObject(File("cases/rodyna/case.json").readText()))
    private fun CaseEngine.req(t: String, target: String, details: String = "") = request("тест", t, target, details)

    @Test fun everyDocumentHasAFile() {
        val case = JSONObject(File("cases/rodyna/case.json").readText())
        val docs = case.getJSONArray("documents")
        for (i in 0 until docs.length()) {
            val id = docs.getJSONObject(i).getString("id")
            assertTrue("no documents/$id.html", File("cases/rodyna/documents/$id.html").exists())
        }
    }

    @Test fun fullWalkthrough() {
        val g = engine()
        // stage 1: who is the "grandson"
        assertFalse(g.req("TEL", "+380 67 512 40 03").ok)
        assertFalse(g.req("INT", "Олександр Яковишин-Россі", "консульство: мама померла 2015").ok)
        assertEquals(listOf("B01"), g.req("ORG", "Чорне зерно, бухгалтерія", "список пакувальників Родини").new)
        assertEquals(listOf("B02"), g.req("DB", "Гуменюк Артем Віталійович").new)
        assertEquals(listOf("B03"), g.req("DB", "Яковишин-Россі Олександр").new)
        assertEquals(listOf("B04"), g.req("ORG", "Консульство Італії", "Олена Яковишин, Неаполь").new)
        assertEquals(listOf("B05"), g.req("DB", "Дячук Руслан Михайлович").new)
        assertEquals(listOf("B06"), g.req("ORG", "БФ «Родина»").new)
        assertEquals(listOf("B07"), g.req("ADR", "с. Підгір'я, вул. Лісова, 12").new)
        assertEquals(listOf("B08"), g.req("INT", "Маланюк Ігор").new)
        assertEquals(listOf("B09"), g.req("INT", "Ліщук Тарас").new)
        assertEquals(listOf("B10"), g.req("INT", "Стефурак").new)
        assertEquals(listOf("B11"), g.req("ORG", "Лаз-Девелопмент", "друга черга").new)
        assertEquals(listOf("B12"), g.req("ORG", "Лаз-Сервіс", "журнал шлагбаума і ключів").new)
        assertFalse(g.req("ORG", "Лаз-Сервіс", "журнал дверей поста").ok)
        assertEquals(listOf("B13"), g.req("INT", "баба Світа", "Василь приходив до вас о 22:45 з папкою").new)
        assertFalse(g.req("INT", "баба Света", "самогон у літній кухні").ok)
        assertEquals(listOf("B14"), g.req("INT", "онук Сашко", "ви Артем Гуменюк, фото є в списку Чорного зерна").new)
        assertEquals(listOf("B15"), g.req("INT", "пастор Бабій").new)
        assertEquals(listOf("B16"), g.req("INT", "Дмитрук Ольга").new)
        assertEquals(listOf("B17"), g.req("INT", "Бабій Галина").new)
        assertEquals(listOf("B18"), g.req("OSINT", "Ярослав Бабій, Facebook").new)
        assertEquals(listOf("B19"), g.req("DB", "Дмитрук Ольга Степанівна").new)
        assertEquals(listOf("B20"), g.req("VEH", "AT 7713 BK").new)
        assertEquals(listOf("D03"), g.req("OSINT", "Прикарпаття, календар матчів").new)

        assertFalse(g.answer("тест", "Q1", mapOf("real" to "справжній онук", "behind" to "Родина", "proof" to "список")).ok)
        g.answer("тест", "Q1", mapOf("real" to "Артем Гуменюк з Калуша", "behind" to "фонд «Родина» пастора Бабія",
            "proof" to "Його фото в списку пакувальників Чорного зерна, а мама Олена померла ще у 2015, справжній онук у Мілані")).let { assertTrue(it.text, it.ok) }
        assertEquals(2, g.state.getInt("stage"))

        // stage 2: the night
        assertFalse(g.req("EXP", "телефон Гуменюка", "PIN").ok)
        assertTrue(g.req("SRCH", "курник баби Світи").new.isEmpty())
        assertEquals(listOf("C05"), g.req("INT", "Гуменюк Артем", "гарантуємо захист, статус свідка").new)
        assertEquals(listOf("C06"), g.req("EXP", "комп'ютерно-технічна: телефон Артема, PIN 1410").new)
        assertEquals(listOf("C07"), g.req("SRCH", "Пасічна, 41-А, курник", "папка Василя під сідалом").new)
        assertEquals(setOf("C08", "C09"), g.req("INT", "Яковишин Світлана", "ви гнали самогон, брага на весь двір").new.toSet())
        assertEquals(listOf("C01"), g.req("EXP", "судово-медична, повторне дослідження рани").new)
        assertEquals(listOf("C02"), g.req("EXP", "трасологічна, сліди взуття").new)
        assertEquals(listOf("C03"), g.req("EXP", "гумовий фрагмент «…AG-…»").new)
        assertEquals(listOf("C04"), g.req("TEL", "067 512 40 03").new)
        assertEquals(listOf("C10"), g.req("ORG", "готель Карпатський двір, Буковель", "Палійчук Інна").new)
        assertEquals(listOf("C11"), g.req("INT", "Палійчук Інна", "ви були в номері 214 з Назаром Кравчуком").new)
        assertEquals(listOf("C12"), g.req("INT", "детектив Тимчишин").new)
        assertEquals(listOf("C13"), g.req("ORG", "Банк", "платежі Чорного зерна на Родину").new)
        assertEquals(listOf("C14"), g.req("ORG", "Лаз-Девелопмент", "договір пожертви Родині").new)
        assertEquals(listOf("C15"), g.req("TEL", "+380672200505").new)
        assertEquals(listOf("C16"), g.req("INT", "Стефурак Едуард", "дзвінок о 22:15 і договір пожертви").new)
        assertEquals(listOf("C17"), g.req("TEL", "+380 50 433 17 26").new)
        assertFalse(g.req("INT", "Ліщук", "який ліхтар був у шефа").ok)
        assertFalse(g.req("TEL", "+380 66 318 90 45").ok)

        assertFalse(g.answer("тест", "Q2", mapOf("time" to "між 23:00 і 01:00", "weapon" to "ліхтар", "task" to "Кум")).ok)
        g.answer("тест", "Q2", mapOf("time" to "15.10 близько 23:35", "weapon" to "важким ліхтарем Maglite",
            "task" to "Кум", "papers" to "у курнику", "car" to "машина Родини, AT 7713")).let { assertTrue(it.text, it.ok) }
        assertEquals(3, g.state.getInt("stage"))

        // stage 3: Kum and Mati
        assertEquals(listOf("D01"), g.req("EXP", "відеореєстратор ЖК, журнал").new)
        assertEquals(listOf("D02"), g.req("EXP", "SD-карта камери поста").new)
        assertEquals(listOf("D04"), g.req("TEL", "+380 66 318 90 45").new)
        assertEquals(listOf("D05"), g.req("TEL", "096 704 18 62").new)
        assertEquals(listOf("D06"), g.req("TEL", "+380 68 455 02 19").new)
        assertEquals(listOf("D07"), g.req("ORG", "Лаз-Сервіс", "журнал дверей поста і хвірток за ухвалою").new)
        assertEquals(listOf("D08"), g.req("INT", "Ліщук", "який ліхтар був у шефа").new)
        assertEquals(listOf("D09"), g.req("INT", "Дячук", "на вашому записі футбол 14.10, камери вимкнено з консолі").new)
        assertEquals(listOf("D10"), g.req("INT", "Ольга Дмитрук", "ви написали в групу о 21:07, а о 23:05 дзвонили Дячуку").new)
        assertEquals(listOf("D11"), g.req("INT", "Бабій Ярослав", "група Родина, ваш наказ о 23:01 — тихо").new)
        assertFalse(g.req("SRCH", "гараж Дячука").ok)

        assertFalse(g.answer("тест", "Q3", mapOf("kum" to "Дячук", "kum_why" to "футбол на записі і камери з консолі",
            "mati" to "Інна Палійчук", "mati_why" to "вона цитувала 21:20")).ok)
        g.answer("тест", "Q3", mapOf("kum" to "Руслан Дячук", "kum_why" to "камери вимкнули з консолі поста о 23:12, на записі — матч 14.10, без мізинця",
            "mati" to "Ольга Дмитрук", "mati_why" to "Мати написала о 21:07, а Василь о 21:05 дзвонив саме їй; о 23:05 вона дзвонила Дячуку",
            "batko" to "Ярослав Бабій", "builder" to "Стефурак", "brat" to "Сенчук")).let { assertTrue(it.text, it.ok) }
        assertEquals(4, g.state.getInt("stage"))

        // stage 4: without a search or an expertise the version is not even judged
        val before = g.score()
        assertFalse(g.answer("тест", "Q4", mapOf("who" to "Руслан Дячук")).ok)
        assertEquals(before, g.score(), 0.001)

        // stage 4: searches
        assertEquals(listOf("E01"), g.req("SRCH", "гараж Дячука, ГК Пасічна, бокс 14", "ліхтар Maglite").new)
        assertEquals(listOf("E02"), g.req("EXP", "ліхтар Maglite: кров, фрагмент").new)
        assertEquals(listOf("E03"), g.req("INT", "Сенчук Іван").new)
        assertEquals(listOf("E04"), g.req("SRCH", "реабцентр Родина, Підгір'я", "піч у лазні").new)

        assertFalse(g.answer("тест", "Q4", mapOf("who" to "Ольга Дмитрук")).ok)
        val r = g.answer("тест", "Q4", mapOf("who" to "Руслан Дячук", "order" to "Ольга Дмитрук", "weapon" to "ліхтарем",
            "why" to "Василь його впізнав, а Мати пообіцяла квартиру", "phone" to "Сенчук відвіз у Підгір'я, Батько спалив у печі лазні",
            "payer" to "Стефурак", "baba" to "гнала самогон і захищала онука"))
        assertTrue(r.text, r.ok)
        assertEquals(setOf("F01", "F02", "F03"), r.new.toSet())
        assertEquals(8.5, g.score(), 0.001)
        assertEquals("Справу передано до суду", g.rank())
    }

    @Test fun timesAcrossMidnightAndDates() {
        val g = engine()
        val q2 = JSONObject(File("cases/rodyna/case.json").readText()).getJSONArray("questions").getJSONObject(1)
        val time = q2.getJSONArray("fields").getJSONObject(0)
        val judge = AnswerJudge(emptyList(), emptySet()) { null }
        assertTrue(judge.field(time, "о пів на дванадцяту").good)
        assertTrue(judge.field(time, "між 23:30 і 00:00").good)
        assertTrue(judge.field(time, "16.10 о 23:40").good)
        assertFalse(judge.field(time, "23:00–01:00").good)
        assertFalse(judge.field(time, "о 22:41").good)
        assertTrue(g.state.getInt("stage") == 1)
    }
}
