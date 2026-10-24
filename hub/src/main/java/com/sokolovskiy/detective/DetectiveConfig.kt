package com.sokolovskiy.detective

import com.sokolovskiy.cases.CaseModule
import com.sokolovskiy.party.core.AppConfig
import org.json.JSONArray

/** The lobby lists every case the build bundled (`data/cases/index.json`, written from `cases/<id>/case.json`). */
fun detectiveConfig() = AppConfig(
    name = "Детективні справи",
    emoji = "🕵️",
    pickerTitle = "Яку справу розслідуємо?",
    repo = "sokolovskiy0103/DetectiveGame",
    apkName = "DetectiveCases.apk",
    modules = { context, _ ->
        val index = JSONArray(context.assets.open("data/cases/index.json").bufferedReader(Charsets.UTF_8).use { it.readText() })
        List(index.length()) {
            val c = index.getJSONObject(it)
            CaseModule(c.getString("id"), c.getString("title"), c.optString("card"), c.optString("emoji"))
        }
    },
)
