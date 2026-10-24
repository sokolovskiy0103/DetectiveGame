package com.sokolovskiy.party.dev

import android.content.Context
import com.sokolovskiy.cases.OnnxJudge
import com.sokolovskiy.cases.SemanticHost
import com.sokolovskiy.cases.SemanticJudge
import com.sokolovskiy.detective.detectiveConfig
import com.sokolovskiy.party.core.Hub
import com.sokolovskiy.party.core.PartyApp
import java.awt.Desktop
import java.io.File

/**
 * Runs the real Hub + LocalServer on the desktop. Assets are served straight from
 * app/src/main/assets, so web edits show up on reload.
 *
 * Also writes and opens a grid page: the host plus N-1 players side by side, each already joined.
 * Every pane uses its own loopback address (127.0.0.1, 127.0.0.2, ...) so each has its own
 * localStorage, i.e. its own player identity. Usage: `gradle :dev:run --args="4"` (panes, default 4).
 */
fun main(args: Array<String>) {
    val panes = (args.firstOrNull()?.toIntOrNull() ?: 4).coerceIn(1, 9)
    val root = listOf(".", "..").map(::File).first { File(it, "core/src/main/assets").isDirectory }
    val assets = listOf("core/src/main/assets", "casekit/src/main/assets", "hub/src/main/assets", "hub/build/generated/caseAssets")
        .map { File(root, it) }.toTypedArray()
    // with the model in dev/models (embed.onnx + embed.vocab) the paraphrase judge runs here too
    val models = File(root, "dev/models")
    PartyApp.config = detectiveConfig { _, _ ->
        if (!OnnxJudge.present(models)) null else object : SemanticHost {
            override val judge: SemanticJudge? = OnnxJudge.load(models)
            override fun status() = org.json.JSONObject().put("enabled", true).put("state", "READY").put("progress", 1).put("message", "").put("sizeMb", 152)
            override fun setEnabled(on: Boolean) {}
        }
    }
    val hub = Hub(Context(*assets))
    hub.start()
    val port = hub.local.port

    val names = listOf("Хост", "Аня", "Богдан", "Віка", "Гліб", "Дана", "Едик", "Жанна", "Ія")
    val frames = (0 until panes).joinToString("\n") { i ->
        val ip = "127.0.0.${i + 1}"
        val url = if (i == 0) "http://$ip:$port/host?autojoin=${names[0]}#${hub.hostToken}"
        else "http://$ip:$port/?autojoin=${names[i]}"
        """<iframe src="$url" title="${names[i]}"></iframe>"""
    }
    val cols = if (panes <= 1) 1 else if (panes <= 4) 2 else 3
    val rows = (panes + cols - 1) / cols
    val page = File(System.getProperty("java.io.tmpdir"), "party-dev-grid.html")
    page.writeText(
        """<!doctype html><meta charset="utf-8"><title>Party dev</title>
<style>html,body{margin:0;height:100%;background:#222}
body{display:grid;grid-template-columns:repeat($cols,1fr);grid-template-rows:repeat($rows,1fr);gap:4px}
iframe{width:100%;height:100%;border:0;background:#0e1320;min-height:0}</style>
$frames""",
    )

    println()
    println("Grid   : ${page.toURI()}")
    println("Host   : http://127.0.0.1:$port/host#${hub.hostToken}")
    println("Players: http://127.0.0.1:$port/   (phones on the same Wi-Fi: use your PC's LAN IP)")
    println("Ctrl+C to stop.")
    try {
        if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(page.toURI())
    } catch (t: Throwable) {
        println("Could not open the browser automatically: ${t.message}")
    }
    Thread.currentThread().join()
}
