package com.marcobehler.chattermore.benchmark

import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ComposerKeyboardTest {
    private lateinit var device: UiDevice
    private var closedComposerBottom = 0

    @Before
    fun launchConversation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        device = UiDevice.getInstance(instrumentation)
        device.executeShellCommand("am force-stop $PACKAGE_NAME")
        instrumentation.context.startActivity(Intent(Intent.ACTION_MAIN).apply {
            setClassName(PACKAGE_NAME, "$PACKAGE_NAME.MainActivity")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra("benchmark_screen", "store-channel")
        })
        assertTrue(device.wait(Until.hasObject(By.res(PACKAGE_NAME, "messageInput")), 5_000))
        device.waitForIdle()
        closedComposerBottom = composer().visibleBounds.bottom
    }

    @Test
    fun tappingMessageWhileTypingKeepsComposerFocusAndCursor() {
        composer().click()
        awaitKeyboard(visible = true)
        composer().text = "hello world"
        repeat(5) { device.pressDPadLeft() }
        device.findObjects(By.res(PACKAGE_NAME, "messageBody")).first().click()
        assertTrue("Feed text stole the typing focus", composer().isFocused)
        device.executeShellCommand("input text X")
        assertEquals("hello Xworld", composer().text)
        device.pressBack()
        awaitKeyboard(visible = false)
        assertEquals("hello Xworld", composer().text)
        assertTrue(device.hasObject(By.res(PACKAGE_NAME, "messageList")))
    }

    @Test
    fun backDuringEditHidesKeyboardWithoutCancellingEditOrMovingCursor() {
        device.findObject(By.text(OWN_MESSAGE)).longClick()
        awaitKeyboard(visible = true)
        assertTrue(composer().isFocused)
        assertEquals(OWN_MESSAGE, composer().text)
        repeat(3) { device.pressDPadLeft() }
        device.pressBack()
        awaitKeyboard(visible = false)
        assertTrue(device.hasObject(By.res(PACKAGE_NAME, "editModeHeader")))
        // Give any old delayed focus retry enough time to run, then type at the saved cursor.
        device.waitForIdle()
        assertTrue(composer().visibleBounds.bottom >= closedComposerBottom - 50)
        device.executeShellCommand("input text X")
        assertEquals(OWN_MESSAGE.dropLast(3) + "X" + OWN_MESSAGE.takeLast(3), composer().text)
    }

    @Test
    fun toolbarBackHidesKeyboardAndPreservesDraft() {
        composer().click()
        awaitKeyboard(visible = true)
        composer().text = "draft to keep"
        device.waitForIdle()
        awaitKeyboard(visible = true)
        device.findObject(By.res(PACKAGE_NAME, "conversationBackButton")).click()
        awaitKeyboard(visible = false)
        assertEquals("draft to keep", composer().text)
    }

    @Test
    fun searchCanTakeFocusFromComposerAndBackHidesItsKeyboardFirst() {
        composer().click()
        awaitKeyboard(visible = true)
        composer().text = "draft to keep"
        device.findObject(By.res(PACKAGE_NAME, "searchMessagesButton")).click()
        assertTrue(device.wait(Until.hasObject(By.res(PACKAGE_NAME, "messageSearchInput").focused(true)), 5_000))
        device.pressBack()
        device.waitForIdle()
        assertTrue(device.hasObject(By.res(PACKAGE_NAME, "messageSearchInput")))
        device.pressBack()
        assertTrue(device.wait(Until.hasObject(By.res(PACKAGE_NAME, "messageInput")), 5_000))
        awaitKeyboard(visible = false)
        assertEquals("draft to keep", composer().text)
    }

    private fun composer(): UiObject2 = device.findObject(By.res(PACKAGE_NAME, "messageInput"))

    private fun awaitKeyboard(visible: Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            val bottom = device.findObject(By.res(PACKAGE_NAME, "messageInput"))?.visibleBounds?.bottom
            if (bottom != null &&
                (if (visible) bottom < closedComposerBottom - 100 else bottom >= closedComposerBottom - 50)
            ) {
                device.waitForIdle()
                return
            }
            SystemClock.sleep(50)
        }
        throw AssertionError("Keyboard did not become ${if (visible) "visible" else "hidden"}")
    }

    private companion object {
        const val PACKAGE_NAME = "com.marcobehler.chattermore"
        const val OWN_MESSAGE = "On it — I’ll post the final copy here."
    }
}
