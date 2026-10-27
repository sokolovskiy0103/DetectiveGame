package com.sokolovskiy.cases

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** `also` on a person field and `any_time` on a time field. */
class JudgeOptionsTest {
    private val persons = listOf(
        JSONObject("""{"id": "iryna", "aliases": ["ірин"]}"""),
        JSONObject("""{"id": "kostya", "aliases": ["кост"]}"""),
        JSONObject("""{"id": "lika", "aliases": ["лік"]}"""),
    )
    private val judge = AnswerJudge(persons, setOf("iryna", "kostya", "lika")) { null }

    @Test fun accompliceMayBeNamed() {
        val f = JSONObject("""{"kind": "person", "person": "iryna", "also": ["kostya"]}""")
        assertTrue(judge.field(f, "Ірина разом із Костею").good)
        assertTrue(judge.field(f, "Ірина").good)
        assertFalse(judge.field(f, "Ірина і Ліка").good)
        assertFalse(judge.field(f, "Костя").good)
        assertFalse(judge.field(JSONObject("""{"kind": "person", "person": "iryna"}"""), "Ірина з Костею").good)
    }

    @Test fun anyTime() {
        val strict = JSONObject("""{"kind": "time", "window": ["03:10", "03:22"], "hour_offset": 0}""")
        val any = JSONObject(strict.toString()).put("any_time", true)
        assertFalse(judge.field(strict, "на записі 04:14, насправді 03:14").good)
        assertTrue(judge.field(any, "на записі 04:14, насправді 03:14").good)
        assertFalse(judge.field(any, "04:14").good)
    }
}
