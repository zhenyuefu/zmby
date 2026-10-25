package app.zmby.store

/** One unit in the cast row (ui-spec §2.2 4): a person, their role or crew badge, and their picture. */
data class CastUnit(
    val personKey: String,
    val name: String,
    /** The character for actors; null for crew. */
    val role: String?,
    /** 「导演」「编剧」 or 「导演 · 编剧」 for crew; null for actors. */
    val badge: String?,
    val image: ImageRef?,
)

object CastRules {
    const val MAX_ACTORS = 20

    /**
     * Actors first in the server's order (at most 20), then directors and writers; a person who is
     * both director and writer is one unit with 「导演 · 编剧」. Other kinds never come here
     * ([LocalStore.CAST_TYPES]).
     */
    fun units(entries: List<CastEntry>): List<CastUnit> {
        val sorted = entries.sortedBy { it.ord }
        val actors = sorted.filter { it.kind == "Actor" }.distinctBy { it.person.personKey }.take(MAX_ACTORS)
            .map { CastUnit(it.person.personKey, it.person.name, it.role?.takeIf(String::isNotBlank), null, it.person.image) }
        val shown = actors.map { it.personKey }.toSet()
        val crew = sorted.filter { it.kind == "Director" || it.kind == "Writer" }
            .groupBy { it.person.personKey }
            .filterKeys { it !in shown }
            .map { (_, rows) ->
                val kinds = rows.map { it.kind }.toSet()
                val badge = listOfNotNull("导演".takeIf { "Director" in kinds }, "编剧".takeIf { "Writer" in kinds }).joinToString(" · ")
                val p = rows.first().person
                CastUnit(p.personKey, p.name, null, badge, p.image)
            }
            .sortedBy { u -> if (u.badge?.startsWith("导演") == true) 0 else 1 }
        return actors + crew
    }

    /** The glass circle's letter when there is no picture: the name's first character. */
    fun initial(name: String): String = name.trim().firstOrNull()?.toString() ?: "?"

    /** 「1995 年生 · 演员 · 美国纽约」 (person page); the role is the kind seen most in the works. */
    fun personMeta(birthDate: String?, birthPlace: String?, kinds: List<String>): String {
        val year = birthDate?.take(4)?.toIntOrNull()
        val role = kinds.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key?.let { KIND_LABEL[it] }
        return listOfNotNull(year?.let { "$it 年生" }, role, birthPlace?.takeIf(String::isNotBlank)).joinToString(" · ")
    }

    val KIND_LABEL = mapOf("Actor" to "演员", "Director" to "导演", "Writer" to "编剧", "GuestStar" to "演员")
}
