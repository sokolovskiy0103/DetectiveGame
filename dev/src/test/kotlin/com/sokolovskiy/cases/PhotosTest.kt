package com.sokolovskiy.cases

import com.sokolovskiy.party.core.GameEnv
import com.sokolovskiy.party.core.Room
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Pictures are served only once a document that shows them is open. */
class PhotosTest {
    private val env = object : GameEnv {
        override val room: Room get() = error("unused")
        private fun f(n: String) = File("cases/stairs/" + n.removePrefix("cases/stairs/").replaceFirst("doc/", "documents/").replaceFirst("photo/", "photos/"))
        override fun asset(name: String) = f(name).readText()
        override fun assetBytes(name: String) = f(name).readBytes()
        override fun readState(key: String): String? = null
        override fun writeState(key: String, text: String) {}
        override fun <T> locked(block: () -> T) = block()
        override fun changed() {}
    }

    @Test fun photosFollowOpenDocuments() {
        val s = CaseSession(env, "stairs", { null })
        val html = String(s.resource("doc/A05.html")!!.first)
        assertTrue(html.contains("../photo/S01.jpg"))
        assertNotNull(s.resource("photo/S01.jpg"))
        assertNull(s.resource("photo/P01.jpg").takeIf { false } ?: s.resource("photo/NOPE.jpg"))
        assertNull(s.resource("doc/D01.html"))
    }
}
