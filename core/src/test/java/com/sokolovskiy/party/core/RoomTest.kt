package com.sokolovskiy.party.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomTest {

    /** Records what the room tells a session. */
    private class FakeSession : GameSession {
        val players = LinkedHashMap<String, String>()
        val handled = mutableListOf<Pair<String?, String>>()
        var closed = false
        override fun onJoin(id: String, name: String) { players[id] = name }
        override fun onLeave(id: String) { players.remove(id) }
        override fun handle(pid: String?, isHost: Boolean, msg: JSONObject, now: Long) { handled += pid to msg.optString("t") }
        override fun state(pid: String?, isHost: Boolean, now: Long) = JSONObject().put("for", pid ?: "host")
        var board: JSONObject? = null
        override fun board(now: Long) = board
        override fun close() { closed = true }
    }

    private class FakeModule(override val id: String) : GameModule {
        override val title = id
        override val subtitle = ""
        override val minPlayers = 2
        override val emoji = "🎲"
        val sessions = mutableListOf<FakeSession>()
        override fun create(env: GameEnv) = FakeSession().also { sessions += it }
    }

    private object Env : GameEnv {
        override val room: Room get() = error("unused")
        override fun asset(name: String) = ""
        override fun assetBytes(name: String) = ByteArray(0)
        override fun readState(key: String): String? = null
        override fun writeState(key: String, text: String) {}
        override fun <T> locked(block: () -> T) = block()
        override fun changed() {}
    }

    private val a = FakeModule("a")
    private val b = FakeModule("b")

    @Test fun playersCarryOverBetweenGames() {
        val room = Room(listOf(a, b))
        val p1 = room.join("Оля")
        val p2 = room.join("Петро")
        assertTrue(room.select("a", Env))
        assertEquals(listOf(p1.id, p2.id), a.sessions[0].players.keys.toList())

        room.select("b", Env)
        assertTrue("previous game closed", a.sessions[0].closed)
        assertEquals(2, b.sessions[0].players.size)
        assertEquals("b", room.gameId)
    }

    @Test fun joinAndLeaveReachTheRunningGame() {
        val room = Room(listOf(a))
        room.select("a", Env)
        val p = room.join("Оля")
        assertEquals("Оля", a.sessions[0].players[p.id])
        room.join("Ольга", p.id)          // rename keeps the id
        assertEquals("Ольга", a.sessions[0].players[p.id])
        room.remove(p.id)
        assertTrue(a.sessions[0].players.isEmpty())
    }

    @Test fun duplicateNamesGetSuffix() {
        val room = Room(listOf(a))
        room.join("Оля")
        assertEquals("оля 2", room.join("оля").name)
    }

    @Test fun exitGameClosesSession() {
        val room = Room(listOf(a))
        room.select("a", Env)
        room.exitGame()
        assertNull(room.gameId)
        assertTrue(a.sessions[0].closed)
        assertFalse(room.stateJson(null, true, 0).has("g"))
    }

    @Test fun unknownGameIsIgnored() {
        val room = Room(listOf(a))
        assertFalse(room.select("nope", Env))
        assertNull(room.session)
    }

    @Test fun stateIsPerViewer() {
        val room = Room(listOf(a))
        val p = room.join("Оля")
        room.select("a", Env)
        val s = room.stateJson(p.id, false, 5)
        assertEquals(p.id, s.getJSONObject("me").getString("id"))
        assertEquals(p.id, s.getJSONObject("g").getString("for"))
        assertEquals(1, s.getJSONArray("games").length())
    }

    @Test fun tvSeesTheBoardOnly() {
        val room = Room(listOf(a))
        val p = room.join("Оля")
        room.select("a", Env)

        val none = room.stateJson(p.id, isHost = true, now = 0, tv = true)
        assertTrue(none.getBoolean("tv"))
        assertFalse("a TV is never the host", none.getBoolean("isHost"))
        assertTrue(none.isNull("me"))
        assertFalse("no board → no game state at all", none.has("g"))

        a.sessions[0].board = JSONObject().put("public", 1)
        val tv = room.stateJson(null, isHost = false, now = 0, tv = true)
        assertEquals(1, tv.getJSONObject("g").getInt("public"))
        assertEquals(p.id, room.stateJson(p.id, false, 0).getJSONObject("g").getString("for"))
    }
}
