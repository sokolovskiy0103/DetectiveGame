package com.sokolovskiy.party.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** One client socket, local or relayed. The hub pushes full state snapshots into [outbox]. */
class Conn(val isHost: Boolean) {
    val outbox = Channel<String>(Channel.CONFLATED)
    @Volatile var playerId: String? = null
}

/**
 * Ties everything together: the [Room], the transports (local Ktor server, Render relay) and timers.
 * Every room access goes through [lock].
 */
class Hub(private val context: Context) : GameEnv {

    val hostToken: String = UUID.randomUUID().toString().replace("-", "")
    val settings = Settings(context)

    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val conns = ConcurrentHashMap.newKeySet<Conn>()

    val modules: List<GameModule> = PartyApp.config.modules(context, this)
    override val room = Room(modules)

    val local = LocalServer(context, this)
    val relay = RelayClient(context, this)

    fun start() {
        local.start()
        if (settings.relayEnabled) relay.start()
        scope.launch {
            while (isActive) {
                delay(250)
                val changed = synchronized(lock) { room.tick(System.currentTimeMillis()) }
                if (changed) broadcast()
            }
        }
    }

    fun stop() {
        scope.cancel()
        relay.stop()
        local.stop()
        synchronized(lock) { room.exitGame() }
    }

    // ---------- connections ----------

    fun attach(c: Conn) {
        conns += c
        sendState(c)
    }

    fun detach(c: Conn) {
        conns -= c
        c.playerId?.let { pid ->
            synchronized(lock) {
                if (conns.none { it.playerId == pid }) room.players[pid]?.connected = false
            }
        }
        broadcast()
    }

    fun handle(c: Conn, text: String) {
        val m = try { JSONObject(text) } catch (_: Exception) { return }
        val now = System.currentTimeMillis()
        synchronized(lock) {
            when (m.optString("t")) {
                "join" -> {
                    val p = room.join(m.optString("name"), m.optString("pid").ifEmpty { null })
                    p.connected = true
                    c.playerId = p.id
                    c.outbox.trySend(JSONObject().put("t", "welcome").put("pid", p.id).toString())
                }
                "resume" -> {
                    val p = room.players[m.optString("pid")]
                    if (p != null) {
                        p.connected = true
                        c.playerId = p.id
                    } else {
                        c.outbox.trySend(JSONObject().put("t", "unknown").toString())
                    }
                }
                "leave" -> c.playerId?.let { room.remove(it); c.playerId = null }
                "g" -> room.session?.handle(c.playerId, c.isHost, m.optJSONObject("d") ?: JSONObject(), now)
            }
            if (c.isHost) when (m.optString("t")) {
                "kick" -> room.remove(m.optString("pid"))
                "select" -> try { room.select(m.optString("game"), this) } catch (t: Throwable) { Log.e(TAG, "game failed to start", t) }
                "exitGame" -> room.exitGame()
                "mod" -> modules.firstOrNull { it.id == m.optString("game") }?.handleHost(m.optJSONObject("d") ?: JSONObject())
                "relay" -> {
                    val on = m.optBoolean("on")
                    settings.relayEnabled = on
                    if (on) relay.start() else relay.stop()
                }
                "snd" -> {
                    if (m.has("music")) settings.music = m.optBoolean("music")
                    if (m.has("sfx")) settings.sfx = m.optBoolean("sfx")
                }
                "relayUrl" -> {
                    val url = m.optString("url").trim().trimEnd('/')
                    if (url.startsWith("https://") || url.startsWith("http://")) {
                        settings.relayUrl = url
                        settings.relayRoom = null
                        if (settings.relayEnabled) { relay.stop(); relay.start() }
                    }
                }
            }
        }
        broadcast()
    }

    // ---------- state ----------

    fun broadcast() {
        for (c in conns) sendState(c)
    }

    private fun sendState(c: Conn) {
        val json = try {
            synchronized(lock) {
                val now = System.currentTimeMillis()
                val o = room.stateJson(c.playerId, c.isHost, now)
                PartyApp.config.let { o.put("app", JSONObject().put("name", it.name).put("emoji", it.emoji).put("pick", it.pickerTitle)) }
                if (c.isHost) o.put("snd", JSONObject().put("music", settings.music).put("sfx", settings.sfx))
                if (c.isHost) {
                    o.put("net", netJson()).put("mods", modsJson())
                }
                o.toString()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "state failed", t); return
        }
        c.outbox.trySend(json)
    }

    private fun netJson() = JSONObject()
        .put("local", JSONArray(NetUtil.lanAddresses().map {
            JSONObject().put("url", "http://${it.ip}:${local.port}/").put("hotspot", it.isHotspot)
        }))
        .put("relay", relay.statusJson())

    private fun modsJson() = JSONObject().apply {
        modules.forEach { m -> m.hostStatus()?.let { put(m.id, it) } }
    }

    // ---------- GameEnv ----------

    override fun asset(name: String): String =
        context.assets.open("data/$name").bufferedReader().use { it.readText() }

    private val stateDir get() = java.io.File(context.filesDir, "state").apply { mkdirs() }

    override fun readState(key: String): String? =
        java.io.File(stateDir, "$key.json").takeIf { it.exists() && key.matches(KEY) }?.readText()

    override fun writeState(key: String, text: String) {
        if (!key.matches(KEY)) return
        // write-then-rename: a crash mid-save must not leave half a file
        val tmp = java.io.File(stateDir, "$key.json.tmp")
        tmp.writeText(text)
        tmp.renameTo(java.io.File(stateDir, "$key.json"))
    }

    override fun assetBytes(name: String): ByteArray = context.assets.open("data/$name").use { it.readBytes() }

    /** A file under `/static/`: the shared web assets, or (`g/…`) what the running game lets players see right now. */
    fun webAsset(name: String): Pair<ByteArray, String>? =
        if (!LocalServer.isSafePath(name)) null
        else if (name.startsWith("g/")) synchronized(lock) { room.session?.resource(name.removePrefix("g/")) }
        else LocalServer.readAsset(context, name)

    override fun <T> locked(block: () -> T): T = synchronized(lock) { block() }

    override fun changed() = broadcast()

    companion object {
        private const val TAG = "PartyHub"
        private val KEY = Regex("[A-Za-z0-9_-]+")
    }
}
