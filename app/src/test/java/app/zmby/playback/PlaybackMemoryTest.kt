package app.zmby.playback

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.zmby.data.ServerItem
import app.zmby.emby.BaseItemDto
import app.zmby.emby.MediaSourceInfo
import app.zmby.emby.MediaStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class PlaybackMemoryTest {
    private val ref = ServerItem("home", BaseItemDto(id = "episode-7", type = "Episode"))
    private val source = MediaSourceInfo("remux", path = "https://example.invalid/video?token=private",
        mediaStreams = listOf(MediaStream(1, "Audio", codec = "aac", language = "eng", isDefault = true),
            MediaStream(2, "Audio", codec = "dts", language = "chi"),
            MediaStream(3, "Subtitle", codec = "srt", language = "chi")),
        defaultAudioStreamIndex = 1, defaultSubtitleStreamIndex = 3)
    private val prefs get() = ApplicationProvider.getApplicationContext<Application>().getSharedPreferences("playback-memory-test", Context.MODE_PRIVATE)
    private fun memory() = PlaybackMemory(prefs) { it() }
    private fun saved(server: String = ref.server, item: String = ref.item.id, at: Long = 100) =
        SavedPlayback(server, item, PlaybackSelection.capture(source, TrackChoice(2, -1))!!, at)

    @Before fun clear() { prefs.edit().clear().commit() }

    @Test fun persistsExactServerItemVersionAndTracksAcrossRecreationWithoutSavingUrls() {
        memory().remember(saved())
        val restored = memory()
        assertEquals(saved(), restored.latest(listOf(ref)))
        assertEquals(TrackChoice(2, -1), restored.tracksFor(ref, source))
        assertFalse(prefs.getString("selections", "")!!.contains("private"))
        assertNull(restored.latest(listOf(ref.copy(server = "other"))))
        assertNull(restored.latest(listOf(ref.copy(item = ref.item.copy(id = "episode-8")))))
    }

    @Test fun automaticTracksAreReevaluatedAndExplicitOffSurvivesChangedDefaults() {
        val defaults = PlaybackSelection.capture(source, TrackChoice())!!
        assertEquals(TrackChoice(), defaults.restore(source))
        val off = saved().selection
        assertEquals(TrackChoice(2, -1), off.restore(source.copy(defaultSubtitleStreamIndex = 3)))
        val silent = MediaSourceInfo("silent")
        assertEquals(TrackChoice(), PlaybackSelection.capture(silent, TrackChoice())!!.restore(silent))
    }

    @Test fun changedOrMissingVersionOrTrackRequiresDetails() {
        val pick = PlaybackSelection.capture(source, TrackChoice(2, 3))!!
        assertNull(pick.restore(source.copy(id = "web")))
        assertNull(pick.restore(source.copy(mediaStreams = source.mediaStreams.filter { it.index != 2 })))
        assertNull(pick.restore(source.copy(mediaStreams = source.mediaStreams.map { if (it.index == 3) it.copy(language = "eng") else it })))
        assertNull(PlaybackSelection.capture(source, TrackChoice(99, -1)))
    }

    @Test fun corruptMemoryFallsBackToNoRememberedPlayback() {
        prefs.edit().putString("selections", "invalid json").commit()
        assertNull(memory().latest(listOf(ref)))
    }

    @Test fun newestMatchingRecordWinsWithoutReusingAnotherEpisode() {
        val other = ref.copy(server = "other")
        val store = memory()
        store.remember(saved())
        store.remember(saved(server = other.server, at = 200))
        store.remember(saved(item = "episode-8", at = 300))
        assertEquals(other.server, store.latest(listOf(ref, other))?.server)
        store.remember(saved(at = 400))
        store.remember(saved(at = 50))
        assertEquals(ref.server, store.latest(listOf(ref, other))?.server)
        assertEquals(400L, store.latest(listOf(ref, other))?.playedAt)
    }

    @Test fun resolverUsesExactRememberedVersionEvenWhenServerReturnsAnotherFirst() = runTest {
        val result = resolveRememberedPlayback(saved(), listOf(ref), { true }) { requested ->
            assertEquals(ref, requested)
            listOf(source.copy(id = "web"), source)
        }
        assertEquals(RememberedPlayback(ref, "remux", TrackChoice(2, -1)), result)
    }

    @Test fun resolverDoesNotGuessForMissingMemoryDisabledServerOrAnotherItem() = runTest {
        var calls = 0
        val load: suspend (ServerItem) -> List<MediaSourceInfo> = { calls++; listOf(source) }
        assertNull(resolveRememberedPlayback(null, listOf(ref), { true }, load))
        assertNull(resolveRememberedPlayback(saved(), listOf(ref), { false }, load))
        assertNull(resolveRememberedPlayback(saved(item = "episode-8"), listOf(ref), { true }, load))
        assertEquals(0, calls)
    }

    @Test fun resolverReturnsToDetailsOnUnavailableSourceOrNetworkFailureAndPreservesCancellation() = runTest {
        assertNull(resolveRememberedPlayback(saved(), listOf(ref), { true }) { emptyList() })
        assertNull(resolveRememberedPlayback(saved(), listOf(ref), { true }) { throw java.io.IOException("offline") })
        try {
            resolveRememberedPlayback(saved(), listOf(ref), { true }) { throw CancellationException("left screen") }
            fail("cancellation must propagate")
        } catch (_: CancellationException) { }
    }
}
