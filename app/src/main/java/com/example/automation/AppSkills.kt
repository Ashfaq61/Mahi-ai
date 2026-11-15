package com.example.automation

import com.example.preferences.AppLanguage
import org.json.JSONObject

enum class ScreenAgentActionType(val wireName: String) {
    TAP("tap"),
    TYPE("type"),
    SCROLL("scroll"),
    SWIPE("swipe"),
    BACK("back"),
    OPEN_APP("openApp"),
    WAIT("wait"),
    DONE("done"),
    ASK_USER("ask_user");

    companion object {
        fun fromWire(value: String?): ScreenAgentActionType? {
            val norm = value?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.wireName.lowercase() == norm }
        }
    }
}

data class ScreenAgentDecision(
    val action: ScreenAgentActionType,
    val targetIndex: Int? = null,
    val targetQuery: String? = null,
    val text: String? = null,
    val direction: String? = null,
    val submit: Boolean = false,
    val clearFirst: Boolean = true,
    val x: Float? = null,
    val y: Float? = null,
    val reason: String = ""
)

data class AppSkillRecipe(
    val id: String,
    val appName: String,
    val packageNames: List<String>,
    val searchCues: List<String>,
    val actionVariants: Map<String, List<String>>,
    val relativeHints: List<String>,
    val verificationRules: Map<String, String>
)

data class AutopilotSubGoal(
    val title: String,
    val goalPhrase: String,
    val verificationRule: String
)

object AppSkills {

    val facebook = AppSkillRecipe(
        id = "facebook",
        appName = "Facebook",
        packageNames = listOf("com.facebook.katana", "com.facebook.lite"),
        searchCues = listOf("search facebook", "search", "find", "talash", "dhundo", "تلاش"),
        actionVariants = mapOf(
            "search" to listOf("search", "search facebook", "find friends", "search icon", "talash", "dhundo", "تلاش"),
            "video_tab" to listOf("video", "watch", "reels", "reels and short videos", "videos"),
            "play" to listOf("play", "watch", "video", "reel", "unmute", "chalao", "chala do", "دیکھیں"),
            "like" to listOf("like", "react", "thumbs up", "like button", "pasand", "لائک"),
            "unlike" to listOf("unlike", "liked", "remove reaction", "like hatao"),
            "comment" to listOf("comment", "write a comment", "add a comment", "comment box", "tabsra", "کمنٹ"),
            "post_comment" to listOf("send", "post", "submit", "comment", "bhejo", "بھیجیں"),
            "follow" to listOf("follow", "add friend", "join", "فالو"),
            "following" to listOf("following", "requested", "friends"),
            "save" to listOf("save", "save video", "save post", "bookmark", "mehfooz", "سیو"),
            "share" to listOf("share", "شیئر")
        ),
        relativeHints = listOf(
            "Top row houses Home/Friends/Watch(Video)/Profile tabs and a Search magnifying-glass icon near the top right.",
            "Post cards show Like, Comment, Share along a horizontal bar at the bottom of the media/post; Reels place Like above Comment above Share on the right edge.",
            "Inside a Page or Profile header, Follow appears next to Message or Share."
        ),
        verificationRules = mapOf(
            "like" to "Like node changes label to Unlike/Liked or isSelected=true.",
            "follow" to "Follow node changes label to Following or isSelected=true.",
            "comment" to "Typed text clears or appears in the visible comments list.",
            "search" to "Feed updates to Search results containing the query or Videos/People tabs."
        )
    )

    val youtube = AppSkillRecipe(
        id = "youtube",
        appName = "YouTube",
        packageNames = listOf("com.google.android.youtube", "com.google.android.apps.youtube.music"),
        searchCues = listOf("search youtube", "search", "talash", "dhundo", "تلاش"),
        actionVariants = mapOf(
            "search" to listOf("search", "search youtube", "talash", "dhundo", "تلاش"),
            "play" to listOf("play", "minutes", "seconds", "views", "watch", "chalao", "دیکھیں"),
            "like" to listOf("like this video", "i like this", "like", "pasand", "لائک"),
            "unlike" to listOf("unlike", "liked", "remove like", "like hatao"),
            "subscribe" to listOf("subscribe", "subscribe to", "سبسکرائب"),
            "subscribed" to listOf("subscribed", "unsubscribe"),
            "comment" to listOf("comments", "add a comment", "add a comment...", "کمنٹ"),
            "post_comment" to listOf("comment", "send", "post", "bhejo", "بھیجیں"),
            "save" to listOf("save", "save to playlist", "saved", "سیو"),
            "next" to listOf("next", "next video", "agla", "اگلا"),
            "share" to listOf("share", "شیئر")
        ),
        relativeHints = listOf(
            "Search magnifying glass sits in the top toolbar.",
            "Below the video player sit Subscribe button and horizontal action chips: Like (with count) next to Dislike, Share, Save.",
            "Comments preview sits directly beneath the action chip row and opens 'Add a comment...'."
        ),
        verificationRules = mapOf(
            "like" to "Like chip toggles isSelected=true or contentDescription indicates liked.",
            "subscribe" to "Subscribe button changes text/description to Subscribed.",
            "comment" to "Comment appears in the comments thread and input field resets.",
            "play" to "Video player controls or channel title/subscribe bar appear."
        )
    )

