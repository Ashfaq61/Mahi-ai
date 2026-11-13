package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.ai.GeminiLiveClient
import com.example.api.ApiErrorCategory
import com.example.api.AiProvider
import com.example.api.ConnectionStatus
import com.example.api.InMemoryAesGcmCipher
import com.example.api.ProviderClient
import com.example.api.SecureApiKeyStore
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ApiKeyManagerTest {

    private lateinit var context: Context
    private lateinit var testCipher: InMemoryAesGcmCipher

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("mahi_secure_api_store", Context.MODE_PRIVATE).edit().clear().commit()
        testCipher = InMemoryAesGcmCipher(ByteArray(32) { (it + 1).toByte() })
    }

    @Test
    fun `add edit delete multiple keys and persist across simulated app restart without plaintext storage`() {
        val rawGemini1 = "AIzaSyTestSecretKeyOne123456"
        val rawGemini2 = "AIzaSyTestBackupKeyTwo654321"

        val store1 = SecureApiKeyStore(context, testCipher)
        val entry1 = store1.addKey(AiProvider.GEMINI, rawGemini1, "Personal", selectAsActiveKey = true)
        val entry2 = store1.addKey(AiProvider.GEMINI, rawGemini2, "Backup", selectAsActiveKey = false)
        store1.setActiveProvider(AiProvider.GROQ)
        store1.addKey(AiProvider.GROQ, "gsk_testGroqSecret998877", "Fast LPU", selectAsActiveKey = true)
        store1.setSelectedModel(AiProvider.GROQ, "llama-3.1-8b-instant")
        store1.setCustomBaseUrl(AiProvider.CUSTOM_OPENAI, "https://my-proxy.example.com/v1")

        // Verify SharedPreferences never stores any raw plaintext API key
        val rawPrefs = context.getSharedPreferences("mahi_secure_api_store", Context.MODE_PRIVATE)
        val serializedEntries = rawPrefs.getString("encrypted_key_entries_json", "").orEmpty()
        assertFalse("Plaintext Gemini key must not appear in SharedPreferences", serializedEntries.contains(rawGemini1))
        assertFalse("Plaintext Gemini key 2 must not appear in SharedPreferences", serializedEntries.contains(rawGemini2))
        assertFalse("Plaintext Groq key must not appear in SharedPreferences", serializedEntries.contains("gsk_testGroqSecret998877"))

        // Simulate app restart with a fresh SecureApiKeyStore instance using the same cipher
        val storeAfterRestart = SecureApiKeyStore(context, testCipher)
        val state = storeAfterRestart.state.value

        assertEquals(AiProvider.GROQ, state.activeProvider)
        assertEquals("llama-3.1-8b-instant", state.getConfig(AiProvider.GROQ).selectedModel)
        assertEquals("https://my-proxy.example.com/v1", state.getConfig(AiProvider.CUSTOM_OPENAI).customBaseUrl)
        assertEquals(2, state.getKeys(AiProvider.GEMINI).size)
        assertEquals(rawGemini1, storeAfterRestart.getDecryptedKey(entry1.id))
        assertEquals(rawGemini2, storeAfterRestart.getDecryptedKey(entry2.id))
        assertEquals("gsk_testGroqSecret998877", storeAfterRestart.getActiveDecryptedKey(AiProvider.GROQ))

        // Edit key 1 label and secret
        val editedKey = "AIzaSyEditedReplacementKey999"
        storeAfterRestart.updateKey(AiProvider.GEMINI, entry1.id, editedKey, "Primary Edited")
        val reloadedAfterEdit = SecureApiKeyStore(context, testCipher)
        assertEquals("Primary Edited", reloadedAfterEdit.state.value.getKeys(AiProvider.GEMINI).first { it.id == entry1.id }.label)
        assertEquals(editedKey, reloadedAfterEdit.getDecryptedKey(entry1.id))

        // Delete key 1 and verify selection moves to remaining key 2
        reloadedAfterEdit.deleteKey(AiProvider.GEMINI, entry1.id)
        val reloadedAfterDelete = SecureApiKeyStore(context, testCipher)
        assertEquals(1, reloadedAfterDelete.state.value.getKeys(AiProvider.GEMINI).size)
        assertEquals(entry2.id, reloadedAfterDelete.state.value.getConfig(AiProvider.GEMINI).selectedKeyId)
        assertNull(reloadedAfterDelete.getDecryptedKey(entry1.id))
    }

    @Test
    fun `testConnection verifies all 5 providers with real HTTP parsing`() = runTest {
        val mockClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val url = req.url.toString()
                val (code, json, headers) = when {
                    url.contains("generativelanguage.googleapis.com") -> {
                        assertEquals("gemini-secret-key", req.header("x-goog-api-key"))
                        Triple(
                            200,
                            """{"models":[{"name":"models/gemini-2.5-flash-native-audio-preview-09-2025","supportedGenerationMethods":["bidiGenerateContent"]}]}""",
                            emptyMap()
                        )
                    }
                    url.contains("openrouter.ai/api/v1/auth/key") -> {
                        assertEquals("Bearer or-secret-key", req.header("Authorization"))
                        Triple(200, """{"data":{"usage":1.25,"limit":10.0,"is_free_tier":false}}""", emptyMap())
                    }
                    url.contains("openrouter.ai/api/v1/models") -> {
                        Triple(200, """{"data":[{"id":"google/gemini-2.5-flash"},{"id":"openai/gpt-4o-mini"}]}""", emptyMap())
                    }
                    url.contains("api.groq.com/openai/v1/models") -> {
                        assertEquals("Bearer gsk-secret", req.header("Authorization"))
                        Triple(
                            200,
                            """{"data":[{"id":"llama-3.3-70b-versatile"}]}""",
                            mapOf("x-ratelimit-remaining-requests" to "29")
                        )
                    }
                    url.contains("huggingface.co/api/whoami-v2") -> {
                        assertEquals("Bearer hf-secret", req.header("Authorization"))
                        Triple(200, """{"name":"mahi_user","type":"user"}""", emptyMap())
                    }
                    url.contains("router.huggingface.co/v1/models") -> {
                        Triple(200, """{"data":[{"id":"meta-llama/Llama-3.3-70B-Instruct"}]}""", emptyMap())
                    }
                    url.contains("custom.endpoint.local/v1/models") -> {
                        assertEquals("Bearer custom-secret", req.header("Authorization"))
                        Triple(200, """{"data":[{"id":"local-llama"}]}""", emptyMap())
                    }
                    else -> Triple(404, """{"error":"unexpected url $url"}""", emptyMap())
                }

                val builder = Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("OK")
                    .body(json.toResponseBody("application/json".toMediaType()))
                headers.forEach { (k, v) -> builder.header(k, v) }
                builder.build()
            }
            .build()

        val client = ProviderClient(mockClient)

        val geminiRes = client.testConnection(
            AiProvider.GEMINI,
            "gemini-secret-key",
            "gemini-2.5-flash-native-audio-preview-09-2025"
        )
        assertTrue(geminiRes.success)
        assertEquals(ConnectionStatus.CONNECTED, geminiRes.status)
        assertTrue(geminiRes.usageInfo.orEmpty().contains("Live Audio supported"))

        val openRouterRes = client.testConnection(AiProvider.OPENROUTER, "or-secret-key", "google/gemini-2.5-flash")
        assertTrue(openRouterRes.success)
        assertTrue(openRouterRes.usageInfo.orEmpty().contains("Used: $1.2500"))

        val groqRes = client.testConnection(AiProvider.GROQ, "gsk-secret", "llama-3.3-70b-versatile")
        assertTrue(groqRes.success)
        assertTrue(groqRes.usageInfo.orEmpty().contains("Req left: 29"))

        val hfRes = client.testConnection(AiProvider.HUGGING_FACE, "hf-secret", "meta-llama/Llama-3.3-70B-Instruct")
        assertTrue(hfRes.success)
        assertTrue(hfRes.usageInfo.orEmpty().contains("mahi_user"))

        val customRes = client.testConnection(
            AiProvider.CUSTOM_OPENAI,
            "custom-secret",
            "local-llama",
            "https://custom.endpoint.local/v1"
        )
        assertTrue(customRes.success)
        assertTrue(customRes.discoveredModels.contains("local-llama"))
    }

    @Test
    fun `testConnection classifies invalid key, quota limit, unsupported model, and network failure`() = runTest {
        var nextStatus = 401
        var nextBody = """{"error":{"message":"API key not valid."}}"""
        var throwIo = false

        val mockClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                if (throwIo) throw IOException("Network unreachable")
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(nextStatus)
                    .message("ERR")
                    .body(nextBody.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val client = ProviderClient(mockClient)

        val invalidRes = client.testConnection(AiProvider.GEMINI, "bad-key", "gemini-2.5-flash")
        assertFalse(invalidRes.success)
        assertEquals(ConnectionStatus.ERROR, invalidRes.status)
        assertEquals(ApiErrorCategory.INVALID_KEY, invalidRes.errorCategory)

        nextStatus = 429
        nextBody = """{"error":{"message":"Quota exceeded for requests per minute."}}"""
        val quotaRes = client.testConnection(AiProvider.GROQ, "gsk_123", "llama-3.3-70b-versatile")
        assertEquals(ApiErrorCategory.QUOTA_EXCEEDED, quotaRes.errorCategory)

        nextStatus = 404
        nextBody = """{"error":{"message":"Model not found or unsupported."}}"""
        val modelRes = client.testConnection(AiProvider.OPENROUTER, "sk-or-123", "nonexistent/model")
        assertEquals(ApiErrorCategory.UNSUPPORTED_MODEL, modelRes.errorCategory)

        throwIo = true
        val netRes = client.testConnection(AiProvider.HUGGING_FACE, "hf_123", "Qwen/Qwen2.5-72B-Instruct")
        assertEquals(ApiErrorCategory.CONNECTION_FAILED, netRes.errorCategory)
    }

    @Test
    fun `GeminiLiveClient builds authenticated AUDIO setup and rejects missing key or non-live models`() {
        val req = GeminiLiveClient.buildWebSocketRequest("AIzaSyConfiguredUserKey")
        assertEquals("AIzaSyConfiguredUserKey", req.header("x-goog-api-key"))

        val setupJson = GeminiLiveClient.buildSetupMessage(
            model = "gemini-2.5-flash-native-audio-preview-09-2025",
            systemPrompt = "You are Mahi."
        )
        val parsedSetup = org.json.JSONObject(setupJson).getJSONObject("setup")
        assertEquals("models/gemini-2.5-flash-native-audio-preview-09-2025", parsedSetup.getString("model"))
        val modalities = parsedSetup.getJSONObject("generationConfig").getJSONArray("responseModalities")
        assertEquals(1, modalities.length())
        assertEquals("AUDIO", modalities.getString(0))

        val client = GeminiLiveClient()
        var capturedCategory: ApiErrorCategory? = null
        var capturedMsg: String? = null

        val callbacks = object : com.example.ai.GeminiLiveCallbacks {
            override fun onSetupComplete() {}
            override fun onUserTranscript(text: String) {}
            override fun onAssistantTranscript(text: String) {}
            override fun onAssistantSpeakingChanged(isSpeaking: Boolean) {}
            override fun onToolCall(id: String, action: com.example.tools.ToolAction) {}
            override fun onInterrupted() {}
            override fun onError(error: com.example.ai.GeminiLiveError) {
                capturedCategory = error.category
                capturedMsg = error.message
            }
            override fun onDisconnected() {}
        }

        // Missing key error
        client.connect("", "gemini-2.5-flash-native-audio-preview-09-2025", "prompt", callbacks)
        assertEquals(ApiErrorCategory.MISSING_KEY, capturedCategory)
        assertNotNull(capturedMsg)

        // Unsupported model for Live audio-to-audio (must error, never silently switch to text)
        capturedCategory = null
        client.connect("AIzaSyValid", "gemini-2.5-pro", "prompt", callbacks)
        assertEquals(ApiErrorCategory.UNSUPPORTED_MODEL, capturedCategory)
    }
}
