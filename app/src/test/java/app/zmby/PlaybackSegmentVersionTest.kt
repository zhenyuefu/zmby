package app.zmby

import app.zmby.emby.BaseItemDto
import app.zmby.emby.MediaSourceInfo
import app.zmby.emby.msToTicks
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSegmentVersionTest {
    private fun source(id: String, runtimeMs: Long?) = MediaSourceInfo(id = id, runTimeTicks = runtimeMs?.let(::msToTicks))
    private val item = BaseItemDto(id = "e", runTimeTicks = msToTicks(2_700_000))

    @Test fun `segments are looked up again only when PlaybackInfo settles on another cut`() {
        // No version asked for: the first lookup used the item's default runtime.
        assertTrue(PlaybackActivity.sameCut(source("a", 2_701_000), null, item))
        assertFalse(PlaybackActivity.sameCut(source("b", 3_300_000), null, item))
        // The version asked for decides, not the item's default.
        assertTrue(PlaybackActivity.sameCut(source("b", 3_300_000), source("b", 3_300_000), item))
        // A played version without a runtime has nothing better to offer.
        assertTrue(PlaybackActivity.sameCut(source("c", null), null, item))
    }
}