    val instagram = AppSkillRecipe(
        id = "instagram",
        appName = "Instagram",
        packageNames = listOf("com.instagram.android", "com.instagram.lite"),
        searchCues = listOf("search and explore", "search", "talash", "تلاش"),
        actionVariants = mapOf(
            "search" to listOf("search and explore", "search", "talash", "تلاش"),
            "play" to listOf("reel", "video", "watch", "chalao", "دیکھیں"),
            "like" to listOf("like", "double tap to like", "pasand", "لائک"),
            "unlike" to listOf("unlike", "liked", "like hatao"),
            "follow" to listOf("follow", "follow back", "فالو"),
            "following" to listOf("following", "requested"),
            "comment" to listOf("comment", "add a comment", "کمنٹ"),
            "post_comment" to listOf("post", "send", "bhejo", "بھیجیں"),
            "save" to listOf("save", "add to saved", "سیو"),
            "share" to listOf("share", "send", "شیئر")
        ),
        relativeHints = listOf(
            "Bottom navigation holds Home, Search, Create, Reels, Profile.",
            "Under feed posts, Heart (Like) is leftmost, followed by Comment bubble, Share airplane, and Bookmark (Save) on the far right.",
            "In Reels, Like, Comment, Share stack vertically on the bottom-right margin."
        ),
        verificationRules = mapOf(
            "like" to "Like button flips to Unlike or becomes selected.",
            "follow" to "Follow button changes to Following or Requested.",
            "save" to "Bookmark icon changes to Remove from Saved or selected."
        )
    )

    val tiktok = AppSkillRecipe(
        id = "tiktok",
        appName = "TikTok",
        packageNames = listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill"),
        searchCues = listOf("search", "find related content", "talash", "تلاش"),
        actionVariants = mapOf(
            "search" to listOf("search", "talash", "تلاش"),
            "play" to listOf("play", "video", "watch", "chalao"),
            "like" to listOf("like", "like video", "pasand", "لائک"),
            "unlike" to listOf("unlike", "liked"),
            "follow" to listOf("follow", "follow creator", "فالو"),
            "following" to listOf("following", "friends"),
            "comment" to listOf("comment", "read or add comments", "add comment", "کمنٹ"),
            "post_comment" to listOf("send", "post", "bhejo"),
            "save" to listOf("add to favorites", "favorite", "save", "سیو"),
            "next" to listOf("next", "swipe up for next", "agla", "اگلی"),
            "share" to listOf("share", "شیئر")
        ),
        relativeHints = listOf(
            "Right-side vertical rail stacks Profile Avatar/+Follow, Heart (Like), Comments, Bookmark (Favorite/Save), Share.",
            "Swiping UP advances to the next video cleanly when buttons are obscured; visual coordinate fallback handles custom canvas controls."
        ),
        verificationRules = mapOf(
            "like" to "Like icon becomes selected or count/label toggles to Liked/Unlike.",
            "follow" to "Follow badge disappears or switches to Following.",
            "next" to "Caption/creator handle on screen changes."
        )
    )

    val whatsapp = AppSkillRecipe(
        id = "whatsapp",
        appName = "WhatsApp",
        packageNames = listOf("com.whatsapp", "com.whatsapp.w4b"),
        searchCues = listOf("search", "ask meta ai or search", "talash", "تلاش"),
        actionVariants = mapOf(
            "search" to listOf("search", "ask meta ai or search", "talash", "تلاش"),
            "comment" to listOf("message", "type a message", "paigham", "پیغام"),
            "post_comment" to listOf("send", "bhejo", "بھیجیں"),
            "play" to listOf("play", "voice message", "chalao")
        ),
        relativeHints = listOf(
            "Chats list shows Search bar or Search icon at top.",
            "Inside a chat thread, 'Type a message' EditText sits at the bottom next to the green Send button."
        ),
        verificationRules = mapOf(
            "comment" to "Message text appears in chat list and input box clears."
        )
    )

