package app.zmby.playback

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MediaStorePlaybackStoreTest {
    @Test fun android9CanConstructStoreButLocalStrmReportsUnsupportedApi() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = MediaStorePlaybackStore(context.contentResolver)
        assertEquals("Download/zmby-playback/", store.location)
        val error = assertThrows(IOException::class.java) {
            store.publish("zmby_test.strm", "http://example.com/video\n".toByteArray())
        }
        assertEquals("本地 STRM 外挂字幕需要 Android 10 或更新版本", error.message)
    }
}
