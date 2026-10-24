package com.sokolovskiy.detective

import com.sokolovskiy.cases.CaseModule
import com.sokolovskiy.party.core.AppConfig

/** The cases this app hosts. A new case = its content folder (see CaseModule) + one line here. */
fun detectiveConfig() = AppConfig(
    name = "Детективні справи",
    emoji = "🕵️",
    pickerTitle = "Яку справу розслідуємо?",
    repo = "sokolovskiy0103/DetectiveGame",
    apkName = "DetectiveCases.apk",
    modules = { _, _ ->
        listOf(
            CaseModule("stairs", "Справа «Сходи»", "Кооперативний детектив, 1,5–2 год", "🪜"),
        )
    },
)
