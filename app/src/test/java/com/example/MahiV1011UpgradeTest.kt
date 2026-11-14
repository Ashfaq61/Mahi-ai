package com.example

import android.app.Application
import android.app.Notification
import android.os.Bundle
import android.os.Parcelable
import androidx.test.core.app.ApplicationProvider
import com.example.ai.GeminiLiveClient
import com.example.api.AiProvider
import com.example.api.isGeminiLiveAudioModel
import com.example.automation.AppLauncherManager
import com.example.automation.MahiNotificationService
import com.example.automation.RoutedVoiceCommand
import com.example.automation.VoiceCommandRouter
import com.example.tools.MahiToolsManager
import com.example.tools.ToolAction
import com.example.viewmodel.AssistantVisualState
import com.example.viewmodel.MahiAssistantViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MahiV1011UpgradeTest {

    private val appContext: Application
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `app launcher matches specific multi-word aliases before generic google alias`() {
        val chrome = AppLauncherManager.matchKnownApp("open google chrome")
        assertNotNull(chrome)
        assertEquals("Chrome", chrome?.canonicalName)
        assertTrue(chrome!!.packageCandidates.contains("com.android.chrome"))

        val maps = AppLauncherManager.matchKnownApp("launch google maps")
        assertNotNull(maps)
        assertEquals("Maps", maps?.canonicalName)
        assertTrue(maps!!.packageCandidates.contains("com.google.android.apps.maps"))

        val google = AppLauncherManager.matchKnownApp("open google")
        assertNotNull(google)
        assertEquals("Google", google?.canonicalName)

        val ytMusic = AppLauncherManager.matchKnownApp("play youtube music")
        assertNotNull(ytMusic)
        assertEquals("YouTube Music", ytMusic?.canonicalName)

        val snap = AppLauncherManager.matchKnownApp("snapchat kholo")
        assertNotNull(snap)
        assertEquals("Snapchat", snap?.canonicalName)

        val fbUrdu = AppLauncherManager.matchKnownApp("فیس بک کھولو")
        assertEquals("Facebook", fbUrdu?.canonicalName)

        val ttHindi = AppLauncherManager.matchKnownApp("टिक टॉक चलाओ")
        assertEquals("TikTok", ttHindi?.canonicalName)

        val wa = AppLauncherManager.matchKnownApp("wa open karo")
        assertEquals("WhatsApp", wa?.canonicalName)
    }

    @Test
    fun `voice command router parses notification reading whatsapp replies and preserves partial guards`() {
        val readWa = VoiceCommandRouter.parseCommand("Read my WhatsApp notifications")
            as RoutedVoiceCommand.ReadNotifications
        assertTrue(readWa.whatsAppOnly)

        val checkAll = VoiceCommandRouter.parseCommand("Notifications check karo")
            as RoutedVoiceCommand.ReadNotifications
        assertFalse(checkAll.whatsAppOnly)

        val replyTo = VoiceCommandRouter.parseCommand("Reply to Ali: On my way")
            as RoutedVoiceCommand.ReplyWhatsApp
        assertEquals("Ali", replyTo.senderHint)
        assertEquals("On my way", replyTo.message)

        val replyWa = VoiceCommandRouter.parseCommand("whatsapp reply Ok")
            as RoutedVoiceCommand.ReplyWhatsApp
        assertEquals("", replyWa.senderHint)
        assertEquals("Ok", replyWa.message)

        assertTrue(VoiceCommandRouter.isLikelyIncompletePartial("open"))
        assertFalse(VoiceCommandRouter.isLikelyIncompletePartial("open facebook"))
    }

    @Test
    fun `notification service extracts MessagingStyle EXTRA_MESSAGES and falls back safely`() {
        val msg1 = Bundle().apply {
            putCharSequence("sender", "Sara")
            putCharSequence("text", "First ping")
        }
        val msg2 = Bundle().apply {
            putCharSequence("sender", "Ali")
            putCharSequence("text", "Are you free now?")
        }
        val groupExtras = Bundle().apply {
            putCharSequence(Notification.EXTRA_CONVERSATION_TITLE, "Family Group")
            putParcelableArray(Notification.EXTRA_MESSAGES, arrayOf<Parcelable>(msg1, msg2))
        }

        val (groupSender, groupText) = MahiNotificationService.extractSenderAndMessage(
            packageName = "com.whatsapp",
            extras = groupExtras
        )
        assertEquals("Family Group (Ali)", groupSender)
        assertEquals("Are you free now?", groupText)

        val fallbackExtras = Bundle().apply {
            putCharSequence(Notification.EXTRA_TITLE, "Zara")
            putCharSequence(Notification.EXTRA_TEXT, "See you at 5")
        }
        val (fallbackSender, fallbackText) = MahiNotificationService.extractSenderAndMessage(
            packageName = "com.whatsapp",
            extras = fallbackExtras
        )
        assertEquals("Zara", fallbackSender)
        assertEquals("See you at 5", fallbackText)
    }

    @Test
    fun `gemini live default model and tool declarations include notification and settings actions`() {
        val defaultLiveModel = AiProvider.GEMINI.defaultModels.first()
        assertEquals("gemini-2.5-flash-native-audio-preview-12-2025", defaultLiveModel)
        assertTrue(isGeminiLiveAudioModel(defaultLiveModel))

        val declarations = GeminiLiveClient.buildToolDeclarations()
        val names = (0 until declarations.length()).map { idx ->
            declarations.getJSONObject(idx).getString("name")
        }.toSet()
        assertTrue(names.contains("readNotifications"))
        assertTrue(names.contains("openSettings"))
        assertTrue(names.contains("openApp"))
        assertTrue(names.contains("stopAutomation"))
    }

    @Test
    fun `tools manager handles readNotifications and openSettings safely in headless test`() {
        val tools = MahiToolsManager(context = appContext)

        val notifRes = tools.execute(ToolAction("readNotifications", mapOf("filter" to "whatsapp")))
        assertEquals("readNotifications", notifRes.actionName)
        assertTrue(notifRes.message.isNotBlank())

        val settingsRes = tools.execute(ToolAction("openSettings", mapOf("category" to "accessibility")))
        assertEquals("openSettings", settingsRes.actionName)
        assertTrue(settingsRes.message.isNotBlank())
    }

    @Test
    fun `view model startSession without RECORD_AUDIO sets clear error state instead of throwing`() {
        val viewModel = MahiAssistantViewModel(appContext)
        viewModel.startSession()

        val state = viewModel.uiState.value
        assertEquals(AssistantVisualState.ERROR, state.visualState)
        assertFalse(state.isGeminiLiveActive)
        assertFalse(state.isMicActivelyCapturing)
        assertTrue(state.errorMessage.orEmpty().contains("Microphone permission", ignoreCase = true))
    }
}
