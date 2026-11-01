package app.zmby.ui

import app.zmby.store.EpisodeMappingRule
import app.zmby.ui.components.ActionFeedback
import app.zmby.ui.components.actionAccepts
import app.zmby.ui.components.actionLook
import app.zmby.ui.screens.HeaderAction
import app.zmby.ui.screens.HoldHint
import app.zmby.ui.screens.RefreshState
import app.zmby.ui.screens.SECTION_CAST
import app.zmby.ui.screens.SECTION_EPISODES
import app.zmby.ui.screens.SECTION_RECS
import app.zmby.ui.screens.SECTION_TRACKS
import app.zmby.ui.screens.SECTION_VERSIONS
import app.zmby.ui.screens.actionTip
import app.zmby.ui.screens.failureNotice
import app.zmby.ui.screens.favoriteNotice
import app.zmby.ui.screens.groupState
import app.zmby.ui.screens.holdHint
import app.zmby.ui.screens.mappingChipCount
import app.zmby.ui.screens.quickPickSubtitle
import app.zmby.ui.screens.refreshState
import app.zmby.ui.screens.ruleSentence
import app.zmby.ui.screens.watchedNotice
import app.zmby.ui.screens.watchlistNotice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ui-spec §2.15: header button states, names, results and the version bar's hints. */
class DetailActionsTest {
    @Test fun `on is a ring on glass and focus is white so the two never look the same`() {
        val rest = actionLook(focused = false, on = false, busy = false, enabled = true)
        assertFalse(rest.white || rest.ring || rest.solidGlyph || rest.spinner)
        assertEquals(1f, rest.scale)
        val on = actionLook(focused = false, on = true, busy = false, enabled = true)
        assertTrue(on.ring && on.solidGlyph)
        assertFalse(on.white)
        val focused = actionLook(focused = true, on = false, busy = false, enabled = true)
        assertTrue(focused.white)
        assertFalse(focused.ring || focused.solidGlyph)
        assertEquals(1.08f, focused.scale)
        val onFocused = actionLook(focused = true, on = true, busy = false, enabled = true)
        assertTrue(onFocused.white && onFocused.solidGlyph)
        assertFalse(onFocused.ring)
    }

    @Test fun `busy turns the arc and ignores presses, unavailable is dimmed but still shown`() {
        val busy = actionLook(focused = true, on = true, busy = true, enabled = true)
        assertTrue(busy.spinner)
        assertFalse(busy.solidGlyph)
        assertFalse(actionAccepts(busy = true, enabled = true))
        val off = actionLook(focused = true, on = false, busy = false, enabled = false)
        assertEquals(0.4f, off.alpha)
        assertTrue(off.white)
        assertFalse(actionAccepts(busy = false, enabled = false))
        assertTrue(actionAccepts(busy = false, enabled = true))
    }

    @Test fun `focus tags name the current state`() {
        assertEquals("加入收藏", actionTip(HeaderAction.Favorite))
        assertEquals("已收藏", actionTip(HeaderAction.Favorite, on = true))
        assertEquals("标记为已观看", actionTip(HeaderAction.Watched))
        assertEquals("标记为未观看", actionTip(HeaderAction.Watched, on = true))
        assertEquals("加入待看", actionTip(HeaderAction.Watchlist))
        assertEquals("已在待看", actionTip(HeaderAction.Watchlist, on = true))
        assertEquals("快速选集", actionTip(HeaderAction.QuickPick))
        assertEquals("片头片尾", actionTip(HeaderAction.Marks))
        assertEquals("正在标记…", actionTip(HeaderAction.Watched, busy = true))
        assertEquals("正在取消收藏…", actionTip(HeaderAction.Favorite, on = true, busy = true))
        assertEquals("暂无服务器条目，不能收藏", actionTip(HeaderAction.Favorite, enabled = false))
        assertEquals("片头片尾 · 暂无可播放的版本", actionTip(HeaderAction.Marks, enabled = false))
    }

    @Test fun `result texts follow the design`() {
        assertEquals("已标记第 7 集为已观看 · 家里 NAS", watchedNotice(7, played = true, server = "家里 NAS"))
        assertEquals("已标记为未观看 · 家里 NAS", watchedNotice(null, played = false, server = "家里 NAS"))
        assertEquals("没能加入待看 · 公益服 A 连接超时", failureNotice("加入待看", "公益服 A", "连接超时"))
        assertEquals("没能保存季集映射 · 编号范围不正确", failureNotice("保存季集映射", null, "编号范围不正确"))
        assertEquals("没能加入收藏", failureNotice("加入收藏", null, null))
        assertEquals("已加入收藏", favoriteNotice(true))
        assertEquals("已移出待看", watchlistNotice(false))
    }

