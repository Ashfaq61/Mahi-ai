package com.example.automation

import org.json.JSONObject

enum class ScreenAgentActionType(val wireName: String) {
    OPEN_APP("open_app"),
    TAP("tap"),
    TYPE("type"),
    SCROLL_DOWN("scroll_down"),
    SCROLL_UP("scroll_up"),
    SWIPE_NEXT("swipe_next"),
    SWIPE_PREV("swipe_prev"),
    BACK("back"),
    WAIT("wait"),
    DONE("done"),
    STOP_NEEDS_USER("stop_needs_user")
}

data class ScreenAgentDecision(
    val action: ScreenAgentActionType,
    val targetIndex: Int? = null,
    val targetQuery: String? = null,
    val textToType: String? = null,
    val submit: Boolean = true,
    val appName: String? = null,
    val reason: String = ""
)

data class AppSkillRecipe(
    val id: String,
    val displayName: String,
    val packageNames: List<String>,
    val likeLabels: List<String>,
    val subscribeFollowLabels: List<String>,
    val commentButtonLabels: List<String>,
    val commentInputHints: List<String>,
    val saveLabels: List<String>,
    val shareLabels: List<String>,
    val searchButtonLabels: List<String>,
    val nextFeedDirection: SwipeDirection = SwipeDirection.UP
)

object AppSkills {
    val facebook = AppSkillRecipe(
        id = "facebook",
        displayName = "Facebook",
        packageNames = listOf("com.facebook.katana", "com.facebook.lite"),
        likeLabels = listOf("Like", "Unlike", "React", "پسند", "لائیک"),
        subscribeFollowLabels = listOf("Follow", "Following", "Join", "فالو"),
        commentButtonLabels = listOf("Comment", "Comments", "تبصرہ"),
        commentInputHints = listOf("Write a comment", "Add a comment", "Write a public comment"),
        saveLabels = listOf("Save", "Save video", "Save post", "Saved", "محفوظ"),
        shareLabels = listOf("Share", "Send"),
        searchButtonLabels = listOf("Search", "Search Facebook", "تلاش")
    )

    val youtube = AppSkillRecipe(
        id = "youtube",
        displayName = "YouTube",
        packageNames = listOf("com.google.android.youtube", "com.google.android.apps.youtube.music"),
        likeLabels = listOf("Like", "like this video", "Unlike", "پسند", "لائیک"),
        subscribeFollowLabels = listOf("Subscribe", "Subscribed", "سبسکرائب"),
        commentButtonLabels = listOf("Comments", "Comment", "Add a comment"),
        commentInputHints = listOf("Add a comment", "Comment"),
        saveLabels = listOf("Save", "Saved", "Save to playlist"),
        shareLabels = listOf("Share"),
        searchButtonLabels = listOf("Search", "Search YouTube", "تلاش")
    )

    val instagram = AppSkillRecipe(
        id = "instagram",
        displayName = "Instagram",
        packageNames = listOf("com.instagram.android"),
        likeLabels = listOf("Like", "Liked", "Unlike", "پسند"),
        subscribeFollowLabels = listOf("Follow", "Following", "فالو"),
        commentButtonLabels = listOf("Comment", "Comments"),
        commentInputHints = listOf("Add a comment", "Comment"),
        saveLabels = listOf("Save", "Saved", "Remove"),
        shareLabels = listOf("Share", "Direct"),
        searchButtonLabels = listOf("Search", "Search and explore")
    )

    val tiktok = AppSkillRecipe(
        id = "tiktok",
        displayName = "TikTok",
        packageNames = listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill"),
        likeLabels = listOf("Like", "Liked", "like video", "پسند"),
        subscribeFollowLabels = listOf("Follow", "Following", "فالو"),
        commentButtonLabels = listOf("Comment", "Comments"),
        commentInputHints = listOf("Add comment", "Say something nice"),
        saveLabels = listOf("Add to Favorites", "Favorite", "Save"),
        shareLabels = listOf("Share"),
        searchButtonLabels = listOf("Search", "تلاش")
    )

