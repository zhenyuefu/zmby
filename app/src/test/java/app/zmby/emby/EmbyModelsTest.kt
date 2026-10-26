package app.zmby.emby

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EmbyModelsTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

    private fun item(fields: String) = json.decodeFromString(BaseItemDto.serializer(), """{"Id":"1",$fields}""")

    @Test
    fun embyUnknownYearAndDateReadAsNull() {
        // A real server's series with no metadata: shown as 「1 · 悬疑」 in the hero before.
        val unknown = item(""""ProductionYear":1,"PremiereDate":"0001-01-01T00:00:00.0000000Z"""")
        assertNull(unknown.productionYear)
        assertNull(unknown.premiereDate)
        assertNull(item(""""ProductionYear":0""").productionYear)
        assertNull(item(""""ProductionYear":null,"PremiereDate":null""").productionYear)
    }

    @Test
    fun realYearsAndDatesAreKept() {
        val known = item(""""ProductionYear":1895,"PremiereDate":"2026-10-26T00:00:00.0000000Z"""")
        assertEquals(1895, known.productionYear)
        assertEquals("2026-10-26T00:00:00.0000000Z", known.premiereDate)
        // Odd but not a placeholder: kept as the server wrote it.
        assertEquals("unknown", item(""""PremiereDate":"unknown"""").premiereDate)
    }

    @Test
    fun cachedItemsRoundTrip() {
        val known = item(""""ProductionYear":2024,"PremiereDate":"2024-01-02T00:00:00Z"""")
        assertEquals(known, json.decodeFromString(BaseItemDto.serializer(), json.encodeToString(BaseItemDto.serializer(), known)))
        val unknown = item(""""ProductionYear":1""")
        assertNull(json.decodeFromString(BaseItemDto.serializer(), json.encodeToString(BaseItemDto.serializer(), unknown)).productionYear)
    }
}
