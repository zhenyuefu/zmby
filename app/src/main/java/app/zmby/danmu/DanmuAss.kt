package app.zmby.danmu

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 弹幕 as an ASS subtitle (danmu design: 播放器字幕): a line-for-line Kotlin port of danmu2ass 1.0.2
 * (https://github.com/gwy15/danmu2ass, commit 3b8046d, MIT licence, © gwy15): its lane layout
 * (`canvas/mod.rs`, `canvas/lane.rs`), text length (`danmu.rs`) and writer (`ass_writer.rs`). Given the
 * same comments and settings the output is byte for byte what its CLI writes; `DanmuAssParityTest`
 * checks that against files the CLI produced. Text cleaning for the screen happens before ([safeText]),
 * so the converter itself stays as upstream. The file goes to the player's second subtitle; the main
 * subtitle stays its own track. Pure, for tests.
 */
object DanmuAss {
    /** danmu2ass's canvas settings, with its CLI's meaning. Defaults are the values tried on the Z9X 8K. */
    data class Config(
        val width: Int = 1920,
        val height: Int = 1080,
        val font: String = "Noto Sans CJK SC",
        val fontSize: Int = 42,
        /** Text length scale (CLI `--width-ratio`). */
        val widthRatio: Double = 1.2,
        /** Least horizontal distance between two comments (CLI `--horizontal-gap`). */
        val horizontalGap: Double = 20.0,
        /** Seconds a comment takes to cross the screen (CLI `--duration`). */
        val duration: Double = 10.0,
        val laneSize: Int = 58,
        /** Share of the height used by scrolling comments (CLI `--float-percentage`). */
        val floatPercentage: Double = 0.35,
        /** Text opacity 0–1 (CLI `--alpha`). */
        val alpha: Double = 0.85,
        val outline: Double = 0.8,
        val bold: Boolean = false,
        val timeOffset: Double = 0.0,
        val title: String = "zmby",
    ) {
        /** ASS alpha byte, as the CLI computes it: `((1.0 - alpha) * 255.0) as u8`. */
        val opacity: Int get() = ((1.0 - alpha) * 255.0).toInt().coerceIn(0, 255)

        /** `(float_percentage * height / lane_size) as usize`. */
        val lanes: Int get() = (floatPercentage * height.toDouble() / laneSize.toDouble()).toInt().coerceAtLeast(0)

        companion object {
            /** The user's 行数 and 不透明度 as danmu2ass settings: exactly [rows] lanes. */
            fun of(rows: Int, opacityPercent: Int): Config {
                val base = Config()
                return base.copy(
                    floatPercentage = (rows.coerceIn(1, 10) + 0.5) * base.laneSize / base.height,
                    alpha = opacityPercent.coerceIn(0, 100) / 100.0,
                )
            }
        }
    }

    /** One comment as danmu2ass sees it: time in seconds, text, colour 0xRRGGBB. */
    data class Item(val timeline: Double, val content: String, val rgb: Int)

    /** A comment placed by the canvas: its (possibly delayed) start, and the move. */
    data class Drawn(val item: Item, val x0: Int, val y: Int, val x1: Int)

    /** `Danmu::length`: non-ASCII counts 3, ASCII 2, per 3 em (integer maths), times the width ratio. */
    fun length(content: String, config: Config): Double {
        var units = 0L
        var i = 0
        while (i < content.length) {
            val cp = content.codePointAt(i)
            units += if (cp < 0x80) 2 else 3
            i += Character.charCount(cp)
        }
        val pts = config.fontSize.toLong() * units / 3
        return pts.toDouble() * config.widthRatio
    }

    private class Lane(val lastShootTime: Double, val lastLength: Double)

    private sealed interface Collision {
        data object Fits : Collision
        data class Collide(val timeNeeded: Double) : Collision
    }

    /** `Lane::available_for`: whether a comment at [time] of [length] can follow the lane's last one. */
    private fun availableFor(lane: Lane, time: Double, length: Double, config: Config): Collision {
        val t = config.duration
        val w = config.width.toDouble()
        val gap = config.horizontalGap
        val t1 = lane.lastShootTime
        val l1 = lane.lastLength
        val l2 = length
        val v1 = (w + l1) / t
        val v2 = (w + l2) / t
        val deltaT = time - t1
        // The first comment's right end to the right edge of the screen.
        val deltaX = v1 * deltaT - l1
        return if (deltaX < gap) {
            if (l2 <= l1) Collision.Collide((gap - deltaX) / v1)
            else Collision.Collide((t - (w - gap) / v2) - deltaT)
        } else if (l2 <= l1) {
            Collision.Fits // Separate: the shorter one is slower and never catches up
        } else {
            val pos = v2 * (t - deltaT)
            if (pos < (w - gap)) Collision.Fits // NotEnoughTime: the first has gone before it is caught
            else Collision.Collide((pos - (w - gap)) / v2)
        }
    }

    /**
     * `convert` + `Canvas::draw`: comments in time order (stable), each into the first free lane, or
     * delayed by less than a second into the lane that frees first, else dropped. Every type scrolls.
     */
    fun layout(items: List<Item>, config: Config): List<Drawn> {
        val lanes = arrayOfNulls<Lane>(config.lanes)
        val out = ArrayList<Drawn>(items.size)
        for (raw in items.sortedBy { it.timeline }) {
            var item = raw.copy(timeline = raw.timeline + config.timeOffset)
            if (item.timeline < 0.0) continue
            val len = length(item.content, config)
            var chosen = -1
            val collisions = ArrayList<Pair<Double, Int>>()
            for (idx in lanes.indices) {
                val lane = lanes[idx]
                if (lane == null) { chosen = idx; break }
                when (val c = availableFor(lane, item.timeline, len, config)) {
                    Collision.Fits -> { chosen = idx; break }
                    is Collision.Collide -> collisions += c.timeNeeded to idx
                }
            }
            if (chosen < 0 && collisions.isNotEmpty()) {
                val (need, idx) = collisions.minWith(compareBy<Pair<Double, Int>> { it.first }.thenBy { it.second })
                if (need < 1.0) {
                    item = item.copy(timeline = item.timeline + (need + 0.01)) // Rust: `timeline_s += time_need + 0.01`
                    chosen = idx
                }
            }
            if (chosen < 0) continue
            lanes[chosen] = Lane(item.timeline, len)
            out += Drawn(item, config.width, chosen * config.laneSize, -(len.toInt()))
        }
        return out
    }

    /** The whole file, as `AssWriter` writes it. */
    fun build(items: List<Item>, config: Config): String {
        val sb = StringBuilder()
        val w = config.width
        val h = config.height
        sb.append("[Script Info]\n")
        sb.append("; Script generated by danmu2ass\n")
        sb.append("Title: ${config.title}\n")
        sb.append("Script Updated By: danmu2ass (https://github.com/gwy15/danmu2ass)\n")
        sb.append("ScriptType: v4.00+\n")
        sb.append("PlayResX: $w\n")
        sb.append("PlayResY: $h\n")
        sb.append("Aspect Ratio: $w:$h\n")
        sb.append("Collisions: Normal\n")
        sb.append("WrapStyle: 2\n")
        sb.append("ScaledBorderAndShadow: yes\n")
        sb.append("YCbCr Matrix: TV.601\n")
        sb.append("\n\n")
        sb.append("[V4+ Styles]\n")
        sb.append("Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\n")
        val a = "%02x".format(config.opacity)
        for (style in listOf("Float", "Bottom", "Top")) {
            sb.append("Style: $style,${config.font},${config.fontSize},&H${a}FFFFFF,&H00FFFFFF,&H${a}000000,&H00000000,")
            sb.append("${if (config.bold) 1 else 0}, 0, 0, 0, 100, 100, 0.00, 0.00, 1, ")
            sb.append("${number(config.outline)}, 0, 7, 0, 0, 0, 1\n")
        }
        sb.append("\n[Events]\n")
        sb.append("Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n")
        for (d in layout(items, config)) {
            val c = d.item.rgb
            sb.append("Dialogue: 2,").append(time(d.item.timeline)).append(',').append(time(d.item.timeline + config.duration))
            sb.append(",Float,,0,0,0,,{\\move(${d.x0}, ${d.y}, ${d.x1}, ${d.y})")
            sb.append("\\c&H%02x%02x%02x&}".format(c and 0xFF, c shr 8 and 0xFF, c shr 16 and 0xFF))
            sb.append(escape(d.item.content)).append('\n')
        }
        return sb.toString()
    }

    /** Our comments for [build]: seconds from milliseconds, text made safe for ASS. */
    fun items(comments: List<Danmaku>): List<Item> =
        comments.map { Item(it.timeMs / 1000.0, safeText(it.text), it.color and 0xFFFFFF) }

    /** The file as the player gets it: UTF-8 without a BOM, as danmu2ass writes it (what was tried on the box). */
    fun bytes(ass: String): ByteArray = ass.toByteArray(Charsets.UTF_8)

    /**
     * Before conversion, not part of it: braces would start override tags and a backslash an escape,
     * so they become their full-width forms (danmu2ass passes them through).
     */
    fun safeText(text: String): String = text.replace('\\', '＼').replace('{', '｛').replace('}', '｝')

    /** `TimePoint`: `{hour}:{minutes:02}:{left:05.2}`, with Rust's rounding of the seconds. */
    fun time(t: Double): String {
        val secs = Math.floor(t).toLong().coerceAtLeast(0)
        val hour = secs / 3600
        val minutes = (secs % 3600) / 60
        val left = t - (hour * 3600).toDouble() - (minutes * 60).toDouble()
        val s = BigDecimal(left).setScale(2, RoundingMode.HALF_EVEN).toPlainString()
        return "$hour:${"%02d".format(minutes)}:${s.padStart(5, '0')}"
    }

    /** `escape_text`: trimmed, line breaks as `\N`. */
    fun escape(text: String): String = text.trim().replace("\n", "\\N")

    /** An f64 as Rust's `Display` writes it (no trailing `.0`). */
    private fun number(v: Double): String = BigDecimal(v.toString()).stripTrailingZeros().toPlainString()
}
