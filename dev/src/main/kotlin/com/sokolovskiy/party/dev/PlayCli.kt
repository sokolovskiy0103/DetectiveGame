package com.sokolovskiy.party.dev

import com.sokolovskiy.cases.CaseEngine
import org.json.JSONObject
import java.io.File

/**
 * A case played from the console, as the players see it: only [CaseEngine.publicState] and the answers to requests
 * get printed — never the rules, the questions' answers or the titles of locked documents. Used by the blind
 * playtester agent (`.claude/agents/case-playtester.md`), which must not read `case.json` itself.
 *
 * The state lives in `dev/build/play/<case>.json` between calls:
 *   gradle -q :dev:play --args="<case> start"                      new game: request types, open documents
 *   gradle -q :dev:play --args="<case> status"                     stage, question, open documents, score
 *   gradle -q :dev:play --args="<case> req <TYPE> '<who/what>' '<details>'"
 *   gradle -q :dev:play --args="<case> answer <QID> field='…' field='…'"
 *   gradle -q :dev:play --args="<case> hint <QID>"                  costs points, like in the app
 *   gradle -q :dev:play --args="<case> doc <ID>"                    an open document as plain text
 *   gradle -q :dev:play --args="<case> @dev/build/play/cmd.txt"     the same, one argument per line in the file
 *
 * Gradle re-encodes the console output to the system code page, so the same text also goes to
 * `dev/build/play/<case>.out.txt` in UTF-8 — read that file.
 */
fun main(argv: Array<String>) {
    if (argv.size < 2) return println("usage: <case> start|status|req|answer|hint …  or  <case> @<file>")
    val root = listOf(".", "..").map(::File).first { File(it, "cases").isDirectory }
    // `<case> @file`: the command one argument per line, in UTF-8 — no shell quoting, apostrophes are fine
    val args = if (!argv[1].startsWith("@")) argv
    else arrayOf(argv[0]) + File(root, argv[1].drop(1)).readLines().filter { it.isNotBlank() }.toTypedArray()
    val caseId = args[0]
    val caseDir = File(root, "cases/$caseId")
    val engine = CaseEngine(JSONObject(File(caseDir, "case.json").readText()))
    val saved = File(root, "dev/build/play/$caseId.json")
    if (args[1] != "start" && saved.exists()) engine.load(JSONObject(saved.readText()))
    val before = engine.unlockedIds()
    val out = StringBuilder()
    fun say(line: String = "") { out.append(line).append('\n') }
    say("> " + args.drop(1).joinToString(" | "))   // echoed back: shows the arguments arrived intact

    when (args[1]) {
        "start" -> {
            val s = engine.publicState()
            say("${s.getString("title")} — ${s.optString("subtitle")}")
            say("\nТипи запитів (тип: що вказати / деталі):")
            val types = s.getJSONObject("request_types")
            for (t in types.keys()) types.getJSONObject(t).let {
                say("  $t — ${it.optString("label")}: ${it.optString("target")} / ${it.optString("details")}")
            }
            if (File(caseDir, "rules.html").exists()) say("\nПравила гри: cases/$caseId/rules.html")
        }
        "status" -> {}
        "req" -> engine.request("гравець", args[2], args.getOrElse(3) { "" }, args.getOrElse(4) { "" }).let {
            say((if (it.ok) "✓ " else "✗ ") + it.text)
        }
        "answer" -> {
            val given = args.drop(3).associate { it.substringBefore('=') to it.substringAfter('=') }
            engine.answer("гравець", args[2], given).let {
                say((if (it.ok) "✓ " else "✗ ") + it.text)
                if (it.ok) say("\n=== НОВИЙ ЕТАП — нове питання й документи нижче ===")
            }
        }
        "doc" -> {
            val id = args[2]
            say(if (id in engine.unlockedIds()) htmlText(File(caseDir, "documents/$id.html").readText()) else "Документ $id ще не відкрито.")
            return File(saved.parentFile.apply { mkdirs() }, "$caseId.out.txt").writeText(out.toString())   // just the text, no status
        }
        "hint" -> {
            if (!engine.hint("гравець", args[2])) say("Підказок більше немає.")
            else say("Підказка: " + engine.publicState().getJSONObject("hints").getJSONArray(args[2]).let { it.getString(it.length() - 1) })
        }
        else -> return println("unknown command ${args[1]}")
    }

    val s = engine.publicState()
    val all = args[1] == "start" || args[1] == "status"
    val new = engine.unlockedIds() - before
    say("\nЕтап ${s.getInt("stage")}: ${s.optString("stage_name")}   бали: ${s.get("score")}" +
        ((s.opt("rank") as? String)?.let { "   ранг: $it" } ?: ""))
    say(if (all) "Відкриті документи:" else "Нові документи:")
    val docs = s.getJSONArray("documents")
    for (i in 0 until docs.length()) docs.getJSONObject(i).let { d ->
        val id = d.getString("id")
        if (all || id in new) say("  $id  ${d.optString("title")}  →  cases/$caseId/documents/$id.html")
    }
    s.optJSONObject("question")?.let { q ->
        say("\nПитання ${q.getString("id")}: ${q.getString("title")}\n  ${q.getString("intro")}")
        val f = q.getJSONArray("fields")
        for (i in 0 until f.length()) f.getJSONObject(i).let { say("  поле ${it.getString("id")}: ${it.optString("label")} (${it.optString("placeholder")})") }
        say("  спроб: ${q.getInt("attempts")}, підказок усього: ${q.getInt("hints_total")}")
    }
    saved.parentFile.mkdirs()
    saved.writeText(engine.state.toString())
    File(saved.parentFile, "$caseId.out.txt").writeText(out.toString())
    print(out)
}

/** A document as plain text: table rows stay rows (cells joined with " | "), pictures and audio become [markers]. */
private fun htmlText(html: String): String {
    var s = html.replace(Regex("(?is)<(style|script|head)\\b.*?</\\1>"), "")
    s = s.replace(Regex("(?is)<img\\b[^>]*?src=\"([^\"]*)\"[^>]*>")) { m ->
        val alt = Regex("(?i)alt=\"([^\"]*)\"").find(m.value)?.groupValues?.get(1).orEmpty()
        "\n[фото: ${m.groupValues[1].substringAfterLast('/')}${if (alt.isNotBlank()) " — $alt" else ""}]\n"
    }
    s = s.replace(Regex("(?is)<(audio|source)\\b[^>]*?src=\"([^\"]*)\"[^>]*>")) { "\n[аудіо: ${it.groupValues[2].substringAfterLast('/')}]\n" }
    s = s.replace(Regex("(?is)<tr\\b.*?</tr>")) { it.value.replace(Regex("\\s+"), " ") }   // one table row — one line
    s = s.replace(Regex("(?i)</t[dh]>"), " | ")
        .replace(Regex("(?i)<br\\s*/?>|</(p|div|tr|li|h[1-6]|table|section|article|header|footer|blockquote)>"), "\n")
        .replace(Regex("(?s)<[^>]+>"), "")
    s = s.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
        .replace(Regex("&#(\\d+);")) { it.groupValues[1].toInt().toChar().toString() }
        .replace("&amp;", "&")
    return s.lines().map { it.replace(Regex("[ \\t]+"), " ").trim() }
        .joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()
}
