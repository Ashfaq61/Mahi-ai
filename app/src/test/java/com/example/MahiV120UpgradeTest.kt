package com.example

import android.app.Application
import android.graphics.Rect
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.example.ai.GeminiLiveClient
import com.example.ai.MahiAiEngine
import com.example.ai.MahiMood
import com.example.automation.AgentActionType
import com.example.automation.AutonomousAgentEngine
import com.example.automation.DeviceControlHelper
import com.example.automation.RoutedVoiceCommand
import com.example.automation.ScreenElementNode
import com.example.automation.ScreenSnapshot
import com.example.automation.SensitiveCategory
import com.example.automation.VoiceCommandRouter
import com.example.control.AllVolumeLevels
import com.example.control.BatteryInfoSnapshot
import com.example.control.DeviceInfoSnapshot
import com.example.control.NetworkInfoSnapshot
import com.example.control.StorageInfoSnapshot
import com.example.control.VolumeManager
import com.example.control.VolumeStreamType
import com.example.preferences.AppLanguage
import com.example.tools.MahiToolsManager
import com.example.tools.MahiVisionToolDelegate
import com.example.tools.ToolAction
import com.example.tools.ToolExecutionResult
import com.example.ui.screens.NeuralLensScreen
import com.example.ui.screens.SmartControlScreen
import com.example.ui.theme.MahiAiTheme
import com.example.vision.LiveVisionSnapshot
import com.example.vision.NeuralLensAnalyzer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MahiV120UpgradeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val appContext: Application
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `gemini live tool declarations include all screen reading, tap, type, visual fallback, and vision tools`() {
        val declarations = GeminiLiveClient.buildToolDeclarations()
        val byName = (0 until declarations.length()).associate { idx ->
            val obj = declarations.getJSONObject(idx)
            obj.getString("name") to obj
        }

        val expectedTools = listOf(
            "readScreen",
            "tapElement",
            "typeText",
            "captureScreenImage",
            "describeScreen",
            "analyzeScreenshot",
            "readTextFromCamera",
            "readTextFromScreenshot",
            "identifyObjects",
            "recognizeScene",
            "describeCameraView",
            "summarizeNotifications",
            "openGalleryAnalysis",
            "compareImages",
            "askAboutLastImage",
            "screenGesture",
            "setVolume",
            "openQuickSettings"
        )
        expectedTools.forEach { toolName ->
            assertTrue("Missing Gemini Live tool declaration: $toolName", byName.containsKey(toolName))
        }

        val gestureProps = byName.getValue("screenGesture")
            .getJSONObject("parameters")
            .getJSONObject("properties")
        assertTrue(gestureProps.has("targetIndex"))
        assertTrue(gestureProps.has("startX"))
        assertTrue(gestureProps.has("startY"))
        assertTrue(gestureProps.has("endX"))
        assertTrue(gestureProps.has("endY"))

        val typeProps = byName.getValue("typeText")
            .getJSONObject("parameters")
            .getJSONObject("properties")
        assertTrue(typeProps.has("text"))
        assertTrue(typeProps.has("append"))
        assertTrue(typeProps.has("clearFirst"))
        assertTrue(typeProps.has("submit"))
    }

    @Test
    fun `ai system prompt includes readScreen-first rule, 6-step loop guidance, and safety constraints`() {
        val prompt = MahiAiEngine().buildSystemPrompt(MahiMood.SASSY, AppLanguage.ENGLISH)
        assertTrue(prompt.contains("readScreen"))
        assertTrue(prompt.contains("tapElement"))
        assertTrue(prompt.contains("typeText"))
        assertTrue(prompt.contains("captureScreenImage"))
        assertTrue(prompt.contains("PASSWORD", ignoreCase = true))
        assertTrue(prompt.contains("NEVER guess", ignoreCase = true))
        assertTrue(prompt.contains("6 steps", ignoreCase = true) || prompt.contains("6 tool steps", ignoreCase = true))
    }

    @Test
    fun `screen snapshot indexes elements 1 to N, masks passwords, and redacts OTP and card numbers`() {
        val normalNode = ScreenElementNode(
            id = "btn1",
            text = "Search",
            contentDescription = "",
            className = "Button",
            viewIdResourceName = "com.app:id/search_btn",
            isClickable = true,
            isLongClickable = false,
            isScrollable = false,
            isEditable = false,
            bounds = Rect(100, 200, 300, 280),
            index = 1,
            isFocused = true
        )
        val passwordNode = ScreenElementNode(
            id = "pwd",
            text = "SuperSecret123",
            contentDescription = "",
            className = "EditText",
            viewIdResourceName = "com.app:id/password",
            isClickable = true,
            isLongClickable = false,
            isScrollable = false,
            isEditable = true,
            bounds = Rect(100, 300, 500, 380),
            index = 2,
            isPassword = true
        )
        val otpNode = ScreenElementNode(
            id = "otp",
            text = "Your OTP is 654321 and card 4532 1111 2222 3333",
            contentDescription = "",
            className = "TextView",
            viewIdResourceName = "",
            isClickable = false,
            isLongClickable = false,
            isScrollable = false,
            isEditable = false,
            bounds = Rect(50, 400, 600, 460),
            index = 3
        )

        val snap = ScreenSnapshot(
            packageName = "com.example.demo",
            elements = listOf(normalNode, passwordNode, otpNode),
            capturedAtMs = 1000L
        )
        val summary = snap.readableSummary
        assertTrue(summary.contains("1. [Button] \"Search\" [clickable,focused] @(200,240)"))
        assertFalse(summary.contains("SuperSecret123"))
        assertTrue(summary.contains("[PASSWORD_HIDDEN]"))
        assertFalse(summary.contains("654321"))
        assertFalse(summary.contains("4532"))
        assertTrue(summary.contains("[REDACTED_CARD_NUMBER]"))
    }

    @Test
    fun `voice command router parses Part 5 tap, type, screen-image, OCR, gallery, and compare commands`() {
        val tapByNum = VoiceCommandRouter.parseCommand("3 number par click karo")
        assertTrue(tapByNum is RoutedVoiceCommand.TapScreenElement)
        assertEquals(3, (tapByNum as RoutedVoiceCommand.TapScreenElement).index)

        val tapByNumEn = VoiceCommandRouter.parseCommand("tap element 2")
        assertTrue(tapByNumEn is RoutedVoiceCommand.TapScreenElement)
        assertEquals(2, (tapByNumEn as RoutedVoiceCommand.TapScreenElement).index)

        val typeUrdu = VoiceCommandRouter.parseCommand("search box mein Mahi AI likho aur search karo")
        assertTrue(typeUrdu is RoutedVoiceCommand.TypeOnScreen)
        val typed = typeUrdu as RoutedVoiceCommand.TypeOnScreen
        assertEquals("Mahi AI", typed.text)
        assertTrue(typed.submit)

        val typeEn = VoiceCommandRouter.parseCommand("type Hello World and send")
        assertTrue(typeEn is RoutedVoiceCommand.TypeOnScreen)
        assertEquals("Hello World", (typeEn as RoutedVoiceCommand.TypeOnScreen).text)
        assertTrue(typeEn.submit)

        assertTrue(VoiceCommandRouter.parseCommand("screen dekho") is RoutedVoiceCommand.AnalyzeScreen)
        assertTrue(VoiceCommandRouter.parseCommand("screenshot samjhao") is RoutedVoiceCommand.AnalyzeScreenshot)
        assertTrue(VoiceCommandRouter.parseCommand("gallery photo samjhao") is RoutedVoiceCommand.OpenGalleryAnalysis)
        assertTrue(VoiceCommandRouter.parseCommand("do images compare karo") is RoutedVoiceCommand.CompareGalleryImages)
        assertTrue(VoiceCommandRouter.parseCommand("text padho") is RoutedVoiceCommand.ExtractOcr)
    }

    @Test
    fun `autonomous agent heuristic plan handles compound open-and-search, type, tap-index, and sensitivity rules`() {
        val engine = AutonomousAgentEngine(appContext, DeviceControlHelper(appContext))

        val compoundSteps = engine.buildHeuristicPlan("YouTube kholo aur Mahi AI search karo")
        assertTrue(compoundSteps.any { it.actionType == AgentActionType.OPEN_APP && it.param.equals("youtube", ignoreCase = true) })
        assertTrue(compoundSteps.any { it.actionType == AgentActionType.YOUTUBE_SEARCH && it.param.contains("Mahi AI") })

        val tapSteps = engine.buildHeuristicPlan("tap element 4")
        assertTrue(tapSteps.any { it.actionType == AgentActionType.TAP_BY_INDEX && it.param == "4" })

        assertEquals(SensitiveCategory.PURCHASE, AutonomousAgentEngine.classifySensitivity(AgentActionType.PURCHASE_ACTION, "Pay 5000", "5000"))
        assertEquals(SensitiveCategory.MESSAGE, AutonomousAgentEngine.classifySensitivity(AgentActionType.TYPE_TEXT, "Send message to Ali", "Salam"))
        assertEquals(SensitiveCategory.NONE, AutonomousAgentEngine.classifySensitivity(AgentActionType.READ_SCREEN, "Read screen", ""))
    }

    @Test
    fun `volume manager setStreamMute and executeAsync tools handle off-service and delegate paths cleanly`() = runBlocking {
        val vm = VolumeManager(appContext)
        VolumeStreamType.entries.forEach { stream ->
            val outcome = vm.setStreamMute(stream, true)
            assertTrue(outcome.message.isNotBlank())
        }

        var galleryCalled = false
        var compareCalled = false
        var captureCalled = false
        val delegate = object : MahiVisionToolDelegate {
            override suspend fun captureScreenImage(question: String): ToolExecutionResult {
                captureCalled = true
                return ToolExecutionResult("captureScreenImage", "Captured", "Sent screen image")
            }
            override suspend fun describeScreen(question: String) = ToolExecutionResult("describeScreen", "Screen", "OK")
            override suspend fun analyzeScreenshot(question: String) = ToolExecutionResult("analyzeScreenshot", "Shot", "OK")
            override suspend fun readTextFromCamera(explainWithAi: Boolean) = ToolExecutionResult("readTextFromCamera", "OCR", "OK")
            override suspend fun readTextFromScreenshot(explainWithAi: Boolean) = ToolExecutionResult("readTextFromScreenshot", "OCR", "OK")
            override suspend fun identifyObjects() = ToolExecutionResult("identifyObjects", "Objects", "OK")
            override suspend fun recognizeScene() = ToolExecutionResult("recognizeScene", "Scene", "OK")
            override suspend fun describeCameraView(question: String) = ToolExecutionResult("describeCameraView", "Cam", "OK")
            override suspend fun summarizeNotifications() = ToolExecutionResult("summarizeNotifications", "Notifs", "OK")
            override suspend fun openGalleryAnalysis(): ToolExecutionResult {
                galleryCalled = true
                return ToolExecutionResult("openGalleryAnalysis", "Gallery", "Opened")
            }
            override suspend fun compareImages(): ToolExecutionResult {
                compareCalled = true
                return ToolExecutionResult("compareImages", "Compare", "Opened")
            }
            override suspend fun askAboutLastImage(question: String) = ToolExecutionResult("askAboutLastImage", "VQA", "OK")
            override suspend fun confirmSensitiveAction(title: String, reason: String) = false
        }

        val tools = MahiToolsManager(context = appContext, visionDelegate = delegate)

        val readRes = tools.executeAsync(ToolAction("readScreen", emptyMap()))
        assertFalse(readRes.isSuccess)
        assertTrue(readRes.message.contains("Accessibility"))

        val tapRes = tools.executeAsync(ToolAction("tapElement", mapOf("index" to "2")))
        assertFalse(tapRes.isSuccess)
        assertTrue(tapRes.message.contains("Accessibility"))

        val typeRes = tools.executeAsync(ToolAction("typeText", mapOf("text" to "Mahi AI", "submit" to "false")))
        assertFalse(typeRes.isSuccess)
        assertTrue(typeRes.message.contains("Accessibility"))

        assertTrue(tools.executeAsync(ToolAction("openGalleryAnalysis", emptyMap())).isSuccess)
        assertTrue(galleryCalled)
        assertTrue(tools.executeAsync(ToolAction("compareImages", emptyMap())).isSuccess)
        assertTrue(compareCalled)
        assertTrue(tools.executeAsync(ToolAction("captureScreenImage", emptyMap())).isSuccess)
        assertTrue(captureCalled)
    }

    @Test
    fun `smart control compose screen renders and triggers refresh cleanly`() {
        composeTestRule.setContent {
            MahiAiTheme {
                SmartControlScreen(
                    isFlashlightOn = false,
                    brightnessPercent = 50,
                    canWriteSettings = true,
                    volumeLevels = AllVolumeLevels(),
                    batteryInfo = BatteryInfoSnapshot(levelPercent = 80, statusSummary = "80%"),
                    storageInfo = StorageInfoSnapshot(totalGb = 128f, freeGb = 64f, usedPercent = 50, summary = "64 GB free"),
                    networkInfo = NetworkInfoSnapshot(isConnected = true, transportLabel = "Wi-Fi", summary = "Connected"),
                    deviceInfo = DeviceInfoSnapshot(manufacturerModel = "Moto", androidVersion = "Android 15", sdkInt = 35, uptimeFormatted = "1h"),
                    lastControlStatus = "",
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
        composeTestRule.onNodeWithTag("smart_control_screen").assertIsDisplayed()
        composeTestRule.onNodeWithTag("smart_control_refresh_button").performClick()
    }

    @Test
    fun `neural lens compose screen switches submodes cleanly`() {
        composeTestRule.setContent {
            MahiAiTheme {
                NeuralLensScreen(
                    analyzer = NeuralLensAnalyzer(),
                    liveSnapshot = LiveVisionSnapshot(),
                    selectedLanguage = AppLanguage.ENGLISH,
                    aiVisionAnswer = "",
                    isAnalyzingAi = false,
                    visionError = null,
                    onLanguageSelected = {},
                    onAskAiVision = {},
                    onAnalyzePhotoUri = {},
                    onSpeakAnswer = {},
                    onOpenApiSettings = {}
                )
            }
        }
        composeTestRule.onNodeWithTag("lens_submode_1").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("lens_read_screen_button").performScrollTo().assertIsDisplayed()
    }
}
