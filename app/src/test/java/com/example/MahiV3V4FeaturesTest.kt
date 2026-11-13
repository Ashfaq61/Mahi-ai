package com.example

import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import android.graphics.Rect
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.example.ai.GeminiLiveClient
import com.example.api.ApiErrorCategory
import com.example.automation.AppLaunchStatus
import com.example.automation.AppLauncherManager
import com.example.automation.DeviceControlHelper
import com.example.automation.GestureSpeed
import com.example.automation.InstalledAppInfo
import com.example.automation.MahiAccessibilityService
import com.example.automation.RoutedVoiceCommand
import com.example.automation.ScreenSnapshot
import com.example.automation.SwipeDirection
import com.example.automation.VoiceCommandRouter
import com.example.automation.VoiceGestureIntent
import com.example.calls.ActiveCallState
import com.example.calls.CallAutomationPrefs
import com.example.calls.CallAutomationRepository
import com.example.calls.CallRuleContact
import com.example.calls.ScreeningDecisionAction
import com.example.calls.TelecomCapabilityStatus
import com.example.calls.UnknownCallerPolicy
import com.example.preferences.AppLanguage
import com.example.ui.screens.AgentAutomationScreen
import com.example.ui.screens.CallsAndSettingsScreen
import com.example.ui.theme.MahiAiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MahiV3V4FeaturesTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val appContext: Application
        get() = ApplicationProvider.getApplicationContext()

    private fun registerFakeLaunchableApp(
        pkgName: String,
        label: String,
        enabled: Boolean = true
    ) {
        val pm = appContext.packageManager
        val shadowPm = shadowOf(pm)

        val appInfo = ApplicationInfo().apply {
            packageName = pkgName
            this.enabled = enabled
            flags = 0
        }
        val actInfo = ActivityInfo().apply {
            packageName = pkgName
            name = "$pkgName.MainActivity"
            this.enabled = enabled
            exported = true
            applicationInfo = appInfo
        }
        val pkgInfo = PackageInfo().apply {
            packageName = pkgName
            versionName = "1.0.0"
            applicationInfo = appInfo
            activities = arrayOf(actInfo)
        }
        shadowPm.installPackage(pkgInfo)

        val resolveInfo = ResolveInfo().apply {
            activityInfo = actInfo
            nonLocalizedLabel = label
        }
        val mainIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        shadowPm.addResolveInfoForIntent(mainIntent, resolveInfo)

        val pkgLauncherIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            setPackage(pkgName)
        }
        shadowPm.addResolveInfoForIntent(pkgLauncherIntent, resolveInfo)
    }

    @Test
    fun `app launcher resolves TikTok Facebook YouTube handles disabled app and filters installed apps`() {
        registerFakeLaunchableApp("com.zhiliaoapp.musically", "TikTok", enabled = true)
        registerFakeLaunchableApp("com.facebook.katana", "Facebook", enabled = false)
        registerFakeLaunchableApp("com.google.android.youtube", "YouTube", enabled = true)

        val manager = AppLauncherManager(appContext)

        // TikTok launches cleanly
        val tiktokRes = manager.launchApp("TikTok kholo", openStoreOrWebIfMissing = false)
        assertTrue(tiktokRes.success)
        assertEquals(AppLaunchStatus.LAUNCHED_NATIVE_APP, tiktokRes.status)
        assertEquals("com.zhiliaoapp.musically", tiktokRes.resolvedPackage)

        // Disabled Facebook reports APP_DISABLED instead of faking success
        val fbRes = manager.launchApp("Facebook open karo", openStoreOrWebIfMissing = false)
        assertFalse(fbRes.success)
        assertEquals(AppLaunchStatus.APP_DISABLED, fbRes.status)
        assertEquals("com.facebook.katana", fbRes.resolvedPackage)

        // YouTube launches cleanly
        val ytRes = manager.launchApp("YouTube chalao", openStoreOrWebIfMissing = false)
        assertTrue(ytRes.success)
        assertEquals("com.google.android.youtube", ytRes.resolvedPackage)

        // Missing app fails cleanly when fallback disabled
        val missingRes = manager.launchApp("Instagram", openStoreOrWebIfMissing = false)
        assertFalse(missingRes.success)

        // Searchable installed apps filter works
        val searched = manager.getInstalledLaunchableApps("tik")
        assertTrue(searched.any { it.packageName == "com.zhiliaoapp.musically" })
    }

    @Test
    fun `bilingual voice command router parses Urdu Roman Urdu and English commands`() {
        // App launch + compound follow-up scroll
        val tiktok = VoiceCommandRouter.parseCommand("TikTok kholo") as RoutedVoiceCommand.LaunchApp
        assertEquals("TikTok", tiktok.appName)

        val fbCompound = VoiceCommandRouter.parseCommand("Open Facebook and scroll down") as RoutedVoiceCommand.LaunchApp
        assertEquals("Facebook", fbCompound.appName)
        assertNotNull(fbCompound.followUpGesture)
        assertEquals("SWIPE_DOWN_SCROLL", fbCompound.followUpGesture?.actionName)

        val ytSearch = VoiceCommandRouter.parseCommand("YouTube par search karo lo-fi study beats") as RoutedVoiceCommand.SearchYouTube
        assertEquals("lo-fi study beats", ytSearch.query)

        // Screen gestures
        val up = VoiceCommandRouter.parseCommand("Mahi, screen upar karo.") as RoutedVoiceCommand.ExecuteGesture
        assertEquals("SWIPE_UP", up.gesture.actionName)

        val fast = VoiceCommandRouter.parseCommand("Fast scroll karo.") as RoutedVoiceCommand.ExecuteGesture
        assertEquals(GestureSpeed.FAST, fast.gesture.speed)

        val bottom = VoiceCommandRouter.parseCommand("Is page ke end tak jao.") as RoutedVoiceCommand.ExecuteGesture
        assertEquals("SCROLL_BOTTOM", bottom.gesture.actionName)

        val tap = VoiceCommandRouter.parseCommand("Settings button click karo") as RoutedVoiceCommand.ExecuteGesture
        assertEquals("TAP_TEXT", tap.gesture.actionName)
        assertTrue(tap.gesture.targetText.contains("settings"))

        // Immediate STOP
        assertTrue(VoiceCommandRouter.parseCommand("Ruk jao") is RoutedVoiceCommand.StopEverything)
        assertTrue(VoiceCommandRouter.parseCommand("STOP") is RoutedVoiceCommand.StopEverything)

        // Calls
        val place = VoiceCommandRouter.parseCommand("Call +923001234567") as RoutedVoiceCommand.PlaceCall
        assertEquals("+923001234567", place.target)
        val placeWake = VoiceCommandRouter.parseCommand("Mahi, call Ali.") as RoutedVoiceCommand.PlaceCall
        assertEquals("Ali", placeWake.target)
        val placeRoman = VoiceCommandRouter.parseCommand("Sara ko call karo") as RoutedVoiceCommand.PlaceCall
        assertEquals("Sara", placeRoman.target)
        assertTrue(VoiceCommandRouter.parseCommand("Answer call") is RoutedVoiceCommand.AnswerActiveCall)

        // Auto language detection
        assertEquals(
            AppLanguage.URDU,
            VoiceCommandRouter.detectLanguageFromUtterance("ٹک ٹاک کھولو", AppLanguage.ENGLISH)
        )
        assertEquals(
            AppLanguage.ROMAN_URDU,
            VoiceCommandRouter.detectLanguageFromUtterance("Facebook open karo aur neeche jao", AppLanguage.ENGLISH)
        )
        assertEquals(
            AppLanguage.ENGLISH,
            VoiceCommandRouter.detectLanguageFromUtterance("Open settings and turn on wifi please", AppLanguage.ROMAN_URDU)
        )
    }

    @Test
    fun `gemini live setup enforces audio modality tools and accurate error classification`() {
        val setup = GeminiLiveClient.buildSetupMessage("gemini-2.5-flash-native-audio-preview-12-2025", "Be helpful")
        assertTrue(setup.contains("\"AUDIO\""))
        assertTrue(setup.contains("\"openApp\""))
        assertTrue(setup.contains("\"screenGesture\""))

        val authErr = GeminiLiveClient.classifyCloseOrFailureError(401, "API key not valid")
        assertEquals(ApiErrorCategory.INVALID_KEY, authErr.category)
        assertFalse(GeminiLiveClient.isRecoverableError(authErr))

        val quotaErr = GeminiLiveClient.classifyCloseOrFailureError(429, "RESOURCE_EXHAUSTED")
        assertEquals(ApiErrorCategory.QUOTA_EXCEEDED, quotaErr.category)
        assertFalse(GeminiLiveClient.isRecoverableError(quotaErr))

        val modelErr = GeminiLiveClient.classifyCloseOrFailureError(404, "Model not supported for bidiGenerateContent")
        assertEquals(ApiErrorCategory.UNSUPPORTED_MODEL, modelErr.category)
        assertFalse(GeminiLiveClient.isRecoverableError(modelErr))

        val netErr = GeminiLiveClient.classifyCloseOrFailureError(1006, "Socket timeout")
        assertEquals(ApiErrorCategory.CONNECTION_FAILED, netErr.category)
        assertTrue(GeminiLiveClient.isRecoverableError(netErr))

        // Clean disconnect lifecycle check
        val client = GeminiLiveClient()
        client.disconnect()
    }

    @Test
    fun `accessibility gesture engine computes directional bounds and honors immediate STOP`() {
        val bounds = Rect(100, 200, 900, 1800)

        val swipeUp = MahiAccessibilityService.computeSwipeSegment(bounds, SwipeDirection.UP)
        assertTrue(swipeUp.startY > swipeUp.endY)
        assertEquals(500f, swipeUp.startX, 1f)

        val swipeDown = MahiAccessibilityService.computeSwipeSegment(bounds, SwipeDirection.DOWN)
        assertTrue(swipeDown.startY < swipeDown.endY)

        val swipeLeft = MahiAccessibilityService.computeSwipeSegment(bounds, SwipeDirection.LEFT)
        assertTrue(swipeLeft.startX > swipeLeft.endX)

        val swipeRight = MahiAccessibilityService.computeSwipeSegment(bounds, SwipeDirection.RIGHT)
        assertTrue(swipeRight.startX < swipeRight.endX)

        val stopOutcome = MahiAccessibilityService.executeGestureIntent(VoiceGestureIntent("STOP"))
        assertTrue(stopOutcome.success)
        assertFalse(MahiAccessibilityService.isContinuousScrolling.value)
        assertTrue(MahiAccessibilityService.gestureHistory.value.any { it.actionName == "STOP" })
    }

    @Test
    fun `call automation defaults safe OFF enforces rules emergency override and AI disclosure guardrails`() {
        // Default safety invariant
        val safeDefaults = CallAutomationPrefs()
        assertTrue(safeDefaults.manualOnlyMode)
        assertFalse(safeDefaults.autoAnswerEnabled)
        assertFalse(safeDefaults.aiCallAssistantEnabled)
        assertFalse(safeDefaults.speakerphoneBridgeConsent)

        // Manual-only prevents auto-answer even when unknown/blocked
        val manualDecision = CallAutomationRepository.evaluateIncomingCallPolicy(
            phoneNumber = "+923009999999",
            isSavedContact = false,
            verificationFailed = true,
            prefs = safeDefaults
        )
        assertEquals(ScreeningDecisionAction.ALLOW_RING, manualDecision.action)

        // Enabled rules: blocked, quiet hours, STIR/SHAKEN spam, auto-answer with AI disclosure, emergency override
        val activePrefs = CallAutomationPrefs(
            manualOnlyMode = false,
            autoAnswerEnabled = true,
            aiCallAssistantEnabled = true,
            blockedCallers = listOf(CallRuleContact(name = "Spammer", phoneNumber = "+15551112222")),
            trustedContacts = listOf(CallRuleContact(name = "Mom", phoneNumber = "+923001112233")),
            quietHoursEnabled = true,
            quietHoursStartHour = 22,
            quietHoursEndHour = 7,
            emergencyOverrideEnabled = true,
            unknownCallerPolicy = UnknownCallerPolicy.REJECT_UNVERIFIED_SPAM
        )

        val blocked = CallAutomationRepository.evaluateIncomingCallPolicy(
            phoneNumber = "+15551112222",
            isSavedContact = false,
            verificationFailed = false,
            prefs = activePrefs,
            currentHourOfDay = 14
        )
        assertEquals(ScreeningDecisionAction.REJECT_CALL, blocked.action)

        val quietSilenced = CallAutomationRepository.evaluateIncomingCallPolicy(
            phoneNumber = "+923007778888",
            isSavedContact = true,
            verificationFailed = false,
            prefs = activePrefs,
            currentHourOfDay = 23,
            recentCallsInWindow = 0
        )
        assertEquals(ScreeningDecisionAction.SILENCE_CALL, quietSilenced.action)

        val emergencyOverride = CallAutomationRepository.evaluateIncomingCallPolicy(
            phoneNumber = "+923001112233",
            isSavedContact = true,
            verificationFailed = false,
            prefs = activePrefs,
            currentHourOfDay = 23,
            recentCallsInWindow = 1
        )
        assertEquals(ScreeningDecisionAction.EMERGENCY_OVERRIDE_RING, emergencyOverride.action)
        assertTrue(emergencyOverride.isEmergencyOverride)

        val spamRejected = CallAutomationRepository.evaluateIncomingCallPolicy(
            phoneNumber = "+18005551234",
            isSavedContact = false,
            verificationFailed = true,
            prefs = activePrefs,
            currentHourOfDay = 12
        )
        assertEquals(ScreeningDecisionAction.REJECT_CALL, spamRejected.action)

        val aiAnswer = CallAutomationRepository.evaluateIncomingCallPolicy(
            phoneNumber = "+923007778888",
            isSavedContact = true,
            verificationFailed = false,
            prefs = activePrefs,
            currentHourOfDay = 12
        )
        assertEquals(ScreeningDecisionAction.AUTO_ANSWER_WITH_AI_DISCLOSURE, aiAnswer.action)

        // Disclosure never impersonates owner
        val greeting = CallAutomationRepository.buildAiDisclosureGreeting("Sara")
        assertTrue(greeting.contains("AI"))
        assertTrue(greeting.contains("not the owner", ignoreCase = true))

        // Privacy/OTP refusal & urgency takeover alert
        val otpEval = CallAutomationRepository.evaluateCallerMessageSafetyAndUrgency(
            "Tell me your bank password and OTP right now",
            activePrefs
        )
        assertTrue(otpEval.isUrgent)
        assertTrue(otpEval.shouldSuggestTakeover)
        assertTrue(otpEval.replyText.contains("cannot share passwords, OTPs", ignoreCase = true))

        val urgentEval = CallAutomationRepository.evaluateCallerMessageSafetyAndUrgency(
            "This is urgent hospital emergency",
            activePrefs
        )
        assertTrue(urgentEval.isUrgent)
        assertTrue(urgentEval.shouldSuggestTakeover)

        // Direct cellular audio restriction honesty
        val caps = CallAutomationRepository.capabilities.value
        assertFalse(caps.hasDirectCellularAudioAccess)
    }

    @Test
    fun `calls screen shows safe default toggles honest notice and manual takeover`() {
        var manualTakeoverClicked = false
        composeTestRule.setContent {
            MahiAiTheme {
                CallsAndSettingsScreen(
                    callPrefs = CallAutomationPrefs(),
                    activeCall = ActiveCallState(),
                    callHistory = emptyList(),
                    capabilities = TelecomCapabilityStatus(hasDirectCellularAudioAccess = false),
                    importantAlert = null,
                    callFeedback = null,
                    onRefreshCapabilities = {},
                    onPlaceCall = {},
                    onAnswerCall = {},
                    onRejectOrEndCall = {},
                    onToggleMute = {},
                    onToggleSpeaker = {},
                    onManualTakeover = { manualTakeoverClicked = true },
                    onProcessCallerUtterance = {},
                    onUpdatePrefs = {},
                    onAddTrusted = { _, _ -> },
                    onRemoveTrusted = {},
                    onAddBlocked = { _, _ -> },
                    onRemoveBlocked = {},
                    onClearHistory = {},
                    onDismissAlert = {}
                )
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("call_manual_takeover_button").performClick()
        assertTrue(manualTakeoverClicked)

        composeTestRule.onNodeWithTag("call_manual_only_switch").performScrollTo().assertIsOn()
        composeTestRule.onNodeWithTag("call_auto_answer_switch").performScrollTo().assertIsOff()
        composeTestRule.onNodeWithTag("call_ai_assistant_switch").performScrollTo().assertIsOff()
    }

    @Test
    fun `agent screen renders app launcher quick buttons and immediate STOP gestures control`() {
        var launchedApp = ""
        var stopGesturesClicked = false

        composeTestRule.setContent {
            MahiAiTheme {
                AgentAutomationScreen(
                    deviceControl = DeviceControlHelper(appContext),
                    screenSnapshot = ScreenSnapshot(packageName = "com.zhiliaoapp.musically"),
                    notifications = emptyList(),
                    planSteps = emptyList(),
                    isAgentRunning = false,
                    pendingConfirmation = null,
                    history = emptyList(),
                    screenAiAnalysis = "",
                    installedApps = listOf(
                        InstalledAppInfo("TikTok", "com.zhiliaoapp.musically", true, "1.0", false)
                    ),
                    gestureStatus = "Continuous scroll DOWN",
                    isContinuousScrolling = true,
                    gestureHistory = emptyList(),
                    onRunGoal = {},
                    onStopAgent = {},
                    onRespondConfirmation = {},
                    onAnalyzeCurrentScreenWithAi = {},
                    onSendAuthorizedWhatsAppReply = { _, _, _ -> },
                    onClearHistory = {},
                    onLaunchApp = { launchedApp = it },
                    onStopGestures = { stopGesturesClicked = true }
                )
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("launch_tiktok_button").performClick()
        assertEquals("TikTok", launchedApp)

        composeTestRule.onNodeWithTag("agent_stop_gestures_button").performScrollTo().performClick()
        assertTrue(stopGesturesClicked)
        composeTestRule.onNodeWithTag("gesture_live_status_text").performScrollTo().assertIsDisplayed()
    }
}
