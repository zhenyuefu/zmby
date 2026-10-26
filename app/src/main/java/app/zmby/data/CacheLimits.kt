package app.zmby.data

/** Disk budgets use binary MB consistently; user lists and playback history have no cache quota. */
object CacheLimits {
    const val MIB = 1024L * 1024
    const val METADATA_DEFAULT_MB = 128
    const val IMAGES_DEFAULT_MB = 300
    val metadataMb = listOf(32, 64, 128, 256, 512, 1024)
    val imagesMb = listOf(100, 300, 500, 1024, 2048)
    fun label(mb: Int) = if (mb >= 1024 && mb % 1024 == 0) "${mb / 1024} GB" else "$mb MB"
    fun usage(bytes: Long) = if (bytes < MIB) "%.1f MB".format(java.util.Locale.ROOT, bytes.toDouble() / MIB) else "${bytes / MIB} MB"
}
