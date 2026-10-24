package com.sokolovskiy.cases

import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Picks FLOOR and MARGIN of [OnnxJudge] on cases/stairs/semantic_cases.json. Needs the model in dev/models
 * (embed.onnx + embed.vocab, see docs/NEW_CASE.md); skipped otherwise. Run: ./gradlew :dev:test --tests "*SemanticTuning*" -i
 */
class SemanticTuning {
    @Test fun sweep() {
        val dir = File("dev/models")
        assumeTrue(OnnxJudge.present(dir))
        val judge = OnnxJudge.load(dir)
        val case = JSONObject(File("cases/stairs/case.json").readText())
        val cases = JSONObject(File("cases/stairs/semantic_cases.json").readText())
        val rules = case.getJSONArray("rules").objects().filter { it.has("examples") }
        val generic = case.strings("semantic_counter_generic")
        class Row(val rid: String, val text: String, val pos: Boolean, val kw: Boolean, val s: Double, val c: Double)
        val rows = mutableListOf<Row>()
        for (rid in cases.keys()) {
            val r = rules.first { it.strings("unlock").first() == rid }
            val others = rules.filter { it !== r && it.optString("person") == r.optString("person") }.flatMap { it.strings("examples") }
            val counters = (case.optJSONObject("semantic_counter")?.strings(r.optString("person")) ?: emptyList()) + generic + others
            val last = r.getJSONArray("match").let { m -> m.getJSONArray(m.length() - 1).let { g -> List(g.length()) { g.getString(it) } } }
            for ((pos, key) in listOf(true to "pos", false to "neg")) for (t in cases.getJSONObject(rid).strings(key)) {
                rows += Row(rid, t, pos, Matcher.groupHit(last, Matcher.Text(t)), judge.similarity(t, r.strings("examples")), judge.similarity(t, counters))
            }
        }
        val nPos = rows.count { it.pos }
        println("TUNE ${rows.size} phrases; keywords only: ${rows.count { it.pos && it.kw }}/$nPos, false ${rows.count { !it.pos && it.kw }}")
        val res = mutableListOf<Triple<Int, Int, String>>()
        var f = 0.40
        while (f <= 0.80) {
            for (m in listOf(0.0, 0.025, 0.05, 0.075, 0.1, 0.125)) {
                val fire = rows.map { it.kw || (it.s >= f && it.s - it.c >= m) }
                val tp = rows.indices.count { fire[it] && rows[it].pos }
                val fp = rows.indices.count { fire[it] && !rows[it].pos }
                res += Triple(fp, -tp, "FLOOR=%.3f MARGIN=%.3f".format(f, m))
            }
            f += 0.025
        }
        res.sortedWith(compareBy({ it.first }, { it.second })).take(8).forEach { println("TUNE FP=${it.first} TP=${-it.second}/$nPos ${it.third}") }
        val cur = rows.filter { (it.kw || (it.s >= OnnxJudge.FLOOR && it.s - it.c >= OnnxJudge.MARGIN)) != it.pos }
        println("TUNE current FLOOR=${OnnxJudge.FLOOR} MARGIN=${OnnxJudge.MARGIN}: ${cur.size} wrong")
        cur.forEach { println("TUNE   ${if (it.pos) "MISS" else "FALSE"} ${it.rid} s=%.2f c=%.2f %s".format(it.s, it.c, it.text)) }
    }
}
