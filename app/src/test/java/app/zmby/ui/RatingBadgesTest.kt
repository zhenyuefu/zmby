package app.zmby.ui

import app.zmby.ratings.ExternalRatings
import app.zmby.ratings.RtScore
import app.zmby.ui.components.Glyph
import app.zmby.ui.screens.ratingBadges
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The detail header's rating badges (ratings design; ui-spec §2.2). */
class RatingBadgesTest {
    @Test
    fun imdbThenTomatometerThenPopcornmeterThenTheCatalogue() {
        val b = ratingBadges(8.13, true, ExternalRatings(8.4, 804343, RtScore(92, true, true), RtScore(95, true)))
        assertEquals(listOf("8.4", "92%", "95%", "8.1"), b.map { it.value })
        assertEquals(listOf("IMDb", null, null, "TMDB"), b.map { it.label })
        assertEquals(listOf(null, Glyph.Tomato, Glyph.Popcorn, null), b.map { it.glyph })
        assertTrue(b[1].spoken.contains("认证新鲜"))
    }

    @Test
    fun rottenAndSpilledHaveTheirOwnGlyphs() {
        val b = ratingBadges(null, false, ExternalRatings(tomatometer = RtScore(31, false), popcornmeter = RtScore(48, false)))
        assertEquals(listOf(Glyph.Splat, Glyph.PopcornSpilled), b.map { it.glyph })
    }

    @Test
    fun withoutExternalRatingsOnlyTheCatalogue() {
        assertEquals(listOf("评分"), ratingBadges(7.0, false, null).map { it.label })
        assertTrue(ratingBadges(0.0, true, ExternalRatings()).isEmpty())
    }
}
