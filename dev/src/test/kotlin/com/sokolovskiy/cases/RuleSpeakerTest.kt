package com.sokolovskiy.cases

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** A rule's `speaker` overrides the request type's usual speaker, in the result and in the log. */
class RuleSpeakerTest {
    private val case = JSONObject("""{
      "documents": [{"id": "A01", "title": "a"}, {"id": "B01", "title": "b"}],
      "start_documents": ["A01"], "questions": [], "persons": [], "suspects": [],
      "request_types": {"OBS": {"label": "Стеження"}},
      "messages": {"stage_locked": "", "already": "", "repeat": "", "unknown_person": "", "OBS": "нічого"},
      "rules": [
        {"type": "OBS", "match": [["лік"]], "unlock": ["B01"], "stage": 1, "speaker": "boss"},
        {"type": "OBS", "match": [["роман"]], "response": "нікого", "stage": 1},
        {"type": "OBS", "match": [["пакет"]], "unlock": ["B01"], "stage": 2},
        {"type": "OBS", "match": [["пакет"]], "response": "ще рано", "stage": 1, "before_stage": 2}
      ]}""")

    @Test fun ruleSpeaker() {
        val g = CaseEngine(case)
        assertEquals("boss", g.request("p", "OBS", "Ліка", "").speaker)
        assertEquals("", g.request("p", "OBS", "Роман", "").speaker)
        val log = g.state.getJSONArray("log")
        assertEquals("boss", log.getJSONObject(0).getString("speaker"))
    }

    @Test fun beforeStage() {
        val g = CaseEngine(case)
        assertEquals("ще рано", g.request("p", "OBS", "пакет", "").text)
        g.state.put("stage", 2)
        assertEquals(listOf("B01"), g.request("p", "OBS", "пакет", "").new)
    }
}
