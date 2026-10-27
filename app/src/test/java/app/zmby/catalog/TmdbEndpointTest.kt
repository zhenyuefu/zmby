package app.zmby.catalog

import org.junit.Assert.*
import org.junit.Test

class TmdbEndpointTest {
    @Test fun `root domain and reset use v3 while proxy paths are preserved`() {
        assertEquals("https://api.tmdb.org/3/", TmdbEndpoint.normalize(""))
        assertEquals(TmdbEndpoint.DEFAULT, TmdbEndpoint.normalize(" https://api.tmdb.org "))
        assertEquals("https://example.com/proxy/3/", TmdbEndpoint.normalize("https://example.com/proxy/3"))
        assertEquals("http://127.0.0.1:8096/_mock/tmdb/3/", TmdbEndpoint.normalize("http://127.0.0.1:8096/_mock/tmdb/3/"))
    }

    @Test fun `invalid addresses cannot carry credentials parameters or hidden whitespace`() {
        listOf("invalid", "ftp://example.com", "https://user@example.com", "https://example.com?api_key=secret",
            "https://example.com#fragment", "https://exam ple.com", "https://example.com/\n3").forEach {
            assertNull(it, TmdbEndpoint.normalize(it))
            assertNotNull(TmdbEndpoint.check(it))
        }
    }
}
