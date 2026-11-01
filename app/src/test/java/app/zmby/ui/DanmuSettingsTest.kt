package app.zmby.ui

import app.zmby.data.DanmuMode
import app.zmby.ui.screens.danmuNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DanmuSettingsTest {
    @Test fun `the note says what keeps 弹幕 from showing in each mode`() {
        assertEquals("填写弹幕服务器后生效", danmuNote(DanmuMode.Subtitle, overlay = true, address = false, zidooPlayer = true))
        assertEquals("需要打开播放浮层", danmuNote(DanmuMode.Overlay, overlay = false, address = true, zidooPlayer = true))
        assertNull(danmuNote(DanmuMode.Overlay, overlay = true, address = true, zidooPlayer = false))
        // 播放器字幕 does not need the overlay, but it needs the Zidoo player.
        assertNull(danmuNote(DanmuMode.Subtitle, overlay = false, address = true, zidooPlayer = true))
        assertEquals("没有芝杜系统播放器，改用浮层显示", danmuNote(DanmuMode.Subtitle, overlay = true, address = true, zidooPlayer = false))
        assertEquals("需要芝杜系统播放器", danmuNote(DanmuMode.Subtitle, overlay = false, address = true, zidooPlayer = false))
    }
}
