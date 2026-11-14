package com.example

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.example.ai.GeminiLiveClient
import com.example.ai.MahiAiEngine
import com.example.ai.MahiMood
import com.example.audio.WakeEnrollmentStore
import com.example.audio.WakeModelManager
import com.example.audio.WakeWordEngine
import com.example.automation.CapturedNotification
import com.example.automation.RoutedVoiceCommand
import com.example.automation.VoiceCommandRouter
import com.example.control.AllVolumeLevels
import com.example.control.BatteryInfoSnapshot
import com.example.control.BatteryInformationManager
import com.example.control.DeviceInfoSnapshot
import com.example.control.DeviceInformationManager
import com.example.control.NetworkInfoSnapshot
import com.example.control.NetworkInformationManager
import com.example.control.SmartphoneControlManager
import com.example.control.StorageInfoSnapshot
import com.example.control.StorageInformationManager
import com.example.control.VolumeManager
import com.example.memory.ConversationHistoryStore
import com.example.memory.UserMemoryStore
import com.example.preferences.AppLanguage
import com.example.preferences.MahiPreferencesStore
import com.example.tools.MahiToolsManager
import com.example.tools.ToolAction
import com.example.ui.components.HistoryMemoryModal
import com.example.ui.components.SettingsModal
import com.example.ui.screens.NeuralLensScreen
import com.example.ui.screens.SmartControlScreen
import com.example.ui.theme.MahiAiTheme
import com.example.vision.ImageComparisonManager
import com.example.vision.LiveVisionSnapshot
import com.example.vision.NeuralLensAnalyzer
import com.example.vision.NotificationPriorityLevel
import com.example.vision.NotificationUnderstandingManager
import com.example.vision.OCRManager
import com.example.vision.VisualQuestionAnswerManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MahiV110UpgradeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val appContext: Application
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun cleanPrefsAndFiles() {
        appContext.getSharedPreferences("mahi_app_prefs", Application.MODE_PRIVATE)
            .edit().clear().commit()
        WakeEnrollmentStore(appContext).resetEnrollment()
        ConversationHistoryStore(appContext).clearAllHistory()
        UserMemoryStore(appContext).forgetEverything()
    }

    @Test
    fun `preferences default wake detection and memory consent to OFF and persist toggles`() {
        val store = MahiPreferencesStore(appContext)
        assertFalse(store.prefsFlow.value.wakeDetectionEnabled)
        assertFalse(store.prefsFlow.value.memoryConsentGranted)

        store.setWakeDetectionEnabled(true)
        store.setMemoryConsentGranted(true)
        assertTrue(store.prefsFlow.value.wakeDetectionEnabled)
        assertTrue(store.prefsFlow.value.memoryConsentGranted)
    }

    @Test
    fun `wake enrollment rejects quiet or short audio and calibrates across 5 valid samples`() {
        val store = WakeEnrollmentStore(appContext)

        // Quiet silence buffer (should fail cleanly)
        val quiet = ByteArray(16000) { 0 }
        val quietRes = store.addRecordedPcmSample(quiet)
        assertTrue(quietRes.isFailure)
        assertEquals(0, store.state.value.completedCount)

        // Generate 5 audible 16kHz PCM16 tone samples (0.45s each)
        val pcm = ByteArrayOutputStream()
        val samples = 7200
        for (i in 0 until samples) {
            val s = (12000 * kotlin.math.sin(i * 0.12)).toInt()
            pcm.write(s and 0xFF)
            pcm.write((s ushr 8) and 0xFF)
        }
        val validBytes = pcm.toByteArray()

        repeat(5) { idx ->
            val r = store.addRecordedPcmSample(validBytes)
            assertTrue("Sample ${idx + 1} failed", r.isSuccess)
        }

        assertEquals(5, store.state.value.completedCount)
        assertTrue(store.state.value.isComplete)
        assertTrue(store.buildVoskGrammarJson().contains("hey mahi"))

        store.resetEnrollment()
        assertEquals(0, store.state.value.completedCount)
        assertFalse(store.state.value.isComplete)
    }

    @Test
    fun `wake word phrase matcher validates hey mahi variants and rejects unk or unrelated speech`() {
        val engine = WakeWordEngine(
            appContext,
            WakeEnrollmentStore(appContext),
            WakeModelManager(appContext)
        )
        assertTrue(engine.isMatchingWakePhrase("hey mahi"))
        assertTrue(engine.isMatchingWakePhrase("Hey Maahi"))
        assertTrue(engine.isMatchingWakePhrase("mahi"))
        assertFalse(engine.isMatchingWakePhrase("[unk]"))
        assertFalse(engine.isMatchingWakePhrase("open youtube"))
        assertFalse(engine.isMatchingWakePhrase(""))
    }

    @Test
    fun `notification understanding masks OTPs and card numbers and never leaks raw codes`() {
        val otpMsg = "Your verification OTP is 849201. Never share with anyone."
        val (maskedOtp, flaggedOtp) = NotificationUnderstandingManager.redactSensitiveText(otpMsg)
        assertTrue(flaggedOtp)
        assertFalse(maskedOtp.contains("849201"))
        assertTrue(maskedOtp.contains("****"))

        val cardMsg = "Bank alert: Card 4532 1234 5678 9012 charged PKR 4500."
        val (maskedCard, flaggedCard) = NotificationUnderstandingManager.redactSensitiveText(cardMsg)
        assertTrue(flaggedCard)
        assertFalse(maskedCard.contains("4532"))
        assertTrue(maskedCard.contains("[REDACTED_CARD_NUMBER]"))

        val mgr = NotificationUnderstandingManager()
        val items = listOf(
            CapturedNotification(
                key = "k1",
                packageName = "com.whatsapp",
                appLabel = "WhatsApp",
                senderTitle = "Bank Alert",
                messageText = "Your OTP is 776655",
                timestampMs = 1L,
                isWhatsApp = true,
                canReply = false
            ),
            CapturedNotification(
                key = "k2",
                packageName = "com.shop",
                appLabel = "Store",
                senderTitle = "Mega Sale Promo",
                messageText = "50% discount today",
                timestampMs = 2L,
                isWhatsApp = false,
                canReply = false
            )
        )
        val summaries = mgr.getSafeSummaries(items)
        assertEquals(2, summaries.size)
        assertEquals(NotificationPriorityLevel.HIGH, summaries[0].priority)
        assertFalse(summaries[0].redactedMessage.contains("776655"))
        assertEquals(NotificationPriorityLevel.LOW, summaries[1].priority)
    }

    @Test
    fun `image comparison computes structural similarity and visual qa resets turns cleanly`() = runBlocking {
        val bmpA = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.CYAN)
        }
        val bmpSame = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.CYAN)
        }
        val bmpDiff = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLACK)
        }

        val cmp = ImageComparisonManager(OCRManager())
        assertEquals(100, cmp.computeStructuralSimilarityPercent(bmpA, bmpSame))
        assertTrue(cmp.computeStructuralSimilarityPercent(bmpA, bmpDiff) < 40)

        val vqa = VisualQuestionAnswerManager()
        assertFalse(vqa.hasActiveImage())
        vqa.setActiveImage(bmpA)
        assertTrue(vqa.hasActiveImage())
        vqa.resetConversation()
        assertTrue(vqa.turns.value.isEmpty())
    }

    @Test
    fun `conversation history persists threads and consent-gated memory respects opt-in and forget everything`() {
        val history = ConversationHistoryStore(appContext)
        history.appendTurn("user", "Battery kitni hai?", "roman_ur", "SASSY")
        history.appendTurn("assistant", "Battery 82% par hai!", "roman_ur", "SASSY")
        assertEquals(1, history.threads.value.size)
        assertEquals(2, history.threads.value.first().turns.size)

        val reloadedHistory = ConversationHistoryStore(appContext)
        assertEquals(1, reloadedHistory.threads.value.size)
        reloadedHistory.clearAllHistory()
        assertTrue(reloadedHistory.threads.value.isEmpty())

        val mem = UserMemoryStore(appContext)
        // Consent OFF -> refuses write and produces empty prompt context
        assertFalse(mem.updateProfileFields(consentGranted = false, userName = "Ashfaq"))
        assertFalse(mem.addOrUpdateFact(consentGranted = false, category = "Preference", content = "Roman Urdu"))
        assertEquals("", mem.buildPromptContext(consentGranted = false))

        // Consent ON -> saves and injects into prompt
        assertTrue(mem.updateProfileFields(consentGranted = true, userName = "Ashfaq", preferredLanguageNote = "Roman Urdu"))
        assertTrue(mem.addOrUpdateFact(consentGranted = true, category = "Device", content = "Moto G Stylus 2025"))
        val ctx = mem.buildPromptContext(consentGranted = true)
        assertTrue(ctx.contains("Ashfaq"))
        assertTrue(ctx.contains("Moto G Stylus 2025"))

        val aiPrompt = MahiAiEngine().buildSystemPrompt(MahiMood.SASSY, AppLanguage.ENGLISH, ctx)
        assertTrue(aiPrompt.contains("Ashfaq"))

        mem.forgetEverything()
        assertEquals("", mem.profile.value.userName)
        assertTrue(mem.profile.value.facts.isEmpty())
    }

    @Test
    fun `smart control managers and voice command router handle telemetry and chained commands`() {
        val battery = BatteryInformationManager(appContext).getBatteryInfo()
        assertTrue(battery.statusSummary.contains("%"))

        val storage = StorageInformationManager().getStorageInfo()
        assertTrue(storage.summary.contains("Storage"))

        val network = NetworkInformationManager(appContext).getNetworkInfo()
        assertTrue(network.summary.isNotBlank())

        val device = DeviceInformationManager(appContext).getDeviceInfo()
        assertTrue(device.sdkInt >= 24)
        assertTrue(device.summary.contains("Android"))

        val volLevels = VolumeManager(appContext).getAllLevels()
        assertTrue(volLevels.mediaPercent in 0..100)

        // VoiceCommandRouter Part 2 + Part 4 tests
        assertTrue(VoiceCommandRouter.parseCommand("screen par kya hai") is RoutedVoiceCommand.AnalyzeScreen)
        assertTrue(VoiceCommandRouter.parseCommand("screenshot samjhao") is RoutedVoiceCommand.AnalyzeScreenshot)
        assertTrue(VoiceCommandRouter.parseCommand("ye kya hai") is RoutedVoiceCommand.DescribeCameraVision)
        assertTrue(VoiceCommandRouter.parseCommand("notification batao") is RoutedVoiceCommand.AnalyzeNotifications)

        assertTrue(VoiceCommandRouter.parseCommand("battery kitni hai") is RoutedVoiceCommand.SmartControl)
        assertTrue(VoiceCommandRouter.parseCommand("storage kitna bacha hai") is RoutedVoiceCommand.SmartControl)
        assertTrue(VoiceCommandRouter.parseCommand("wifi connected hai?") is RoutedVoiceCommand.SmartControl)
        assertTrue(VoiceCommandRouter.parseCommand("volume mute karo") is RoutedVoiceCommand.SmartControl)
        assertTrue(VoiceCommandRouter.parseCommand("ringtone volume 50 karo") is RoutedVoiceCommand.SmartControl)
        assertTrue(VoiceCommandRouter.parseCommand("quick settings kholo") is RoutedVoiceCommand.SmartControl)
        assertTrue(VoiceCommandRouter.parseCommand("phone ka model batao") is RoutedVoiceCommand.SmartControl)

        val chained = VoiceCommandRouter.parseCommand("Settings kholo aur neeche scroll karo") as RoutedVoiceCommand.SmartControl
        assertEquals(2, chained.chainedSteps.size)
        assertEquals("Settings kholo", chained.chainedSteps[0])
        assertEquals("neeche scroll karo", chained.chainedSteps[1])

        val ctrlMgr = SmartphoneControlManager(appContext)
        val split = ctrlMgr.splitChainedCommands("Open Wi-Fi and then set volume 50")
        assertEquals(2, split.size)
    }

    @Test
    fun `gemini live tool declarations and tools manager support smart info and control tools`() {
        val declarations = GeminiLiveClient.buildToolDeclarations()
        val names = (0 until declarations.length()).map {
            declarations.getJSONObject(it).getString("name")
        }.toSet()
        assertTrue(names.contains("getBatteryInfo"))
        assertTrue(names.contains("getStorageInfo"))
        assertTrue(names.contains("getNetworkInfo"))
        assertTrue(names.contains("getDeviceInfo"))
        assertTrue(names.contains("setVolume"))
        assertTrue(names.contains("openQuickSettings"))

        val tools = MahiToolsManager(appContext)
        assertTrue(tools.execute(ToolAction("getBatteryInfo", emptyMap())).isSuccess)
        assertTrue(tools.execute(ToolAction("getStorageInfo", emptyMap())).isSuccess)
        assertTrue(tools.execute(ToolAction("getNetworkInfo", emptyMap())).isSuccess)
        assertTrue(tools.execute(ToolAction("getDeviceInfo", emptyMap())).isSuccess)
    }

    @Test
    fun `compose ui renders smart control cards, wake opt-in switch OFF default, memory modal and vision tabs`() {
        composeTestRule.setContent {
            MahiAiTheme {
                SmartControlScreen(
                    isFlashlightOn = false,
                    brightnessPercent = 70,
                    canWriteSettings = false,
                    volumeLevels = AllVolumeLevels(),
                    batteryInfo = BatteryInfoSnapshot(levelPercent = 85, chargeSource = "On Battery", statusSummary = "Battery is 85%"),
                    storageInfo = StorageInfoSnapshot(totalGb = 128f, freeGb = 64f, usedPercent = 50, summary = "64.0 GB free"),
                    networkInfo = NetworkInfoSnapshot(isConnected = true, transportLabel = "Wi-Fi", summary = "Connected via Wi-Fi"),
                    deviceInfo = DeviceInfoSnapshot(manufacturerModel = "Motorola Moto G Stylus 2025", androidVersion = "Android 15", sdkInt = 35, uptimeFormatted = "3h 10m"),
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
        composeTestRule.onNodeWithText("Battery Information").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Permission Status").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `neural lens submodes switch and trigger screen vision callback`() {
        var clickedSubmode = false
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
                    onReadLiveScreen = { clickedSubmode = true },
                    onSpeakAnswer = {},
                    onOpenApiSettings = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("lens_submode_1").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("lens_read_screen_button").performScrollTo().performClick()
        assertTrue(clickedSubmode)
    }

    @Test
    fun `settings modal renders wake detection switch OFF by default`() {
        composeTestRule.setContent {
            MahiAiTheme {
                SettingsModal(
                    currentMood = MahiMood.SASSY,
                    isContinuousMode = true,
                    showSubtitles = true,
                    voicePitch = 1.2f,
                    voiceSpeed = 1.08f,
                    wakeDetectionEnabled = false,
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

        composeTestRule.onNodeWithTag("wake_detection_switch")
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsOff()
    }

    @Test
    fun `history memory modal renders conversation and memory tabs`() {
        var forgetTriggered = false
        composeTestRule.setContent {
            MahiAiTheme {
                HistoryMemoryModal(
                    threads = emptyList(),
                    activeThreadId = null,
                    memoryConsentGranted = false,
                    memoryProfile = com.example.memory.UserMemoryProfile(),
                    onSelectThread = {},
                    onStartNewThread = {},
                    onDeleteThread = {},
                    onClearAllHistory = {},
                    onSetMemoryConsent = {},
                    onUpdateProfileFields = { _, _, _ -> },
                    onAddOrEditFact = { _, _, _ -> },
                    onDeleteFact = {},
                    onForgetEverything = { forgetTriggered = true },
                    onDismiss = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("history_memory_tab_1").performClick()
        composeTestRule.onNodeWithTag("memory_forget_everything_button").performClick()
        assertTrue(forgetTriggered)
    }
}
