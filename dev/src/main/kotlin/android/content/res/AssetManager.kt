package android.content.res

import java.io.File
import java.io.InputStream

/** Dev stand-in: the app's asset folders are layered like Gradle merges them (the first folder that has the file wins). */
class AssetManager(private val roots: List<File>) {
    fun open(path: String): InputStream =
        (roots.map { File(it, path) }.firstOrNull { it.isFile } ?: throw java.io.FileNotFoundException(path)).inputStream()
}
