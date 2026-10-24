package android.content

import android.content.res.AssetManager

interface SharedPreferences {
    fun getBoolean(key: String, def: Boolean): Boolean
    fun getString(key: String, def: String?): String?
    fun getLong(key: String, def: Long): Long
    fun edit(): Editor

    interface Editor {
        fun putBoolean(key: String, v: Boolean): Editor
        fun putString(key: String, v: String?): Editor
        fun putLong(key: String, v: Long): Editor
        fun apply()
    }
}

private class MemPrefs : SharedPreferences {
    private val m = HashMap<String, Any?>()
    override fun getBoolean(key: String, def: Boolean) = m[key] as? Boolean ?: def
    override fun getString(key: String, def: String?) = m[key] as? String ?: def
    override fun getLong(key: String, def: Long) = m[key] as? Long ?: def
    override fun edit() = object : SharedPreferences.Editor {
        override fun putBoolean(key: String, v: Boolean) = apply { m[key] = v }
        override fun putString(key: String, v: String?) = apply { m[key] = v }
        override fun putLong(key: String, v: Long) = apply { m[key] = v }
        override fun apply() {}
    }
}

/** Dev stand-in: assets are read live from [assetsDir], so JS/CSS edits only need a page reload. */
class Context(vararg assetsDirs: java.io.File) {
    val assets = AssetManager(assetsDirs.toList())
    val filesDir: java.io.File = java.io.File(System.getProperty("java.io.tmpdir"), "detective-cases-dev").apply { mkdirs() }
    private val prefs = HashMap<String, SharedPreferences>()
    fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs.getOrPut(name) { MemPrefs() }

    companion object { const val MODE_PRIVATE = 0 }
}