    @Test fun `a failure retries once on OK and a newer notice is not expired by the older timer`() {
        val feedback = ActionFeedback()
        var retried = 0
        feedback.failure("没能加入待看") { retried++ }
        assertTrue(feedback.retryPending)
        assertTrue(feedback.retry())
        assertEquals(1, retried)
        assertNull(feedback.notice)
        assertFalse(feedback.retry())
        feedback.success("已加入待看")
        assertFalse(feedback.retryPending)
        assertFalse(feedback.retry())
        val first = feedback.notice!!
        feedback.success("已加入收藏")
        feedback.expire(first)
        assertEquals("已加入收藏", feedback.notice?.text)
        feedback.expire(feedback.notice!!)
        assertNull(feedback.notice)
        feedback.failure("当前没有这组片源")
        assertFalse(feedback.retryPending)
    }

    @Test fun `hold hints name the neighbouring blocks and drop the missing half`() {
        val series = listOf(SECTION_EPISODES, SECTION_VERSIONS, SECTION_TRACKS, SECTION_CAST)
        assertEquals("长按 ↑ 剧集　长按 ↓ 音轨与字幕", holdHint(series, SECTION_VERSIONS).text)
        assertEquals("长按 ↑ 版本　长按 ↓ 演职人员", holdHint(series, SECTION_TRACKS).text)
        val movie = listOf(SECTION_VERSIONS, SECTION_TRACKS)
        assertEquals(HoldHint(null, "音轨与字幕"), holdHint(movie, SECTION_VERSIONS))
        assertEquals("长按 ↓ 音轨与字幕", holdHint(movie, SECTION_VERSIONS).text)
        assertEquals("长按 ↑ 版本", holdHint(movie, SECTION_TRACKS).text)
        assertEquals("长按 ↓ 为你推荐", holdHint(listOf(SECTION_VERSIONS, SECTION_RECS), SECTION_VERSIONS).text)
        assertNull(holdHint(listOf(SECTION_VERSIONS), SECTION_VERSIONS).text)
        assertNull(holdHint(movie, SECTION_CAST).text)
    }

    @Test fun `refresh has three states`() {
        assertEquals(RefreshState.Idle, refreshState(refreshing = false, searching = 0, unresponsive = 0))
        // Lookups still running without a press keep 刷新 (the status shows 还在查找 n 台).
        assertEquals(RefreshState.Idle, refreshState(refreshing = false, searching = 2, unresponsive = 0))
        assertEquals(RefreshState.Refreshing, refreshState(refreshing = true, searching = 2, unresponsive = 1))
        assertEquals(RefreshState.Unresponsive, refreshState(refreshing = false, searching = 0, unresponsive = 2))
        assertEquals(RefreshState.Unresponsive, refreshState(refreshing = true, searching = 0, unresponsive = 2))
        assertEquals("刷新", RefreshState.Idle.label(0))
        assertEquals("刷新中…", RefreshState.Refreshing.label(0))
        assertEquals("2 台未响应 · 重试", RefreshState.Unresponsive.label(2))
    }

    @Test fun `mapping texts`() {
        val range = EpisodeMappingRule("r", "tmdb:tv:10", "a", "parent", 4, 1, 8, 3)
        assertEquals("第 4 季 1–8 集 → 第 3 季 1–8 集", ruleSentence(range))
        assertEquals("第 4 季 1–8 集 → 第 3 季 13–20 集", ruleSentence(range.copy(offset = 12)))
        assertEquals("第 4 季第 7 集 → 第 3 季第 2 集", ruleSentence(range.copy(firstEpisode = 7, lastEpisode = 7, offset = -5, itemId = "f", targetEpisodeId = "e")))
        assertEquals("1 条规则", mappingChipCount(1))
        assertNull(mappingChipCount(0))
        val aligned = groupState(7, emptyList())
        assertEquals("已对齐", aligned.tag)
        assertEquals("7 条 · 已对齐", aligned.sub)
        assertFalse(aligned.warn)
        val unsorted = groupState(8, listOf("片源编号与目录不一致", "片源编号与目录不一致", "没有季集编号"))
        assertEquals("未归类 3", unsorted.tag)
        assertEquals("8 条 · 片源编号与目录不一致", unsorted.sub)
        assertTrue(unsorted.warn)
        assertEquals("漫长的季节 · 当前第 7 集", quickPickSubtitle("漫长的季节", 7))
        assertEquals("漫长的季节", quickPickSubtitle("漫长的季节", null))
    }
}
