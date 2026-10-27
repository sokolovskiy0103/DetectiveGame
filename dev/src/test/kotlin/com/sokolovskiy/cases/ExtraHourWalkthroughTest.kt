package com.sokolovskiy.cases

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** «Рибалка з ночівлею» played through the engine, keywords only (no model). */
class ExtraHourWalkthroughTest {
    private val path = "cases/extra-hour/case.json"
    private val case = JSONObject(File(path).readText())
    private val locked = case.getJSONObject("messages").getString("stage_locked")
    private fun engine() = CaseEngine(JSONObject(File(path).readText()))
    private fun CaseEngine.req(t: String, target: String, details: String = "") = request("тест", t, target, details)
    private fun CaseEngine.ans(q: String, vararg a: Pair<String, String>) = answer("тест", q, mapOf(*a))
    private fun CaseEngine.got(t: String, target: String, details: String = "") = req(t, target, details).new

    /** A refusal told in the world of the case, not the engine's "not yet". */
    private fun CaseEngine.refused(t: String, target: String, details: String = "") {
        val r = req(t, target, details)
        assertTrue("$t $target $details: ${r.text}", r.new.isEmpty())
        assertNotEquals("$t $target $details", locked, r.text)
    }

    @Test fun everyDocumentHasAFile() {
        val docs = case.getJSONArray("documents")
        for (i in 0 until docs.length()) {
            val id = docs.getJSONObject(i).getString("id")
            assertTrue("no documents/$id.html", File("cases/extra-hour/documents/$id.html").exists())
        }
    }

    @Test fun noAnswerWithoutEvidence() {
        val g = engine()
        g.ans("Q1", "who" to "Ліка Мороз", "what" to "свінгери").let { assertFalse(it.ok) }
        assertEquals(0.0, g.state.getDouble("penalty"), 0.0)
    }

    @Test fun earlyRequestsAreRefusedInWorld() {
        val g = engine()
        assertEquals(listOf("B13"), g.got("INT", "Костя Рябоконь"))
        g.refused("INT", "Костя Рябоконь", "журнал, ти спав?")
        g.refused("INT", "Рябоконь", "номер квартири жінки в червоному")
        g.refused("STEZH", "Кравець")
        g.refused("TECH", "карта камери Кості", "повні файли")
        g.refused("ZNAI", "оператор", "білінг Кості")
        g.refused("VYIZD", "службовий Ланос")
        g.refused("ARCH", "відомість спецодягу")
        g.refused("INT", "магазин Карнавал", "продавчиня")
        g.refused("VYIZD", "лазня")
        g.refused("ZNAI", "морг", "розтин Левченко")
        g.refused("ZNAI", "оператор", "білінг Ліки")
        assertEquals(listOf("B09"), g.got("INT", "Ірина Головко"))
        g.refused("INT", "мати Ірини", "Ганна Степанівна")
        g.refused("TECH", "домашній комп'ютер Ірини")
        g.refused("INT", "Сіренко")
        g.refused("STEZH", "Анжеліка Мороз")
        assertEquals(listOf("B10"), g.got("INT", "Бондарі"))
        g.refused("INT", "Бондарі", "камера, шантаж")
        g.refused("VYIZD", "котельня")
        g.refused("VYIZD", "берег за парканом")
    }

