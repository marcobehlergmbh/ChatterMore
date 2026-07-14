package com.example.mattermostlite.benchmark

import android.content.Intent
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MemoryUsageMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalMetricApi::class)
@RunWith(AndroidJUnit4::class)
class FeedBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun coldStartup() = benchmarkRule.measureRepeated(
        packageName = PACKAGE_NAME,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = CompilationMode.Partial(),
        startupMode = StartupMode.COLD,
        iterations = 10,
        setupBlock = { pressHome() },
        measureBlock = { startFixture() },
    )

    @Test
    fun scrollTwoThousandMessages() = benchmarkRule.measureRepeated(
        packageName = PACKAGE_NAME,
        metrics = listOf(
            FrameTimingMetric(),
            MemoryUsageMetric(MemoryUsageMetric.Mode.Last),
        ),
        compilationMode = CompilationMode.Partial(),
        startupMode = StartupMode.WARM,
        iterations = 10,
        setupBlock = {
            startFixture()
            device.wait(Until.hasObject(By.res(PACKAGE_NAME, "messageList")), 5_000)
        },
        measureBlock = {
            val list = device.findObject(By.res(PACKAGE_NAME, "messageList"))
            list.setGestureMargin(device.displayWidth / 5)
            repeat(8) {
                list.fling(Direction.DOWN)
                device.waitForIdle()
            }
            repeat(8) {
                list.fling(Direction.UP)
                device.waitForIdle()
            }
        },
    )

    private fun MacrobenchmarkScope.startFixture() {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            setClassName(PACKAGE_NAME, "$PACKAGE_NAME.MainActivity")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra("benchmark_message_count", 2_000)
        }
        startActivityAndWait(intent)
    }

    private companion object {
        const val PACKAGE_NAME = "com.example.mattermostlite"
    }
}
