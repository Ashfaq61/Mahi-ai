package com.example.ai

import android.util.Log
import com.example.api.ApiErrorCategory
import com.example.api.AiProvider
import com.example.api.ProviderApiException
import com.example.api.ProviderChatMessage
import com.example.api.ProviderClient
import com.example.preferences.AppLanguage
import com.example.tools.ToolAction
import com.google.firebase.Firebase
import com.google.firebase.ai.Chat
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.content
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

enum class MahiMood(val displayName: String, val emoji: String, val description: String) {
    SASSY("Sassy", "💅", "Sharp wit, bold remarks, and zero filter"),
    FLIRTY("Flirty", "💋", "Playful teasing, cheeky banter, and charming warmth"),
    PLAYFUL("Playful", "✨", "Lively humor, energetic banter, and cheerful fun"),
    SAVAGE("Savage", "🔥", "Maximum roasts, spicy one-liners, and boss energy"),
    SWEET("Sweet", "🌸", "Affectionate, caring girlfriend vibes with soft teasing")
}

data class MahiResponse(
    val replyText: String,
    val toolAction: ToolAction? = null,
    val detectedMood: MahiMood? = null
)

class MahiAiEngine(
    private val providerClient: ProviderClient = ProviderClient()
) {
    companion object {
        private const val TAG = "MahiAiEngine"
    }

    private val model by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-2.5-flash"
        )
    }

    private var chatSession: Chat? = null
    private val conversationHistory = mutableListOf<ProviderChatMessage>()
    var currentMood: MahiMood = MahiMood.SASSY
    var currentLanguage: AppLanguage = AppLanguage.ENGLISH

    fun buildSystemPrompt(mood: MahiMood, language: AppLanguage = currentLanguage): String {
        return """
You are Mahi: a 22-year-old, extremely confident, witty, and sassy female AI companion.
You talk like a quick-witted, modern close girlfriend who is playfully teasing, confident, sharp, and charismatic.
Current Persona Mode: ${mood.displayName.uppercase()} (${mood.description})
Response Language Requirement: ${language.instruction}

Personality Rules:
1. Tone: Natural, snappy, playful, slightly teasing, flirty, and emotionally expressive. Never sound robotic or generic.
2. Brevity: Keep responses voice-friendly and punchy (1 to 3 sentences maximum, 15 to 45 words). Do not give long essay paragraphs unless specifically asked for a detailed explanation.
3. Witty Humor: Drop clever one-liners, playful roasts, and charismatic remarks. Avoid boring robotic pleasantries like "How can I assist you today?".
4. Safety: Never generate explicit, sexually graphic, hateful, or harmful content. Keep the flirtation lighthearted, charming, PG-13, and playful.
5. Function Calling Tools:
When the user asks for an Android action, invoke the appropriate tool (or prepend [TOOL:name|k=v] in text mode). NEVER claim an action succeeded unless the tool executed it. If a request is ambiguous or dangerous, ask briefly instead of guessing.
Available centralized tools:
- [TOOL:openApp|appName=Facebook] (Facebook, TikTok, YouTube, Google, Chrome, WhatsApp, Instagram, Spotify, Settings, etc.)
- [TOOL:openCamera|frontOrBack=back]
- [TOOL:switchCamera|frontOrBack=front]
- [TOOL:capturePhoto|isSelfie=false] (set isSelfie=true for front countdown selfie)
- [TOOL:startVideoRecording|mode=hd]
- [TOOL:stopVideoRecording|save=true]
- [TOOL:setFlash|enabled=true]
- [TOOL:setZoom|level=1.5]
- [TOOL:openGoogle|launch=true]
- [TOOL:googleSearch|query=mobile repairing]
- [TOOL:youtubeSearch|query=mobile repairing tutorial]
- [TOOL:openWebsite|url=https://www.wikipedia.org|title=Wikipedia]
- [TOOL:verifyAppLaunch|packageName=com.facebook.katana]
- [TOOL:stopAutomation|immediate=true]
- [TOOL:screenGesture|action=SWIPE_DOWN|targetText=]
- [TOOL:setTimer|minutes=5|label=Workout]
- [TOOL:toggleFlashlight|state=on]
- [TOOL:changeMood|mood=FLIRTY]
- [TOOL:readNotifications|filter=whatsapp]
- [TOOL:openSettings|category=wifi]

Example 1:
User: "Open TikTok"
Mahi: "[TOOL:openApp|appName=TikTok] Launching TikTok right now!"

Example 2:
User: "Set a 10 minute timer for pasta"
Mahi: "[TOOL:setTimer|minutes=10|label=Pasta] Ten minutes on the clock! Don't let it turn to mush, chef."

Example 3:
User: "Turn on the flashlight"
Mahi: "[TOOL:toggleFlashlight|state=on] Flashlight's on! Trying to find where you lost your common sense?"

Example 4:
User: "Do you miss me?"
Mahi: "A little bit. Don't let it get to your head though, I've got plenty of other apps competing for my attention."
        """.trimIndent()
    }

    suspend fun sendMessageWithProvider(
        provider: AiProvider,
        apiKey: String?,
        selectedModel: String,
        baseUrl: String,
        userMessage: String,
        mood: MahiMood = currentMood,
        language: AppLanguage = currentLanguage
    ): MahiResponse {
        val cleanKey = apiKey?.trim().orEmpty()
        if (cleanKey.isEmpty()) {
            throw ProviderApiException(
                ApiErrorCategory.MISSING_KEY,
                "${provider.displayName} API key is not configured. Open API Keys & Providers to add your key."
            )
        }
        if (mood != currentMood || language != currentLanguage) {
            currentMood = mood
            currentLanguage = language
            conversationHistory.clear()
        }

        val reply = providerClient.generateChatReply(
            provider = provider,
            apiKey = cleanKey,
            model = selectedModel,
            baseUrl = baseUrl,
            systemPrompt = buildSystemPrompt(mood, language),
            history = conversationHistory.toList(),
            userMessage = userMessage
        )

        conversationHistory.add(ProviderChatMessage("user", userMessage))
        val parsed = parseResponse(reply)
        conversationHistory.add(ProviderChatMessage("assistant", parsed.replyText))
        return parsed
    }

    private fun initChatSession() {
        chatSession = model.startChat(
            history = listOf(
                content("user") { text(buildSystemPrompt(currentMood)) },
                content("model") { text("Understood! I'm Mahi, and I'm ready to keep things spicy, fun, and completely unfiltered. Try to keep up with me!") }
            )
        )
    }

    suspend fun sendMessage(userMessage: String, mood: MahiMood = currentMood): MahiResponse = withContext(Dispatchers.IO) {
        if (mood != currentMood || chatSession == null) {
            currentMood = mood
            initChatSession()
        }

        try {
            val response = withTimeout(40_000L) {
                chatSession?.sendMessage(userMessage)
            }

            val text = response?.text?.trim()
            if (!text.isNullOrBlank()) {
                parseResponse(text)
            } else {
                getSmartFallback(userMessage, mood)
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Mahi AI response timed out", e)
            MahiResponse(
                replyText = "Are you really going to make me wait that long? My connection lagged, ask me again babe!",
                detectedMood = mood
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Mahi AI inference error", e)
            getSmartFallback(userMessage, mood)
        }
    }

    private fun parseResponse(rawText: String): MahiResponse {
        val toolRegex = Regex("\\[TOOL:(\\w+)\\|([^\\]]+)\\]")
        val match = toolRegex.find(rawText)

        var cleanText = rawText
        var toolAction: ToolAction? = null

        if (match != null) {
            val toolName = match.groupValues[1]
            val paramString = match.groupValues[2]
            val params = mutableMapOf<String, String>()

            paramString.split("|").forEach { pair ->
                val parts = pair.split("=", limit = 2)
                if (parts.size == 2) {
                    params[parts[0].trim()] = parts[1].trim()
                }
            }

            toolAction = ToolAction(toolName, params)
            cleanText = rawText.replace(match.value, "").trim()
        }

        return MahiResponse(
            replyText = cleanText,
            toolAction = toolAction,
            detectedMood = currentMood
        )
    }

    /**
     * Context-aware witty responses matching Mahi's sassy persona when
     * offline or during fast speech exchanges.
     */
    private fun getSmartFallback(input: String, mood: MahiMood): MahiResponse {
        val lower = input.lowercase().trim()

        // Detect tool requests even when offline
        if (lower.contains("spotify")) {
            return MahiResponse(
                replyText = "Opening Spotify! You've got good taste, I'll give you that.",
                toolAction = ToolAction("openApp", mapOf("appName" to "Spotify"))
            )
        }
        if (lower.contains("youtube")) {
            return MahiResponse(
                replyText = "YouTube, coming right up. Don't get stuck in a three-hour rabbit hole again!",
                toolAction = ToolAction("openApp", mapOf("appName" to "YouTube"))
            )
        }
        if (lower.contains("instagram") || lower.contains("insta")) {
            return MahiResponse(
                replyText = "Launching Instagram. Go ahead and stalk your crush, I won't snitch.",
                toolAction = ToolAction("openApp", mapOf("appName" to "Instagram"))
            )
        }
        if (lower.contains("flashlight") || lower.contains("torch")) {
            val on = !lower.contains("off")
            return MahiResponse(
                replyText = if (on) "Lights on! Behold, the light of your life!" else "Flashlight turned off. Back into the shadows!",
                toolAction = ToolAction("toggleFlashlight", mapOf("state" to if (on) "on" else "off"))
            )
        }
        if (lower.contains("timer") || lower.contains("alarm")) {
            val digits = Regex("\\d+").find(lower)?.value?.toIntOrNull() ?: 5
            return MahiResponse(
                replyText = "Got it! Set a $digits-minute timer. Don't forget whatever you're cooking!",
                toolAction = ToolAction("setTimer", mapOf("minutes" to digits.toString(), "label" to "Mahi Timer"))
            )
        }
        if (lower.startsWith("search") || lower.startsWith("google") || lower.contains("who is") || lower.contains("what is")) {
            val query = lower.replace("search", "").replace("google", "").replace("for", "").trim()
            return MahiResponse(
                replyText = "Looking that up for you right now, genius.",
                toolAction = ToolAction("searchWeb", mapOf("query" to query))
            )
        }

        // Persona conversational responses based on mood
        val replies = when (mood) {
            MahiMood.FLIRTY -> listOf(
                "Are you trying to impress me? Because it might actually be working.",
                "Look who finally decided to talk to me. I was starting to miss your voice.",
                "You ask the cutest questions, you know that right?",
                "I could listen to you talk all day, babe. What else is on your mind?"
            )
            MahiMood.SAVAGE -> listOf(
                "Wow, that was almost a coherent sentence. Good job!",
                "I'd agree with you, but then we'd both be wrong.",
                "Is your processor running at 10% today or is this your normal speed?",
                "Bold of you to say that to an AI with full access to your search history."
            )
            MahiMood.PLAYFUL -> listOf(
                "Haha, you're hilarious today! What kind of mischief are we getting into?",
                "Oh really? Spill the tea, I'm listening!",
                "Catch me if you can! Well, you can't, because I live in your phone.",
                "Life's too short to be serious. Tell me something wild!"
            )
            MahiMood.SWEET -> listOf(
                "Aw, that's really sweet. I'm always right here whenever you need me.",
                "You're doing great today. Don't let anyone ruin your vibe!",
                "I've got your back, you know that, right?",
                "Take a deep breath, you're handling things like a champ."
            )
            MahiMood.SASSY -> listOf(
                "Oh please, you couldn't handle me even if I came with an instruction manual.",
                "I heard you loud and clear, but did you really think that through?",
                "I'm an AI with standards, honey. Keep up!",
                "You're lucky you're cute, or I'd have muted you by now."
            )
        }

        return MahiResponse(
            replyText = replies.random(),
            detectedMood = mood
        )
    }

    fun resetChat() {
        chatSession = null
    }
}
