package app.zmby.aggregate

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.zmby.emby.BaseItemDto
import app.zmby.emby.ImageInfo
import app.zmby.emby.PersonDto
import app.zmby.store.LocalStore
import app.zmby.store.ZmbyDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class MetadataRepositoryTest {
    private lateinit var db: ZmbyDatabase
    private lateinit var store: LocalStore
    private val source = Fake()
    private val selected = mutableListOf("a", "b", "bad")
    private lateinit var repo: MetadataRepository
    private val actor = person("pa", "金多美", "2053219", "人物简介")

    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), ZmbyDatabase::class.java).allowMainThreadQueries().build()
        store = LocalStore(db, Json { ignoreUnknownKeys = true; encodeDefaults = true })
        store.metadataServers = { selected.toList() }
        val book = object : HealthBook {
            override fun isCooling(serverId: String) = false
            override fun onSuccess(serverId: String) = Unit
            override fun onFailure(serverId: String, error: Throwable, timedOut: Boolean) = Unit
        }
        repo = MetadataRepository(source, store, FanOut(book)) { selected.toList() }
    }

    @After fun close() = db.close()

    @Test fun `compat responses cannot replace biography or contribute 200 unrelated works`() = runBlocking {
        source.items["a:pa"] = actor
        source.persons["bad"] = listOf(person("compat_item", "金多美", null))
        source.names["bad:金多美"] = person("compat_item", "金多美", null)
        source.works["a:pa"] = listOf(work("film", "血谜拼图", "227191", "pa"))
        source.works["bad:compat_item"] = (1..200).map { work("bad$it", "unrelated", "$it") }
        val updates = mutableListOf<PersonUpdate>()
        repo.refreshPerson("a", "pa", updates::add)
        assertEquals("人物简介", store.person("person:tmdb:2053219")?.overview)
        assertEquals(setOf("person:tmdb:2053219"), updates.map { it.key }.toSet())
        assertEquals(listOf("film"), store.list("person:a:pa")!!.items.map { it.item.id })
        assertNull(store.alias("bad", "compat_item"))
        assertFalse(source.calls.any { it == "works:bad:compat_item" })
    }

    @Test fun `different language names match by external id and keep the Chinese biography`() = runBlocking {
        source.items["a:pa"] = actor.copy(overview = null)
        val foreign = person("pb", "Kim Da-mi", "2053219", "Korean actor")
        source.persons["b"] = listOf(foreign)
        source.items["b:pb"] = foreign
        source.works["b:pb"] = listOf(work("foreign", "Nine Puzzles", "227191", "pb"))
        val keys = mutableListOf<String>()
        repo.refreshPerson("a", "pa") { keys += it.key }
        val p = store.person("person:tmdb:2053219")!!
        assertEquals("金多美", p.name)
        assertEquals("Korean actor", p.overview)
        assertTrue(keys.all { it == "person:tmdb:2053219" })
        assertEquals(setOf("a", "b"), store.aliases(p.personKey).map { it.serverId }.toSet())
        assertEquals(1, store.list("person:b:pb")!!.items.size)
    }

    @Test fun `a server ignoring PersonIds cannot add unrelated works even with a real person id`() = runBlocking {
        source.items["a:pa"] = actor
        source.works["a:pa"] = listOf(work("yes", "real", "1", "pa"), work("wrong", "unrelated", "2", "other"), work("empty", "no proof", "3"))
        repo.refreshPerson("a", "pa") {}
        assertEquals(listOf("yes"), store.list("person:a:pa")!!.items.map { it.item.id })
    }

    @Test fun `same name with missing or conflicting IDs is never an identity match`() = runBlocking {
        source.items["a:pa"] = actor
        source.persons["b"] = listOf(person("pb", "金多美", "9999"))
        source.names["b:金多美"] = person("pb", "金多美", null)
        repo.refreshPerson("a", "pa") {}
        assertNull(store.alias("b", "pb"))
        assertFalse(source.calls.any { it.startsWith("works:b:") })
    }

    @Test fun `series detail merges full translated metadata and cast independent of playback versions`() = runBlocking {
        val original = work("sa", "Nine Puzzles", "227191", "pa").copy(type = "Series", overview = "English overview")
        val translated = work("sb", "血谜拼图", "227191", "pb").copy(type = "Series", overview = "中文剧情", imageTags = mapOf("Primary" to "large"))
        source.items["b:sb"] = translated
        source.matches["b"] = listOf(translated.copy(overview = null, people = emptyList()))
        source.cast["a"] = listOf(actor.copy(name = "Kim Da-mi"))
        source.cast["b"] = listOf(actor.copy(id = "pb"))
        source.imageSizes["b:sb"] = listOf(ImageInfo(imageType = "Primary", width = 2000, height = 3000))
        val key = store.putDetail("a", original)
        repo.refreshWork("a", original)
        val meta = store.metaFlow(key).first()!!
        assertEquals("血谜拼图", meta.title)
        assertEquals("中文剧情", meta.overview)
        assertEquals("b", meta.primary?.server)
        assertEquals("中文剧情", store.detail("b", "sb")!!.item.overview)
        val cast = store.castFlow(key).first()
        assertEquals(1, cast.size)
        assertEquals("金多美", cast.single().person.name)
        assertEquals("person:tmdb:2053219", cast.single().person.personKey)
        assertTrue(source.calls.contains("item:b:sb"))
    }

    @Test fun `metadata sources are independent and ignored ID filters do not contaminate details`() = runBlocking {
        selected.clear(); selected += listOf("a", "bad")
        val original = work("film", "Original", "10")
        source.matches["bad"] = listOf(work("bad", "Original", "11"))
        source.matches["b"] = listOf(work("b", "译名", "10"))
        val key = store.putDetail("a", original)
        repo.refreshWork("a", original)
        assertEquals("Original", store.metaFlow(key).first()!!.title)
        assertFalse(source.calls.any { it.contains(":b:") || it == "lookup:b" })
        assertNull(store.detail("bad", "bad"))
    }

    @Test fun `metadata shares cache when one source only has IMDb`() = runBlocking {
        val original = work("a", "Original", "10").copy(providerIds = mapOf("Tmdb" to "10", "Imdb" to "tt10"))
        val translated = work("b", "译名", null).copy(providerIds = mapOf("Imdb" to "tt10"))
        source.matches["b"] = listOf(translated)
        source.items["b:b"] = translated
        val key = store.putDetail("a", original)
        repo.refreshWork("a", original)
        assertEquals(key, store.workKeyOf("b", "b"))
        store.putItems("b", listOf(translated))
        assertEquals(key, store.workKeyOf("b", "b"))
        assertEquals("译名", store.metaFlow(key).first()!!.title)
    }

    @Test fun `conflicting secondary IDs block a match even when TMDB is equal`() {
        val a = actor.copy(providerIds = mapOf("Tmdb" to "1", "Imdb" to "nm1"))
        val b = actor.copy(providerIds = mapOf("Tmdb" to "1", "Imdb" to "nm2"))
        assertFalse(MetadataIdentity.same(a, b))
    }

    @Test fun `person work cards never merge by name alone`() {
        val a = Hit(app.zmby.data.ServerItem("a", work("a", "同名", "10").copy(productionYear = 2025)), 0)
        val b = Hit(app.zmby.data.ServerItem("b", work("b", "同名", null).copy(productionYear = 2025)), 1)
        val merged = Merger.merge(listOf(a, b), strictExternal = true)
        assertEquals(1, merged.works.size)
        assertEquals(1, merged.works.single().servers)
        assertEquals(listOf(b), merged.unmerged)
    }

    @Test fun `poisoned person lists are repaired once without deleting normal library cache`() = runBlocking {
        store.putList("person:bad:compat_item", "bad", listOf(work("wrong", "unrelated", "1")))
        store.putList("latest:a", "a", listOf(work("keep", "Cached film", "2")))
        store.repairPersonCache()
        assertNull(store.list("person:bad:compat_item"))
        assertEquals(1, store.list("latest:a")!!.items.size)
        store.putList("person:a:pa", "a", listOf(work("good", "Real work", "3", "pa")))
        store.repairPersonCache()
        assertEquals(1, store.list("person:a:pa")!!.items.size)
    }

    @Test fun `a later source cannot blank a biography or downgrade the canonical person key`() = runBlocking {
        val key = store.mergePerson("a", actor.copy(providerIds = mapOf("Tmdb" to "2053219", "Imdb" to "nm9913973")))
        val second = actor.copy(id = "pb", name = "Kim Da-mi", overview = "", providerIds = mapOf("Imdb" to "nm9913973"))
        assertEquals(key, store.mergePerson("b", second, key))
        assertEquals("人物简介", store.person(key)?.overview)
        assertEquals("金多美", store.person(key)?.name)
        assertEquals(key, store.alias("b", "pb")?.personKey)
    }

    @Test fun `large casts retain membership evidence in person works cache`() = runBlocking {
        val people = (1..100).map { PersonDto("p$it", "演员$it", type = "Actor") }
        store.putList("person:a:p99", "a", listOf(work("film", "Ensemble", "1").copy(people = people)))
        assertTrue(MetadataIdentity.member(store.list("person:a:p99")!!.items.single().item, "p99"))
    }

    @Test fun `a same-server response omitting ProviderIds cannot downgrade a verified alias`() = runBlocking {
        val key = store.mergePerson("a", actor)
        assertEquals(key, store.mergePerson("a", actor.copy(providerIds = emptyMap())))
        assertEquals("2053219", store.person(key)?.tmdb)
        assertEquals(key, store.alias("a", "pa")?.personKey)
    }

    @Test fun `verified lookup IDs survive a sparse full-detail response`() = runBlocking {
        source.items["a:pa"] = actor.copy(overview = null)
        source.persons["b"] = listOf(person("pb", "Kim Da-mi", "2053219"))
        source.items["b:pb"] = person("pb", "Kim Da-mi", null, "Verified biography")
        repo.refreshPerson("a", "pa") {}
        assertEquals("Verified biography", store.person("person:tmdb:2053219")?.overview)
        assertEquals("person:tmdb:2053219", store.alias("b", "pb")?.personKey)
    }

    @Test fun `merging all sources off clears old merged fields and cast`() = runBlocking {
        val key = store.putDetail("a", work("film", "Cached", "1", "pa"))
        assertNotNull(store.metaFlow(key).first())
        store.remerge(key, emptyList())
        assertNull(store.metaFlow(key).first())
        assertTrue(store.castFlow(key).first().isEmpty())
    }

    @Test fun `a higher-priority cached copy with conflicting IDs cannot displace the opened work`() = runBlocking {
        val wrong = work("wrong", "错误资料", "10").copy(providerIds = mapOf("Tmdb" to "10", "Imdb" to "tt999"))
        store.putDetail("a", wrong)
        val right = work("right", "Correct", "10").copy(providerIds = mapOf("Tmdb" to "10", "Imdb" to "tt10"))
        val key = store.putDetail("b", right)
        repo.refreshWork("b", right)
        assertEquals("Correct", store.metaFlow(key).first()!!.title)
    }

    @Test fun `a conflicting secondary ID cannot overwrite an existing canonical person`() = runBlocking {
        val key = store.mergePerson("a", actor.copy(providerIds = mapOf("Tmdb" to "2053219", "Imdb" to "nm1")))
        val result = runCatching { store.mergePerson("b", actor.copy(id = "pb", providerIds = mapOf("Tmdb" to "2053219", "Imdb" to "nm2"))) }
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        assertEquals("nm1", store.person(key)?.imdb)
        assertNull(store.alias("b", "pb"))
    }

    private class Fake : MetadataSource {
        val items = mutableMapOf<String, BaseItemDto>()
        val persons = mutableMapOf<String, List<BaseItemDto>>()
        val names = mutableMapOf<String, BaseItemDto>()
        val matches = mutableMapOf<String, List<BaseItemDto>>()
        val cast = mutableMapOf<String, List<BaseItemDto>>()
        val works = mutableMapOf<String, List<BaseItemDto>>()
        val imageSizes = mutableMapOf<String, List<ImageInfo>>()
        val calls: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
        override suspend fun item(server: String, id: String): BaseItemDto { calls += "item:$server:$id"; return items["$server:$id"] ?: error("not found") }
        override suspend fun byProviderIds(server: String, type: String, ids: List<String>): List<BaseItemDto> { calls += "lookup:$server"; return matches[server].orEmpty() }
        override suspend fun persons(server: String, ids: List<String>) = persons[server].orEmpty()
        override suspend fun person(server: String, name: String) = names["$server:$name"] ?: error("not found")
        override suspend fun peopleByIds(server: String, ids: List<String>) = cast[server].orEmpty()
        override suspend fun worksOf(server: String, personId: String): List<BaseItemDto> { calls += "works:$server:$personId"; return works["$server:$personId"].orEmpty() }
        override suspend fun images(server: String, itemId: String) = imageSizes["$server:$itemId"].orEmpty()
    }

    private companion object {
        fun person(id: String, name: String, tmdb: String?, bio: String? = null) = BaseItemDto(id = id, name = name, type = "Person", overview = bio,
            providerIds = tmdb?.let { mapOf("Tmdb" to it) }.orEmpty())
        fun work(id: String, name: String, tmdb: String?, person: String? = null) = BaseItemDto(id = id, name = name, type = "Movie",
            providerIds = tmdb?.let { mapOf("Tmdb" to it) }.orEmpty(), people = person?.let { listOf(PersonDto(it, "Actor name", type = "Actor")) }.orEmpty())
    }
}
