package app.zmby.aggregate

import app.zmby.data.QualityPreference
import app.zmby.data.ServerItem
import app.zmby.emby.BaseItemDto
import app.zmby.emby.MediaSourceInfo
import app.zmby.emby.MediaStream
import app.zmby.emby.UserItemData
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class VersionRankerTest {
    private fun src(id: String, w: Int, name: String, iso: Boolean = false, ext: String? = null, bitrate: Long = 1) = MediaSourceInfo(
        id = id, name = name, path = "/m/$name", container = if (iso) "iso" else "mkv", bitrate = bitrate,
        mediaStreams = if (iso) emptyList() else listOf(MediaStream(0, "Video", width = w, height = w * 9 / 16, extendedVideoType = ext)),
    )

    private val nas = VersionFacts("nas", 0, true, 6, src("n1", 1920, "Film.1080p.mkv"))
    private val friend = VersionFacts("friend", 1, true, 42, src("f1", 3840, "Film.2160p.REMUX.mkv", ext = "DolbyVision"))
    private val friendWeb = VersionFacts("friend", 1, true, 42, src("f2", 3840, "Film.2160p.WEB-DL.mkv"))
    private val slow = VersionFacts("slow", 2, false, null, src("s1", 3840, "Film.2160p.iso", iso = true))

    @Test fun `server priority beats quality by default`() {
        assertEquals("n1", VersionRanker.best(listOf(friend, nas, slow), VersionPrefs())!!.source.id)
    }

    @Test fun `manual pick wins, then the pattern, then the quality preference`() {
        assertEquals("f2", VersionRanker.best(listOf(nas, friend, friendWeb), VersionPrefs(manual = "friend" to "f2"))!!.source.id)
        assertEquals("f1", VersionRanker.best(listOf(nas, friend, friendWeb), VersionPrefs(pattern = VersionPrefs.pattern("2160p.*REMUX")))!!.source.id)
        assertEquals("f1", VersionRanker.best(listOf(nas, friendWeb, friend), VersionPrefs(quality = QualityPreference.UHD))!!.source.id)
    }

    @Test fun `within a server quality and HDR decide, disc images last`() {
        val iso = VersionFacts("friend", 1, true, 42, src("f3", 3840, "Film.2160p.iso", iso = true, bitrate = 99))
        assertEquals("f1", VersionRanker.best(listOf(iso, friendWeb, friend), VersionPrefs())!!.source.id)
    }

    @Test fun `an invalid pattern is ignored`() {
        assertEquals(null, VersionPrefs.pattern("2160p.*(REMUX"))
    }
}

class HomeMergeTest {
    private fun ref(server: String, id: String, type: String, name: String, played: String, tmdb: String? = null, series: String? = null, s: Int? = null, e: Int? = null) =
        ServerItem(server, BaseItemDto(id = id, name = name, type = type, seriesName = series, seriesId = series?.let { "$server-$it" }, parentIndexNumber = s, indexNumber = e,
            providerIds = tmdb?.let { mapOf("Tmdb" to it) }.orEmpty(), userData = UserItemData(lastPlayedDate = played)))

    @Test fun `resume keeps the most recent copy of each work`() = runBlocking {
        val hits = listOf(
            Hit(ref("a", "1", "Movie", "沙丘2", "2026-10-20", tmdb = "693134"), 0),
            Hit(ref("b", "9", "Movie", "沙丘：第二部", "2026-10-22", tmdb = "693134"), 1),
            Hit(ref("a", "2", "Movie", "无 ID 的片", "2026-10-21"), 0),
            Hit(ref("b", "3", "Movie", "无 ID 的片", "2026-10-19"), 1),
        )
        val out = HomeMerge.resume(hits) { null }
        // The ID-less pair is not deduplicated: a wrong merge would hide a resume point.
        assertEquals(listOf("b:9", "a:2", "b:3"), out.map { it.key })
    }

    @Test fun `episodes look their series up only when two servers could collide`() = runBlocking {
        val asked = mutableListOf<String>()
        val hits = listOf(
            Hit(ref("a", "e1", "Episode", "第 7 集", "2026-10-23", series = "漫长的季节", s = 1, e = 7), 0),
            Hit(ref("b", "e2", "Episode", "第 7 集", "2026-10-22", series = "漫长的季节", s = 1, e = 7), 1),
            Hit(ref("a", "e3", "Episode", "第 3 集", "2026-10-21", series = "三体", s = 1, e = 3), 0),
        )
        val out = HomeMerge.resume(hits) { h -> asked += h.server; "series:tmdb:1" }
        assertEquals(listOf("a:e1", "a:e3"), out.map { it.key })
        assertEquals(setOf("a", "b"), asked.toSet())
    }

    @Test fun `latest merges strong ids and counts the servers`() {
        val a = ServerItem("a", BaseItemDto(id = "1", name = "X", type = "Movie", providerIds = mapOf("Tmdb" to "5"), dateCreated = "2026-10-01"))
        val b = ServerItem("b", BaseItemDto(id = "2", name = "X 译名", type = "Movie", providerIds = mapOf("Tmdb" to "5"), dateCreated = "2026-10-03"))
        val c = ServerItem("b", BaseItemDto(id = "3", name = "Y", type = "Movie", dateCreated = "2026-10-02"))
        val (rows, copies) = HomeMerge.latest(listOf(Hit(a, 0), Hit(b, 1), Hit(c, 1, 1)))
        assertEquals(listOf("a:1", "b:3"), rows.map { it.key })
        assertEquals(2, copies["a:1"])
    }
}
