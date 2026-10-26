package com.ebookreader.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Records which code a cold start runs, so it ships compiled ahead of time instead of
 * being interpreted and JIT-compiled on every launch (A10).
 *
 * Run with a device or emulator connected: `./gradlew :app:generateBaselineProfile`.
 * The profile lands in app/src/release/generated/baselineProfiles.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun startup() = rule.collect(packageName = PACKAGE, includeInStartupProfile = true) {
        pressHome()
        startActivityAndWait()
        // A fresh install starts on the welcome pages; the library is behind them.
        device.wait(Until.findObject(By.text("Skip")), TIMEOUT)?.click()
        device.wait(Until.hasObject(By.desc("Library menu")), TIMEOUT)
        // The library's menu and the settings pages are the next things most people open.
        device.findObject(By.desc("Library menu"))?.click()
        device.wait(Until.findObject(By.text("Settings")), TIMEOUT)?.click()
        device.wait(Until.findObject(By.text("Reading")), TIMEOUT)?.click()
        device.waitForIdle()
        device.pressBack()
        device.pressBack()
        device.wait(Until.hasObject(By.desc("Library menu")), TIMEOUT)
    }

    private companion object {
        const val PACKAGE = "com.ebookreader"
        const val TIMEOUT = 5_000L
    }
}
