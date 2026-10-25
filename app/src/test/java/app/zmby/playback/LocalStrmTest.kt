package app.zmby.playback

import app.zmby.emby.MediaStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class LocalStrmTest {
    @get:Rule val tmp = TemporaryFolder()

    private val srt = "1\n00:00:01,000 --> 00:00:04,000\n外挂中文\n\n".toByteArray()
    private val stream = MediaStream(6, type = "Subtitle", codec = "subrip", language = "chi", isExternal = true)

    @Test
    fun pidIsTheLowercaseMd5OfTheBytes() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", LocalStrm.subtitlePid(ByteArray(0)))
        assertEquals("900150983cd24fb0d6963f7d28e17f72", LocalStrm.subtitlePid("abc".toByteArray()))
    }

    @Test
    fun namesShareTheStrmBaseNameAndKeepTheRealExtension() {
        val key = LocalStrm.sourceKey("server", "item", "source")
        assertEquals(16, key.length)
        assertEquals(key, LocalStrm.sourceKey("server", "item", "source"))
        assertFalse(key == LocalStrm.sourceKey("server", "item", "other"))
        val base = LocalStrm.baseName(key, 1_761_300_000_000L, 0xbeef)
        assertEquals("zmby_${key}_19a15a92d00beef", base)
        assertEquals("$base.strm", LocalStrm.strmName(base))
        assertEquals("$base.chi.6.srt", LocalStrm.subtitleName(base, stream))
        assertEquals("$base.und.3.ass", LocalStrm.subtitleName(base, MediaStream(3, type = "Subtitle", codec = "ass", language = "", isExternal = true)))
        assertEquals("$base.x.3.ass", LocalStrm.subtitleName(base, MediaStream(3, type = "Subtitle", codec = "ass", language = "../x", isExternal = true)))
        // Fixed length, so one session's base name is never a prefix of another's.
        assertEquals(base.length, LocalStrm.baseName(key, 1L, 0).length)
    }

    @Test
    fun strmIsOneUtf8LineWithoutBom() {
        assertEquals("http://h/v?api_key=t\n", String(LocalStrm.strmContent("http://h/v?api_key=t")))
        for (bad in listOf("file:///x", "http://h/a\nhttp://evil", "")) {
            try { LocalStrm.strmContent(bad); fail(bad) } catch (_: IllegalArgumentException) {}
        }
    }

    @Test
    fun onlyExternalTextSubtitlesAreSupported() {
        assertTrue(LocalStrm.supports(stream))
        assertFalse(LocalStrm.supports(stream.copy(isExternal = false)))
        assertFalse(LocalStrm.supports(stream.copy(codec = "pgssub")))
    }

    @Test
    fun rejectsWebPagesAndWrongFormats() {
        assertNull(LocalStrm.problem(srt, "srt"))
        assertNull(LocalStrm.problem("﻿[Script Info]\n[Events]\nDialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,x".toByteArray(), "ass"))
        assertNotNull(LocalStrm.problem(ByteArray(0), "srt"))
        assertNotNull(LocalStrm.problem("<!DOCTYPE html><html><body>login</body></html>".toByteArray(), "srt"))
        assertNotNull(LocalStrm.problem("just text".toByteArray(), "srt"))
    }

    @Test
    fun preparesSubtitleThenStrmUnderAFreshNameAndCleansUp() = runTest {
        val root = tmp.root.resolve("zmby-playback")
        val preparer = LocalStrmPreparer(DirectoryPlaybackStore(root), { srt })
        val a = preparer.prepare("http://h/v", "k", stream, "http://h/s.srt")
        val b = preparer.prepare("http://h/v", "k", stream, "http://h/s.srt")
        assertFalse(a.baseName == b.baseName)
        assertEquals("http://h/v\n", a.strm.readText())
        assertEquals("${a.baseName}.chi.6.srt", a.subtitle.name)
        assertEquals(a.strm.parentFile, a.subtitle.parentFile)
        assertEquals(LocalStrm.subtitlePid(srt), a.subtitlePid)
        assertEquals("file://${a.strm.absolutePath}", a.uri)
        preparer.release(a)
        assertFalse(a.strm.exists() || a.subtitle.exists())
        assertTrue(b.strm.exists() && b.subtitle.exists())
        assertEquals(setOf(b.strm.name, b.subtitle.name), root.list()!!.toSet())
    }

    @Test
    fun failedDownloadOrBadContentLeavesNothingBehind() = runTest {
        val root = tmp.newFolder("zmby-playback")
        for (download in listOf<suspend (String) -> ByteArray>({ throw IOException("HTTP 401") }, { "<html><body>401</body></html>".toByteArray() })) {
            try {
                LocalStrmPreparer(DirectoryPlaybackStore(root), download).prepare("http://h/v", "k", stream, "http://h/s.srt")
                fail()
            } catch (_: SubtitlePrepareException) {}
        }
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun sweepRemovesOnlyOldFilesOfOurs() {
        val now = 1_000_000_000_000L
        val root = tmp.newFolder("p")
        val store = DirectoryPlaybackStore(root, now = { now })
        fun file(name: String, age: Long) = File(root, name).apply { writeText("x"); setLastModified(now - age) }
        val old = file("zmby_a.strm", 13 * 3600_000L)
        val kept = file("zmby_b.strm", 13 * 3600_000L)
        val fresh = file("zmby_c.strm", 0)
        val foreign = file("movie.srt", 13 * 3600_000L)
        assertEquals(1, store.sweep(12 * 3600_000L, keep = setOf(kept.name)))
        assertFalse(old.exists())
        assertTrue(kept.exists() && fresh.exists() && foreign.exists())
    }
}