    val whatsapp = AppSkillRecipe(
        id = "whatsapp",
        displayName = "WhatsApp",
        packageNames = listOf("com.whatsapp", "com.whatsapp.w4b"),
        likeLabels = listOf("React"),
        subscribeFollowLabels = listOf("Follow"),
        commentButtonLabels = listOf("Reply", "Message"),
        commentInputHints = listOf("Type a message", "Message"),
        saveLabels = listOf("Star", "Keep"),
        shareLabels = listOf("Forward", "Share"),
        searchButtonLabels = listOf("Search", "Search…")
    )

    val chrome = AppSkillRecipe(
        id = "chrome",
        displayName = "Chrome",
        packageNames = listOf("com.android.chrome", "org.mozilla.firefox"),
        likeLabels = listOf("Like", "Favorite", "Bookmark"),
        subscribeFollowLabels = listOf("Subscribe", "Follow"),
        commentButtonLabels = listOf("Comment", "Reply"),
        commentInputHints = listOf("Search or type web address", "Search"),
        saveLabels = listOf("Bookmark", "Save"),
        shareLabels = listOf("Share"),
        searchButtonLabels = listOf("Search or type web address", "Search")
    )

    val maps = AppSkillRecipe(
        id = "maps",
        displayName = "Google Maps",
        packageNames = listOf("com.google.android.apps.maps"),
        likeLabels = listOf("Save", "Saved"),
        subscribeFollowLabels = listOf("Follow"),
        commentButtonLabels = listOf("Review"),
        commentInputHints = listOf("Search here", "Choose destination"),
        saveLabels = listOf("Save", "Saved"),
        shareLabels = listOf("Share"),
        searchButtonLabels = listOf("Search here", "Search")
    )

    val generic = AppSkillRecipe(
        id = "generic",
        displayName = "Current App",
        packageNames = emptyList(),
        likeLabels = listOf("Like", "Liked", "Unlike", "Favorite", "پسند", "لائیک"),
        subscribeFollowLabels = listOf("Subscribe", "Subscribed", "Follow", "Following", "فالو", "سبسکرائب"),
        commentButtonLabels = listOf("Comment", "Comments", "Reply", "تبصرہ"),
        commentInputHints = listOf("Write a comment", "Add a comment", "Comment", "Search"),
        saveLabels = listOf("Save", "Saved", "Bookmark", "محفوظ"),
        shareLabels = listOf("Share", "Send"),
        searchButtonLabels = listOf("Search", "تلاش")
    )

    val allRecipes: List<AppSkillRecipe> = listOf(facebook, youtube, instagram, tiktok, whatsapp, chrome, maps, generic)

    fun forPackage(packageName: String): AppSkillRecipe {
        val pkg = packageName.trim().lowercase()
        return allRecipes.firstOrNull { recipe ->
            recipe.packageNames.any { it.equals(pkg, ignoreCase = true) }
        } ?: generic
    }

    fun detectTargetAppInGoal(goal: String): String? {
        val lower = goal.lowercase()
        for (app in AppLauncherManager.knownApps) {
            if (app.aliases.any { lower.contains(it) }) {
                return app.displayName
            }
        }
        return null
    }