    @Test fun fullWalkthrough() {
        val g = engine()
        // stage 1: the object
        assertEquals(listOf("B01"), g.got("PROB", "Клочківська 23", "хто живе в кв. 41"))
        assertEquals(listOf("B02"), g.got("INT", "Петренко Галина Іванівна", "квартирантка"))
        assertEquals(listOf("B08"), g.got("INT", "Анжеліка Мороз"))
        assertEquals(listOf("B03"), g.got("VYIZD", "Toyota Camry Головка"))
        assertEquals(listOf("B04"), g.got("NET", "форум Тет-а-тет"))
        assertEquals(listOf("B11"), g.got("NET", "анкета Граф і Графиня"))
        assertEquals(listOf("B05"), g.got("VYIZD", "база Тиха заводь"))
        assertEquals(listOf("B06"), g.got("INT", "адміністраторка Людмила"))
        assertEquals(listOf("B07"), g.got("PROB", "Lancer АХ 2208 АМ"))
        assertEquals(listOf("B10"), g.got("INT", "подружжя Бондарів"))
        assertEquals(listOf("B09"), g.got("INT", "Ірина Головко"))
        assertEquals(listOf("B13"), g.got("INT", "Костя Рябоконь"))
        assertEquals(listOf("B14"), g.got("TECH", "комп'ютер Віктора на фірмі"))
        assertEquals(listOf("B12"), g.got("INT", "Віталій Кравець"))
        assertEquals(listOf("C05"), g.got("INT", "охоронець Гнатюк"))
        assertEquals(listOf("C04"), g.got("VYIZD", "база", "журнал охоронця"))
        assertEquals(listOf("C11"), g.got("INT", "Дорошенко"))
        assertEquals(listOf("C12"), g.got("STEZH", "Ірина Головко"))
        assertEquals(listOf("F07"), g.got("STEZH", "Дорошенко"))
        assertEquals(listOf("C06"), g.got("INT", "Ліка", "де його годинник Лонжин?"))
        assertEquals(listOf("C07"), g.got("ZNAI", "банк", "картки Головка"))
        g.refused("ZNAI", "міліція", "орієнтування на клофелінщиць")     // stage 2
        g.refused("INT", "Людмила Кравець", "камери")                     // not E08 by the surname
        assertEquals(listOf("C09"), g.got("VYIZD", "кабінет Головка", "щоденник"))
        assertEquals(listOf("C10"), g.got("TECH", "пошта Віктора", "лист аудитору"))
        assertEquals(listOf("C13"), g.got("PROB", "Скло-Транзит"))
        g.refused("INT", "Ліка", "годинник")                              // C06 already: her own words
        assertEquals("not a person refusal", false, g.req("INT", "Ярошенко").ok)

        g.ans("Q1", "who" to "Анжеліка Мороз", "what" to "день народження").let { assertFalse(it.text, it.ok) }
        g.ans("Q1", "who" to "Анжеліка Мороз", "what" to "маскарад клубу для пар Тет-а-тет").let { assertTrue(it.text, it.ok) }
        assertEquals(2, g.state.getInt("stage"))

        // stage 2: the steam room
        assertEquals(listOf("C01"), g.got("ZNAI", "Ярошенко", "протокол огляду"))
        assertEquals(listOf("C02"), g.got("ZNAI", "Левченко", "висновок експертизи"))
        assertEquals(listOf("C08"), g.got("VYIZD", "лазня"))
        assertEquals(listOf("C03"), g.got("ZNAI", "міліція", "орієнтування на клофелінщиць"))
        g.refused("VYIZD", "лазня", "камери в годиннику")
        g.ans("Q2", "cause" to "серце", "more" to "термостат").let { assertFalse(it.text, it.ok) }
        g.ans("Q2", "cause" to "його приспали клофеліном", "more" to "зникли годинник і гаманець").let { assertTrue(it.text, it.ok) }

        // stage 3: the clonidine woman
        assertEquals(listOf("D01"), g.got("ZNAI", "Ярошенко", "версія про клофелінщицю"))
        assertEquals(listOf("D02"), g.got("ZNAI", "Ярошенко", "пояснення гостей"))
        assertEquals(listOf("D03"), g.got("INT", "Ліза Бондар"))
        assertEquals(listOf("D11"), g.got("INT", "Ігор Бондар", "SMS від дружини"))
        assertTrue(g.req("ZNAI", "оператор", "білінг Ігоря Бондаря").let { it.ok && it.new.isEmpty() })
        assertEquals(listOf("D04"), g.got("ZNAI", "оператор", "білінг Лізи Бондар"))
        assertEquals(listOf("D05"), g.got("ZNAI", "оператор", "білінг Віктора Головка"))
        assertEquals(listOf("D06"), g.got("ZNAI", "оператор", "білінг Ліки Мороз"))
        assertEquals(listOf("D07"), g.got("INT", "таксі Рапід", "журнал диспетчера"))
        assertEquals(listOf("D08"), g.got("INT", "таксист Рустам"))
        assertEquals(listOf("D09"), g.got("INT", "Ліка", "500 доларів, білінг"))
        assertEquals(listOf("D10"), g.got("ARCH", "газета Слобідський кур'єр"))
        assertEquals(listOf("E03", "E04"), g.got("INT", "Бондарі", "камера, шантаж"))

        g.ans("Q3", "left" to "03:12", "call" to "03:58", "alive" to "03:10").let { assertFalse(it.text, it.ok) }
        g.ans("Q3", "left" to "02:10", "call" to "02:58", "alive" to "02:10").let { assertFalse(it.text, it.ok) } // minus an hour from all
        g.ans("Q3", "left" to "03:12 по-літньому, тобто 02:12", "call" to "о 02:58", "alive" to "десь о 03:10", "took" to "500 доларів")
            .let { assertTrue(it.text, it.ok); assertEquals(listOf("E01"), it.new) }

        // stage 4: the eye
        assertEquals(listOf("E02"), g.got("ZNAI", "Ярошенко", "затримання Дзюби"))
        assertEquals(listOf("E09"), g.got("ZNAI", "Ярошенко", "годинник Лонжин"))
        assertEquals(listOf("E10"), g.got("STEZH", "Роман Дзюба"))
        g.refused("INT", "Роман Дзюба")
        g.refused("INT", "Ліка", "білий пакет і Рома")
        assertEquals(listOf("E06"), g.got("VYIZD", "котельня", "реєстратор"))
        assertEquals(listOf("E07"), g.got("INT", "охоронець", "пакет Кравця"))
        assertEquals(listOf("E08"), g.got("INT", "Кравець", "приховані камери"))
        assertEquals(listOf("E05"), g.got("NET", "форум Тет-а-тет", "видалена тема про камери"))
        assertEquals(listOf("E11"), g.got("PROB", "телефон (05746) 7-12-40"))
        g.ans("Q4", "who" to "Гнатюк", "how" to "камери й шантаж").let { assertFalse(it.text, it.ok) }
        g.ans("Q4", "who" to "Віталій Кравець", "how" to "приховані камери, шантаж гостей; Головко погрожував міліцією")
            .let { assertTrue(it.text, it.ok); assertEquals(listOf("F01", "F02", "F03"), it.new) }

        // stage 5: the recording
        assertEquals(listOf("F04"), g.got("TECH", "кадр із записом", "зріст людини в масці"))
        assertEquals(listOf("F05"), g.got("VYIZD", "берег за парканом"))
        assertEquals(listOf("F06"), g.got("INT", "охоронець", "на записі ви не заходили"))
        assertEquals(listOf("F08"), g.got("INT", "Джекпот", "Дорошенко"))
        assertEquals(listOf("F09"), g.got("INT", "Наталя Дорошенко"))
        assertEquals(listOf("F10"), g.got("INT", "Тетяна Гончар", "запис з ресторану"))
        assertEquals(listOf("F11"), g.got("INT", "Кравець", "виріз на диску"))
        assertEquals(listOf("F12"), g.got("INT", "Бондарі", "руда жінка на записі"))
        assertEquals(listOf("H10"), g.got("INT", "Дорошенко", "Джекпот і гроші"))
        g.refused("INT", "Карнавал", "магазин")
        g.refused("TECH", "карта камери Кості")
        g.ans("Q5", "when" to "04:14", "where" to "ззовні", "cleared" to "Кравець у ресторані").let { assertFalse(it.text, it.ok) }
        g.ans("Q5", "when" to "на записі 04:14, тобто 03:14", "where" to "задніми дверима з боку ставка",
            "cleared" to "Кравець весь час у ресторані, Дорошенко грав у Джекпоті").let { assertTrue(it.text, it.ok) }

        // stage 6: the Longines watch
        assertEquals(listOf("G11"), g.got("INT", "Роман Дзюба"))
        assertEquals(listOf("G12"), g.got("INT", "Ліка", "білий пакет"))
        assertEquals(listOf("G09"), g.got("INT", "Зінаїда Пилипівна"))
        assertEquals(listOf("G10"), g.got("NET", "фотозвіт клубу Мілан"))
        assertEquals(listOf("G06"), g.got("INT", "Карнавал", "продавчиня магазину"))
        assertEquals(listOf("G01"), g.got("TECH", "карта камери Кості"))
        assertEquals(listOf("G02"), g.got("INT", "Костя", "розрив на карті"))
        assertEquals(listOf("G03"), g.got("ARCH", "відомість спецодягу"))
        assertEquals(listOf("G04", "G08"), g.got("VYIZD", "службовий Ланос"))
        assertEquals(listOf("G05"), g.got("INT", "Даша", "Костя в неділю"))
        assertEquals(listOf("G07"), g.got("ZNAI", "оператор", "білінг Кості"))
        g.ans("Q6", "who" to "Роман Дзюба", "alibi" to "журнал", "proof" to "куртка").let { assertFalse(it.text, it.ok) }
        g.ans("Q6", "who" to "Костя Рябоконь", "alibi" to "просто задрімав", "proof" to "маска").let { assertFalse(it.text, it.ok) }
        g.ans("Q6", "who" to "Костя Рябоконь (з Іриною)", "alibi" to "у журналі контрольні кадри, яких немає на карті: розрив 03:47–05:15",
            "proof" to "чек Карнавалу в Ланосі, мул і очерет").let { assertTrue(it.text, it.ok); assertEquals(listOf("H00"), it.new) }

        // stage 7: the flask
        g.refused("INT", "Костя")
        assertEquals(listOf("H04"), g.got("INT", "мати Ірини", "Ганна Степанівна, ліки"))
        assertEquals(listOf("H03"), g.got("TECH", "домашній комп'ютер Ірини", "QIP"))
        assertEquals(listOf("H02"), g.got("TECH", "карта камери", "відновити видалені файли"))
        assertEquals(listOf("H01"), g.got("ARCH", "справа Ірис", "квітень"))
        assertEquals(listOf("H05"), g.got("INT", "адвокат Сіренко"))
        assertEquals(listOf("H06"), g.got("ZNAI", "страхова", "поліс Головка"))
        assertEquals(listOf("H07"), g.got("INT", "Ірина", "QIP Ірис і готель"))
        assertEquals(listOf("H08"), g.got("INT", "Ліка", "фляга"))
        g.req("STEZH", "Ірина Головко").let { assertEquals(listOf("H09"), it.new); assertEquals("shulha", it.speaker) }
        assertEquals(listOf("H11"), g.got("TECH", "службовий телефон Кості"))
        g.ans("Q7", "who" to "Анжеліка Мороз", "flask" to "підсипала", "why" to "гроші").let { assertFalse(it.text, it.ok) }
        g.ans("Q7", "who" to "Ірина Головко разом з Костею", "flask" to "потовкла мамині таблетки клофеліну в ступці й заправила флягу вдома",
            "why" to "роман з Костею, розлучення за шлюбним договором і страховка; найняла Сову, щоб Костя мав алібі")
            .let { assertTrue(it.text, it.ok); assertEquals(listOf("Z01", "Z02", "Z03", "Z04"), it.new) }
        assertEquals(7.5, g.score(), 0.01)                                // 21 − 13.5 for every wrong answer above
        assertEquals("Тітка біля під'їзду", g.rank())
    }
}
