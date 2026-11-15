package com.example

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.example.api.AiProvider
import com.example.api.ProviderClient
import com.example.automation.AutonomousAgentEngine
import com.example.automation.GroundingPoint
import com.example.automation.GuiGroundingProvider
import com.example.automation.RoutedVoiceCommand
import com.example.automation.ScreenActionResult
import com.example.automation.ScreenElementNode
import com.example.automation.ScreenReaderV2
import com.example.automation.ScreenSnapshot
import com.example.automation.SetOfMarkGrounding
import com.example.automation.SwipeDirection
import com.example.automation.TestPlaygroundScreen
import com.example.automation.VoiceCommandRouter
import com.example.preferences.AppLanguage
import com.example.tools.MahiToolsManager
import com.example.tools.MahiVisionToolDelegate
import com.example.tools.ToolAction
import com.example.tools.ToolExecutionResult
import com.example.ui.components.ScreenDoctorModal
import com.example.ui.theme.MahiAiTheme
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ScreenEngineV2Test {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val appContext: Application
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `ScreenReaderV2 supports deep hierarchy over depth 14 and filters systemui while keeping foreground systemui`() {
        // Build a 20-level deep chain (depth 0..20 > old limit 14) ending in a leaf button
        val leaf = AccessibilityNodeInfo.obtain().apply {
            packageName = "com.instagram.android"
            className = "android.widget.Button"
            text = "Deep Like"
            isClickable = true
            isVisibleToUser = true
            setBoundsInScreen(Rect(100, 800, 320, 900))
        }
        var current = leaf
        for (d in 19 downTo 0) {
            val parent = AccessibilityNodeInfo.obtain().apply {
                packageName = "com.instagram.android"
                className = "android.widget.FrameLayout"
                isVisibleToUser = true
                setBoundsInScreen(Rect(0, 0, 1080, 2400))
            }
            shadowOf(parent).addChild(current)
            current = parent
        }
        val sysUiStatus = AccessibilityNodeInfo.obtain().apply {
            packageName = "com.android.systemui"
            className = "android.widget.TextView"
            text = "9:41 AM"
            isVisibleToUser = true
            setBoundsInScreen(Rect(20, 10, 200, 60))
        }

        val (snap, live) = ScreenReaderV2.capture(
            roots = listOf(current to false, sysUiStatus to false),
            imeBounds = Rect(0, 1500, 1080, 2400),
            screenWidth = 1080,
            screenHeight = 2400
        )

        assertEquals("com.instagram.android", snap.packageName)
        assertTrue(snap.isKeyboardOpen)
        assertEquals(1500, snap.imeBounds.top)
        assertTrue(snap.elements.any { it.text == "Deep Like" })
        // systemui background bar must be filtered out when Instagram is foreground
        assertFalse(snap.elements.any { it.text == "9:41 AM" })
        assertEquals(snap.elements.size, live.size)

        // When systemui itself IS the primary foreground (Quick Settings / shade), keep it!
        val (sysSnap, _) = ScreenReaderV2.capture(
            roots = listOf(sysUiStatus to false),
            imeBounds = null,
            screenWidth = 1080,
            screenHeight = 2400
        )
        assertEquals("com.android.systemui", sysSnap.packageName)
        assertTrue(sysSnap.elements.any { it.text == "9:41 AM" })
    }

    @Test
    fun `ScreenReaderV2 merges descendant labels, masks passwords and OTPs, caps at 80, and prioritizes dialogs`() {
        val root = AccessibilityNodeInfo.obtain().apply {
            packageName = "com.whatsapp"
            className = "android.widget.FrameLayout"
            isVisibleToUser = true
            setBoundsInScreen(Rect(0, 0, 1080, 2400))
        }

        // Blank clickable row with inner TextView child
        val childTxt = AccessibilityNodeInfo.obtain().apply {
            packageName = "com.whatsapp"
            className = "android.widget.TextView"
            text = "Send Voice Note"
            isVisibleToUser = true
            setBoundsInScreen(Rect(100, 600, 480, 680))
        }
        val blankBtn = AccessibilityNodeInfo.obtain().apply {
            packageName = "com.whatsapp"
            className = "android.widget.LinearLayout"
            isClickable = true
            isVisibleToUser = true
            setBoundsInScreen(Rect(80, 590, 520, 690))
        }
        shadowOf(blankBtn).addChild(childTxt)
        shadowOf(root).addChild(blankBtn)

        // Password field + OTP node + 95 extra buttons to verify top-80 cap
        val pwdNode = AccessibilityNodeInfo.obtain().apply {
            packageName = "com.whatsapp"
            className = "android.widget.EditText"
            text = "SuperSecret123!"
            isPassword = true
            isEditable = true
            isVisibleToUser = true
            setBoundsInScreen(Rect(60, 720, 900, 820))
        }
        shadowOf(root).addChild(pwdNode)

        val otpNode = AccessibilityNodeInfo.obtain().apply {
            packageName = "com.whatsapp"
            className = "android.widget.TextView"
            text = "Your OTP code is 482910 for login"
            isVisibleToUser = true
            setBoundsInScreen(Rect(60, 840, 900, 920))
        }
        shadowOf(root).addChild(otpNode)

        for (i in 1..95) {
            val row = AccessibilityNodeInfo.obtain().apply {
                packageName = "com.whatsapp"
                className = "android.widget.Button"
                text = "Feed Action #$i"
                isClickable = true
                isVisibleToUser = true
                setBoundsInScreen(Rect(40, 940 + i * 10, 400, 948 + i * 10))
            }
            shadowOf(root).addChild(row)
        }

        // Separate dialog window root
        val dialogBtn = AccessibilityNodeInfo.obtain().apply {
            packageName = "com.whatsapp"
            className = "android.widget.Button"
            text = "Allow Camera"
            isClickable = true
            isVisibleToUser = true
            setBoundsInScreen(Rect(200, 1200, 600, 1320))
        }

        val (snap, _) = ScreenReaderV2.capture(
            roots = listOf(root to false, dialogBtn to true),
            imeBounds = null,
            screenWidth = 1080,
            screenHeight = 2400
        )

        assertEquals(80, snap.elements.size)
        // Dialog must appear first before top_bar / content / bottom_bar
        assertEquals("dialog", snap.elements.first().region)
        assertEquals("Allow Camera", snap.elements.first().text)

        // Merged descendant label present on blank clickable parent
        val sendRow = snap.elements.firstOrNull { it.bestLabel.contains("Send Voice Note") }
        assertNotNull(sendRow)

        // Password cleared and never exposed in text/summary
        val pwdEl = snap.elements.firstOrNull { it.isPassword }
        assertNotNull(pwdEl)
        assertEquals("", pwdEl?.text)
        assertFalse(snap.readableSummary.contains("SuperSecret123!"))

        // OTP digits redacted
        assertFalse(snap.readableSummary.contains("482910"))
    }

    @Test
    fun `ScreenReaderV2 snapshot node matching survives slight scroll drift and rejects missing nodes`() {
        val target = ScreenElementNode(
            id = "el_1",
            text = "Subscribe",
            contentDescription = "",
            className = "Button",
            viewIdResourceName = "com.google.android.youtube:id/subscribe_button",
            isClickable = true,
            isLongClickable = false,
            isScrollable = false,
            isEditable = false,
            bounds = Rect(700, 900, 980, 990),
            index = 3,
            region = "content",
            fingerprint = "subscribe_button|Button|subscribe|35|39|content"
        )
        val liveMatch = target.copy(
            index = 5,
            bounds = Rect(700, 940, 980, 1030), // 40px vertical drift
            fingerprint = "subscribe_button|Button|subscribe|35|41|content"
        )
        val matched = ScreenReaderV2.findBestLiveMatchForSnapshotNode(
            target = target,
            liveElements = listOf(liveMatch),
            liveNodes = listOf(null)
        )
        assertNotNull(matched)
        assertEquals(5, matched!!.first.index)

        val farDrift = target.copy(bounds = Rect(700, 1600, 980, 1690), fingerprint = "other")
        val miss = ScreenReaderV2.findBestLiveMatchForSnapshotNode(
            target = target,
            liveElements = listOf(farDrift),
            liveNodes = listOf(null)
        )
        assertNull(miss)
    }

    @Test
    fun `safe swipe bounds exclude 10 percent horizontal edges and IME keyboard bottom`() {
        val safe = ScreenReaderV2.computeSwipeSafeBounds(
            screenW = 1080,
            screenH = 2400,
            imeBounds = Rect(0, 1400, 1080, 2400)
        )
        assertTrue(safe.left >= 108)
        assertTrue(safe.right <= 972)
        assertTrue(safe.top >= 432)
        assertTrue(safe.bottom <= 1376) // strictly above keyboard top (1400 - 24)
    }

    @Test
    fun `SetOfMarkGrounding black-boxes passwords, annotates elements, parses index and point JSON, and detects thin trees`() {
        val rawBmp = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.WHITE)
        }
        val pwdEl = ScreenElementNode(
            id = "pwd",
            text = "",
            contentDescription = "",
            className = "EditText",
            viewIdResourceName = "pwd_input",
            isClickable = true,
            isLongClickable = false,
            isScrollable = false,
            isEditable = true,
            bounds = Rect(100, 400, 900, 560),
            index = 1,
            isPassword = true
        )
        val submitEl = ScreenElementNode(
            id = "submit",
            text = "Sign In",
            contentDescription = "Sign In",
            className = "Button",
            viewIdResourceName = "btn_signin",
            isClickable = true,
            isLongClickable = false,
            isScrollable = false,
            isEditable = false,
            bounds = Rect(200, 800, 800, 920),
            index = 2
        )
        val snap = ScreenSnapshot("com.example.bank", listOf(pwdEl, submitEl))

        val redactedJpeg = SetOfMarkGrounding.redactPasswordsToJpeg(rawBmp, snap, 1080, 2400)
        assertTrue(redactedJpeg.isNotEmpty())

        val som = SetOfMarkGrounding.annotateSnapshot(rawBmp, snap, 1080, 2400)
        assertTrue(som.jpegBytes.isNotEmpty())
        assertEquals(1, som.markMap.size) // password field omitted from SoM marks; submit marked #1
        assertEquals("Sign In", som.markMap[1]?.text)

        val indexParsed = SetOfMarkGrounding.parseSetOfMarkResponse(
            raw = """{"index": 1}""",
            markMap = som.markMap,
            screenWidth = 1080,
            screenHeight = 2400,
            providerName = "Gemini"
        )
        assertNotNull(indexParsed)
        assertEquals(500f, indexParsed!!.xPx, 1f)
        assertEquals(860f, indexParsed.yPx, 1f)

        val pointParsed = SetOfMarkGrounding.parseSetOfMarkResponse(
            raw = """{"point": [500, 250]}""",
            markMap = som.markMap,
            screenWidth = 1080,
            screenHeight = 2400,
            providerName = "Remote"
        )
        assertNotNull(pointParsed)
        assertEquals(270f, pointParsed!!.xPx, 2f)
        assertEquals(1200f, pointParsed.yPx, 2f)

        assertTrue(SetOfMarkGrounding.isThinAccessibilityTree(snap))
    }

    @Test
    fun `ScreenActionResult formats structured JSON, AutomationOutcome, and localized messages`() {
        val okTyped = ScreenActionResult(
            success = true,
            changed = true,
            packageName = "com.example",
            newSummary = "Search box updated",
            readBackText = "hello mahi"
        )
        val json = JSONObject(okTyped.toStructuredJson())
        assertTrue(json.getBoolean("success"))
        assertTrue(json.getBoolean("changed"))
        assertEquals("hello mahi", json.getString("readBackText"))
        assertTrue(okTyped.toAutomationOutcome().message.contains("hello mahi"))
        assertTrue(okTyped.localizedMessage(AppLanguage.ROMAN_URDU).startsWith("Ho gaya"))
        assertTrue(okTyped.localizedMessage(AppLanguage.URDU).contains("ہو گیا"))

        val staleErr = ScreenActionResult(
            success = false,
            stale = true,
            failureReason = "Element disappeared from screen.",
            hint = "Re-read the screen first."
        )
        assertTrue(JSONObject(staleErr.toStructuredJson()).getBoolean("stale"))
        assertTrue(staleErr.localizedMessage(AppLanguage.ENGLISH).contains("Hint:"))
    }

    @Test
    fun `VoiceCommandRouter routes Watch Mode start and stop commands across English, Roman Urdu, and Urdu`() {
        assertEquals(RoutedVoiceCommand.StartWatchMode, VoiceCommandRouter.parseCommand("meri screen dekhti raho"))
        assertEquals(RoutedVoiceCommand.StartWatchMode, VoiceCommandRouter.parseCommand("watch my screen please"))
        assertEquals(RoutedVoiceCommand.StopWatchMode, VoiceCommandRouter.parseCommand("screen dekhna band karo"))
        assertEquals(RoutedVoiceCommand.StopWatchMode, VoiceCommandRouter.parseCommand("stop watching screen"))
        assertEquals(RoutedVoiceCommand.StopWatchMode, VoiceCommandRouter.parseCommand("اسکرین دیکھنا بند کرو"))
        assertEquals(RoutedVoiceCommand.StopEverything, VoiceCommandRouter.parseCommand("ruk jao mahi"))
    }

    @Test
    fun `MahiToolsManager handles setWatchMode via executeAsync delegate`() = runBlocking {
        var latestWatchState: Boolean? = null
        val delegate = object : MahiVisionToolDelegate {
            override suspend fun captureScreenImage(question: String) = ToolExecutionResult("c", "c", "ok")
            override suspend fun describeScreen(question: String) = ToolExecutionResult("d", "d", "ok")
            override suspend fun analyzeScreenshot(question: String) = ToolExecutionResult("a", "a", "ok")
            override suspend fun readTextFromCamera(explainWithAi: Boolean) = ToolExecutionResult("rc", "rc", "ok")
            override suspend fun readTextFromScreenshot(explainWithAi: Boolean) = ToolExecutionResult("rs", "rs", "ok")
            override suspend fun identifyObjects() = ToolExecutionResult("o", "o", "ok")
            override suspend fun recognizeScene() = ToolExecutionResult("s", "s", "ok")
            override suspend fun describeCameraView(question: String) = ToolExecutionResult("dc", "dc", "ok")
            override suspend fun summarizeNotifications() = ToolExecutionResult("n", "n", "ok")
            override suspend fun openGalleryAnalysis() = ToolExecutionResult("g", "g", "ok")
            override suspend fun compareImages() = ToolExecutionResult("ci", "ci", "ok")
            override suspend fun askAboutLastImage(question: String) = ToolExecutionResult("q", "q", "ok")
            override suspend fun performScreenTask(goal: String) = ToolExecutionResult("p", "p", "ok")
            override suspend fun setWatchMode(enabled: Boolean): ToolExecutionResult {
                latestWatchState = enabled
                return ToolExecutionResult("setWatchMode", "Watch Mode", if (enabled) "ON" else "OFF")
            }
            override suspend fun confirmSensitiveAction(title: String, reason: String) = true
        }
        val mgr = MahiToolsManager(appContext, visionDelegate = delegate)
        val onRes = mgr.executeAsync(ToolAction("setWatchMode", mapOf("enabled" to "true")))
        assertTrue(onRes.isSuccess)
        assertEquals(true, latestWatchState)

        val offRes = mgr.executeAsync(ToolAction("setWatchMode", mapOf("enabled" to "false")))
        assertTrue(offRes.isSuccess)
        assertEquals(false, latestWatchState)
    }

    @Test
    fun `AutonomousAgentEngine invokes SetOfMarkGrounding on thin trees when driver provides bitmap`() = runBlocking {
        val engine = AutonomousAgentEngine(
            appContext,
            com.example.automation.DeviceControlHelper(appContext),
            ProviderClient()
        )
        var capturedBitmapCalled = false
        var tappedPoint: Pair<Float, Float>? = null
        var clickedTarget = false
        val bmp = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888)

        val driver = object : AutonomousAgentEngine.ScreenAgentDriver {
            override fun isAccessibilityEnabled() = true
            override suspend fun readScreen(): Result<ScreenSnapshot> {
                val webNode = ScreenElementNode(
                    id = "web",
                    text = if (clickedTarget) "Liked!" else "",
                    contentDescription = "",
                    className = "android.webkit.WebView",
                    viewIdResourceName = "",
                    isClickable = false,
                    isLongClickable = false,
                    isScrollable = false,
                    isEditable = false,
                    bounds = Rect(0, 200, 1080, 2000),
                    index = 1,
                    isSelected = clickedTarget
                )
                return Result.success(ScreenSnapshot("com.example.canvas", listOf(webNode)))
            }
            override suspend fun openApp(appName: String) = com.example.automation.AutomationOutcome(true, "Opened")
            override suspend fun tap(index: Int?, query: String?, viewId: String?, x: Float?, y: Float?): com.example.automation.AutomationOutcome {
                if (x != null && y != null) {
                    tappedPoint = x to y
                    clickedTarget = true
                    return com.example.automation.AutomationOutcome(true, "Tapped via grounding")
                }
                return com.example.automation.AutomationOutcome(false, "No label match")
            }
            override suspend fun typeText(index: Int?, query: String?, text: String, submit: Boolean, clearFirst: Boolean) =
                com.example.automation.AutomationOutcome(true, "Typed")
            override suspend fun scroll(forward: Boolean) = com.example.automation.AutomationOutcome(true, "Scrolled")
            override suspend fun swipe(direction: SwipeDirection) = com.example.automation.AutomationOutcome(true, "Swiped")
            override suspend fun pressBack() = com.example.automation.AutomationOutcome(true, "Back")
            override suspend fun captureScreenJpeg(): Result<ByteArray> = Result.success(byteArrayOf(1, 2, 3))
            override suspend fun captureScreenBitmap(): Result<Bitmap> {
                capturedBitmapCalled = true
                return Result.success(bmp)
            }
            override fun displaySize() = 1080 to 2400
        }

        val fakeGrounding = object : GuiGroundingProvider {
            override val name = "TestSoMGrounding"
            override suspend fun locateTarget(jpegBytes: ByteArray, targetDescription: String, screenWidth: Int, screenHeight: Int) =
                Result.success(GroundingPoint(540f, 1100f, 0.5f, 0.45f, name))
        }

        val outcome = engine.runScreenTask(
            goal = "is post ko like karo",
            driver = driver,
            autopilotAllowed = true,
            groundingProvider = fakeGrounding,
            stepDelayMs = 0L,
            verifyDelayMs = 0L
        )
        assertTrue(capturedBitmapCalled)
        assertNotNull(tappedPoint)
        assertEquals(540f, tappedPoint!!.first, 1f)
        assertEquals(1100f, tappedPoint!!.second, 1f)
        assertTrue(outcome.completed)
    }

    @Test
    fun `TestPlaygroundScreen renders and responds across interactive controls`() {
        composeTestRule.setContent {
            MahiAiTheme {
                TestPlaygroundScreen()
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("playground_action_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Taps: 1").assertIsDisplayed()

        composeTestRule.onNodeWithTag("playground_text_input").performScrollTo().performTextInput("Cyber Glow")
        composeTestRule.onNodeWithTag("playground_submit_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Submitted: Cyber Glow").assertIsDisplayed()

        composeTestRule.onNodeWithTag("playground_checkbox_row").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Checked").assertIsDisplayed()
    }

    @Test
    fun `ScreenDoctorModal shows Restricted Settings guide, Test Playground launcher, and copies report to clipboard`() {
        var openedPlayground = false
        composeTestRule.setContent {
            MahiAiTheme {
                ScreenDoctorModal(
                    onDismiss = {},
                    onOpenPlayground = { openedPlayground = true }
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("screen_doctor_modal").assertIsDisplayed()
        composeTestRule.onNodeWithText("2. Android 13–15 Sideloaded APK Guide (Restricted Setting)").assertIsDisplayed()
        composeTestRule.onNodeWithTag("screen_doctor_copy_report_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        val cm = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
        assertTrue(clip.contains("MAHI AI SCREEN DOCTOR REPORT"))

        composeTestRule.onNodeWithTag("screen_doctor_open_playground_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        assertTrue(openedPlayground)
    }
}