    val chrome = AppSkillRecipe(
        id = "chrome",
        appName = "Chrome",
        packageNames = listOf("com.android.chrome", "com.chrome.beta"),
        searchCues = listOf("search or type web address", "search or type url", "url bar", "address bar", "talash", "تلاش"),
        actionVariants = mapOf(
            "search" to listOf("search or type web address", "search or type url", "search", "omnibox", "talash", "تلاش"),
            "play" to listOf("play", "video", "watch", "chalao"),
            "save" to listOf("bookmark", "star", "download", "سیو"),
            "back" to listOf("back", "navigate up", "wapis", "واپس")
        ),
        relativeHints = listOf(
            "Address/Search box (omnibox) sits at the top toolbar alongside tab switcher and 3-dot menu."
        ),
        verificationRules = mapOf(
            "search" to "Page title/URL or web search result links appear on screen."
        )
    )

    val generic = AppSkillRecipe(
        id = "generic",
        appName = "Current App",
        packageNames = emptyList(),
        searchCues = listOf("search", "find", "filter", "talash", "dhundo", "تلاش"),
        actionVariants = mapOf(
            "search" to listOf("search", "find", "filter", "talash", "dhundo", "تلاش"),
            "play" to listOf("play", "watch", "start", "open", "chalao", "kholo", "دیکھیں"),
            "like" to listOf("like", "heart", "favorite", "thumbs up", "pasand", "لائک"),
            "unlike" to listOf("unlike", "liked", "like hatao"),
            "follow" to listOf("follow", "subscribe", "فالو", "سبسکرائب"),
            "following" to listOf("following", "subscribed"),
            "comment" to listOf("comment", "reply", "write", "likho", "کمنٹ"),
            "post_comment" to listOf("post", "send", "submit", "bhejo", "بھیجیں"),
            "save" to listOf("save", "bookmark", "favorite", "mehfooz", "سیو"),
            "next" to listOf("next", "skip", "agla", "aage", "اگلا"),
            "share" to listOf("share", "bhejo", "شیئر")
        ),
        relativeHints = listOf(
            "Prefer clickable elements matching action labels, viewIds, or visual layout position."
        ),
        verificationRules = mapOf(
            "tap" to "Screen hierarchy updates, target toggles selected/checked state, or label updates."
        )
    )

    val allRecipes: List<AppSkillRecipe> = listOf(facebook, youtube, instagram, tiktok, whatsapp, chrome, generic)

    fun forPackage(packageName: String, goalHint: String = ""): AppSkillRecipe {
        val pkg = packageName.lowercase().trim()
        if (pkg.isNotBlank()) {
            allRecipes.firstOrNull { recipe ->
                recipe.packageNames.any { it.equals(pkg, ignoreCase = true) }
            }?.let { return it }
        }
        return detectTargetAppInGoal(goalHint) ?: generic
    }

    fun detectTargetAppInGoal(goal: String): AppSkillRecipe? {
        val lower = goal.lowercase()
        return when {
            lower.contains("facebook") || lower.contains("fb") || lower.contains("فیس بک") -> facebook
            lower.contains("youtube") || lower.contains("yt") || lower.contains("یوٹیوب") -> youtube
            lower.contains("instagram") || lower.contains("insta") || lower.contains("انسٹاگرام") -> instagram
            lower.contains("tiktok") || lower.contains("tik tok") || lower.contains("ٹک ٹاک") -> tiktok
            lower.contains("whatsapp") || lower.contains("واٹس ایپ") -> whatsapp
            lower.contains("chrome") || lower.contains("browser") || lower.contains("کروم") -> chrome
            else -> null
        }
    }

    fun buildCompactPromptHints(recipe: AppSkillRecipe): String {
        val variants = recipe.actionVariants.entries.take(7).joinToString("; ") { (k, v) ->
            "$k=[${v.take(3).joinToString("/")}]"
        }
        val hints = recipe.relativeHints.joinToString(" ")
        return "AppSkill(${recipe.appName}): labels{$variants}. Hints: $hints"
    }

