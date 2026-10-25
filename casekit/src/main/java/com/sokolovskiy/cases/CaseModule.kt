package com.sokolovskiy.cases

import com.sokolovskiy.party.core.GameEnv
import com.sokolovskiy.party.core.GameModule
import com.sokolovskiy.party.core.GameSession
import com.sokolovskiy.party.core.LocalServer
import org.json.JSONArray
import org.json.JSONObject

/**
 * One investigation in the lobby. Everything about the case lives in its content folder in the app's assets:
 *
 *     assets/data/cases/<id>/case.json        documents, rules, questions (read only by the phone)
 *     assets/data/cases/<id>/doc/<ID>.html    the documents; served to players only once unlocked
 *     assets/data/cases/<id>/photo/<name>     pictures used by documents; same rule
 *     assets/web/games/<id>/…                 theme (game.css), rules.html, avatars — public
 *
 * so a new case is new content plus one line registering it; the engine and the screens (`kit/case.js`) are shared.
 */
class CaseModule(
    override val id: String,
    override val title: String,
    override val subtitle: String,
    override val emoji: String,
    /** The paraphrase model (always on, fetched by the app itself); null = keywords only (tests, no model available). */
    private val semantic: SemanticHost? = null,
) : GameModule {

    override val minPlayers = 1
    override val webAssets = listOf("kit/case.js", "kit/case.css")

    override fun create(env: GameEnv): GameSession {
        semantic?.onCaseOpened()
        return CaseSession(env, id) { semantic?.judge }
    }
}

class CaseSession(private val env: GameEnv, private val caseId: String, semantic: () -> SemanticJudge?) : GameSession {

    private val engine = CaseEngine(JSONObject(env.asset("cases/$caseId/case.json")), semantic)
    private val saveKey = "case-$caseId"

    /** Where a CI build's media live on jsDelivr (`hub/build.gradle.kts`); null in local builds. */
    private val cdn: String? = runCatching { env.asset("cases/$caseId/cdn.txt").trim() }.getOrNull()?.takeIf { it.isNotEmpty() }

    /** Which documents show which pictures: a picture is served once any document using it is open. */
    private val photoUsers: Map<String, Set<String>> = HashMap<String, MutableSet<String>>().also { index ->
        for (docId in engine.docs.keys) {
            val html = docText(docId) ?: continue
            for (m in PHOTO.findAll(html)) index.getOrPut(m.groupValues[1]) { HashSet() } += docId
        }
    }

    /** The latest thing somebody did, for toasts on the other screens; [seq] tells a screen it has not seen it yet. */
    private var seq = 0
    private var event: JSONObject? = null

    init {
        env.readState(saveKey)?.let { runCatching { engine.load(JSONObject(it)) } }
    }

    override fun onJoin(id: String, name: String) {}
    override fun onLeave(id: String) {}

    private fun nameOf(pid: String?) = pid?.let { env.room.players[it]?.name }

    override fun handle(pid: String?, isHost: Boolean, msg: JSONObject, now: Long) {
        if (isHost && msg.optString("t") == "newGame") {
            engine.reset()
            emit(JSONObject().put("kind", "reset"))
            save()
            return
        }
        val name = nameOf(pid) ?: return
        when (msg.optString("a")) {
            "request" -> {
                val r = engine.request(name, msg.optString("type"), msg.optString("target").take(300), msg.optString("details").take(600))
                emit(JSONObject().put("kind", "request").put("player", name).put("ok", r.ok).put("text", r.text).put("new", JSONArray(r.new)))
            }
            "answer" -> {
                val given = HashMap<String, String>()
                msg.optJSONObject("answers")?.let { a -> for (k in a.keys()) given[k] = a.optString(k) }
                val r = engine.answer(name, msg.optString("question"), given)
                emit(JSONObject().put("kind", "answer").put("player", name).put("ok", r.ok).put("text", r.text).put("new", JSONArray(r.new)))
            }
            "hint" -> {
                if (engine.hint(name, msg.optString("question"))) emit(JSONObject().put("kind", "hint").put("player", name))
            }
            "note_add" -> engine.addNote(name, msg.optString("text"))
            "note_del" -> engine.deleteNote(msg.optInt("id"))
            "note_pin" -> engine.togglePin(msg.optInt("id"))
            else -> return
        }
        save()
    }

    private fun emit(e: JSONObject) {
        seq++
        event = e.put("seq", seq)
    }

    private fun save() = env.writeState(saveKey, engine.state.toString())

    override fun state(pid: String?, isHost: Boolean, now: Long): JSONObject {
        val s = engine.publicState()
        s.put("phase", if (s.isNull("finished_at")) "PLAY" else "DONE")
        s.put("seq", seq)
        s.put("ev", event ?: JSONObject.NULL)
        s.put("caseId", caseId)
        return s
    }

    // ---------- gated files ----------

    private fun docText(id: String): String? = try {
        String(env.assetBytes("cases/$caseId/doc/$id.html"), Charsets.UTF_8)
    } catch (_: Exception) {
        null
    }

    /** `/static/g/doc/<ID>.html`, `doc/_base.css`, `photo/<name>` — only what the case has opened so far. */
    override fun resource(path: String): Pair<ByteArray, String>? {
        val parts = path.split('/')
        if (parts.size != 2) return null
        val (dir, file) = parts
        val open = engine.unlockedIds()
        return try {
            when {
                dir == "doc" && file == "_base.css" -> env.assetBytes("cases/$caseId/doc/_base.css") to LocalServer.mimeOf(file)
                dir == "doc" && file.endsWith(".html") && file.removeSuffix(".html") in open -> {
                    // documents were written for the folder layout of the authoring project
                    val html = docText(file.removeSuffix(".html"))?.let { mediaLinks(it, cdn) } ?: return null
                    html.toByteArray(Charsets.UTF_8) to LocalServer.mimeOf(file)
                }
                dir == "photo" && (photoUsers[file] ?: emptySet()).any { it in open } ->
                    env.assetBytes("cases/$caseId/photo/$file") to LocalServer.mimeOf(file)
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private val PHOTO = Regex("photos/([\\w.-]+)")
        private val PHOTO_SRC = Regex("src=\"\\.\\./photos/([\\w.-]+)\"")

        /**
         * Documents were written for the folder layout of the authoring project (`../photos/`). With a [cdn] the
         * picture comes from jsDelivr (no size limit, no traffic through the relay or this phone) and falls back
         * to the phone when the player has no internet.
         */
        fun mediaLinks(html: String, cdn: String?): String {
            val linked = if (cdn == null) html else PHOTO_SRC.replace(html) { m ->
                val f = m.groupValues[1]
                "src=\"${cdn}photos/$f\" onerror=\"this.onerror=null;this.src='../photo/$f'\""
            }
            return linked.replace("../photos/", "../photo/")
        }
    }
}
