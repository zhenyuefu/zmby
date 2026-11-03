package app.zmby.aggregate

import app.zmby.data.BitrateOrder
import app.zmby.data.CodecAvoid
import app.zmby.data.HdrFormat
import app.zmby.data.ImaxMode
import app.zmby.data.QualityPreference
import app.zmby.data.RankOrder
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
        // 线路优先 (the default) keeps server priority before resolution unless lower ones are refused.
        assertEquals("n1", VersionRanker.best(listOf(nas, friendWeb, friend), VersionPrefs(quality = QualityPreference.UHD))!!.source.id)
        assertEquals("f1", VersionRanker.best(listOf(nas, friendWeb, friend), VersionPrefs(quality = QualityPreference.UHD, acceptLower = false))!!.source.id)
        assertEquals("f1", VersionRanker.best(listOf(nas, friendWeb, friend), VersionPrefs(quality = QualityPreference.UHD, order = RankOrder.Quality))!!.source.id)
    }

    @Test fun `quality first ranks picture before server priority`() {
        assertEquals("f1", VersionRanker.best(listOf(nas, friendWeb, friend), VersionPrefs(order = RankOrder.Quality))!!.source.id)
        // The HDR order is the user's: SDR first puts the plain WEB-DL ahead of Dolby Vision.
        val sdrFirst = listOf(HdrFormat.SDR) + HdrFormat.entries.filter { it != HdrFormat.SDR }
        assertEquals("f2", VersionRanker.best(listOf(friend, friendWeb), VersionPrefs(order = RankOrder.Quality, hdrOrder = sdrFirst))!!.source.id)
    }

    @Test fun `over the bitrate cap sorts last but stays`() {
        val big = VersionFacts("nas", 0, true, 6, src("big", 3840, "Film.2160p.mkv", bitrate = 98_000_000))
        val small = VersionFacts("friend", 1, true, 40, src("small", 3840, "Film.2160p.WEB.mkv", bitrate = 24_000_000))
        val ranked = VersionRanker.rank(listOf(big, small), VersionPrefs(bitrateCapMbps = 80)) { it }
        assertEquals(listOf("small", "big"), ranked.map { it.source.id })
        assertEquals(true, VersionRanker.marks(listOf(big, small), VersionPrefs(bitrateCapMbps = 80))["nas" to "big"]!!.overCap)
        // Unknown bitrates are never over the cap.
        assertEquals(false, VersionRanker.overCap(MediaSourceInfo("x"), 20))
    }

    @Test fun `same-tier bitrate follows the chosen direction with unknown last`() {
        val hi = VersionFacts("nas", 0, true, 6, src("hi", 3840, "a.mkv", bitrate = 60_000_000))
        val lo = VersionFacts("nas", 0, true, 6, src("lo", 3840, "b.mkv", bitrate = 20_000_000))
        val unknown = VersionFacts("nas", 0, true, 6, MediaSourceInfo("u", mediaStreams = listOf(MediaStream(0, "Video", width = 3840, height = 2160))))
        assertEquals(listOf("hi", "lo", "u"), VersionRanker.rank(listOf(unknown, lo, hi), VersionPrefs()) { it }.map { it.source.id })
        assertEquals(listOf("lo", "hi", "u"), VersionRanker.rank(listOf(unknown, hi, lo), VersionPrefs(bitrateOrder = BitrateOrder.Low)) { it }.map { it.source.id })
    }

    @Test fun `imax by name or by a taller frame next to a scope version`() {
        val named = MediaSourceInfo("n", name = "Film.2160p.IMAX.Enhanced.mkv")
        assertEquals(true, VersionRanker.imaxByName(named))
        assertEquals(false, VersionRanker.imaxByName(MediaSourceInfo("m", name = "Film.MAXIMAL.mkv")))
        fun shaped(id: String, w: Int, h: Int) = MediaSourceInfo(id, mediaStreams = listOf(MediaStream(0, "Video", width = w, height = h)))
        val tall = shaped("tall", 3840, 2160)
        val scope = shaped("scope", 3840, 1600)
        assertEquals(true, VersionRanker.imaxRule(listOf(tall, scope))(tall))
        assertEquals(false, VersionRanker.imaxRule(listOf(tall, scope))(scope))
        // Alone, a 16:9 file says nothing about IMAX.
        assertEquals(false, VersionRanker.imaxRule(listOf(tall))(tall))
        val facts = listOf(VersionFacts("a", 0, true, 1, scope), VersionFacts("b", 1, true, 1, tall))
        assertEquals("tall", VersionRanker.best(facts, VersionPrefs(imax = ImaxMode.Prefer, order = RankOrder.Quality))!!.source.id)
        assertEquals("scope", VersionRanker.best(facts, VersionPrefs(imax = ImaxMode.Avoid, order = RankOrder.Quality))!!.source.id)
    }

    @Test fun `an avoided codec sorts last within its tier`() {
        fun coded(id: String, codec: String) = VersionFacts("nas", 0, true, 1, MediaSourceInfo(id, mediaStreams = listOf(MediaStream(0, "Video", codec = codec, width = 3840, height = 2160))))
        val av1 = coded("av1", "av1")
        val hevc = coded("hevc", "hevc")
        assertEquals("hevc", VersionRanker.best(listOf(av1, hevc), VersionPrefs(codecAvoid = CodecAvoid.AV1))!!.source.id)
        assertEquals("av1", VersionRanker.best(listOf(av1, hevc), VersionPrefs())!!.source.id)
    }

    @Test fun `below the wanted resolution is marked only when lower ones are refused`() {
        val prefs = VersionPrefs(quality = QualityPreference.UHD, acceptLower = false)
        assertEquals(true, VersionRanker.marks(listOf(nas), prefs)["nas" to "n1"]!!.belowResolution)
        assertEquals(false, VersionRanker.marks(listOf(nas), prefs.copy(acceptLower = true))["nas" to "n1"]!!.belowResolution)
        assertEquals(false, VersionRanker.marks(listOf(friend), prefs)["friend" to "f1"]!!.belowResolution)
    }

    @Test fun `hdr order parses stored names and fills in the rest`() {
        assertEquals(listOf(HdrFormat.HLG, HdrFormat.SDR, HdrFormat.DolbyVision, HdrFormat.HDR10Plus, HdrFormat.HDR10), HdrFormat.parse("HLG,SDR,bogus,HLG"))
        assertEquals("DolbyVision,HDR10Plus,HDR10,HLG,SDR", HdrFormat.store(HdrFormat.parse("")))
    }

    @Test fun `within a server quality and HDR decide, disc images last`() {
        val iso = VersionFacts("friend", 1, true, 42, src("f3", 3840, "Film.2160p.iso", iso = true, bitrate = 99))
        assertEquals("f1", VersionRanker.best(listOf(iso, friendWeb, friend), VersionPrefs())!!.source.id)
    }

    @Test fun `an invalid pattern is ignored`() {
        assertEquals(null, VersionPrefs.pattern("2160p.*(REMUX"))
    }

    @Test fun `home source ranking uses detail preferences instead of response order`() {
        val sources = listOf(friendWeb.source, friend.source, nas.source)
        assertEquals("f1", VersionRanker.bestSource("nas", sources, VersionPrefs())?.id)
        assertEquals("n1", VersionRanker.bestSource("nas", sources, VersionPrefs(quality = QualityPreference.FHD))?.id)
        assertEquals("f2", VersionRanker.bestSource("nas", sources, VersionPrefs(pattern = VersionPrefs.pattern("WEB-DL")))?.id)
        assertEquals("n1", VersionRanker.bestSource("nas", sources, VersionPrefs(manual = "nas" to "n1"))?.id)
        // Source IDs from a different server must never be treated as this server's manual choice.
        assertEquals("f1", VersionRanker.bestSource("nas", sources, VersionPrefs(manual = "friend" to "n1"))?.id)
        assertEquals(null, VersionRanker.bestSource("nas", emptyList(), VersionPrefs()))
    }
}