    fun parseScreenAgentDecision(raw: String): ScreenAgentDecision? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try {
            val obj = JSONObject(raw.substring(start, end + 1))
            val action = ScreenAgentActionType.fromWire(obj.optString("action")) ?: return null
            val targetIndex = if (obj.has("targetIndex") && !obj.isNull("targetIndex")) {
                obj.optInt("targetIndex", -1).takeIf { it > 0 }
            } else null
            val targetQuery = obj.optString("targetQuery").trim().takeIf { it.isNotBlank() && it != "null" }
            val text = obj.optString("text").takeIf { it.isNotBlank() && it != "null" }
            val direction = obj.optString("direction").trim().takeIf { it.isNotBlank() && it != "null" }
            val submit = obj.optBoolean("submit", false)
            val clearFirst = obj.optBoolean("clearFirst", true)
            val x = if (obj.has("x") && !obj.isNull("x")) obj.optDouble("x", Double.NaN).takeIf { !it.isNaN() }?.toFloat() else null
            val y = if (obj.has("y") && !obj.isNull("y")) obj.optDouble("y", Double.NaN).takeIf { !it.isNaN() }?.toFloat() else null
            val reason = obj.optString("reason").trim()

            ScreenAgentDecision(
                action = action,
                targetIndex = targetIndex,
                targetQuery = targetQuery,
                text = text,
                direction = direction,
                submit = submit,
                clearFirst = clearFirst,
                x = x,
                y = y,
                reason = reason
            )
        } catch (_: Exception) {
            null
        }
    }

    fun parseVisualCoordinates(raw: String): Pair<Float, Float>? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try {
            val obj = JSONObject(raw.substring(start, end + 1))
            val x = obj.optDouble("xNorm", Double.NaN)
            val y = obj.optDouble("yNorm", Double.NaN)
            if (x.isNaN() || y.isNaN()) return null
            if (x < 0.0 || x > 1.0 || y < 0.0 || y > 1.0) return null
            x.toFloat() to y.toFloat()
        } catch (_: Exception) {
            null
        }
    }

    fun extractExplicitCommentText(goal: String): String? {
        val patterns = listOf(
            Regex("""(?i)comment\s+(?:karo|karna|kro|write|likho|post)?\s*[:\-]\s*["']?(.+?)["']?$"""),
            Regex("""(?i)(?:comment|reply)\s+(?:karo|likho)\s+["']?(.+?)["']?$"""),
            Regex("""(?i)(?:type|say|post)\s+comment\s*[:\-]?\s*["']?(.+?)["']?$""")
        )
        for (regex in patterns) {
            val match = regex.find(goal.trim())
            val candidate = match?.groupValues?.getOrNull(1)?.trim()?.trim('"', '\'')
            if (!candidate.isNullOrBlank()) return candidate
        }
        return null
    }

    fun detectSecurityOrCaptchaWall(snapshot: ScreenSnapshot): String? {
        val allText = snapshot.elements.joinToString(" ") {
            "${it.text} ${it.contentDescription} ${it.viewIdResourceName}"
        }.lowercase()
        val hasPassword = snapshot.elements.any {
            it.isPassword || it.viewIdResourceName.lowercase().contains("password") || it.bestLabel.lowercase().contains("password")
        }
        return when {
            allText.contains("captcha") || allText.contains("verify you're human") ||
                allText.contains("i'm not a robot") || allText.contains("security check") ->
                "CAPTCHA or human-verification screen detected — please solve it manually."
            allText.contains("two-factor") || allText.contains("2-step verification") ||
                allText.contains("enter authentication code") || allText.contains("enter otp") ->
                "Two-factor / OTP verification detected — Mahi stopped so you can complete it safely."
            hasPassword && (allText.contains("log in") || allText.contains("sign in")) ->
                "Login / password wall detected — please sign in manually for security."
            else -> null
        }
    }

    fun detectBulkSpamViolation(goal: String): String? {
        val lower = goal.lowercase()
        val spamPatterns = listOf(
            Regex("""\b(?:all|every|sab|sabko|tamam)\s+(?:posts?|videos?|reels?|accounts?|pages?|users?|followers?)\b"""),
            Regex("""\b(?:mass|bulk|spam|flood)\s+(?:like|follow|comment|message|share)\b"""),
            Regex("""\b\d{2,}\s*(?:times|baar|dafaa|posts?|videos?|comments?)\b""")
        )
        return if (spamPatterns.any { it.containsMatchIn(lower) }) {
            "Blocked bulk/repeated action: Mahi executes one focused task per command to protect your account."
        } else null
    }

    /**
     * Detects benign interruptions that Autopilot can dismiss safely and continue the goal:
     * "Not now", "Skip", "Maybe later", "No thanks", cookie banners, update-later dialogs.
     */
    fun detectHarmlessInterruption(snapshot: ScreenSnapshot): ScreenElementNode? {
        if (detectProtectedSystemOrPaywallPrompt(snapshot) != null) return null
        val dismissLabels = listOf(
            "not now", "maybe later", "no thanks", "skip", "skip ad", "update later",
            "not right now", "dismiss", "close", "later", "accept cookies", "accept all cookies", "got it"
        )
        return snapshot.elements.firstOrNull { node ->
            val lbl = node.bestLabel.lowercase().trim()
            node.isClickable && lbl.isNotBlank() && lbl.length <= 28 &&
                dismissLabels.any { cand -> lbl == cand || (cand.length >= 6 && lbl.contains(cand)) }
        }
    }

    /**
     * Detects OS permission prompts, paid subscriptions/purchases, or sensitive confirmations
     * that Autopilot must NEVER auto-accept without asking the user.
     */
    fun detectProtectedSystemOrPaywallPrompt(snapshot: ScreenSnapshot): String? {
        val pkg = snapshot.packageName.lowercase()
        val allText = snapshot.elements.joinToString(" ") {
            "${it.text} ${it.contentDescription} ${it.viewIdResourceName}"
        }.lowercase()

        val isPermissionPkg = pkg.contains("permissioncontroller") || pkg.contains("packageinstaller")
        val hasPermissionCue = allText.contains("while using the app") ||
            allText.contains("only this time") ||
            allText.contains("allow access to") ||
            (allText.contains("allow ") && (allText.contains("contacts") || allText.contains("location") ||
                allText.contains("microphone") || allText.contains("camera") || allText.contains("photos")))
        if (isPermissionPkg || hasPermissionCue) {
            return "Android permission prompt detected — paused so you can choose Allow or Deny."
        }

        val hasPaywallCue = allText.contains("subscribe for") ||
            allText.contains("confirm payment") ||
            allText.contains("complete purchase") ||
            allText.contains("start free trial") ||
            allText.contains("buy now") ||
            allText.contains("pay now")
        if (hasPaywallCue) {
            return "Payment or paid-subscription prompt detected — paused for your approval."
        }
        return null
    }

    /**
     * Finds collapsed/overflow menu buttons ("See more", "More options", "...") to reveal hidden actions like Follow.
     */
    fun findHiddenMenuNode(snapshot: ScreenSnapshot): ScreenElementNode? {
        val cues = listOf("more options", "see more", "more actions", "more", "...", "⋮")
        return snapshot.elements.firstOrNull { node ->
            val lbl = node.bestLabel.lowercase().trim()
            val desc = node.contentDescription.lowercase().trim()
            node.isClickable && !node.isEditable &&
                cues.any { cue -> lbl == cue || desc == cue || lbl.contains("more options") || desc.contains("more options") || lbl.contains("see more") }
        }
    }

    /**
     * Heuristic check for loading spinners / empty skeleton states so Autopilot waits up to ~5s before retrying.
     */
    fun isLoadingScreenSnapshot(snapshot: ScreenSnapshot): Boolean {
        if (snapshot.elements.isEmpty()) return true
        val labels = snapshot.elements.joinToString(" ") { it.bestLabel.lowercase() }
        val hasProgressWidget = snapshot.elements.any { it.className.contains("ProgressBar", ignoreCase = true) }
        return (hasProgressWidget && snapshot.elements.size <= 4) ||
            (labels.contains("loading") && snapshot.elements.size <= 5)
    }

    /**
     * Decomposes compound spoken goals ("Facebook kholo, pehli video chalao, like karo, follow karo aur comment karo: zabardast")
     * into sequential AutopilotSubGoals, each paired with an expected verification rule.
     */
    fun decomposeGoalIntoSubGoals(goal: String): List<AutopilotSubGoal> {
        val trimmed = goal.trim()
        if (trimmed.isEmpty()) return emptyList()

        // Preserve explicit negated constraints like "share mat karna, bas save karo" as one coherent goal
        val lower = trimmed.lowercase()
        if (lower.contains("share mat") || lower.contains("mat share") || lower.contains("don't share")) {
            return listOf(
                AutopilotSubGoal(
                    title = trimmed,
                    goalPhrase = trimmed,
                    verificationRule = "Perform non-share target action (Save) without tapping Share."
                )
            )
        }

        // Preserve comment text intact before splitting connectors
        val commentMatch = Regex("""(?i)(comment\s+(?:karo|karna|kro|write|likho|post)?\s*[:\-]\s*.+)$""").find(trimmed)
        val commentClause = commentMatch?.groupValues?.getOrNull(1)?.trim()
        val headWithoutComment = if (commentMatch != null) {
            trimmed.substring(0, commentMatch.range.first).trim().trimEnd(',', ' ')
        } else trimmed

        val rawParts = headWithoutComment
            .split(Regex("""\s*(?:,|;|\bthen\b|\baur\b|\band\b|\bphir\b)\s*""", RegexOption.IGNORE_CASE))
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toMutableList()
        if (!commentClause.isNullOrBlank()) {
            rawParts.add(commentClause)
        }

        if (rawParts.size <= 1) {
            return listOf(
                AutopilotSubGoal(
                    title = trimmed,
                    goalPhrase = trimmed,
                    verificationRule = inferVerificationRule(trimmed)
                )
            )
        }

        val detectedApp = detectTargetAppInGoal(trimmed)
        val merged = mutableListOf<String>()
        var i = 0
        while (i < rawParts.size) {
            val cur = rawParts[i]
            val curLower = cur.lowercase()
            val isPureAppOpen = detectedApp != null &&
                (curLower.contains(detectedApp.appName.lowercase()) || curLower.contains("fb") || curLower.contains("yt")) &&
                (curLower.contains("kholo") || curLower.contains("open") || curLower.contains("chalao")) &&
                !curLower.contains("video") && !curLower.contains("search") && !curLower.contains("like") &&
                !curLower.contains("follow") && !curLower.contains("comment")
            if (isPureAppOpen && i + 1 < rawParts.size) {
                merged.add("$cur, ${rawParts[i + 1]}")
                i += 2
            } else {
                val withContext = if (detectedApp != null && detectTargetAppInGoal(cur) == null) {
                    "${detectedApp.appName}: $cur"
                } else cur
                merged.add(withContext)
                i += 1
            }
        }

        return merged.map { part ->
            AutopilotSubGoal(
                title = part,
                goalPhrase = part,
                verificationRule = inferVerificationRule(part)
            )
        }
    }

    private fun inferVerificationRule(phrase: String): String {
        val l = phrase.lowercase()
        return when {
            l.contains("comment") -> "Comment text typed and submitted into comment thread."
            l.contains("unlike") || l.contains("like hata") -> "Like state flipped back to unliked."
            l.contains("like") -> "Like control flipped to Liked/Unlike or isSelected=true."
            l.contains("subscribe") -> "Subscribe button changed to Subscribed."
            l.contains("follow") -> "Follow button changed to Following or isSelected=true."
            l.contains("save") || l.contains("bookmark") -> "Item saved / bookmark toggled."
            l.contains("search") -> "Search query submitted and results screen updated."
            l.contains("video") || l.contains("play") || l.contains("chalao") -> "Video/reel playback or player screen opened."
            else -> "Target UI action verified by updated screen hierarchy."
        }
    }

    fun formatProgressCheckpoint(
        appName: String,
        stepCount: Int,
        language: AppLanguage = AppLanguage.ENGLISH
    ): String {
        val safeApp = appName.ifBlank { "current app" }
        return when (language) {
            AppLanguage.ROMAN_URDU ->
                "Abhi $safeApp par kaam jari hai — $stepCount steps mukammal ho gaye."
            AppLanguage.URDU ->
                "ابھی $safeApp پر کام جاری ہے — $stepCount مراحل مکمل ہو گئے ہیں۔"
            AppLanguage.ENGLISH ->
                "Still working on $safeApp — $stepCount steps done."
        }
    }

    fun formatTerminalAnnouncement(
        completed: Boolean,
        appName: String,
        summary: String,
        language: AppLanguage = AppLanguage.ENGLISH
    ): String {
        val clean = summary.trim()
        if (completed) return clean
        val safeApp = appName.ifBlank { "the screen" }
        return when (language) {
            AppLanguage.ROMAN_URDU -> "Autopilot $safeApp par ruk gaya: $clean"
            AppLanguage.URDU -> "آٹو پائلٹ $safeApp پر رک گیا: $clean"
            AppLanguage.ENGLISH -> clean
        }
    }
}
