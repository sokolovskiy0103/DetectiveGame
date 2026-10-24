package com.sokolovskiy.party.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Self-update from GitHub Releases: check the latest release, download its APK and
 * hand it to the system installer. Every build is signed with the same key, so this is
 * an in-place update that keeps the app's data.
 *
 * On Android 12+ the update goes through without a confirmation dialog once this app
 * is its own "installer of record" — i.e. from the second self-update on.
 */
object Updater {

    fun installedVersion(context: Context): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    }

    fun installedName(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"

    /** Blocking. The newest release, or null when GitHub has none with an APK. */
    @Throws(IOException::class)
    fun fetchLatest(): Release? {
        val c = open(Release.LATEST_URL)
        c.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            if (c.responseCode == 404) return null
            if (c.responseCode != 200) throw IOException("GitHub: HTTP ${c.responseCode}")
            val text = c.inputStream.bufferedReader().use { it.readText() }
            return Release.parse(JSONObject(text))
        } finally {
            c.disconnect()
        }
    }

    /** Blocking. Downloads the APK to the cache; [progress] gets 0..1 (or -1 if size unknown). */
    @Throws(IOException::class)
    fun download(context: Context, release: Release, cancelled: () -> Boolean, progress: (Float) -> Unit): File {
        val dir = File(context.cacheDir, "update").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "update-${release.number}.apk")
        val c = open(release.apkUrl) // GitHub redirects to its CDN; HttpURLConnection follows https→https
        try {
            if (c.responseCode != 200) throw IOException("Завантаження: HTTP ${c.responseCode}")
            val total = c.contentLengthLong.takeIf { it > 0 } ?: release.size
            var done = 0L
            var lastPct = -1
            c.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (cancelled()) throw IOException("cancelled")
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val pct = if (total > 0) (done * 100 / total).toInt() else -1
                        if (pct != lastPct) { lastPct = pct; progress(if (total > 0) done.toFloat() / total else -1f) }
                    }
                }
            }
            if (total > 0 && done != total) throw IOException("Файл завантажився не повністю")
            return file
        } catch (e: IOException) {
            file.delete()
            throw e
        } finally {
            c.disconnect()
        }
    }

    /**
     * Opens the APK in the regular system installer. (The PackageInstaller session path was dropped: vendor
     * installers abort it.) No result callback; MY_PACKAGE_REPLACED fires on success.
     */
    fun installViaSystemUi(activity: Activity, apk: File) {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.update", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        activity.startActivity(intent)
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "PartyHost-Android")
        }
}
