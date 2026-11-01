package app.zmby.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
// A plain Application: ZmbyApp builds its own Settings in the background, racing these tests' prefs.
@Config(sdk = [28], application = android.app.Application::class)
class SettingsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs get() = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    @Before fun clearPreferences() {
        prefs.edit().clear().commit()
    }

    @After fun finishPreferenceWrites() {
        val done = CountDownLatch(1)
        Disk.write { done.countDown() }
        assertTrue("preference writes finished", done.await(5, TimeUnit.SECONDS))
    }

    @Test fun `backup reads live preferences and restore updates flows with type validation`() {
        val first=Settings(context)
        first.metadataLanguage.set("ja-JP");first.quality.set(QualityPreference.UHD);first.metadataCacheMb.set(64);first.skipIntro.set(app.zmby.segments.SkipMode.Off);first.setWatchedPercent(80)
        val backup=first.backupValues()
        first.metadataLanguage.set("en-US");first.quality.set(QualityPreference.Auto);first.metadataCacheMb.set(128);first.skipIntro.set(app.zmby.segments.SkipMode.Auto)
        first.restoreValues(backup)
        assertEquals("ja-JP",first.metadataLanguage.flow.value);assertEquals(QualityPreference.UHD,first.quality.flow.value)
        assertEquals(64,first.metadataCacheMb.value);assertEquals(app.zmby.segments.SkipMode.Off,first.skipIntro.value);assertEquals(80,first.watchedPercent.value)
        first.restoreValues(kotlinx.serialization.json.buildJsonObject { put("metadata_cache_mb",kotlinx.serialization.json.JsonPrimitive("bad")); put("skip_intro_mode",kotlinx.serialization.json.JsonPrimitive("bad")) })
        assertEquals(64,first.metadataCacheMb.value);assertEquals(app.zmby.segments.SkipMode.Off,first.skipIntro.value)
    }

    @Test fun `TMDB address persists normalizes restores and resets without changing the token`() {
        val settings = Settings(context)
        assertEquals("https://api.tmdb.org/3/", settings.tmdbApiBase.value)
        settings.tmdbToken.set("test-token")
        settings.tmdbApiBase.set("https://example.com/proxy/3")
        val backup = settings.backupValues()
        finishPreferenceWrites()
        assertEquals("https://example.com/proxy/3/", Settings(context).tmdbApiBase.value)
        settings.tmdbApiBase.set("")
        assertEquals("https://api.tmdb.org/3/", settings.tmdbApiBase.value)
        settings.restoreValues(backup)
        assertEquals("https://example.com/proxy/3/", settings.tmdbApiBase.flow.value)
        settings.tmdbApiBase.set("https://example.com?api_key=secret")
        assertEquals("https://api.tmdb.org/3/", settings.tmdbApiBase.value)
        assertEquals("test-token", settings.tmdbToken.value)
    }

    @Test fun `cache budgets have defaults and persist independently`() {
        val settings = Settings(context)
        assertEquals(128, settings.metadataCacheMb.value)
        assertEquals(300, settings.imageCacheMb.value)
        settings.metadataCacheMb.set(256)
        settings.imageCacheMb.set(1024)
        finishPreferenceWrites()
        val reloaded = Settings(context)
        assertEquals(256, reloaded.metadataCacheMb.value)
        assertEquals(1024, reloaded.imageCacheMb.value)
    }

    @Test fun `invalid restored cache budgets fall back to supported defaults`() {
        prefs.edit().putInt("metadata_cache_mb", -1).putInt("image_cache_mb", Int.MAX_VALUE).commit()
        val settings = Settings(context)
        assertEquals(128, settings.metadataCacheMb.value)
        assertEquals(300, settings.imageCacheMb.value)
        settings.metadataCacheMb.set(17)
        assertEquals(128, settings.metadataCacheMb.flow.value)
        finishPreferenceWrites()
        assertEquals(128, Settings(context).metadataCacheMb.value)
    }

    @Test fun `fresh install skips both intro and recap automatically`() {
        val settings = Settings(context)
        assertEquals(app.zmby.segments.SkipMode.Auto, settings.skipIntro.value)
        assertEquals(app.zmby.segments.SkipMode.Auto, settings.skipRecap.value)
        assertEquals(app.zmby.segments.SkipMode.Auto, settings.skipCreditsMode.value)
        assertFalse(settings.playbackOverlay.value)
    }

    @Test fun `upgrade preserves either value of the old combined switch`() {
        for (oldValue in listOf(false, true)) {
            prefs.edit().clear().putBoolean("skip_intro", oldValue).commit()
            val settings = Settings(context)
            val mode = if (oldValue) app.zmby.segments.SkipMode.Auto else app.zmby.segments.SkipMode.Off
            assertEquals(mode, settings.skipIntro.value)
            assertEquals(mode, settings.skipRecap.value)
            assertTrue(prefs.contains("skip_recap"))
        }
    }

    @Test fun `the independent switches become modes once and saved modes are kept`() {
        for (intro in listOf(false, true)) {
            prefs.edit().clear().putBoolean("skip_intro", intro).putBoolean("skip_recap", !intro).commit()
            val first = Settings(context)
            assertEquals(if (intro) app.zmby.segments.SkipMode.Auto else app.zmby.segments.SkipMode.Off, first.skipIntro.value)
            assertEquals(if (intro) app.zmby.segments.SkipMode.Off else app.zmby.segments.SkipMode.Auto, first.skipRecap.value)
            first.skipIntro.set(app.zmby.segments.SkipMode.Button)
            finishPreferenceWrites()
            repeat(2) { assertEquals(app.zmby.segments.SkipMode.Button, Settings(context).skipIntro.value) }
        }
    }

    @Test fun `an old backup with the on-off switches restores as modes`() {
        val settings = Settings(context)
        settings.restoreValues(kotlinx.serialization.json.buildJsonObject {
            put("skip_intro", kotlinx.serialization.json.JsonPrimitive(false))
            put("skip_recap", kotlinx.serialization.json.JsonPrimitive(true))
        })
        assertEquals(app.zmby.segments.SkipMode.Off, settings.skipIntro.value)
        assertEquals(app.zmby.segments.SkipMode.Auto, settings.skipRecap.value)
        settings.skipCreditsMode.set(app.zmby.segments.SkipMode.Off)
        assertEquals(app.zmby.segments.SkipMode.Auto, settings.skipCreditsMode.value)
    }

    @Test fun `changing either mode leaves the other flow alone`() {
        val settings = Settings(context)
        settings.skipIntro.set(app.zmby.segments.SkipMode.Off)
        assertEquals(app.zmby.segments.SkipMode.Off, settings.skipIntro.flow.value)
        assertEquals(app.zmby.segments.SkipMode.Auto, settings.skipRecap.flow.value)
        settings.skipRecap.set(app.zmby.segments.SkipMode.Off)
        settings.skipIntro.set(app.zmby.segments.SkipMode.Button)
        assertEquals(app.zmby.segments.SkipMode.Button, settings.skipIntro.flow.value)
        assertEquals(app.zmby.segments.SkipMode.Off, settings.skipRecap.flow.value)
        finishPreferenceWrites()
        val reloaded = Settings(context)
        assertEquals(app.zmby.segments.SkipMode.Button, reloaded.skipIntro.value)
        assertEquals(app.zmby.segments.SkipMode.Off, reloaded.skipRecap.value)
    }

    @Test fun `在看包含下一集 keeps the value of the former 下一集 row switch`() {
        prefs.edit().putBoolean("home_next_up", false).commit()
        assertFalse(Settings(context).watchingIncludesNextUp.value)
        prefs.edit().clear().commit()
        assertTrue(Settings(context).watchingIncludesNextUp.value)
        // Backups written before the rename restore it too.
        val restored = Settings(context)
        restored.restoreValues(kotlinx.serialization.json.JsonObject(mapOf("home_next_up" to kotlinx.serialization.json.JsonPrimitive(false))))
        assertFalse(restored.watchingIncludesNextUp.value)
    }
}
