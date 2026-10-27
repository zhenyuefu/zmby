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
@Config(sdk = [28])
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
        first.metadataLanguage.set("ja-JP");first.quality.set(QualityPreference.UHD);first.metadataCacheMb.set(64);first.skipIntro.set(false);first.setWatchedPercent(80)
        val backup=first.backupValues()
        first.metadataLanguage.set("en-US");first.quality.set(QualityPreference.Auto);first.metadataCacheMb.set(128);first.skipIntro.set(true)
        first.restoreValues(backup)
        assertEquals("ja-JP",first.metadataLanguage.flow.value);assertEquals(QualityPreference.UHD,first.quality.flow.value)
        assertEquals(64,first.metadataCacheMb.value);assertFalse(first.skipIntro.value);assertEquals(80,first.watchedPercent.value)
        first.restoreValues(kotlinx.serialization.json.buildJsonObject { put("metadata_cache_mb",kotlinx.serialization.json.JsonPrimitive("bad")); put("skip_intro",kotlinx.serialization.json.JsonPrimitive("bad")) })
        assertEquals(64,first.metadataCacheMb.value);assertFalse(first.skipIntro.value)
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

    @Test fun `fresh install skips both intro and recap`() {
        val settings = Settings(context)
        assertTrue(settings.skipIntro.value)
        assertTrue(settings.skipRecap.value)
    }

    @Test fun `upgrade preserves either value of the old combined switch`() {
        for (oldValue in listOf(false, true)) {
            prefs.edit().clear().putBoolean("skip_intro", oldValue).commit()
            val settings = Settings(context)
            assertEquals(oldValue, settings.skipIntro.value)
            assertEquals(oldValue, settings.skipRecap.value)
            assertTrue(prefs.contains("skip_recap"))
        }
    }

    @Test fun `reopening settings never copies intro over the saved recap choice`() {
        // After migration, users can save either independent combination.
        for (intro in listOf(false, true)) {
            prefs.edit().putBoolean("skip_intro", intro).putBoolean("skip_recap", !intro).commit()
            repeat(2) {
                val settings = Settings(context)
                assertEquals(intro, settings.skipIntro.value)
                assertEquals(!intro, settings.skipRecap.value)
            }
        }
    }

    @Test fun `changing either switch leaves the other flow alone`() {
        val settings = Settings(context)
        settings.skipIntro.set(false)
        assertFalse(settings.skipIntro.flow.value)
        assertTrue(settings.skipRecap.flow.value)
        settings.skipRecap.set(false)
        settings.skipIntro.set(true)
        assertTrue(settings.skipIntro.flow.value)
        assertFalse(settings.skipRecap.flow.value)
        finishPreferenceWrites()
        val reloaded = Settings(context)
        assertTrue(reloaded.skipIntro.value)
        assertFalse(reloaded.skipRecap.value)
    }
}
