package app.zmby.net

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

class ProxyRoutesTest {
    private fun plan(
        kind: ProxyKind = ProxyKind.Http, address: String = "192.168.1.2:7890", user: String = "", password: String = "",
        lines: List<String> = emptyList(), apis: Set<ProxyApi> = emptySet(),
        tmdbBase: String = "https://api.tmdb.org/3/", danmuBase: String = "",
    ) = ProxyPlan.build(kind, address, user, password, lines, apis, tmdbBase, danmuBase)

    @Test fun `addresses accept host and port, bracketed IPv6 and a scheme, nothing else`() {
        assertEquals(ProxyAddress("192.168.1.2", 7890), ProxyAddress.parse(" 192.168.1.2:7890 "))
        assertEquals(ProxyAddress("proxy.example.net", 1080), ProxyAddress.parse("socks5://Proxy.Example.net:1080/"))
        assertEquals(ProxyAddress("::1", 8080), ProxyAddress.parse("[::1]:8080"))
        assertEquals("[::1]:8080", ProxyAddress("::1", 8080).toString())
        for (bad in listOf("", "192.168.1.2", "host:0", "host:65536", "host:80/path", "user:pw@host:80", "::1:80", "[::1]8080", "ho st:80", ".host:80")) {
            assertNull(bad, ProxyAddress.parse(bad))
            assertNotNull(bad, ProxyAddress.check(bad))
        }
        assertEquals("用户名和密码请分别填写", ProxyAddress.check("user:pw@host:80"))
    }

    @Test fun `api sets round trip in a fixed order and drop unknown names`() {
        assertEquals(setOf(ProxyApi.Imdb, ProxyApi.Tmdb), ProxyApi.parse("Imdb, Tmdb,Gone"))
        assertEquals("Tmdb,Imdb", ProxyApi.store(setOf(ProxyApi.Imdb, ProxyApi.Tmdb)))
    }

    @Test fun `only chosen lines and api domains use the proxy`() {
        val p = plan(lines = listOf("https://emby.example.net:8920/emby", "http://lan.example.test/"), apis = setOf(ProxyApi.Tmdb, ProxyApi.Imdb))
        assertTrue(p.proxies("https://emby.example.net:8920/emby/Items?api_key=x"))
        assertTrue(p.proxies("https://EMBY.example.net:8920/other"))
        assertFalse("another port is another line", p.proxies("https://emby.example.net/emby"))
        assertFalse("another scheme is another line", p.proxies("http://emby.example.net:8920/emby"))
        assertTrue("default port", p.proxies("http://lan.example.test:80/x"))
        assertTrue(p.proxies("https://api.tmdb.org/3/movie/1"))
        assertTrue(p.proxies("https://image.tmdb.org/t/p/w780/a.jpg"))
        assertTrue(p.proxies("https://m.media-amazon.com/images/a.jpg"))
        assertFalse("suffix only on a label boundary", p.proxies("https://notimdb.com/"))
        assertFalse(p.proxies("https://api.bgm.tv/v0/subjects/1"))
        assertFalse(p.proxies("https://query.wikidata.org/sparql"))
        assertFalse(p.proxies("not a url"))
    }

    @Test fun `typed api addresses follow their switch`() {
        val off = plan(tmdbBase = "https://tmdb.mirror.example.net/3/", danmuBase = "http://192.168.1.9:9321/")
        assertFalse(off.proxies("https://tmdb.mirror.example.net/3/movie/1"))
        assertFalse(off.proxies("http://192.168.1.9:9321/api/v2/match"))
        assertFalse(off.active)
        val on = plan(apis = setOf(ProxyApi.Tmdb, ProxyApi.Danmu), tmdbBase = "https://tmdb.mirror.example.net/3/", danmuBase = "http://192.168.1.9:9321/")
        assertTrue(on.proxies("https://tmdb.mirror.example.net/3/movie/1"))
        assertTrue(on.proxies("http://192.168.1.9:9321/api/v2/match"))
        assertFalse(on.proxies("http://192.168.1.9:8096/"))
        assertTrue(on.active)
    }

