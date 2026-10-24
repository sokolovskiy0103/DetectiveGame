package com.sokolovskiy.detective

import com.sokolovskiy.cases.CaseModule
import android.content.Context
import com.sokolovskiy.cases.SemanticHost
import com.sokolovskiy.party.core.AppConfig
import com.sokolovskiy.party.core.Hub
import org.json.JSONArray

/** The lobby lists every case the build bundled (`data/cases/index.json`, written from `cases/<id>/case.json`). */
/** [semantic] builds the paraphrase model of the app (none in tests; the desktop launcher brings its own). */
fun detectiveConfig(semantic: (Context, Hub) -> SemanticHost? = { _, _ -> null }) = AppConfig(
    name = "Детективні справи",
    emoji = "🕵️",
    pickerTitle = "Яку справу розслідуємо?",
    repo = "sokolovskiy0103/DetectiveGame",
    apkName = "DetectiveCases.apk",
    modules = { context, hub ->
        val host = semantic(context, hub)
        val index = JSONArray(context.assets.open("data/cases/index.json").bufferedReader(Charsets.UTF_8).use { it.readText() })
        List(index.length()) {
            val c = index.getJSONObject(it)
            CaseModule(c.getString("id"), c.getString("title"), c.optString("card"), c.optString("emoji"), host)
        }
    },
)
