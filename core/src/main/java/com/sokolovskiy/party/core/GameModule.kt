package com.sokolovskiy.party.core

import org.json.JSONObject

/**
 * A game in the collection. Modules are created once per app run; a [GameSession] is created
 * every time the host picks the game in the room and dropped when they go back to the menu.
 */
interface GameModule {
    /** Stable id, also the folder of the web module (`assets/web/games/<id>/`). */
    val id: String
    val title: String
    val subtitle: String
    val minPlayers: Int
    val emoji: String

    /**
     * Shared web code this game needs besides its own `games/<id>/game.js` and `game.css`, as paths under `assets/web`
     * (`kit/case.js`, `kit/case.css`). The shell loads each file once, before the game's own code.
     */
    val webAssets: List<String> get() = emptyList()

    fun create(env: GameEnv): GameSession

    /** Extra host-only status for the menu (e.g. a model download). */
    fun hostStatus(): JSONObject? = null

    /** Module-wide host commands that work outside a session (e.g. toggling a model). */
    fun handleHost(msg: JSONObject) {}
}

/** What a session may use from its surroundings. */
interface GameEnv {
    val room: Room

    /** Reads a text file from `assets/data` (word banks and other content the server keeps to itself). */
    fun asset(name: String): String

    /** Same as [asset] for binary content (documents, pictures). */
    fun assetBytes(name: String): ByteArray

    /** Saved game data that outlives the app (`key`: letters, digits, `-`, `_`). Null if there is none. */
    fun readState(key: String): String?

    fun writeState(key: String, text: String)

    /** Runs [block] under the room lock (for code running outside a message, e.g. async jobs). */
    fun <T> locked(block: () -> T): T

    /** Ask for a state push to everyone (after async changes). */
    fun changed()
}

/**
 * One running game. All calls happen under the room lock, so implementations need no
 * synchronisation of their own. Player identity (id, name, online) belongs to the [Room];
 * sessions keep only game data (score, role, answers…) keyed by player id.
 */
interface GameSession {
    fun onJoin(id: String, name: String)
    fun onLeave(id: String)

    /** A game message from a player ([pid] is null for a host that does not play). */
    fun handle(pid: String?, isHost: Boolean, msg: JSONObject, now: Long)

    /** Snapshot for one viewer. Must only contain what that viewer may see. */
    fun state(pid: String?, isHost: Boolean, now: Long): JSONObject

    /** Timers. Return true if something changed and everyone needs a new state. */
    fun tick(now: Long): Boolean = false

    /**
     * A file the page may load from `/static/g/<path>` only while the game allows it (documents that are still
     * locked, hidden pictures). Returns the bytes and MIME type, or null for "not found / not yet". Not per viewer.
     */
    fun resource(path: String): Pair<ByteArray, String>? = null

    fun close() {}
}
