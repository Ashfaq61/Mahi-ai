package com.example

import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import android.graphics.Rect
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.example.ai.MahiMood
import com.example.api.AiProvider
import com.example.api.ApiManagerState
import com.example.api.InMemoryAesGcmCipher
import com.example.api.ProviderClient
import com.example.api.SecureApiKeyStore
import com.example.audio.AutopilotStopReceiver
import com.example.audio.MahiVoiceForegroundService
import com.example.automation.AppSkills
import com.example.automation.AutomationOutcome
import com.example.automation.AutonomousAgentEngine
import com.example.automation.CompositeGuiGroundingProvider
import com.example.automation.DeviceControlHelper
import com.example.automation.GroundingPoint
import com.example.automation.GuiGroundingProvider
import com.example.automation.RemoteHttpGroundingProvider
import com.example.automation.ScreenElementNode
import com.example.automation.ScreenSnapshot
import com.example.automation.SwipeDirection
import com.example.control.AllVolumeLevels
import com.example.control.BatteryInfoSnapshot
import com.example.control.DeviceInfoSnapshot
import com.example.control.NetworkInfoSnapshot
import com.example.control.StorageInfoSnapshot
import com.example.preferences.AppLanguage
import com.example.preferences.MahiPreferencesStore
import com.example.tools.MahiToolsManager
import com.example.tools.MahiVisionToolDelegate
import com.example.tools.ToolAction
import com.example.tools.ToolExecutionResult
import com.example.ui.components.SettingsModal
import com.example.ui.screens.ApiProvidersScreen
import com.example.ui.screens.SmartControlScreen
import com.example.ui.theme.MahiAiTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class MahiV130AutopilotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val appContext: Application
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `autopilot defaults off in preferences and runScreenTask refuses cleanly when disabled`() = runBlocking {
        val prefs = MahiPreferencesStore(appContext)
        prefs.setAutopilotEnabled(false)
        assertFalse(prefs.prefsFlow.value.autopilotEnabled)

        prefs.setAutopilotEnabled(true)
        assertTrue(MahiPreferencesStore(appContext).prefsFlow.value.autopilotEnabled)
        prefs.setAutopilotEnabled(false)

        var driverInvoked = false
        val driver = object : AutonomousAgentEngine.ScreenAgentDriver {
            override fun isAccessibilityEnabled() = true
            override suspend fun readScreen(): Result<ScreenSnapshot> {
                driverInvoked = true
                return Result.success(ScreenSnapshot())
            }
            override suspend fun openApp(appName: String) = AutomationOutcome(true, "Opened")
            override suspend fun tap(index: Int?, query: String?, viewId: String?, x: Float?, y: Float?) = AutomationOutcome(true, "Tapped")
            override suspend fun typeText(index: Int?, query: String?, text: String, submit: Boolean, clearFirst: Boolean) = AutomationOutcome(true, "Typed")
            override suspend fun scroll(forward: Boolean) = AutomationOutcome(true, "Scrolled")
            override suspend fun swipe(direction: SwipeDirection) = AutomationOutcome(true, "Swiped")
            override suspend fun pressBack() = AutomationOutcome(true, "Back")
            override suspend fun captureScreenJpeg(): Result<ByteArray> = Result.failure(IllegalStateException("none"))
            override fun displaySize() = 1080 to 2400
        }

        val engine = AutonomousAgentEngine(appContext, DeviceControlHelper(appContext), ProviderClient())
        val res = engine.runScreenTask(
            goal = "Facebook kholo aur like karo",
            driver = driver,
            autopilotAllowed = false,
            stepDelayMs = 0L,
            verifyDelayMs = 0L
        )
        assertFalse(res.completed)
        assertFalse(driverInvoked)
        assertEquals(0, res.stepsExecuted)
        assertTrue(res.summary.contains("Allow Autopilot", ignoreCase = true))
    }

    @Test
    fun `mahi tools manager routes tapElement through executeAsync cleanly without duplicate execute branch`() = runBlocking {
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
            override suspend fun performScreenTask(goal: String) = ToolExecutionResult("performScreenTask", "Screen Task", "ok", isSuccess = true)
            override suspend fun setWatchMode(enabled: Boolean) = ToolExecutionResult("setWatchMode", "Watch Mode", "ok")
            override suspend fun confirmSensitiveAction(title: String, reason: String) = true
        }
        val mgr = MahiToolsManager(appContext, visionDelegate = delegate)
        val syncResult = mgr.execute(ToolAction("tapElement", mapOf("query" to "Like")))
        assertFalse(syncResult.isSuccess)
        assertTrue(syncResult.message.contains("Unsupported tool", ignoreCase = true))

        val asyncResult = mgr.executeAsync(ToolAction("tapElement", mapOf("query" to "Like")))
        assertEquals("tapElement", asyncResult.actionName)
    }

    @Test
    fun `app skills cover six apps plus generic with roman urdu and urdu variants and localized progress`() {
        assertEquals(7, AppSkills.allRecipes.size)
        assertTrue(AppSkills.facebook.actionVariants.getValue("like").contains("pasand"))
        assertTrue(AppSkills.facebook.actionVariants.getValue("like").contains("لائک"))
        assertTrue(AppSkills.youtube.actionVariants.getValue("subscribe").contains("سبسکرائب"))
        assertTrue(AppSkills.tiktok.actionVariants.getValue("next").contains("agla"))
        assertTrue(AppSkills.whatsapp.actionVariants.getValue("post_comment").contains("bhejo"))
        assertTrue(AppSkills.chrome.searchCues.contains("تلاش"))
        assertTrue(AppSkills.generic.actionVariants.getValue("follow").contains("فالو"))

        val romanMsg = AppSkills.formatProgressCheckpoint("Facebook", 10, AppLanguage.ROMAN_URDU)
        assertTrue(romanMsg.contains("Facebook") && romanMsg.contains("10"))
        val urduMsg = AppSkills.formatProgressCheckpoint("YouTube", 20, AppLanguage.URDU)
        assertTrue(urduMsg.contains("YouTube") && urduMsg.contains("20"))
    }

    @Test
    fun `autopilot records actual tapped node label in label memory and clears cleanly`() = runBlocking {
        val engine = AutonomousAgentEngine(appContext, DeviceControlHelper(appContext), ProviderClient())
        engine.labelMemoryStore.clear()
        assertEquals(0, engine.labelMemoryStore.entryCount.value)

        var liked = false
        val driver = object : AutonomousAgentEngine.ScreenAgentDriver {
            override fun isAccessibilityEnabled() = true
            override suspend fun readScreen(): Result<ScreenSnapshot> {
                val node = ScreenElementNode(
                    id = "thumb",
                    text = if (liked) "Liked" else "Pasand",
                    contentDescription = if (liked) "Unlike" else "Like",
                    className = "Button",
                    viewIdResourceName = "com.facebook.katana:id/like_btn",
                    isClickable = true,
                    isLongClickable = false,
                    isScrollable = false,
                    isEditable = false,
                    bounds = Rect(10, 800, 140, 880),
                    index = 1,
                    isSelected = liked
                )
                return Result.success(ScreenSnapshot("com.facebook.katana", listOf(node), System.currentTimeMillis()))
            }
            override suspend fun openApp(appName: String) = AutomationOutcome(true, "Opened")
            override suspend fun tap(index: Int?, query: String?, viewId: String?, x: Float?, y: Float?): AutomationOutcome {
                liked = true
                return AutomationOutcome(true, "Tapped")
            }
            override suspend fun typeText(index: Int?, query: String?, text: String, submit: Boolean, clearFirst: Boolean) = AutomationOutcome(true, "Typed")
            override suspend fun scroll(forward: Boolean) = AutomationOutcome(true, "Scrolled")
            override suspend fun swipe(direction: SwipeDirection) = AutomationOutcome(true, "Swiped")
            override suspend fun pressBack() = AutomationOutcome(true, "Back")
            override suspend fun captureScreenJpeg(): Result<ByteArray> = Result.failure(IllegalStateException("none"))
            override fun displaySize() = 1080 to 2400
        }

        val res = engine.runScreenTask(
            goal = "is post ko like karo",
            driver = driver,
            autopilotAllowed = true,
            stepDelayMs = 0L,
            verifyDelayMs = 0L
        )
        assertTrue(res.completed)
        assertTrue(engine.labelMemoryStore.entryCount.value >= 1)
        assertTrue(engine.labelMemoryStore.getVariants("com.facebook.katana", "like").contains("Pasand"))

        engine.labelMemoryStore.clear()
        assertEquals(0, engine.labelMemoryStore.entryCount.value)
    }

    @Test
    fun `remote grounding encrypts token, composites with fallback, and autopilot notification posts and clears cleanly`() = runBlocking {
        val store = SecureApiKeyStore(appContext, InMemoryAesGcmCipher())
        store.clearRemoteGrounding()
        assertFalse(store.remoteGrounding.value.enabled)

        store.saveRemoteGrounding(true, "https://grounding.example.com/predict", "secret-token-9876")
        assertTrue(store.remoteGrounding.value.enabled)
        assertEquals("https://grounding.example.com/predict", store.remoteGrounding.value.endpointUrl)
        assertTrue(store.remoteGrounding.value.hasToken)
        assertEquals("secret-token-9876", store.getDecryptedRemoteGroundingToken())

        val remoteFail = RemoteHttpGroundingProvider(enabled = false, endpointUrl = "")
        val fallbackOk = object : GuiGroundingProvider {
            override val name = "FakeGemini"
            override suspend fun locateTarget(jpegBytes: ByteArray, targetDescription: String, screenWidth: Int, screenHeight: Int) =
                Result.success(GroundingPoint(540f, 1200f, 0.5f, 0.5f, name))
        }
        val composite = CompositeGuiGroundingProvider(remoteFail, fallbackOk)
        val point = composite.locateTarget(byteArrayOf(1, 2), "Like", 1080, 2400).getOrThrow()
        assertEquals(540f, point.xPx, 0.1f)

        val badEndpointTest = RemoteHttpGroundingProvider.testEndpoint("ftp://invalid", null)
        assertTrue(badEndpointTest.isFailure)

        store.clearRemoteGrounding()
        assertNull(store.getDecryptedRemoteGroundingToken())
        assertFalse(store.remoteGrounding.value.enabled)

        // Notification post + broadcast stop + clear
        val nm = appContext.getSystemService(NotificationManager::class.java)
        val shadowNm = shadowOf(nm)
        MahiVoiceForegroundService.showAutopilotNotification(appContext, "Step 2: Tapping Like")
        assertEquals(1, shadowNm.allNotifications.size)

        var stopTriggered = false
        MahiVoiceForegroundService.onStopAutopilotRequested = { stopTriggered = true }
        AutopilotStopReceiver().onReceive(
            appContext,
            Intent(appContext, AutopilotStopReceiver::class.java).apply {
                action = MahiVoiceForegroundService.ACTION_STOP_AUTOPILOT
            }
        )
        assertTrue(stopTriggered)
        assertEquals(0, shadowNm.allNotifications.size)
        MahiVoiceForegroundService.onStopAutopilotRequested = null
    }

    @Test
    fun `autopilot speaks progress strictly every 10 actions instead of every 1 to 3 steps`() = runBlocking {
        val engine = AutonomousAgentEngine(appContext, DeviceControlHelper(appContext), ProviderClient())
        var scrollCalls = 0
        var moreTapped = false
        val driver = object : AutonomousAgentEngine.ScreenAgentDriver {
            override fun isAccessibilityEnabled() = true
            override suspend fun readScreen(): Result<ScreenSnapshot> {
                val nodes = buildList {
                    if (!moreTapped) {
                        add(
                            ScreenElementNode(
                                id = "more_btn",
                                text = "More options",
                                contentDescription = "More options",
                                className = "Button",
                                viewIdResourceName = "",
                                isClickable = true,
                                isLongClickable = false,
                                isScrollable = false,
                                isEditable = false,
                                bounds = Rect(900, 100, 1040, 200),
                                index = 1
                            )
                        )
                    }
                    add(
                        ScreenElementNode(
                            id = "feed_${scrollCalls}_${if (moreTapped) 1 else 0}",
                            text = "Feed Item #$scrollCalls",
                            contentDescription = "",
                            className = "TextView",
                            viewIdResourceName = "",
                            isClickable = false,
                            isLongClickable = false,
                            isScrollable = true,
                            isEditable = false,
                            bounds = Rect(0, 220, 1080, 1800),
                            index = if (moreTapped) 1 else 2
                        )
                    )
                }
                return Result.success(ScreenSnapshot("com.facebook.katana", nodes, System.currentTimeMillis()))
            }
            override suspend fun openApp(appName: String) = AutomationOutcome(true, "Opened")
            override suspend fun tap(index: Int?, query: String?, viewId: String?, x: Float?, y: Float?): AutomationOutcome {
                moreTapped = true
                return AutomationOutcome(true, "Opened More options")
            }
            override suspend fun typeText(index: Int?, query: String?, text: String, submit: Boolean, clearFirst: Boolean) = AutomationOutcome(true, "Typed")
            override suspend fun scroll(forward: Boolean): AutomationOutcome {
                scrollCalls++
                return AutomationOutcome(true, "Scrolled $scrollCalls")
            }
            override suspend fun swipe(direction: SwipeDirection) = AutomationOutcome(true, "Swiped")
            override suspend fun pressBack() = AutomationOutcome(true, "Back")
            override suspend fun captureScreenJpeg(): Result<ByteArray> = Result.failure(IllegalStateException("none"))
            override fun displaySize() = 1080 to 2400
        }

        val spokenCheckpoints = mutableListOf<String>()
        val notifUpdates = mutableListOf<Pair<Boolean, String>>()
        engine.runScreenTask(
            goal = "is post ko like karo",
            language = AppLanguage.ENGLISH,
            driver = driver,
            autopilotAllowed = true,
            stepDelayMs = 0L,
            verifyDelayMs = 0L,
            onProgressUpdate = { spokenCheckpoints.add(it) },
            onAutopilotNotificationUpdate = { active, text -> notifUpdates.add(active to text) }
        )

        // Scrolling down 6x + up 3x + hidden/grounding attempts reaches >=10 actions -> exactly 1 spoken checkpoint at 10
        assertEquals(1, spokenCheckpoints.size)
        assertTrue(spokenCheckpoints.first().contains("10 steps done"))
        assertTrue(notifUpdates.any { it.first })
        assertFalse(notifUpdates.last().first)
    }

    @Test
    fun `settings modal exposes Allow Autopilot switch and Clear learned labels button`() {
        var autopilotToggled: Boolean? = null
        var clearLabelsClicked = false

        composeTestRule.setContent {
            MahiAiTheme {
                SettingsModal(
                    currentMood = MahiMood.PLAYFUL,
                    isContinuousMode = false,
                    showSubtitles = true,
                    voicePitch = 1.0f,
                    voiceSpeed = 1.0f,
                    autopilotEnabled = true,
                    learnedLabelCount = 4,
                    onToggleAutopilot = { autopilotToggled = it },
                    onClearLearnedLabels = { clearLabelsClicked = true },
                    onMoodSelected = {},
                    onContinuousToggle = {},
                    onSubtitlesToggle = {},
                    onPitchChanged = {},
                    onSpeedChanged = {},
                    onTestVoice = {},
                    onDismiss = {}
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("settings_autopilot_switch").performScrollTo().performClick()
        assertEquals(false, autopilotToggled)
        composeTestRule.onNodeWithTag("settings_clear_learned_labels_button").performScrollTo().performClick()
        assertTrue(clearLabelsClicked)
    }

    @Test
    fun `smart control screen exposes Allow Autopilot toggle and POST_NOTIFICATIONS row`() {
        var autopilotToggled: Boolean? = null

        composeTestRule.setContent {
            MahiAiTheme {
                SmartControlScreen(
                    isFlashlightOn = false,
                    brightnessPercent = 50,
                    canWriteSettings = true,
                    volumeLevels = AllVolumeLevels(50, 50, 50, 50),
                    batteryInfo = BatteryInfoSnapshot(levelPercent = 85, isCharging = false, temperatureCelsius = 31f, healthLabel = "Good"),
                    storageInfo = StorageInfoSnapshot(totalGb = 128f, freeGb = 40f),
                    networkInfo = NetworkInfoSnapshot(isConnected = true, transportLabel = "Wi-Fi"),
                    deviceInfo = DeviceInfoSnapshot(manufacturerModel = "moto g stylus", androidVersion = "15"),
                    lastControlStatus = "",
                    autopilotEnabled = false,
                    onToggleAutopilot = { autopilotToggled = it },
                    onBack = {},
                    onRefreshAll = {},
                    onLaunchApp = {},
                    onNavigateToAgent = {},
                    onOpenQuickSettingsShade = {},
                    onOpenNotificationShade = {},
                    onOpenWifiPanel = {},
                    onOpenBluetoothPanel = {},
                    onOpenMobileDataSettings = {},
                    onOpenAirplaneSettings = {},
                    onOpenNfcPanel = {},
                    onToggleFlashlight = {},
                    onSetVolumeStream = { _, _ -> },
                    onToggleMuteMedia = {},
                    onSetBrightness = {},
                    onOpenWriteSettings = {},
                    onOpenDndSettings = {}
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("smart_control_autopilot_switch").performScrollTo().performClick()
        assertEquals(true, autopilotToggled)
        composeTestRule.onNodeWithText("Notifications (POST_NOTIFICATIONS)").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `api providers screen exposes collapsible GUI grounding server card`() {
        composeTestRule.setContent {
            MahiAiTheme {
                ApiProvidersScreen(
                    apiState = ApiManagerState(),
                    onBack = {},
                    onSetActiveProvider = {},
                    onAddKey = { _, _, _, _ -> },
                    onUpdateKey = { _, _, _, _, _ -> },
                    onDeleteKey = { _, _ -> },
                    onSelectKey = { _, _ -> },
                    onTestKey = { _, _ -> },
                    onRevealKey = { null },
                    onModelSelected = { _, _ -> },
                    onBaseUrlChanged = { _, _ -> },
                    remoteGroundingConfig = SecureApiKeyStore.RemoteGroundingConfig(enabled = false)
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("api_providers_list").performScrollToNode(hasTestTag("remote_grounding_card"))
        composeTestRule.onNodeWithTag("remote_grounding_card").assertIsDisplayed()
        composeTestRule.onNodeWithTag("remote_grounding_header").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("api_providers_list").performScrollToNode(hasTestTag("remote_grounding_clear_button"))
        composeTestRule.onNodeWithTag("remote_grounding_switch").assertIsDisplayed()
        composeTestRule.onNodeWithTag("remote_grounding_url_input").assertIsDisplayed()
        composeTestRule.onNodeWithTag("remote_grounding_save_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("remote_grounding_test_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("remote_grounding_clear_button").assertIsDisplayed()
    }
}
