package app.zmby.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cold start to home without ahead-of-time compilation and with the Baseline Profile (performance
 * plan R6: 「Profile 前后冷启动各测 4 次」): `./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest`.
 * Emulator numbers are only relative; conclusions need the box.
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule val rule = MacrobenchmarkRule()

    @Test fun startupWithoutCompilation() = startup(CompilationMode.None())

    @Test fun startupWithBaselineProfile() = startup(CompilationMode.Partial(BaselineProfileMode.Require))

    private fun startup(mode: CompilationMode) = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = mode,
        startupMode = StartupMode.COLD,
        iterations = 4,
    ) { home() }
}
