package com.sokolovskiy.detective

import android.app.DownloadManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.util.Log
import com.sokolovskiy.cases.OnnxJudge
import com.sokolovskiy.cases.SemanticHost
import com.sokolovskiy.cases.SemanticJudge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

/**
 * The paraphrase model on this phone: the host turns it on once, the app downloads one archive (embed.onnx +
 * embed.vocab), unpacks it and loads it. Every failure leaves the game on plain keywords.
 */
class SemanticManager(private val context: Context, private val onChange: () -> Unit) : SemanticHost {

    enum class State { OFF, DOWNLOADING, LOADING, READY, ERROR }

    @Volatile private var state = State.OFF
    @Volatile private var progress = 0.0
    @Volatile private var message = ""
    @Volatile private var model: OnnxJudge? = null

    override val judge: SemanticJudge? get() = model

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dir: File get() = context.getExternalFilesDir(null) ?: context.filesDir
    private val modelDir: File get() = File(dir, MODEL_DIR)
    private val prefs get() = context.getSharedPreferences("semantic", Context.MODE_PRIVATE)
    private val enabled get() = prefs.getBoolean("enabled", false)

    init {
        if (enabled) setEnabled(true)
    }

    override fun status(): JSONObject = JSONObject().put("enabled", enabled).put("state", state.name)
        .put("progress", progress).put("message", message).put("sizeMb", SIZE_MB)

    /** First time, on Wi-Fi: download without asking (≈150 MB, once). A host who switched it off is not asked again. */
    override fun onCaseOpened() {
        if (prefs.contains("enabled") || state != State.OFF || !unmetered()) return
        setEnabled(true)
    }

    private fun unmetered(): Boolean = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
    } catch (_: Throwable) {
        false
    }

    override fun setEnabled(on: Boolean) {
        prefs.edit().putBoolean("enabled", on).apply()
        if (!on) {
            cancelDownload()
            model?.close(); model = null
            set(State.OFF, "")
        } else if (state == State.OFF || state == State.ERROR) {
            if (OnnxJudge.present(modelDir)) load() else download()
        }
    }

    private fun set(s: State, msg: String) {
        state = s; message = msg
        progress = if (s == State.READY) 1.0 else if (s == State.DOWNLOADING) progress else 0.0
        onChange()
    }

    private fun cancelDownload() {
        val id = prefs.getLong("download", -1)
        if (id >= 0) {
            (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).remove(id)
            prefs.edit().remove("download").apply()
        }
        File(dir, PART_NAME).delete()
    }

    private fun download() {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        File(dir, PART_NAME).delete()
        val req = DownloadManager.Request(Uri.parse(MODEL_URL))
            .setTitle("Модель розуміння запитів («Детективні справи»)")
            .setDescription("Один раз, ≈$SIZE_MB МБ")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationUri(Uri.fromFile(File(dir, PART_NAME)))
            .setAllowedOverMetered(true)
        val id = dm.enqueue(req)
        prefs.edit().putLong("download", id).apply()
        track(id)
    }

    private fun track(id: Long) {
        set(State.DOWNLOADING, "Завантаження…")
        scope.launch {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            while (enabled) {
                val c = dm.query(DownloadManager.Query().setFilterById(id))
                if (c == null || !c.moveToFirst()) {
                    c?.close(); prefs.edit().remove("download").apply()
                    set(State.ERROR, "Завантаження зникло, спробуйте ще раз"); return@launch
                }
                val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                c.close()
                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        prefs.edit().remove("download").apply()
                        set(State.LOADING, "Розпаковую…")
                        val problem = unpack(File(dir, PART_NAME))
                        dm.remove(id)
                        if (problem == null) load() else set(State.ERROR, problem)
                        return@launch
                    }
                    DownloadManager.STATUS_FAILED -> {
                        prefs.edit().remove("download").apply(); dm.remove(id)
                        set(State.ERROR, "Завантаження не вдалося (код $reason)"); return@launch
                    }
                    else -> {
                        val p = if (total > 0) done.toDouble() / total else 0.0
                        val msg = when (status) {
                            DownloadManager.STATUS_PAUSED -> "Пауза — чекаю на мережу"
                            DownloadManager.STATUS_PENDING -> "В черзі…"
                            else -> "Завантаження ${(p * 100).toInt()}%"
                        }
                        if (p != progress || msg != message) { progress = p; message = msg; onChange() }
                    }
                }
                delay(1000)
            }
        }
    }

    /** Extracts only the known files; returns null on success, otherwise a reason. */
    private fun unpack(zip: File): String? = try {
        if (!zip.exists()) {
            "Файл завантаження зник, спробуйте ще раз"
        } else {
            modelDir.deleteRecursively(); modelDir.mkdirs()
            ZipInputStream(zip.inputStream().buffered()).use { zin ->
                while (true) {
                    val e = zin.nextEntry ?: break
                    val name = File(e.name).name
                    if (name !in OnnxJudge.MODEL_FILES) continue
                    File(modelDir, name).outputStream().buffered().use { zin.copyTo(it) }
                }
            }
            zip.delete()
            if (OnnxJudge.present(modelDir)) null else "В архіві бракує файлів, спробуйте ще раз"
        }
    } catch (t: Throwable) {
        Log.w(TAG, "unpack failed", t)
        modelDir.deleteRecursively()
        "Не вдалося розпакувати (можливо, мало місця): ${t.message?.take(80)}"
    }

    private fun load() {
        set(State.LOADING, "Готую…")
        scope.launch {
            try {
                model?.close()
                model = OnnxJudge.load(modelDir)
                set(State.READY, "Готово")
            } catch (t: Throwable) {
                Log.w(TAG, "model load failed", t)
                modelDir.deleteRecursively()
                set(State.ERROR, "Не вдалося запустити: ${t.message?.take(100)}")
            }
        }
    }

    companion object {
        private const val TAG = "Semantic"
        private const val MODEL_DIR = "semantic-v1"
        private const val PART_NAME = "semantic-v1.zip.part"
        private const val SIZE_MB = 152
        const val MODEL_URL = "https://github.com/sokolovskiy0103/DetectiveGame/releases/download/semantic-v1/semantic-v1.zip"
    }
}