class HomeMergeTest {
    private fun ref(server: String, id: String, type: String, name: String, played: String, tmdb: String? = null, series: String? = null, s: Int? = null, e: Int? = null) =
        ServerItem(server, BaseItemDto(id = id, name = name, type = type, seriesName = series, seriesId = series?.let { "$server-$it" }, parentIndexNumber = s, indexNumber = e,
            providerIds = tmdb?.let { mapOf("Tmdb" to it) }.orEmpty(), userData = UserItemData(lastPlayedDate = played)))

    @Test fun `latest merges strong ids and counts the servers`() {
        val a = ServerItem("a", BaseItemDto(id = "1", name = "X", type = "Movie", providerIds = mapOf("Tmdb" to "5"), dateCreated = "2026-10-01"))
        val b = ServerItem("b", BaseItemDto(id = "2", name = "X 译名", type = "Movie", providerIds = mapOf("Tmdb" to "5"), dateCreated = "2026-10-03"))
        val c = ServerItem("b", BaseItemDto(id = "3", name = "Y", type = "Movie", dateCreated = "2026-10-02"))
        val (rows, copies) = HomeMerge.latest(listOf(Hit(a, 0), Hit(b, 1), Hit(c, 1, 1)))
        assertEquals(listOf("a:1", "b:3"), rows.map { it.key })
        assertEquals(2, copies["a:1"])
    }
}
