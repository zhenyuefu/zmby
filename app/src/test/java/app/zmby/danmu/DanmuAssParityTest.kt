package app.zmby.danmu

import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.MessageDigest

/**
 * [DanmuAss] against danmu2ass 1.0.2 itself (commit 3b8046d, built with
 * `cargo build --release --no-default-features --features quick_xml,rustls`): each case's comments
 * were written as Bilibili XML (`zmby.xml`, p = "seconds,type,25,colour,0,0,0,0") and converted with
 * the CLI flags of [args]; [expected] is the SHA-256 of the file it wrote (331–1327 events each,
 * with delays and drops). 2026-11-01 these and a further 124 seeded cases (random sizes, gaps,
 * durations, offsets, opacity, bold) matched byte for byte.
 */
class DanmuAssParityTest {
    private data class Case(val seed: Long, val count: Int, val config: DanmuAss.Config, val expected: String)

    private val texts = listOf("前方高能", "哈哈哈哈哈哈哈哈", "awsl", "Hello world 123", "名场面 来了", "😂😂笑死", "这集太好看了吧，一口气看完", "a", "第一次看 有点紧张 (笑)", "多行\n弹幕")

    /** The comments of a case; the XML for the CLI is [xml]. */
    private fun items(seed: Long, count: Int): List<DanmuAss.Item> {
        val r = java.util.Random(seed)
        return (0 until count).map { i ->
            val ms = when (i % 13) {
                0 -> 1125L + 1000L * r.nextInt(60) // ties at the second decimal
                1 -> 2375L + 1000L * r.nextInt(60)
                else -> (r.nextDouble() * 300_000).toLong()
            }
            DanmuAss.Item(ms / 1000.0, texts[r.nextInt(texts.size)] + (if (r.nextBoolean()) i.toString() else ""), if (r.nextInt(4) == 0) r.nextInt(0x1000000) else 0xFFFFFF)
        }
    }

    /** What the CLI read for a case (types 1, 4, 5, 6 all scroll). */
    @Suppress("unused")
    private fun xml(items: List<DanmuAss.Item>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><i>\n")
        for ((i, it) in items.withIndex()) {
            val ms = Math.round(it.timeline * 1000)
            append("<d p=\"${ms / 1000}.${"%03d".format(ms % 1000)},${listOf(1, 1, 1, 4, 5, 6)[i % 6]},25,${it.rgb},0,0,0,0\">${it.content}</d>\n")
        }
        append("</i>\n")
    }

    /** The CLI flags for [config], with the XML named `zmby.xml` (its stem is the title). */
    @Suppress("unused")
    private fun args(c: DanmuAss.Config): List<String> = listOf(
        "--width=${c.width}", "--height=${c.height}", "--font=${c.font}", "--font-size=${c.fontSize}", "--width-ratio=${c.widthRatio}",
        "--horizontal-gap=${c.horizontalGap}", "--duration=${c.duration}", "--lane-size=${c.laneSize}", "--float-percentage=${c.floatPercentage}",
        "--alpha=${c.alpha}", "--outline=${c.outline}", "--time-offset=${c.timeOffset}",
    ) + if (c.bold) listOf("--bold") else emptyList()

    private val cases = listOf(
        Case(101, 400, DanmuAss.Config.of(3, 100), "71e174775a7b53940a7413aa8ee01170a5a1c822eff1d2fed9d2cc3fa858923b"),
        Case(102, 3000, DanmuAss.Config.of(5, 75), "724947442748ec4472c57c6eb2820fc95f0c2b7e88e43ec2f58718196502f003"),
        Case(103, 3000, DanmuAss.Config(), "df8ebe194bcd66a4eb1fb9086d6e6ade8fc2de6018f8599ef401ec95a4522b28"),
        Case(104, 800, DanmuAss.Config(width = 1280, height = 720, font = "黑体", fontSize = 25, duration = 15.0, laneSize = 32, floatPercentage = 0.5, alpha = 0.7), "ceda5180bdeb3478caff6c330c376aceeb85bd8df2dc37c161f8a880f7ae11e9"),
        Case(105, 1500, DanmuAss.Config(bold = true, outline = 1.5, timeOffset = -3.5, horizontalGap = 0.0, widthRatio = 1.0), "3226b262bd50249242d61ed1be743ea373718fa33be5896a7497baf58b1bd8b2"),
    )

    private fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    @Test fun `the port writes exactly what the danmu2ass CLI wrote`() {
        for ((i, c) in cases.withIndex()) {
            assertEquals("case $i (seed ${c.seed})", c.expected, sha256(DanmuAss.build(items(c.seed, c.count), c.config)))
        }
    }

    /** Writes each case's XML, flags and our output for checking against the CLI again. */
    @Test fun `cases can be exported for the CLI`() {
        val out = System.getProperty("zmby.danmu2ass.export") ?: return
        for ((i, c) in cases.withIndex()) {
            val dir = java.io.File(out, "case$i").apply { mkdirs() }
            val list = items(c.seed, c.count)
            java.io.File(dir, "zmby.xml").writeText(xml(list))
            java.io.File(dir, "args").writeText(args(c.config).joinToString("\n"))
            java.io.File(dir, "kotlin.ass").writeText(DanmuAss.build(list, c.config))
        }
    }
}