    @Test fun `off, a bad address or this box itself never use the proxy`() {
        val lines = listOf("http://127.0.0.1:8096/", "http://localhost:8096/", "http://[::1]:8096/", "http://emby.example.net/")
        assertSame(ProxyPlan.Off, plan(kind = ProxyKind.Off, lines = lines))
        assertSame(ProxyPlan.Off, plan(address = "nowhere", lines = lines))
        val p = plan(lines = lines)
        assertFalse(p.proxies("http://127.0.0.1:8096/r/abc/stream"))
        assertFalse(p.proxies("http://localhost:8096/"))
        assertFalse(p.proxies("http://[::1]:8096/"))
        assertTrue(p.proxies("http://emby.example.net/Items"))
    }

    @Test fun `the selector answers the proxy for chosen hosts and the fallback otherwise`() {
        val system = Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("system.example.test", 3128))
        val failed = mutableListOf<URI>()
        val fallback = object : ProxySelector() {
            override fun select(uri: URI?) = listOf(system)
            override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: java.io.IOException?) { failed += uri!! }
        }
        val routes = ProxyRoutes(fallback)
        assertEquals(listOf(system), routes.select(URI("https://emby.example.net/")))
        assertTrue(routes.update(plan(kind = ProxyKind.Socks, lines = listOf("https://emby.example.net/"))))
        assertFalse("same plan", routes.update(plan(kind = ProxyKind.Socks, lines = listOf("https://emby.example.net/"))))
        val chosen = routes.select(URI("https://emby.example.net/"))
        assertEquals(Proxy.Type.SOCKS, chosen.single().type())
        assertEquals(InetSocketAddress.createUnresolved("192.168.1.2", 7890), chosen.single().address())
        assertEquals(listOf(system), routes.select(URI("https://other.example.net/")))
        routes.connectFailed(URI("https://emby.example.net/"), chosen.single().address(), java.io.IOException("x"))
        assertTrue("the proxy's failure is not the system's", failed.isEmpty())
        assertEquals(listOf(Proxy.NO_PROXY), ProxyRoutes(null).select(URI("https://emby.example.net/")))
    }

    @Test fun `an http proxy carries chosen requests and gets credentials once asked`() {
        val proxy = MockWebServer()
        proxy.enqueue(MockResponse().setResponseCode(407).setHeader("Proxy-Authenticate", "Basic realm=\"proxy\""))
        proxy.enqueue(MockResponse().setBody("pong"))
        // The next call is asked again, and refuses the credentials too.
        repeat(2) { proxy.enqueue(MockResponse().setResponseCode(407).setHeader("Proxy-Authenticate", "Basic realm=\"proxy\"")) }
        proxy.start()
        val routes = ProxyRoutes(null)
        val http = OkHttpClient.Builder().proxySelector(routes).proxyAuthenticator(routes.okHttpAuthenticator).build()
        try {
            routes.update(plan(address = "127.0.0.1:${proxy.port}", user = "me", password = "secret", lines = listOf("http://emby.example.test:8096/")))
            http.newCall(Request.Builder().url("http://emby.example.test:8096/System/Ping").build()).execute().use {
                assertEquals("pong", it.body!!.string())
            }
            val first = proxy.takeRequest()
            assertEquals("GET http://emby.example.test:8096/System/Ping HTTP/1.1", first.requestLine)
            assertNull(first.getHeader("Proxy-Authorization"))
            assertEquals(okhttp3.Credentials.basic("me", "secret"), proxy.takeRequest().getHeader("Proxy-Authorization"))
            // Refused with the credentials: no second round.
            http.newCall(Request.Builder().url("http://emby.example.test:8096/System/Ping").build()).execute().use { assertEquals(407, it.code) }
            assertEquals(4, proxy.requestCount)
        } finally {
            http.connectionPool.evictAll()
            proxy.shutdown()
        }
    }
}
