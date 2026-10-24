package com.sokolovskiy.party.core

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * The party: who is here and which game is running. Pure logic, not thread-safe —
 * the [Hub] serialises every call with one lock.
 *
 * Players join once per evening and stay while the host switches games.
 */
class Room(val modules: List<GameModule>) {

    class Player(val id: String, var name: String) {
        var connected = false
        val joinOrder = nextOrder++

        companion object {
            private var nextOrder = 0
        }
    }

    val players = LinkedHashMap<String, Player>()
    var gameId: String? = null; private set
    var session: GameSession? = null; private set

    // ---------- players ----------

    /** New player, or an existing one ([existingId]) renaming themselves. */
    fun join(rawName: String, existingId: String? = null): Player {
        existingId?.let { id ->
            players[id]?.let { p ->
                p.name = uniqueName(rawName, p)
                session?.onJoin(p.id, p.name)
                return p
            }
        }
        val p = Player(UUID.randomUUID().toString().take(8), "")
        p.name = uniqueName(rawName, p)
        players[p.id] = p
        session?.onJoin(p.id, p.name)
        return p
    }

    fun remove(id: String) {
        players.remove(id) ?: return
        session?.onLeave(id)
    }

    private fun uniqueName(raw: String, self: Player): String {
        var base = raw.trim().replace(Regex("\\s+"), " ").take(20)
        if (base.isEmpty()) base = "Гравець ${players.size + 1}"
        var name = base
        var n = 2
        while (players.values.any { it !== self && it.name.equals(name, ignoreCase = true) }) name = "$base ${n++}"
        return name
    }

    // ---------- games ----------

    fun select(id: String, env: GameEnv): Boolean {
        val m = modules.firstOrNull { it.id == id } ?: return false
        session?.close()
        val s = m.create(env)
        session = s
        gameId = id
        players.values.sortedBy { it.joinOrder }.forEach { s.onJoin(it.id, it.name) }
        return true
    }

    fun exitGame() {
        session?.close()
        session = null
        gameId = null
    }

    fun tick(now: Long): Boolean = session?.tick(now) ?: false

    // ---------- state ----------

    fun stateJson(pid: String?, isHost: Boolean, now: Long): JSONObject {
        val o = JSONObject()
        o.put("t", "state")
        o.put("now", now)
        o.put("isHost", isHost)
        o.put("game", gameId ?: JSONObject.NULL)
        o.put("players", JSONArray(players.values.sortedBy { it.joinOrder }.map {
            JSONObject().put("id", it.id).put("name", it.name).put("connected", it.connected)
        }))
        o.put("games", JSONArray(modules.map {
            JSONObject().put("id", it.id).put("title", it.title).put("subtitle", it.subtitle)
                .put("minPlayers", it.minPlayers).put("emoji", it.emoji).put("assets", JSONArray(it.webAssets))
        }))
        val me = pid?.let { players[it] }
        o.put("me", if (me != null) JSONObject().put("id", me.id).put("name", me.name) else JSONObject.NULL)
        session?.let { o.put("g", it.state(me?.id, isHost, now)) }
        return o
    }
}
