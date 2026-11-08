package app.zmby.ui

import app.zmby.trailer.TrailerFormat
import app.zmby.trailer.TrailerInfo
import app.zmby.ui.components.Scrims
import app.zmby.ui.components.trailerClock
import app.zmby.ui.screens.trailerSourceLabel
import app.zmby.ui.screens.trailerTip
import org.junit.Assert.assertEquals
import org.junit.Test

/** The trailer's labels and the scrim that lightens while it shows (ui-spec §2.23). */
class TrailerChromeTest {
    private fun info(format: TrailerFormat = TrailerFormat.Mp4, runtime: Int? = 151, source: String = "imdb") =
        TrailerInfo("vi1", "http://127.0.0.1/t.mp4", 1080, runtime, 0L, format, source)

    @Test
    fun timeLeftRoundsUpAndTimePlayedDown() {
        assertEquals("2:31", trailerClock(151_000))
        assertEquals("0:01", trailerClock(200))
        assertEquals("0:00", trailerClock(200, roundUp = false))
        assertEquals("1:12", trailerClock(72_900, roundUp = false))
        assertEquals("0:00", trailerClock(-5))
    }

    @Test
    fun buttonTipCarriesTheLengthWhenKnown() {
        assertEquals("预告片 · 2:31", trailerTip(info()))
        assertEquals("预告片", trailerTip(info(runtime = null)))
        assertEquals("预告片", trailerTip(info(runtime = 0)))
    }

    @Test
    fun sourceFollowsWhereItCameFrom() {
        assertEquals("预告片 · IMDb", trailerSourceLabel(info()) { null })
        assertEquals("预告片 · 烂番茄", trailerSourceLabel(info(TrailerFormat.Hls, source = "rottentomatoes")) { null })
        assertEquals("预告片 · 家里 NAS", trailerSourceLabel(info(TrailerFormat.Server, source = "s1")) { if (it == "s1") "家里 NAS" else null })
        assertEquals("预告片 · 服务器", trailerSourceLabel(info(TrailerFormat.Server, source = "gone")) { null })
    }

    @Test
    fun scrimBlendsTowardsTheLighterStops() {
        var t = 0f
        val base = listOf(0f to 0.96f, 0.34f to 0.78f, 0.70f to 0.1f)
        val light = listOf(0f to 0.88f, 0.32f to 0.6f, 0.62f to 0f)
        val scrims = Scrims(base, lighter = light, lightness = { t })
        assertEquals(base, scrims.horizontalStops())
        t = 1f
        val full = scrims.horizontalStops()
        light.zip(full).forEach { (e, a) -> assertEquals(e.first, a.first, 1e-5f); assertEquals(e.second, a.second, 1e-5f) }
        t = 0.5f
        assertEquals(0.69f, scrims.horizontalStops()[1].second, 1e-5f)
        // Without lighter stops nothing changes.
        assertEquals(base, Scrims(base, lightness = { 1f }).horizontalStops())
    }
}
