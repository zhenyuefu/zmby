package app.zmby.aggregate

import app.zmby.emby.BaseItemDto
import app.zmby.media.visibleText

/** Metadata needs proof of identity; a plausible title match is only a playback candidate. */
object MetadataIdentity {
    fun ids(item: BaseItemDto): Map<String, String> = listOf("tmdb", "imdb", "tvdb", "bangumi")
        .mapNotNull { p -> item.providerId(p)?.lowercase()?.let { p to it } }.toMap()

    fun query(item: BaseItemDto): List<String> = ids(item).map { (p, id) -> "$p.$id" }

    fun conflicts(a: BaseItemDto, b: BaseItemDto): Boolean {
        val left = ids(a); val right = ids(b)
        return left.any { (p, id) -> right[p]?.let { it != id } == true }
    }

    fun same(a: BaseItemDto, b: BaseItemDto): Boolean {
        if (a.type != b.type || conflicts(a, b)) return false
        val right = ids(b)
        return ids(a).any { (p, id) -> right[p] == id }
    }

    fun validPerson(item: BaseItemDto): Boolean = item.type == "Person" &&
        item.id.isNotBlank() && !item.id.startsWith("compat_", ignoreCase = true) && visibleText(item.name) != null

    fun member(item: BaseItemDto, personId: String): Boolean =
        item.type in setOf("Movie", "Series") && item.people.any { it.id == personId }
}
