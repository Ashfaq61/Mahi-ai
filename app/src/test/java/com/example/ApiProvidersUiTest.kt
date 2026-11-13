package com.example

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ApplicationProvider
import com.example.ai.MahiMood
import com.example.api.AiProvider
import com.example.api.ConnectionStatus
import com.example.api.InMemoryAesGcmCipher
import com.example.api.SecureApiKeyStore
import com.example.ui.components.MahiStatusBanner
import com.example.ui.components.SettingsModal
import com.example.ui.screens.ApiProvidersScreen
import com.example.ui.theme.MahiAiTheme
import com.example.viewmodel.AssistantVisualState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ApiProvidersUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var store: SecureApiKeyStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("mahi_secure_api_store", Context.MODE_PRIVATE).edit().clear().commit()
        store = SecureApiKeyStore(context, InMemoryAesGcmCipher(ByteArray(32) { 7 }))
    }

    @Test
    fun `home status banner and settings modal expose working API Keys & Providers triggers`() {
        var openedFromHome = false
        var openedFromSettings = false

        composeTestRule.setContent {
            MahiAiTheme {
                androidx.compose.foundation.layout.Column {
                    MahiStatusBanner(
                        visualState = AssistantVisualState.DISCONNECTED,
                        currentMood = MahiMood.SASSY,
                        isContinuousMode = true,
                        isMuted = false,
                        connectionStatus = ConnectionStatus.CONNECTED,
                        onMoodClick = {},
                        onContinuousToggle = {},
                        onMuteToggle = {},
                        onApiSettingsClick = { openedFromHome = true },
                        onSettingsClick = {}
                    )
                    SettingsModal(
                        currentMood = MahiMood.SASSY,
                        isContinuousMode = true,
                        showSubtitles = true,
                        voicePitch = 1.2f,
                        voiceSpeed = 1.08f,
                        activeProvider = AiProvider.GEMINI,
                        activeModel = "gemini-2.5-flash-native-audio-preview-09-2025",
                        connectionStatus = ConnectionStatus.CONNECTED,
                        onOpenApiSettings = { openedFromSettings = true },
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
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("home_api_settings_button").assertIsDisplayed().performClick()
        assertTrue(openedFromHome)

        composeTestRule.onNodeWithTag("settings_api_providers_button").assertIsDisplayed()
        composeTestRule.onNodeWithText("API Keys & Providers").assertIsDisplayed()
        composeTestRule.onNodeWithTag("settings_api_providers_button").performClick()
        assertTrue(openedFromSettings)
    }

    @Test
    fun `api providers screen allows adding showing saving editing deleting keys and switching providers`() {
        composeTestRule.setContent {
            val apiState by store.state.collectAsStateWithLifecycle()
            MahiAiTheme {
                ApiProvidersScreen(
                    apiState = apiState,
                    onBack = {},
                    onSetActiveProvider = { store.setActiveProvider(it) },
                    onAddKey = { provider, rawKey, label, _ ->
                        store.addKey(provider, rawKey, label, selectAsActiveKey = true)
                    },
                    onUpdateKey = { provider, keyId, newRawKey, newLabel, _ ->
                        store.updateKey(provider, keyId, newRawKey, newLabel)
                    },
                    onDeleteKey = { provider, keyId -> store.deleteKey(provider, keyId) },
                    onSelectKey = { provider, keyId -> store.selectKey(provider, keyId) },
                    onTestKey = { _, _ -> },
                    onRevealKey = { keyId -> store.getDecryptedKey(keyId) },
                    onModelSelected = { provider, model -> store.setSelectedModel(provider, model) },
                    onBaseUrlChanged = { provider, url -> store.setCustomBaseUrl(provider, url) }
                )
            }
        }

        composeTestRule.waitForIdle()

        // Active Gemini card should be displayed & expanded by default
        composeTestRule.onNodeWithTag("provider_card_gemini").assertIsDisplayed()
        composeTestRule.onNodeWithTag("key_label_input_gemini").performScrollTo().performTextInput("Primary Gemini")
        composeTestRule.onNodeWithTag("api_key_input_gemini").performScrollTo().performTextInput("AIzaSyComposeUiTestKey8899")
        composeTestRule.onNodeWithTag("toggle_key_visibility_gemini").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("save_key_button_gemini").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        // Verify saved key appears in list and encrypted store
        val savedEntry = store.state.value.getKeys(AiProvider.GEMINI).first()
        assertEquals("Primary Gemini", savedEntry.label)
        assertEquals("AIzaSyComposeUiTestKey8899", store.getDecryptedKey(savedEntry.id))
        composeTestRule.onNodeWithTag("saved_key_row_${savedEntry.id}").performScrollTo().assertIsDisplayed()

        // Reveal saved key in row
        composeTestRule.onNodeWithTag("reveal_saved_key_${savedEntry.id}").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("AIzaSyComposeUiTestKey8899").assertIsDisplayed()

        // Delete saved key via UI
        composeTestRule.onNodeWithTag("delete_saved_key_${savedEntry.id}").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        assertTrue(store.state.value.getKeys(AiProvider.GEMINI).isEmpty())

        // Scroll to OpenRouter card and activate provider via UI
        composeTestRule.onNodeWithTag("api_providers_list").performScrollToKey("openrouter")
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("activate_provider_openrouter").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        assertEquals(AiProvider.OPENROUTER, store.state.value.activeProvider)
    }
}
