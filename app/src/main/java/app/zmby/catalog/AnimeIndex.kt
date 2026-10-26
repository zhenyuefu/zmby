package app.zmby.catalog

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import java.io.File
import java.io.InputStream

/**
 * What alias matching needs from bangumi-data, and nothing more: each item's normalized original and
 * translated titles, its start year and Bangumi subject. The published dataset is about 8 MB; this
 * index is a fraction of it, lives in an app-private file (never in Room, whose cursor window cannot
 * read multi-megabyte rows) and is what stays in memory.
 */
class AnimeIndex private constructor(private val entries: List<Entry>) {
    /** [key] tells items apart: the subject, or the title for an item without one. */
    class Entry(val key: String, val subject: String?, val year: Int?, val names: List<String>)

    private val byName: Map<String, List<Entry>> = HashMap<String, MutableList<Entry>>().also { map ->
        for (e in entries) for (n in e.names) map.getOrPut(n) { mutableListOf() } += e
    }

    val size: Int get() = entries.size

    /**
     * The Bangumi subject whose original or translated title is one of [names] (and started in
     * [year] when given). Several matching items, or one without a subject, give null: no guesses.
     */
    fun find(names: List<String>, year: Int?): String? {
        val keys = names.map(AnimeAliases::normalize).filter { it.isNotEmpty() }.toSet()
        return keys.flatMap { byName[it].orEmpty() }.filter { year == null || it.year == year }
            .distinctBy { it.key }.singleOrNull()?.subject
    }

    /** Writes the index to [file] via a temporary file, so a crash never leaves half an index. */
    fun write(file: File, fetchedAt: Long) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.bufferedWriter(Charsets.UTF_8).use { w ->
            w.write("$MAGIC\t$VERSION\t$fetchedAt\n")
            for (e in entries) {
                w.write(e.key); w.write("\t"); w.write(e.subject.orEmpty()); w.write("\t"); w.write(e.year?.toString().orEmpty())
                for (n in e.names) { w.write("\t"); w.write(n) }
                w.write("\n")
            }
        }
        if (!tmp.renameTo(file)) { file.delete(); check(tmp.renameTo(file)) { "cannot replace ${file.name}" } }
    }

    @Serializable private class Site(val site: String = "", val id: String? = null)
    @Serializable private class Item(val title: String? = null, val titleTranslate: Map<String, List<String>> = emptyMap(),
        val begin: String? = null, val sites: List<Site> = emptyList())
    @Serializable private class Dataset(val items: List<Item> = emptyList())

    companion object {
        private const val MAGIC = "zmby-anime-index"
        private const val VERSION = 1
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        private fun entry(item: Item): Entry? {
            val names = (listOfNotNull(item.title) + item.titleTranslate.values.flatten())
                .map(AnimeAliases::normalize).filter { it.isNotEmpty() }.distinct()
            if (names.isEmpty()) return null
            val subject = item.sites.firstOrNull { it.site == "bangumi" }?.id?.takeIf { it.isNotBlank() && '\t' !in it && '\n' !in it }
            return Entry(subject ?: "t:" + names.first(), subject, item.begin?.take(4)?.toIntOrNull(), names)
        }

        /**
         * Decodes the published `{siteMeta, items}` object or a plain array straight from [input]
         * into the index, without building a JSON tree of the whole dataset.
         */
        @OptIn(ExperimentalSerializationApi::class)
        fun decode(input: InputStream): AnimeIndex {
            val buffered = input.buffered()
            buffered.mark(64)
            var first: Int
            do { first = buffered.read() } while (first != -1 && first.toChar().isWhitespace())
            buffered.reset()
            val items = if (first == '['.code) json.decodeFromStream<List<Item>>(buffered) else json.decodeFromStream<Dataset>(buffered).items
            require(items.isNotEmpty()) { "bangumi-data has no items" }
            return AnimeIndex(items.mapNotNull(::entry))
        }

        /** The stored index and when its dataset was fetched; null for a missing or foreign file. */
        fun read(file: File): Pair<Long, AnimeIndex>? {
            if (!file.isFile) return null
            file.bufferedReader(Charsets.UTF_8).use { r ->
                val head = r.readLine()?.split('\t') ?: return null
                if (head.size != 3 || head[0] != MAGIC || head[1] != VERSION.toString()) return null
                val at = head[2].toLongOrNull() ?: return null
                val entries = r.lineSequence().mapNotNull { line ->
                    val parts = line.split('\t')
                    if (parts.size < 4) null else Entry(parts[0], parts[1].ifEmpty { null }, parts[2].toIntOrNull(), parts.drop(3))
                }.toList()
                return at to AnimeIndex(entries)
            }
        }
    }
}
