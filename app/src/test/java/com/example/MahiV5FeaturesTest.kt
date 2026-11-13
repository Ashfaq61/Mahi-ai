package com.example

import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.example.ai.GeminiLiveClient
import com.example.automation.AppLaunchStatus
import com.example.automation.AppLauncherManager
import com.example.automation.CommandExecutionGuard
import com.example.automation.RoutedVoiceCommand
import com.example.automation.VoiceCommandRouter
import com.example.camera.CameraLensFacing
import com.example.camera.CameraStudioState
import com.example.camera.MahiCameraController
import com.example.tools.MahiToolsManager
import com.example.tools.ToolAction
import com.example.ui.components.HomeQuickControlsBar
import com.example.ui.screens.CameraStudioScreen
import com.example.ui.theme.MahiAiTheme
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
@Config(sdk = [34])
class MahiV5FeaturesTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val appContext: Application
        get() = ApplicationProvider.getApplicationContext()

    private fun registerPackageAndBrowser(
        pkgName: String,
        label: String,
        enabled: Boolean = true,
        supportsWebViewIntent: Boolean = false
    ) {
        val shadowPm = shadowOf(appContext.packageManager)
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
            versionName = "2.0.0"
            applicationInfo = appInfo
            activities = arrayOf(actInfo)
        }
        shadowPm.installPackage(pkgInfo)

        val resolveInfo = ResolveInfo().apply {
            activityInfo = actInfo
            nonLocalizedLabel = label
        }
        @Suppress("DEPRECATION")
        shadowPm.addResolveInfoForIntent(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            resolveInfo
        )
        @Suppress("DEPRECATION")
        shadowPm.addResolveInfoForIntent(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkgName),
            resolveInfo
        )
        if (supportsWebViewIntent) {
            listOf(
                "https://www.google.com/search?q=mobile%20repairing%20%26%20tips",
                "https://www.google.com/search?q=mobile%20repairing",
                "https://openai.com",
                "https://www.google.com"
            ).forEach { url ->
                @Suppress("DEPRECATION")
                shadowPm.addResolveInfoForIntent(
                    Intent(Intent.ACTION_VIEW, Uri.parse(url)),
                    resolveInfo
                )
                @Suppress("DEPRECATION")
                shadowPm.addResolveInfoForIntent(
                    Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(pkgName),
                    resolveInfo
                )
            }
        }
    }

    @Test
    fun `voice command router handles multilingual apps google search youtube website and camera commands safely`() {
        // App Launches (Urdu / Roman Urdu / Hindi / English)
        val fb = VoiceCommandRouter.parseCommand("Mahi, Facebook open karo") as RoutedVoiceCommand.LaunchApp
        assertEquals("Facebook", fb.appName)

        val tt = VoiceCommandRouter.parseCommand("TikTok kholo") as RoutedVoiceCommand.LaunchApp
        assertEquals("TikTok", tt.appName)

        val ytHi = VoiceCommandRouter.parseCommand("यूट्यूब चलाओ") as RoutedVoiceCommand.LaunchApp
        assertEquals("YouTube", ytHi.appName)

        val wa = VoiceCommandRouter.parseCommand("WhatsApp kholo") as RoutedVoiceCommand.LaunchApp
        assertEquals("WhatsApp", wa.appName)

        // Google Open vs Google Search vs YouTube Search vs Website
        assertTrue(VoiceCommandRouter.parseCommand("Google kholo") is RoutedVoiceCommand.OpenGoogle)

        val gSearch = VoiceCommandRouter.parseCommand("Google par mobile repairing search karo") as RoutedVoiceCommand.GoogleSearch
        assertTrue(gSearch.query.contains("mobile repairing"))

        val netSearch = VoiceCommandRouter.parseCommand("Internet par latest AI tools dhoondo") as RoutedVoiceCommand.GoogleSearch
        assertTrue(netSearch.query.contains("latest AI tools"))

        val ytSearch = VoiceCommandRouter.parseCommand("YouTube par mobile repairing tutorial search karo") as RoutedVoiceCommand.SearchYouTube
        assertTrue(ytSearch.query.contains("mobile repairing tutorial"))

        val site = VoiceCommandRouter.parseCommand("Chrome mein openai.com kholo") as RoutedVoiceCommand.OpenWebsite
        assertEquals("https://openai.com", site.url)

        // Camera voice controls
        val openBackCam = VoiceCommandRouter.parseCommand("Camera kholo") as RoutedVoiceCommand.OpenCamera
        assertEquals(CameraLensFacing.BACK, openBackCam.lens)

        val openFrontCam = VoiceCommandRouter.parseCommand("Front camera kholo") as RoutedVoiceCommand.OpenCamera
        assertEquals(CameraLensFacing.FRONT, openFrontCam.lens)

        val switchBack = VoiceCommandRouter.parseCommand("Camera back karo") as RoutedVoiceCommand.SwitchCamera
        assertEquals(CameraLensFacing.BACK, switchBack.lens)

        val selfie = VoiceCommandRouter.parseCommand("Selfie lo") as RoutedVoiceCommand.CapturePhoto
        assertTrue(selfie.isSelfie)

        val photo = VoiceCommandRouter.parseCommand("Picture khaincho") as RoutedVoiceCommand.CapturePhoto
        assertFalse(photo.isSelfie)

        assertTrue(VoiceCommandRouter.parseCommand("Video record karo") is RoutedVoiceCommand.StartVideoRecording)
        assertTrue(VoiceCommandRouter.parseCommand("Recording stop karo") is RoutedVoiceCommand.StopVideoRecording)

        val flashOn = VoiceCommandRouter.parseCommand("Flash on karo") as RoutedVoiceCommand.SetCameraFlash
        assertTrue(flashOn.enabled)
        val flashOff = VoiceCommandRouter.parseCommand("Flash off karo") as RoutedVoiceCommand.SetCameraFlash
        assertFalse(flashOff.enabled)

        val zoomIn = VoiceCommandRouter.parseCommand("Zoom in karo") as RoutedVoiceCommand.SetCameraZoom
        assertTrue(zoomIn.deltaStep > 0f)
        val zoomOut = VoiceCommandRouter.parseCommand("Zoom out karo") as RoutedVoiceCommand.SetCameraZoom
        assertTrue(zoomOut.deltaStep < 0f)

        // Safety: incomplete partial and conversational unknown sentences must NOT trigger guessed actions
        assertTrue(VoiceCommandRouter.isLikelyIncompletePartial("Mahi"))
        assertTrue(VoiceCommandRouter.isLikelyIncompletePartial("open"))
        assertNull(VoiceCommandRouter.parseCommand("Aaj mausam kaisa hai?"))
    }

    @Test
    fun `command execution guard prevents duplicate rapid launches while allowing immediate STOP`() {
        var fakeTime = 1_000L
        val guard = CommandExecutionGuard(dedupWindowMs = 2_500L, clockMs = { fakeTime })

        assertTrue(guard.shouldExecute("LAUNCH:facebook:"))
        fakeTime += 500L
        assertFalse(guard.shouldExecute("LAUNCH:facebook:"))
        // Different command allowed immediately
        assertTrue(guard.shouldExecute("GOOGLE_SEARCH:ai tools"))
        // After cooldown window passes, repeat command is permitted
        fakeTime += 3_000L
        assertTrue(guard.shouldExecute("LAUNCH:facebook:"))
        // STOP is never deduplicated
        assertTrue(guard.shouldExecute("STOP"))
        assertTrue(guard.shouldExecute("STOP"))
    }

    @Test
    fun `app launcher supports Google search encoding website security verifyAppLaunch and fallback honesty`() {
        registerPackageAndBrowser("com.android.chrome", "Chrome", enabled = true, supportsWebViewIntent = true)
        registerPackageAndBrowser("com.facebook.katana", "Facebook", enabled = true)

        val launcher = AppLauncherManager(appContext)

        // verifyAppLaunch verifies native installed package truthfully
        val verifyFb = launcher.verifyAppLaunch("com.facebook.katana")
        assertTrue(verifyFb.success)
        assertEquals(AppLaunchStatus.LAUNCHED_NATIVE_APP, verifyFb.status)

        val verifyMissing = launcher.verifyAppLaunch("com.missing.app")
        assertFalse(verifyMissing.success)

        // Google Search builds cleanly encoded URL
        val gSearch = launcher.openGoogleSearch("mobile repairing & tips")
        assertNotNull(gSearch.fallbackUrl)
        assertTrue(gSearch.fallbackUrl!!.startsWith("https://www.google.com/search?q="))
        assertTrue(gSearch.fallbackUrl!!.contains("mobile%20repairing"))

        // Security: unsafe javascript URI is blocked
        val unsafeSite = launcher.openWebsite("javascript:alert(1)")
        assertFalse(unsafeSite.success)
        assertEquals(AppLaunchStatus.NOT_FOUND_OR_FAILED, unsafeSite.status)

        // Safe HTTPS website normalization succeeds when browser exists
        val safeSite = launcher.openWebsite("openai.com")
        assertTrue(safeSite.success)
        assertEquals("https://openai.com", safeSite.fallbackUrl)
    }

    @Test
    fun `gemini live setup and tools manager cover complete V5 centralized command registry`() {
        val setupJson = GeminiLiveClient.buildSetupMessage(
            model = "gemini-2.5-flash-native-audio-preview-12-2025",
            systemPrompt = "You are Mahi"
        )
        val requiredTools = listOf(
            "openApp", "openCamera", "switchCamera", "capturePhoto",
            "startVideoRecording", "stopVideoRecording", "setFlash", "setZoom",
            "openGoogle", "googleSearch", "youtubeSearch", "openWebsite",
            "verifyAppLaunch", "stopAutomation"
        )
        requiredTools.forEach { toolName ->
            assertTrue("Missing tool declaration '$toolName'", setupJson.contains("\"$toolName\""))
        }
        assertTrue(setupJson.contains("\"AUDIO\""))

        registerPackageAndBrowser("com.android.chrome", "Chrome", enabled = true, supportsWebViewIntent = true)
        registerPackageAndBrowser("com.zhiliaoapp.musically", "TikTok", enabled = true)

        val camController = MahiCameraController(appContext)
        val tools = MahiToolsManager(
            context = appContext,
            cameraController = camController
        )

        assertTrue(tools.execute(ToolAction("openApp", mapOf("appName" to "TikTok"))).isSuccess)
        assertTrue(tools.execute(ToolAction("openCamera", mapOf("frontOrBack" to "front"))).isSuccess)
        assertEquals(CameraLensFacing.FRONT, camController.state.value.lensFacing)

        assertTrue(tools.execute(ToolAction("switchCamera", mapOf("frontOrBack" to "back"))).isSuccess)
        assertEquals(CameraLensFacing.BACK, camController.state.value.lensFacing)

        assertTrue(tools.execute(ToolAction("setZoom", mapOf("level" to "2.5"))).isSuccess)
        assertEquals(2.5f, camController.state.value.zoomRatio, 0.01f)

        assertTrue(tools.execute(ToolAction("googleSearch", mapOf("query" to "mobile repairing"))).isSuccess)
        assertTrue(tools.execute(ToolAction("verifyAppLaunch", mapOf("packageName" to "com.zhiliaoapp.musically"))).isSuccess)
        assertTrue(tools.execute(ToolAction("stopAutomation", emptyMap())).isSuccess)

        // Unknown tool rejected safely
        assertFalse(tools.execute(ToolAction("dangerousUnknownTool", emptyMap())).isSuccess)
    }

    @Test
    fun `camera controller resolves system camera fallback intent safely`() {
        val shadowPm = shadowOf(appContext.packageManager)
        val actInfo = ActivityInfo().apply {
            packageName = "com.android.camera2"
            name = "com.android.camera2.CameraActivity"
            exported = true
            enabled = true
            applicationInfo = ApplicationInfo().apply {
                packageName = "com.android.camera2"
                enabled = true
            }
        }
        val resolveInfo = ResolveInfo().apply { activityInfo = actInfo }
        shadowPm.addResolveInfoForIntent(Intent(MediaStore.ACTION_IMAGE_CAPTURE), resolveInfo)
        shadowPm.addResolveInfoForIntent(Intent(MediaStore.ACTION_VIDEO_CAPTURE), resolveInfo)

        val controller = MahiCameraController(appContext)
        assertNotNull(controller.buildCameraFallbackIntent("photo"))
        assertNotNull(controller.buildCameraFallbackIntent("video"))
    }

    @Test
    fun `home quick controls wire real actions for all 8 dashboard shortcuts`() {
        val clickedActions = mutableListOf<String>()

        composeTestRule.setContent {
            MahiAiTheme {
                HomeQuickControlsBar(
                    onOpenFacebook = { clickedActions.add("facebook") },
                    onOpenTikTok = { clickedActions.add("tiktok") },
                    onOpenYouTube = { clickedActions.add("youtube") },
                    onOpenGoogle = { clickedActions.add("google") },
                    onOpenCamera = { clickedActions.add("camera") },
                    onTakeSelfie = { clickedActions.add("selfie") },
                    onStartVideo = { clickedActions.add("video") },
                    onOpenChrome = { clickedActions.add("chrome") }
                )
            }
        }
        composeTestRule.waitForIdle()
        listOf(
            "quick_btn_facebook" to "facebook",
            "quick_btn_tiktok" to "tiktok",
            "quick_btn_youtube" to "youtube",
            "quick_btn_google" to "google",
            "quick_btn_camera" to "camera",
            "quick_btn_selfie" to "selfie",
            "quick_btn_video" to "video",
            "quick_btn_chrome" to "chrome"
        ).forEach { (tag, expected) ->
            composeTestRule.onNodeWithTag(tag).performScrollTo().performClick()
            assertTrue(clickedActions.contains(expected))
        }
    }

    @Test
    fun `camera studio UI renders runtime permission prompt and system fallback controls`() {
        val controller = MahiCameraController(appContext)
        var fallbackTriggered = ""

        composeTestRule.setContent {
            MahiAiTheme {
                CameraStudioScreen(
                    cameraController = controller,
                    cameraState = CameraStudioState(lensFacing = CameraLensFacing.FRONT, selfieCountdownSeconds = 2),
                    onSwitchLens = {},
                    onCapturePhoto = {},
                    onCaptureSelfie = {},
                    onToggleVideoRecording = {},
                    onToggleFlash = {},
                    onZoomIn = {},
                    onZoomOut = {},
                    onSystemCameraFallback = { fallbackTriggered = it }
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("camera_grant_permission_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("camera_fallback_photo_button").performScrollTo().performClick()
        assertEquals("image_capture", fallbackTriggered)
    }
}
