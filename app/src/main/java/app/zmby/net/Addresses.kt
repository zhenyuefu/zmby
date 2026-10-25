package app.zmby.net

/**
 * A server address as the UI shows it: host and port, then any path segments after a dot, never a
 * slash — "emby.example.net", "192.168.1.10:8096", "host:8096 · emby". The full URL stays in the
 * configuration; this is display only.
 */
fun displayAddress(url: String): String {
    val rest = url.trim().substringAfter("://")
    val host = rest.substringBefore('/')
    val path = rest.substringAfter('/', "").split('/').filter { it.isNotBlank() }
    return (listOf(host) + path).joinToString(" · ")
}
