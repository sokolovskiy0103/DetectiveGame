package com.sokolovskiy.party.core

import android.content.Context
import android.util.Log
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

/**
 * HTTP + WebSocket on this phone. The host's WebView always uses it (via 127.0.0.1);
 * players use it when they are on the same Wi-Fi / hotspot.
 */
class LocalServer(private val context: Context, private val hub: Hub) {

    var port = 0; private set
    private var server: EmbeddedServer<*, *>? = null

    fun start() {
        if (server != null) return
        port = NetUtil.freePort(8080)
        server = embeddedServer(CIO, port = port, host = "0.0.0.0") {
            install(WebSockets) {
                pingPeriod = 10.seconds
                timeout = 30.seconds
            }
            routing {
                get("/") { serve(call, "index.html") }
                get("/host") { serve(call, "index.html") }
                get("/static/{path...}") { serve(call, call.parameters.getAll("path")?.joinToString("/") ?: "") }
                get("/qr.svg") {
                    val text = call.request.queryParameters["t"] ?: ""
                    call.respondText(NetUtil.qrSvg(text), ContentType.parse("image/svg+xml"))
                }
                webSocket("/ws") {
                    val conn = Conn(isHost = call.request.queryParameters["host"] == hub.hostToken)
                    val sender = launch { for (msg in conn.outbox) send(Frame.Text(msg)) }
                    hub.attach(conn)
                    try {
                        for (frame in incoming) if (frame is Frame.Text) hub.handle(conn, frame.readText())
                    } catch (t: Throwable) {
                        Log.d(TAG, "ws closed: ${t.message}")
                    } finally {
                        sender.cancel()
                        hub.detach(conn)
                    }
                }
            }
        }.also { it.start(wait = false) }
        Log.i(TAG, "Local server on port $port")
    }

    fun stop() {
        server?.stop(500, 1000)
        server = null
    }

    private suspend fun serve(call: ApplicationCall, name: String) {
        val a = hub.webAsset(name)
        if (a == null) call.respondText("Not found", status = HttpStatusCode.NotFound)
        else call.respondBytes(a.first, ContentType.parse(a.second))
    }

    companion object {
        private const val TAG = "PartyLocal"

        /** A path under `assets/web`: segments of letters, digits, `.`, `_`, `-`, none starting with a dot. */
        private val SAFE_PATH = Regex("""^[A-Za-z0-9_-][A-Za-z0-9._-]*(/[A-Za-z0-9_-][A-Za-z0-9._-]*)*$""")

        /** Web asset bytes and MIME type, or null. [name] is a path inside `assets/web`, e.g. `games/hat/game.js`. */
        fun readAsset(context: Context, name: String): Pair<ByteArray, String>? {
            if (!SAFE_PATH.matches(name)) return null
            val bytes = try {
                context.assets.open("web/$name").use { it.readBytes() }
            } catch (_: Exception) {
                return null
            }
            return bytes to mimeOf(name)
        }

        fun isSafePath(name: String) = SAFE_PATH.matches(name)

        fun mimeOf(name: String): String = when (name.substringAfterLast('.').lowercase()) {
            "html" -> "text/html; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "js" -> "application/javascript; charset=utf-8"
            "json" -> "application/json; charset=utf-8"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "mp3" -> "audio/mpeg"
            "webp" -> "image/webp"
            "webm" -> "video/webm"
            "mp4" -> "video/mp4"
            else -> "application/octet-stream"
        }
    }
}
