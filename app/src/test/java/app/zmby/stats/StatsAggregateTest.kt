package app.zmby.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StatsAggregateTest {
    @Test fun `server play dates parse`() {
        assertEquals(1792915121420L, StatsAggregate.parseDate("2026-10-25T07:58:41.4200000Z"))
        assertNull(StatsAggregate.parseDate("yesterday"))
        assertNull(StatsAggregate.parseDate(null))
    }
}