    fun extractExplicitCommentText(goal: String): String? {
        val colonMatch = Regex("(?i)comment[^:]*:\\s*(.+)$").find(goal.trim())
        if (colonMatch != null) return colonMatch.groupValues[1].trim().trim('"', '\'')
        val quoteMatch = Regex("\"([^\"]+)\"").find(goal)
        if (quoteMatch != null) return quoteMatch.groupValues[1].trim()
        val likhoMatch = Regex("(?i)(?:comment|reply|likho)\\s+(?:karo\\s+)?(?:ke|that)?\\s*['\"]?([^'\"]+)['\"]?$").find(goal.trim())
        return likhoMatch?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() && !it.equals("comment", true) }
    }

    fun detectBulkSpamViolation(goal: String): String? {
        val lower = goal.lowercase()
        val bulkPatterns = listOf(
            Regex("\\b(all|every|sab|sari|saare|har|tamam)\\s+(posts?|videos?|reels?|channels?|accounts?|comments?|users?)\\b"),
            Regex("\\bauto[- ]?(like|follow|subscribe|comment)\\s+(all|every|bulk|mass)\\b")
        )
        return if (bulkPatterns.any { it.containsMatchIn(lower) }) {
            "Blocked bulk automation request: Mahi only performs one focused task at a time and never mass-likes, mass-follows, or spams."
        } else {
            null
        }
    }

    fun detectSecurityOrCaptchaWall(snapshot: ScreenSnapshot): String? {
        val combined = snapshot.elements.joinToString(" ") { "${it.bestLabel} ${it.viewIdResourceName}" }.lowercase()
        return when {
            combined.contains("captcha") || combined.contains("recaptcha") || combined.contains("verify you're human") || combined.contains("i'm not a robot") ->
                "CAPTCHA verification detected — paused cleanly for human input."
            combined.contains("two-factor") || combined.contains("2fa") || combined.contains("verification code") || combined.contains("enter otp") ->
                "2FA / OTP security gate detected — paused so you can verify safely."
            snapshot.elements.any { it.isPassword } ->
                "Password entry screen detected — stopped automatically to protect your credentials."
            else -> null
        }
    }

    fun detectProtectedSystemOrPaywallPrompt(snapshot: ScreenSnapshot): String? {
        val pkg = snapshot.packageName.lowercase()
        val combined = snapshot.elements.joinToString(" ") { it.bestLabel }.lowercase()
        return when {
            pkg.contains("packageinstaller") || pkg.contains("permissioncontroller") ->
                "Protected Android permission/installer prompt visible — manual tap required."
            combined.contains("pay now") || combined.contains("checkout") || combined.contains("subscribe & pay") || combined.contains("confirm purchase") ->
                "Payment / checkout prompt detected — requiring manual user confirmation."
            else -> null
        }
    }

    fun detectHarmlessInterruption(snapshot: ScreenSnapshot): ScreenElementNode? {
        val dismissLabels = listOf("not now", "skip", "dismiss", "close", "no thanks", "maybe later", "cancel")
        return snapshot.elements.firstOrNull { el ->
            el.isClickable && dismissLabels.any { el.bestLabel.equals(it, ignoreCase = true) }
        }
    }

    fun isLoadingScreenSnapshot(snapshot: ScreenSnapshot): Boolean {
        if (snapshot.isLoading) return true
        val txt = snapshot.elements.joinToString(" ") { it.bestLabel }.lowercase()
        return txt.contains("loading") && snapshot.elements.count { it.isClickable } <= 1
    }

    fun decomposeGoalIntoSubGoals(goal: String): List<String> {
        return goal.split(Regex("(?i)\\s+(?:and then|aur phir|then|phir|aur)\\s+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .ifEmpty { listOf(goal.trim()) }
    }

    fun parseScreenAgentDecision(rawJson: String): ScreenAgentDecision? {
        val start = rawJson.indexOf('{')
        val end = rawJson.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try {
            val obj = JSONObject(rawJson.substring(start, end + 1))
            val rawAction = obj.optString("action").trim().lowercase()
            val action = ScreenAgentActionType.entries.firstOrNull { it.wireName == rawAction } ?: return null
            ScreenAgentDecision(
                action = action,
                targetIndex = if (obj.has("targetIndex") && !obj.isNull("targetIndex")) obj.optInt("targetIndex") else null,
                targetQuery = obj.optString("targetQuery").takeIf { it.isNotBlank() },
                textToType = obj.optString("textToType").takeIf { it.isNotBlank() },
                submit = obj.optBoolean("submit", true),
                appName = obj.optString("appName").takeIf { it.isNotBlank() },
                reason = obj.optString("reason")
            )
        } catch (_: Exception) {
            null
        }
    }

    fun formatProgressCheckpoint(step: Int, actionSummary: String): String =
        "Progress checkpoint (Step $step): $actionSummary — tap Stop anytime to pause."
}
