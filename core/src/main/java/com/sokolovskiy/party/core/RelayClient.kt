package com.sokolovskiy.party.core

import android.content.Context
import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Base64

/**
 * Connects this phone to the relay on Render. The relay only forwards bytes: players open
 * `<relay>/r/<CODE>/`, the relay fetches the web client from this phone and pipes each player's
 * WebSocket here, where it becomes an ordinary [Conn]. All game logic stays on the phone.
 *
 * Relay → host:  hello{code,key} · open{c} · m{c,d} · close{c} · asset{id,name}
 * Host → relay:  m{c,d} · asset{id,status,type,b64}
 */
class RelayClient(private val context: Context, private val hub: Hub) {

    enum class State { OFF, WAKING, CONNECTING, ONLINE, RETRY }

    @Volatile var state = State.OFF; private set
    @Volatile var message = ""; private set
    @Volatile var code: String? = null; private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val client by lazy {
        HttpClient(CIO) {
            install(WebSockets) { pingIntervalMillis = 20_000 }
            install(HttpTimeout)
            engine { requestTimeout = 0 }
        }
    }

    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { loop() }
    }

    @Synchronized
    fun stop() {
        val j = job ?: return
        job = null
        scope.launch {
            j.cancelAndJoin()
            if (job == null) { code = null; set(State.OFF, "") }
        }
    }

    fun statusJson(): JSONObject {
        val base = hub.settings.relayUrl
        val c = code
        return JSONObject()
            .put("enabled", hub.settings.relayEnabled)
            .put("state", state.name)
            .put("message", message)
            .put("url", base)
            .put("code", c ?: JSONObject.NULL)
            .put("join", if (c != null && state == State.ONLINE) "$base/r/$c/" else JSONObject.NULL)
    }

    private fun set(s: State, msg: String) {
        state = s; message = msg
        hub.broadcast()
    }

    private suspend fun loop() {
        var failures = 0
        while (currentCoroutineContext().isActive) {
            val base = hub.settings.relayUrl.trimEnd('/')
            try {
                // A sleeping free Render service needs up to a minute to wake: poke it over HTTP first.
                set(State.WAKING, "Будимо сервер (до хвилини)…")
                val r = client.get("$base/health") { timeout { requestTimeoutMillis = 120_000 } }
                if (!r.status.isSuccess()) error("сервер відповів ${r.status.value}")
                r.bodyAsText()
                set(State.CONNECTING, "Підключаюсь…")
                session(base)
                failures = 0
                set(State.RETRY, "Зв'язок перервався, перепідключаюсь…")
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                failures++
                Log.w(TAG, "relay failed", t)
                set(State.RETRY, "Немає зв'язку з сервером: ${t.message?.take(80) ?: t.javaClass.simpleName}")
            }
            delay((1000L * (1 shl failures.coerceAtMost(5))).coerceAtMost(30_000))
        }
    }

    private suspend fun session(base: String) {
        val saved = hub.settings.relayRoom?.split(':')
        val q = if (saved != null && saved.size == 2) "?code=${enc(saved[0])}&key=${enc(saved[1])}" else ""
        val wsUrl = base.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/host$q"

        client.webSocket(urlString = wsUrl) {
            val out = Channel<String>(Channel.UNLIMITED)
            val remotes = HashMap<Int, Pair<Conn, Job>>()
            val writer = launch { for (m in out) send(Frame.Text(m)) }
            try {
                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    val m = try { JSONObject(frame.readText()) } catch (_: Exception) { continue }
                    when (m.optString("t")) {
                        "hello" -> {
                            code = m.getString("code")
                            hub.settings.relayRoom = "${m.getString("code")}:${m.getString("key")}"
                            set(State.ONLINE, "Онлайн")
                        }
                        "open" -> {
                            val c = m.getInt("c")
                            val conn = Conn(isHost = false)
                            val fwd = launch {
                                for (msg in conn.outbox) out.send(JSONObject().put("t", "m").put("c", c).put("d", msg).toString())
                            }
                            remotes[c] = conn to fwd
                            hub.attach(conn)
                        }
                        "m" -> remotes[m.optInt("c")]?.let { hub.handle(it.first, m.optString("d")) }
                        "close" -> remotes.remove(m.optInt("c"))?.let { (conn, fwd) ->
                            fwd.cancel(); hub.detach(conn)
                        }
                        "asset" -> {
                            val a = hub.webAsset(m.optString("name"))
                            val reply = JSONObject().put("t", "asset").put("id", m.opt("id"))
                            if (a == null) reply.put("status", 404)
                            // base64 of a music track would overflow the relay's message limit and drop this socket
                            else if (a.first.size > MAX_ASSET) reply.put("status", 413)
                            else reply.put("status", 200).put("type", a.second)
                                .put("b64", Base64.getEncoder().encodeToString(a.first))
                            out.send(reply.toString())
                        }
                    }
                }
            } finally {
                remotes.values.forEach { (conn, fwd) -> fwd.cancel(); hub.detach(conn) }
                writer.cancel()
            }
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    companion object {
        private const val TAG = "PartyRelay"

        /** Largest asset sent through the relay: its base64 must fit the relay's 256 KB message limit. */
        private const val MAX_ASSET = 180 * 1024
    }
}
