package com.example

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.agents.AgentNetworkCoordinator
import com.example.api.AiProvider
import com.example.api.ApiManagerState
import com.example.api.InMemoryAesGcmCipher
import com.example.api.ModelCapability
import com.example.api.ModelRegistry
import com.example.api.SecureApiKeyStore
import com.example.audio.MahiSpeechSynthesizer
import com.example.automation.RoutedVoiceCommand
import com.example.automation.VoiceCommandRouter
import com.example.control.PermissionAuditManager
import com.example.data.AgentCollaborationEntity
import com.example.data.CachedModelEntity
import com.example.data.ChatMessageEntity
import com.example.data.KnowledgeNoteEntity
import com.example.data.MahiDatabase
import com.example.data.ReleaseRecordEntity
import com.example.marketplace.ToolMarketplaceManager
import com.example.memory.UserMemoryProfile
import com.example.pipeline.SelfCodingPipelineManager
import com.example.preferences.MahiPreferencesStore
import com.example.preferences.MahiUserPreferences
import com.example.ui.components.SettingsModal
import com.example.ui.screens.ApiProvidersScreen
import com.example.ui.theme.MahiAiTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MahiV33FeaturesTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val appContext: Application
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `tts speech speed applies clarity compensation at high rates and preserves bounds`() {
        val low = MahiSpeechSynthesizer.computeEffectiveTtsParams(0.2f, 1.05f, clarityCompensation = true)
        assertEquals(MahiSpeechSynthesizer.MIN_SPEED, low.speechRate, 0.001f)
        assertEquals(1.05f, low.pitch, 0.001f)

        val fastWithClarity = MahiSpeechSynthesizer.computeEffectiveTtsParams(2.0f, 1.15f, clarityCompensation = true)
        assertEquals(2.0f, fastWithClarity.speechRate, 0.001f)
        assertTrue("High-speed clarity should gently lower pitch below requested base", fastWithClarity.pitch < 1.15f)

        val fastWithoutClarity = MahiSpeechSynthesizer.computeEffectiveTtsParams(2.0f, 1.15f, clarityCompensation = false)
        assertEquals(1.15f, fastWithoutClarity.pitch, 0.001f)

        val prepared = MahiSpeechSynthesizer.prepareIntelligibleText("Hello;   world—fast   speech", 1.7f)
        assertEquals("Hello, world, fast speech", prepared)

        val prefsStore = MahiPreferencesStore(appContext)
        prefsStore.setVoiceSpeed(1.65f)
        assertEquals(1.65f, prefsStore.prefsFlow.value.voiceSpeed, 0.01f)

        val routed = VoiceCommandRouter.parseCommand("Set speech speed to 1.75")
        assertTrue(routed is RoutedVoiceCommand.SetTtsSpeed)
        assertEquals(1.75f, (routed as RoutedVoiceCommand.SetTtsSpeed).speed, 0.01f)
    }

    @Test
    fun `settings modal renders tts speed slider, clarity toggle, and preview button`() {
        var speedUpdated = 1.0f
        var previewClicked = false
        composeTestRule.setContent {
            MahiAiTheme {
                SettingsModal(
                    prefs = MahiUserPreferences(voiceSpeed = 1.35f, ttsClarityCompensation = true),
                    wakeModelState = com.example.audio.WakeModelState(),
                    learnedLabelCount = 2,
                    memoryProfile = UserMemoryProfile(),
                    onLanguageChange = {},
                    onVoiceGenderChange = {},
                    onVoicePitchChange = {},
                    onVoiceSpeedChange = { speedUpdated = it },
                    onTtsClarityChange = {},
                    onTestVoiceSpeed = { previewClicked = true },
                    onContinuousModeChange = {},
                    onShowSubtitlesChange = {},
                    onDarkThemeChange = {},
                    onAutopilotChange = {},
                    onWakeDetectionChange = {},
                    onPreferGeminiLiveChange = {},
                    onDownloadWakeModel = {},
                    onClearLearnedLabels = {},
                    onMemoryConsentChange = {},
                    onForgetMemory = {},
                    onDismiss = {}
                )
            }
        }
        composeTestRule.onNodeWithTag("tts_speed_label").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("tts_clarity_switch").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("tts_test_voice_button").performScrollTo().performClick()
        assertTrue(previewClicked)
    }

    @Test
    fun `secure api key store encrypts multi-slot keys, masks preview, and classifies models`() {
        val keyStore = SecureApiKeyStore(appContext, cipher = InMemoryAesGcmCipher())
        val slot1 = keyStore.addSlot(AiProvider.GEMINI, "Main", "AIzaSyExampleSecretKey1234567890")
        val slot2 = keyStore.addSlot(AiProvider.GEMINI, "Backup", "AIzaSyBackupSecretKey9988776655")
        assertNotNull(slot1)
        assertNotNull(slot2)

        assertTrue(slot1!!.maskedPreview.contains("…") || slot1.maskedPreview.contains("..."))
        assertFalse(slot1.maskedPreview.contains("ExampleSecretKey"))
        assertEquals(2, keyStore.state.value.slotsByProvider[AiProvider.GEMINI]?.size)

        keyStore.selectSlot(AiProvider.GEMINI, slot1.id)
        assertEquals("AIzaSyExampleSecretKey1234567890", keyStore.decryptSlotKey(AiProvider.GEMINI))
        keyStore.selectSlot(AiProvider.GEMINI, slot2!!.id)
        assertEquals("AIzaSyBackupSecretKey9988776655", keyStore.decryptSlotKey(AiProvider.GEMINI))

        val liveCaps = ModelRegistry.classifyCapabilities(
            AiProvider.GEMINI,
            "gemini-2.5-flash",
            listOf("generateContent", "bidiGenerateContent")
        )
        assertTrue(liveCaps.contains(ModelCapability.CHAT))
        assertTrue(liveCaps.contains(ModelCapability.LIVE))
        assertTrue(liveCaps.contains(ModelCapability.VISION))
        assertTrue(liveCaps.contains(ModelCapability.TOOLS))

        val embedCaps = ModelRegistry.classifyCapabilities(
            AiProvider.GEMINI,
            "text-embedding-004",
            listOf("embedContent")
        )
        assertTrue(embedCaps.contains(ModelCapability.EMBEDDINGS))
    }

    @Test
    fun `room database persists cached models, chats, collaborations, releases, and knowledge notes`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(appContext, MahiDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            db.cachedModelDao().upsertAll(
                listOf(
                    CachedModelEntity("GEMINI:gemini-2.5-flash", "GEMINI", "gemini-2.5-flash", "Gemini 2.5 Flash", "chat,vision,live,tools", "Free/Standard", 1_048_576, true, "OK", 120L, 1000L)
                )
            )
            assertEquals(1, db.cachedModelDao().getByProvider("GEMINI").size)

            db.chatMessageDao().insert(ChatMessageEntity("m1", "s1", "user", "Salam", "", "", 100L))
            assertEquals("Salam", db.chatMessageDao().observeHistory().first().first().text)

            db.agentCollaborationDao().insert(AgentCollaborationEntity("c1", "orchestrator", "screen_reader", "snap", "captured", "DELIVERED", 200L))
            assertEquals(1, db.agentCollaborationDao().observeAll().first().size)

            db.releaseRecordDao().insert(ReleaseRecordEntity("r1", "3.3.0", 16, "main", "abc123", "APPROVED", "notes", 300L))
            assertEquals("3.3.0", db.releaseRecordDao().observeAll().first().first().versionName)

            db.knowledgeNoteDao().upsert(KnowledgeNoteEntity("k1", "Mahi Architecture", "manual", "Clean MVVM", "android", 400L))
            assertEquals(1, db.knowledgeNoteDao().observeAll().first().size)
        } finally {
            db.close()
        }
    }

    @Test
    fun `agent coordinator 17-node bus, marketplace self-test, sandbox security scan, and permissions audit work cleanly`() {
        val coordinator = AgentNetworkCoordinator(appContext)
        assertEquals(17, coordinator.nodes.value.size)
        assertEquals(17, coordinator.runHeartbeatSweep())
        assertTrue(coordinator.recentLogs.value.isNotEmpty())

        val market = ToolMarketplaceManager(appContext)
        val diagResult = market.runStaticSecurityCheckAndSelfTest("privacy_scrubber")
        assertTrue(diagResult.startsWith("PASS"))

        val pipeline = SelfCodingPipelineManager(appContext)
        val blocked = pipeline.stagePatchInSandbox(
            "Unsafe Patch",
            "bad.kt",
            "val key = \"AIzaSyFakeHardcodedKey1234567890123\"; val cl = DexClassLoader()"
        )
        assertEquals("BLOCKED_SECURITY", blocked.status)
        assertTrue(blocked.securityFindings.size >= 2)

        val clean = pipeline.stagePatchInSandbox("Clean Utility", "safe.kt", "fun greet() = \"Salam\"")
        assertEquals("STAGED_AWAITING_APPROVAL", clean.status)
        assertTrue(clean.securityFindings.isEmpty())

        val permItems = PermissionAuditManager(appContext).auditAll()
        assertNotNull(permItems.firstOrNull { it.id == "mic" })
        assertNotNull(permItems.firstOrNull { it.id == "accessibility" })
    }

    @Test
    fun `api providers screen renders remote grounding collapsible toggle cleanly`() {
        composeTestRule.setContent {
            MahiAiTheme {
                ApiProvidersScreen(
                    apiState = ApiManagerState(),
                    discoveredModels = emptyList(),
                    remoteGroundingConfig = SecureApiKeyStore.RemoteGroundingConfig(),
                    isTestingConnection = false,
                    registryStatus = "Ready",
                    onSelectProvider = {},
                    onAddKeySlot = { _, _, _, _ -> },
                    onSelectSlot = { _, _ -> },
                    onDeleteSlot = { _, _ -> },
                    onTestConnection = {},
                    onRefreshModels = {},
                    onSelectModel = { _, _ -> },
                    onSaveRemoteGrounding = { _, _, _ -> }
                )
            }
        }
        composeTestRule.onNodeWithTag("api_providers_screen").assertIsDisplayed()
        composeTestRule.onNodeWithTag("remote_grounding_toggle").performScrollTo().performClick()
    }
}
