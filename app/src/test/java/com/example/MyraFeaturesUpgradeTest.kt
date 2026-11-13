package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.util.Base64
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.example.ai.MahiMood
import com.example.api.AiProvider
import com.example.api.ApiErrorCategory
import com.example.api.ConnectionStatus
import com.example.api.ProviderApiException
import com.example.api.ProviderClient
import com.example.automation.AgentActionType
import com.example.automation.AgentPlanStep
import com.example.automation.AutomationHistoryEntry
import com.example.automation.AutonomousAgentEngine
import com.example.automation.DeviceControlHelper
import com.example.automation.PendingSensitiveConfirmation
import com.example.automation.ScreenSnapshot
import com.example.automation.SensitiveCategory
import com.example.automation.StepExecutionStatus
import com.example.preferences.AppLanguage
import com.example.preferences.AppThemeMode
import com.example.preferences.MahiPreferencesStore
import com.example.research.DeepResearchReport
import com.example.research.ResearchEngine
import com.example.research.ResearchPdfExporter
import com.example.research.WebSearchClient
import com.example.research.WebSearchSource
import com.example.ui.components.SettingsModal
import com.example.ui.screens.AgentAutomationScreen
import com.example.ui.screens.NeuralLensScreen
import com.example.ui.screens.SearchAndResearchScreen
import com.example.ui.theme.MahiAiTheme
import com.example.vision.LiveVisionSnapshot
import com.example.vision.NeuralLensAnalyzer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MyraFeaturesUpgradeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("mahi_app_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("mahi_automation_history", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `preferences store persists Urdu Roman Urdu English and Light Dark System themes`() {
        val store1 = MahiPreferencesStore(context)
        assertEquals(AppLanguage.ENGLISH, store1.prefsFlow.value.language)
        assertEquals(AppThemeMode.DARK, store1.prefsFlow.value.themeMode)

        store1.setLanguage(AppLanguage.URDU)
        store1.setThemeMode(AppThemeMode.LIGHT)

        val reloaded = MahiPreferencesStore(context)
        assertEquals(AppLanguage.URDU, reloaded.prefsFlow.value.language)
        assertEquals(AppThemeMode.LIGHT, reloaded.prefsFlow.value.themeMode)

        reloaded.setLanguage(AppLanguage.ROMAN_URDU)
        reloaded.setThemeMode(AppThemeMode.SYSTEM)

        val reloaded2 = MahiPreferencesStore(context)
        assertEquals(AppLanguage.ROMAN_URDU, reloaded2.prefsFlow.value.language)
        assertEquals(AppThemeMode.SYSTEM, reloaded2.prefsFlow.value.themeMode)
    }

    @Test
    fun `autonomous agent classifies sensitive actions pauses for approval and supports stop and history`() = runBlocking {
        val engine = AutonomousAgentEngine(context)

        // Classify sensitive categories accurately
        assertEquals(
            SensitiveCategory.CALL,
            AutonomousAgentEngine.classifySensitivity(AgentActionType.MAKE_CALL, "Call Ali", "03001234567")
        )
        assertEquals(
            SensitiveCategory.MESSAGE,
            AutonomousAgentEngine.classifySensitivity(AgentActionType.SEND_WHATSAPP_REPLY, "Reply on WhatsApp", "Hello")
        )
        assertEquals(
            SensitiveCategory.PURCHASE,
            AutonomousAgentEngine.classifySensitivity(AgentActionType.PURCHASE_ACTION, "Buy item", "headphones")
        )
        assertEquals(
            SensitiveCategory.DELETE_FILE,
            AutonomousAgentEngine.classifySensitivity(AgentActionType.DELETE_FILE, "Delete report", "report.pdf")
        )
        assertEquals(
            SensitiveCategory.PUBLISH_CONTENT,
            AutonomousAgentEngine.classifySensitivity(AgentActionType.PUBLISH_CONTENT, "Publish post", "status")
        )

        // Heuristic plan splits multi-step goal cleanly
        val planned = engine.buildHeuristicPlan("Search lofi on YouTube and set volume 40")
        assertTrue(planned.any { it.actionType == AgentActionType.YOUTUBE_SEARCH })
        assertTrue(planned.any { it.actionType == AgentActionType.SET_VOLUME && it.param == "40" })

        // Sensitive action MUST pause for user confirmation and respect DENY
        val sensitiveSteps = listOf(
            AgentPlanStep(
                stepNumber = 1,
                title = "Delete backup.zip",
                actionType = AgentActionType.DELETE_FILE,
                param = "backup.zip",
                sensitiveCategory = SensitiveCategory.DELETE_FILE
            )
        )

        val job = launch {
            engine.executePlan("Delete backup archive", sensitiveSteps, this)
        }

        // Wait briefly for confirmation prompt to surface
        var waitTicks = 0
        while (engine.pendingConfirmation.value == null && waitTicks < 30) {
            delay(20)
            waitTicks++
        }
        assertNotNull("Expected sensitive confirmation gate", engine.pendingConfirmation.value)
        assertEquals(SensitiveCategory.DELETE_FILE, engine.pendingConfirmation.value?.step?.sensitiveCategory)

        // Deny action -> security gate upheld
        engine.respondToSensitiveConfirmation(false)
        job.join()

        assertNull(engine.pendingConfirmation.value)
        assertEquals(StepExecutionStatus.DENIED_BY_USER, engine.planSteps.value.first().status)
        assertTrue(engine.historyStore.history.value.any { it.goal == "Delete backup archive" })

        // Verify stop() cancels pending gate immediately
        val stopSteps = listOf(
            AgentPlanStep(
                stepNumber = 1,
                title = "Publish status update",
                actionType = AgentActionType.PUBLISH_CONTENT,
                param = "Hello world",
                sensitiveCategory = SensitiveCategory.PUBLISH_CONTENT
            )
        )
        val stopJob = launch {
            engine.executePlan("Publish update", stopSteps, this)
        }
        waitTicks = 0
        while (engine.pendingConfirmation.value == null && waitTicks < 30) {
            delay(20)
            waitTicks++
        }
        engine.stop()
        stopJob.join()

        assertFalse(engine.isRunning.value)
        assertNull(engine.pendingConfirmation.value)
        assertEquals(StepExecutionStatus.STOPPED, engine.planSteps.value.first().status)
    }

    @Test
    fun `web search client deep research engine and PDF exporter produce cited report and valid PDF`() = runBlocking {
        val sampleDdgHtml = """
            <div class="result">
                <a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.org%2Fsolid-state">Solid State Batteries 2026</a>
                <a class="result__snippet">Energy density reached 450 Wh/kg in pilot lines.</a>
            </div>
            <div class="result">
                <a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Ftech.example.com%2Fev-range">EV Battery Benchmarks</a>
                <a class="result__snippet">Some lab tests report 410 Wh/kg while commercial cells target 2027.</a>
            </div>
        """.trimIndent()

        val parsedDdg = WebSearchClient.parseDuckDuckGoLiteHtml(sampleDdgHtml, maxResults = 5)
        assertEquals(2, parsedDdg.size)
        assertEquals("https://example.org/solid-state", parsedDdg[0].url)
        assertEquals("example.org", parsedDdg[0].domain)

        val mockHttp = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val url = chain.request().url.toString()
                val (code, mime, body) = when {
                    url.contains("duckduckgo.com") -> Triple(200, "text/html", sampleDdgHtml)
                    url.contains("en.wikipedia.org") -> Triple(200, "application/json", """{"query":{"search":[]}}""")
                    url.contains("example.org/solid-state") -> Triple(200, "text/html", "<p>Pilot lines verified 450 Wh/kg cells.</p>")
                    url.contains("tech.example.com") -> Triple(200, "text/html", "<p>Commercial readiness disputed between 2026 and 2027.</p>")
                    url.contains("generativelanguage.googleapis.com") && chain.request().method == "POST" -> {
                        val buf = Buffer()
                        chain.request().body?.writeTo(buf)
                        val reqTxt = buf.readUtf8()
                        val jsonOut = if (reqTxt.contains("3 concise web search sub-queries")) {
                            """{"candidates":[{"content":{"parts":[{"text":"[\"solid state battery 2026\", \"solid state wh kg\", \"solid state timeline\"]"}]}}]}"""
                        } else if (reqTxt.contains("Deep Research Agent")) {
                            """{"candidates":[{"content":{"parts":[{"text":"{\"executiveSummary\":\"Solid-state cells exceed 410-450 Wh/kg [1][2].\",\"keyFindings\":[\"450 Wh/kg pilot milestone [1]\",\"Timeline varies 2026 vs 2027 [2]\"],\"conflictingInformation\":\"Source [1] cites 2026 pilot rollout whereas [2] estimates 2027 commercial launch.\",\"detailedAnalysis\":\"Detailed synthesis comparing [1] and [2].\"}"}]}}]}"""
                        } else {
                            """{"candidates":[{"content":{"parts":[{"text":"{\"summary\":\"Summary [1][2]\",\"sourceComparison\":\"[1] and [2] differ slightly on density.\"}"}]}}]}"""
                        }
                        Triple(200, "application/json", jsonOut)
                    }
                    else -> Triple(404, "text/plain", "not found")
                }
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("OK")
                    .body(body.toResponseBody(mime.toMediaType()))
                    .build()
            }
            .build()

        val searchClient = WebSearchClient(mockHttp)
        val providerClient = ProviderClient(mockHttp)
        val researchEngine = ResearchEngine(searchClient, providerClient)

        val searchRes = researchEngine.runAiSearch(
            query = "solid state batteries",
            language = AppLanguage.ROMAN_URDU,
            provider = AiProvider.GEMINI,
            apiKey = "AIzaTestKey",
            model = "gemini-2.5-flash",
            baseUrl = AiProvider.GEMINI.defaultBaseUrl
        )
        assertTrue(searchRes.sources.isNotEmpty())
        assertTrue(searchRes.summary.contains("Summary"))

        val deepReport = researchEngine.runDeepResearch(
            topic = "Solid-state batteries 2026",
            language = AppLanguage.ENGLISH,
            provider = AiProvider.GEMINI,
            apiKey = "AIzaTestKey",
            model = "gemini-2.5-flash",
            baseUrl = AiProvider.GEMINI.defaultBaseUrl
        )
        assertEquals(3, deepReport.subQueries.size)
        assertTrue(deepReport.conflictingInformation.contains("2026"))
        assertTrue(deepReport.sources.isNotEmpty())

        // Export real multi-page Android PdfDocument and check %PDF header
        val pdfResult = ResearchPdfExporter.exportReportToPdf(context, deepReport)
        assertTrue(pdfResult.file.exists())
        assertTrue(pdfResult.byteSize > 400L)
        val header = pdfResult.file.inputStream().use { stream ->
            ByteArray(4).also { stream.read(it) }.decodeToString()
        }
        assertEquals("%PDF", header)
        assertTrue(pdfResult.contentUri.toString().contains("fileprovider"))
    }

    @Test
    fun `neural lens formats multilingual summaries and routes Gemini live model vision to flash`() = runBlocking {
        var capturedRequestUrl = ""
        var capturedRequestBody = ""

        val mockHttp = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                capturedRequestUrl = req.url.toString()
                val buf = Buffer()
                req.body?.writeTo(buf)
                capturedRequestBody = buf.readUtf8()
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(
                        """{"candidates":[{"content":{"parts":[{"text":"Yeh ek wireless mouse aur mechanical keyboard hai."}]}}]}"""
                            .toResponseBody("application/json".toMediaType())
                    )
                    .build()
            }
            .build()

        val providerClient = ProviderClient(mockHttp)
        val analyzer = NeuralLensAnalyzer(providerClient)

        val enSummary = analyzer.formatOfflineMlKitSummary(AppLanguage.ENGLISH)
        val romanSummary = analyzer.formatOfflineMlKitSummary(AppLanguage.ROMAN_URDU)
        val urSummary = analyzer.formatOfflineMlKitSummary(AppLanguage.URDU)
        assertTrue(enSummary.contains("On-device Scan"))
        assertTrue(romanSummary.contains("Offline Camera Scan"))
        assertTrue(urSummary.contains("آف لائن کیمرہ تجزیہ"))

        val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            Canvas(this).drawColor(Color.CYAN)
        }
        val answer = analyzer.askAiAboutVision(
            provider = AiProvider.GEMINI,
            apiKey = "AIzaVisionTest",
            model = "gemini-2.5-flash-native-audio-preview-09-2025",
            baseUrl = AiProvider.GEMINI.defaultBaseUrl,
            language = AppLanguage.ROMAN_URDU,
            question = "What is on my desk?",
            overrideBitmap = bmp
        )

        assertTrue(answer.contains("wireless mouse"))
        assertTrue("Expected live model to fall back to gemini-2.5-flash REST vision endpoint", capturedRequestUrl.contains("gemini-2.5-flash:generateContent"))
        assertTrue(capturedRequestBody.contains("inlineData"))

        // Missing key raises ProviderApiException(MISSING_KEY)
        var missingKeyThrown = false
        try {
            providerClient.analyzeImageWithProvider(
                provider = AiProvider.GEMINI,
                apiKey = "",
                model = "gemini-2.5-flash",
                baseUrl = AiProvider.GEMINI.defaultBaseUrl,
                base64Jpeg = Base64.encodeToString(byteArrayOf(1, 2, 3), Base64.NO_WRAP),
                systemPrompt = "sys",
                userPrompt = "usr"
            )
        } catch (e: ProviderApiException) {
            missingKeyThrown = e.category == ApiErrorCategory.MISSING_KEY
        }
        assertTrue(missingKeyThrown)
        analyzer.close()
    }

    @Test
    fun `settings modal renders and updates language and theme selections`() {
        var chosenLanguage = AppLanguage.ENGLISH
        var chosenTheme = AppThemeMode.DARK

        composeTestRule.setContent {
            MahiAiTheme(themeMode = AppThemeMode.LIGHT) {
                SettingsModal(
                    currentMood = MahiMood.SASSY,
                    currentLanguage = AppLanguage.ENGLISH,
                    currentThemeMode = AppThemeMode.LIGHT,
                    isContinuousMode = true,
                    showSubtitles = true,
                    voicePitch = 1.2f,
                    voiceSpeed = 1.08f,
                    activeProvider = AiProvider.GEMINI,
                    connectionStatus = ConnectionStatus.CONNECTED,
                    onLanguageSelected = { chosenLanguage = it },
                    onThemeSelected = { chosenTheme = it },
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
        composeTestRule.onNodeWithTag("settings_lang_ur").performScrollTo().performClick()
        assertEquals(AppLanguage.URDU, chosenLanguage)
        composeTestRule.onNodeWithTag("settings_theme_dark").performScrollTo().performClick()
        assertEquals(AppThemeMode.DARK, chosenTheme)
    }

    @Test
    fun `agent automation screen shows sensitive confirmation dialog and stop button`() {
        var stopClicked = false
        var confirmResult: Boolean? = null

        val pendingConfirm = PendingSensitiveConfirmation(
            step = AgentPlanStep(
                stepNumber = 1,
                title = "Send WhatsApp reply to Sara",
                actionType = AgentActionType.SEND_WHATSAPP_REPLY,
                sensitiveCategory = SensitiveCategory.MESSAGE
            ),
            goal = "Reply to Sara",
            reason = "Explicit user approval required before sending messages."
        )

        composeTestRule.setContent {
            MahiAiTheme {
                AgentAutomationScreen(
                    deviceControl = DeviceControlHelper(context),
                    screenSnapshot = ScreenSnapshot(packageName = "com.example"),
                    notifications = emptyList(),
                    planSteps = listOf(
                        AgentPlanStep(
                            stepNumber = 1,
                            title = "Send WhatsApp reply to Sara",
                            actionType = AgentActionType.SEND_WHATSAPP_REPLY,
                            sensitiveCategory = SensitiveCategory.MESSAGE,
                            status = StepExecutionStatus.AWAITING_CONFIRMATION
                        )
                    ),
                    isAgentRunning = true,
                    pendingConfirmation = pendingConfirm,
                    history = listOf(AutomationHistoryEntry(goal = "Set volume 50", statusSummary = "Verified", stepCount = 1)),
                    screenAiAnalysis = "",
                    onRunGoal = {},
                    onStopAgent = { stopClicked = true },
                    onRespondConfirmation = { confirmResult = it },
                    onAnalyzeCurrentScreenWithAi = {},
                    onSendAuthorizedWhatsAppReply = { _, _, _ -> },
                    onClearHistory = {}
                )
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Approve Sensitive Action?").assertIsDisplayed()
        composeTestRule.onNodeWithTag("deny_sensitive_action_button").performClick()
        assertEquals(false, confirmResult)

        composeTestRule.onNodeWithTag("agent_stop_button").performScrollTo().performClick()
        assertTrue(stopClicked)
    }

    @Test
    fun `research screen switches to deep research and triggers PDF export`() {
        var pdfExportTriggered = false
        val sampleReport = DeepResearchReport(
            topic = "Quantum Chips",
            language = AppLanguage.ROMAN_URDU,
            createdAtMs = 1700000000000L,
            subQueries = listOf("quantum chips 2026"),
            executiveSummary = "Quantum error correction improved markedly [1].",
            keyFindings = listOf("Logical qubits scaled cleanly [1]."),
            conflictingInformation = "Vendors differ on fault-tolerant timelines.",
            detailedAnalysis = "Detailed comparison.",
            sources = listOf(
                WebSearchSource(1, "Nature Quantum Review", "https://nature.com/q", "nature.com", "Milestone summary")
            )
        )

        composeTestRule.setContent {
            MahiAiTheme {
                SearchAndResearchScreen(
                    selectedLanguage = AppLanguage.ROMAN_URDU,
                    isLoading = false,
                    progressMessage = "",
                    searchResult = null,
                    deepReport = sampleReport,
                    exportedPdf = null,
                    onLanguageSelected = {},
                    onRunAiSearch = {},
                    onRunDeepResearch = {},
                    onExportPdf = { pdfExportTriggered = true },
                    onSpeakSummary = {}
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("mode_deep_research").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("export_research_pdf_button").performScrollTo().performClick()
        assertTrue(pdfExportTriggered)
    }

    @Test
    fun `neural lens screen submits vision question in selected language`() {
        var askedVisionQuery = ""
        val analyzer = NeuralLensAnalyzer()
        composeTestRule.setContent {
            MahiAiTheme {
                NeuralLensScreen(
                    analyzer = analyzer,
                    liveSnapshot = LiveVisionSnapshot(),
                    selectedLanguage = AppLanguage.URDU,
                    aiVisionAnswer = "",
                    isAnalyzingAi = false,
                    visionError = null,
                    onLanguageSelected = {},
                    onAskAiVision = { askedVisionQuery = it },
                    onAnalyzePhotoUri = {},
                    onSpeakAnswer = {},
                    onOpenApiSettings = {}
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("lens_question_input").performScrollTo().performTextInput("Yeh kya hai?")
        composeTestRule.onNodeWithTag("lens_send_question_button").performScrollTo().performClick()
        assertEquals("Yeh kya hai?", askedVisionQuery)
        analyzer.close()
    }
}
