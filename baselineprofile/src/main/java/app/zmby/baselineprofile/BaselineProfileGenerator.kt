package app.zmby.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Collects zmby's Baseline Profile from the remote-control [journeys] (performance plan R6):
 * `./gradlew :app:generateBaselineProfile` with an Android 13+ (or rooted) emulator or device
 * connected, the mock servers imported. The result replaces the hand-written rules in
 * app/src/main/baseline-prof.txt.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule val rule = BaselineProfileRule()

    /** Cold start to home: also the startup profile, which lays out the code start-up runs. */
    @Test fun startup() = rule.collect(packageName = PACKAGE, includeInStartupProfile = true) { home() }

    @Test fun paths() = rule.collect(packageName = PACKAGE) { journeys() }
}
