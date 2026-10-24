package com.sokolovskiy.cases

import com.sokolovskiy.cases.Matcher.Text
import org.json.JSONArray
import org.json.JSONObject

/**
 * The rules of one case: the state of the investigation, matching of the players' requests to the case's rules,
 * control questions, hints, notes and the score. Pure logic (no Android): the same code runs on the phone, in the
 * desktop launcher and in the unit tests. The state is a [JSONObject] so it can be saved as-is and survives restarts.
 *
 * Ported from the Python prototype (`app/engine.py`); `content/case.json` is shared with it.
 */
class CaseEngine(
    val case: JSONObject,
    private val semantic: () -> SemanticJudge? = { null },
    private val clock: () -> Double = { System.currentTimeMillis() / 1000.0 },
) {
    val docs: Map<String, JSONObject> = case.getJSONArray("documents").objects().associateBy { it.getString("id") }
    private val questionList: List<JSONObject> = case.getJSONArray("questions").objects()
    val questions: Map<String, JSONObject> = questionList.associateBy { it.getString("id") }
    private val rules: List<JSONObject> = case.getJSONArray("rules").objects()
    private val messages = case.getJSONObject("messages")
    private val requestTypes = case.getJSONObject("request_types")
    private val judge = AnswerJudge(
        case.getJSONArray("persons").objects(),
        case.strings("suspects").toSet(),
        semantic,
    )
    private val counters = HashMap<JSONObject, List<String>>()

    var state: JSONObject = newState()
        private set

    init {
        // A rule's counter-examples: the person's "neutral" topics, generic phrases and the examples of her other rules.
        val sem = rules.filter { it.has("examples") }
        val generic = case.strings("semantic_counter_generic")
        val perPerson = case.optJSONObject("semantic_counter")
        for (r in sem) {
            val others = sem.filter { it !== r && it.optString("person") == r.optString("person") }.flatMap { it.strings("examples") }
            counters[r] = (perPerson?.strings(r.optString("person")) ?: emptyList()) + generic + others
        }
    }

    // ---------- state ----------

    private fun newState(): JSONObject {
        val now = clock()
        return JSONObject()
            .put("started_at", now)
            .put("finished_at", JSONObject.NULL)
            .put("stage", 1)
            .put("unlocked", JSONArray(case.strings("start_documents").map { unlockEntry(it, now, "Справа") }))
            .put("log", JSONArray())        // requests, answers and hints
            .put("notes", JSONArray())      // shared notes
            .put("attempts", JSONObject())  // Q -> failed attempts
            .put("solved", JSONObject())    // Q -> {by, at, answers, points}
            .put("hints", JSONObject())     // Q -> opened hints
            .put("penalty", 0.0)
    }

    private fun unlockEntry(id: String, at: Double, by: String) = JSONObject().put("id", id).put("at", at).put("by", by)

    fun load(saved: JSONObject) { state = saved }

    fun reset() { state = newState() }

    fun unlockedIds(): Set<String> = state.getJSONArray("unlocked").objects().map { it.getString("id") }.toSet()

    private fun unlock(ids: List<String>, by: String): List<String> {
        val have = unlockedIds()
        val new = ids.filter { it !in have }
        val now = clock()
        val arr = state.getJSONArray("unlocked")
        new.forEach { arr.put(unlockEntry(it, now, by)) }
        return new
    }

    private fun log(entry: JSONObject) {
        entry.put("at", clock())
        state.getJSONArray("log").put(entry)
    }

    private val stage get() = state.getInt("stage")

    // ---------- requests ----------

    class Result(val ok: Boolean, val text: String, val new: List<String> = emptyList(), val verdicts: List<Verdict> = emptyList())
    class Verdict(val label: String, val ok: Boolean, val required: Boolean)

    private fun ruleMatches(rule: JSONObject, text: Text, details: String): Boolean {
        val match = rule.getJSONArray("match").let { m ->
            List(m.length()) { i -> m.getJSONArray(i).let { g -> List(g.length()) { j -> g.getString(j) } } }
        }
        val head = match.dropLast(1)
        val last = match.last()
        if (!head.all { Matcher.groupHit(it, text) }) return false
        if (Matcher.groupHit(last, text)) return true
        val examples = rule.strings("examples")
        if (examples.isNotEmpty() && details.isNotBlank()) {
            return semantic()?.decide(details, examples, counters[rule] ?: emptyList()) == true
        }
        return false
    }

    fun request(player: String, rtype: String, target: String, details: String): Result {
        if (!requestTypes.has(rtype)) return Result(false, "Невідомий тип запиту.")
        val text = Text("$target $details")
        val have = unlockedIds()
        val matched = rules.filter { it.getString("type") == rtype && ruleMatches(it, text, details) }
        var blocked: String? = null
        var allUnlocked = matched.isNotEmpty()
        var result: Result? = null
        for (r in matched) {
            val unlock = r.strings("unlock")
            if (unlock.isNotEmpty() && unlock.all { it in have }) continue
            allUnlocked = false
            if (stage < r.getInt("stage")) {
                blocked = blocked ?: messages.optString(r.optString("stage_msg", "stage_locked"))
                continue
            }
            val all = r.strings("requires_all")
            if (all.isNotEmpty() && !all.all { it in have }) { blocked = blocked ?: r.optString("blocked"); continue }
            val any = r.strings("requires_any")
            if (any.isNotEmpty() && !any.any { it in have }) { blocked = blocked ?: r.optString("blocked"); continue }
            result = if (r.optString("response").isNotEmpty()) {
                Result(true, r.getString("response"))
            } else {
                val new = unlock(unlock, player)
                Result(true, "До справи додано: " + new.joinToString(", ") { "$it «${docs.getValue(it).getString("title")}»" } + ".", new)
            }
            break
        }

        val res = result ?: when {
            blocked != null -> Result(false, blocked)
            matched.isNotEmpty() && allUnlocked -> Result(false, messages.getString(if (rtype != "INT") "already" else "repeat"))
            (rtype == "TEL" || rtype == "EXP") && stage < 2 -> Result(false, messages.getString("stage_locked"))
            rtype == "INT" -> {
                val person = findPerson(text)
                if (person == null) Result(false, messages.getString("unknown_person"))
                else Result(false, case.getJSONObject("person_refusals").optString(person, messages.getString("repeat")))
            }
            else -> Result(false, messages.getString(rtype))
        }

        log(JSONObject().put("kind", "request").put("player", player).put("type", rtype)
            .put("type_label", requestTypes.getJSONObject(rtype).getString("label"))
            .put("target", target).put("details", details)
            .put("ok", res.ok).put("text", res.text).put("new", JSONArray(res.new)))
        return res
    }

    private fun findPerson(text: Text): String? =
        case.getJSONArray("persons").objects().firstOrNull { p -> p.strings("aliases").any { Matcher.keywordHit(it, text) } }?.getString("id")

    // ---------- questions ----------

    fun availableQuestion(): JSONObject? {
        val solved = state.getJSONObject("solved")
        val q = questionList.firstOrNull { !solved.has(it.getString("id")) } ?: return null
        return if (q.getInt("stage") <= stage) q else null
    }

    /** The last question closes the case; its `ranks` turn the score into a title. */
    private val finalQuestion get() = questionList.last()

    fun answer(player: String, qid: String, given: Map<String, String>): Result {
        val q = questions[qid]
        val solved = state.getJSONObject("solved")
        if (q == null || solved.has(qid) || q.getInt("stage") > stage) return Result(false, "Це питання зараз недоступне.")

        val fields = q.getJSONArray("fields").objects()
        val answers = fields.associate { it.getString("id") to (given[it.getString("id")] ?: "").take(1000) }
        var passed = true
        var points = 0.0
        val verdicts = mutableListOf<Verdict>()   // feedback to the players: what convinced and what did not — no answers
        for (f in fields) {
            val v = judge.field(f, answers.getValue(f.getString("id")))
            points += v.points
            if (f.optBoolean("required") && !v.good) passed = false
            verdicts += Verdict(f.getString("label"), v.good, f.optBoolean("required"))
        }

        val text = when {
            passed -> q.getString("success")
            q.optDouble("fail_penalty", 0.0) != 0.0 && !verdicts[0].ok -> q.getString("fail")  // the finale: wrong on the main point
            else -> q.getString("fail") + " " + verdicts.filter { it.required }.joinToString("; ") { (if (it.ok) "✓ " else "✗ ") + it.label }
        }
        log(JSONObject().put("kind", "answer").put("player", player).put("question", qid).put("title", q.getString("title"))
            .put("ok", passed).put("text", text).put("answers", JSONObject(answers)))
        if (!passed) {
            val att = state.getJSONObject("attempts")
            att.put(qid, att.optInt(qid, 0) + 1)
            state.put("penalty", state.getDouble("penalty") + q.optDouble("fail_penalty", WRONG_ANSWER_PENALTY))
            return Result(false, text, verdicts = verdicts)
        }

        solved.put(qid, JSONObject().put("by", player).put("at", clock()).put("answers", JSONObject(answers)).put("points", points))
        state.put("stage", q.getInt("stage") + 1)
        var new = emptyList<String>()
        if (qid == finalQuestion.getString("id")) {
            new = unlock(case.strings("final_documents"), "Обшук")
            state.put("finished_at", clock())
        }
        return Result(true, q.getString("success"), new, verdicts)
    }

    fun hint(player: String, qid: String): Boolean {
        val q = questions[qid] ?: return false
        val hints = q.strings("hints")
        val hs = state.getJSONObject("hints")
        val n = hs.optInt(qid, 0)
        if (n >= hints.size) return false
        hs.put(qid, n + 1)
        state.put("penalty", state.getDouble("penalty") + HINT_PENALTY)
        log(JSONObject().put("kind", "hint").put("player", player).put("question", qid).put("text", hints[n]))
        return true
    }

    // ---------- notes ----------

    fun addNote(player: String, raw: String) {
        val text = raw.trim().take(2000)
        if (text.isEmpty()) return
        val notes = state.getJSONArray("notes")
        val id = (notes.objects().maxOfOrNull { it.getInt("id") } ?: 0) + 1
        notes.put(JSONObject().put("id", id).put("player", player).put("text", text).put("at", clock()).put("pinned", false))
    }

    fun deleteNote(id: Int) {
        val keep = state.getJSONArray("notes").objects().filter { it.getInt("id") != id }
        state.put("notes", JSONArray(keep))
    }

    fun togglePin(id: Int) {
        state.getJSONArray("notes").objects().firstOrNull { it.getInt("id") == id }?.let { it.put("pinned", !it.getBoolean("pinned")) }
    }

    // ---------- score and the public state ----------

    fun score(): Double {
        val sv = state.getJSONObject("solved")
        val pts = sv.keys().asSequence().sumOf { sv.getJSONObject(it).getDouble("points") }
        return Math.round((pts - state.getDouble("penalty")) * 10) / 10.0
    }

    fun rank(): String? {
        val fq = finalQuestion
        if (!state.getJSONObject("solved").has(fq.getString("id"))) return null
        val s = score()
        val ranks = fq.optJSONArray("ranks") ?: return null
        return ranks.objects().first { s >= it.getDouble("min") }.getString("title")
    }

    /** What the players see. Rules, answers and the titles of locked documents never get here. */
    fun publicState(): JSONObject {
        val q = availableQuestion()
        val hints = JSONObject()
        val hs = state.getJSONObject("hints")
        for (qid in hs.keys()) hints.put(qid, JSONArray(questions.getValue(qid).strings("hints").take(hs.getInt(qid))))
        val documents = state.getJSONArray("unlocked").objects().map { u ->
            JSONObject(docs.getValue(u.getString("id")).toString()).put("at", u.getDouble("at")).put("by", u.getString("by"))
        }
        val sv = state.getJSONObject("solved")
        val solved = JSONObject()
        for (k in sv.keys()) solved.put(k, JSONObject().put("by", sv.getJSONObject(k).getString("by")).put("at", sv.getJSONObject(k).getDouble("at")))
        return JSONObject()
            .put("title", case.getString("title"))
            .put("subtitle", case.optString("subtitle"))
            .put("stage", stage)
            .put("stage_name", case.getJSONObject("stage_names").optString(stage.toString()))
            .put("categories", case.getJSONObject("categories"))
            .put("documents", JSONArray(documents))
            .put("request_types", requestTypes)
            .put("log", JSONArray(state.getJSONArray("log").objects().takeLast(200)))
            .put("notes", state.getJSONArray("notes"))
            .put("question", q?.let { publicQuestion(it) } ?: JSONObject.NULL)
            .put("hints", hints)
            .put("solved", solved)
            .put("started_at", state.getDouble("started_at"))
            .put("finished_at", if (state.opt("finished_at") is Number) state.get("finished_at") else JSONObject.NULL)
            .put("score", score())
            .put("rank", rank() ?: JSONObject.NULL)
            // who "speaks" in the answers: a name and a picture, picked by the kind of request
            .put("speakers", case.optJSONObject("speakers") ?: JSONObject())
            .put("default_speaker", case.optString("default_speaker"))
            .put("speaker_by_type", case.optJSONObject("speaker_by_type") ?: JSONObject())
    }

    private fun publicQuestion(q: JSONObject): JSONObject {
        val fields = JSONArray()
        for (f in q.getJSONArray("fields").objects()) {
            val o = JSONObject()
            for (k in listOf("id", "label", "kind", "placeholder", "multiline")) if (f.has(k)) o.put(k, f.get(k))
            fields.put(o)
        }
        return JSONObject().put("id", q.getString("id")).put("title", q.getString("title")).put("intro", q.getString("intro"))
            .put("fields", fields)
            .put("hints_total", q.strings("hints").size)
            .put("attempts", state.getJSONObject("attempts").optInt(q.getString("id"), 0))
    }

    companion object {
        const val HINT_PENALTY = 0.5
        const val WRONG_ANSWER_PENALTY = 0.5
    }
}

internal fun JSONArray.objects(): List<JSONObject> = List(length()) { getJSONObject(it) }
