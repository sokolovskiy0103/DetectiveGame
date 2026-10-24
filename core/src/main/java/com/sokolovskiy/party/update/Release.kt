package com.sokolovskiy.party.update

import org.json.JSONObject

/** A published build on GitHub Releases. [number] is the CI run number = versionCode. */
data class Release(
    val number: Int,
    val name: String,
    val apkUrl: String,
    val size: Long,
    val notes: String,
) {
    companion object {
        val REPO get() = com.sokolovskiy.party.core.PartyApp.config.repo
        val LATEST_URL get() = "https://api.github.com/repos/$REPO/releases/latest"
        val APK_NAME get() = com.sokolovskiy.party.core.PartyApp.config.apkName

        /** "v1.0.42" → 42 (the last number in the tag). */
        fun tagNumber(tag: String): Int? =
            Regex("(\\d+)\\D*$").find(tag.trim())?.groupValues?.get(1)?.toIntOrNull()

        /** Parses GitHub's `releases/latest` response; null when it has no usable APK. */
        fun parse(json: JSONObject): Release? {
            if (json.optBoolean("draft") || json.optBoolean("prerelease")) return null
            val tag = json.optString("tag_name")
            val number = tagNumber(tag) ?: return null
            val assets = json.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                if (a.optString("name") != APK_NAME) continue
                val url = a.optString("browser_download_url")
                if (!url.startsWith("https://")) return null
                return Release(
                    number = number,
                    name = tag.removePrefix("v"),
                    apkUrl = url,
                    size = a.optLong("size"),
                    notes = cleanNotes(json.optString("body")),
                )
            }
            return null
        }

        /**
         * The release body is the commit message, then "---" and install boilerplate.
         * Keeps only the human part, without git trailers.
         */
        fun cleanNotes(body: String): String {
            val text = body.replace("\r\n", "\n").substringBefore("\n---").trim()
            val trailer = Regex("^(Co-Authored-By:|Claude-Session:|Signed-off-by:|Збірка з коміту |Завантаж .*\\.apk).*", RegexOption.IGNORE_CASE)
            val lines = text.lines().filterNot { trailer.matches(it.trim()) }
            return lines.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim().take(600)
        }
    }
}
