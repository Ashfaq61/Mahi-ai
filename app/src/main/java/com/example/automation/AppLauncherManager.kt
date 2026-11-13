package com.example.automation

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build

enum class AppLaunchStatus {
    LAUNCHED_NATIVE_APP,
    OPENED_PLAY_STORE,
    OPENED_WEB_FALLBACK,
    APP_DISABLED,
    NO_LAUNCHABLE_ACTIVITY,
    NOT_FOUND_OR_FAILED
}

data class KnownAppDescriptor(
    val canonicalName: String,
    val aliases: List<String>,
    val packageCandidates: List<String>,
    val primaryStorePackage: String,
    val officialWebsiteUrl: String
)

data class InstalledAppInfo(
    val label: String,
    val packageName: String,
    val isEnabled: Boolean,
    val versionName: String,
    val isSystemApp: Boolean
)

data class AppLaunchResult(
    val success: Boolean,
    val appName: String,
    val resolvedPackage: String? = null,
    val status: AppLaunchStatus,
    val message: String,
    val verificationDetail: String = "",
    val fallbackUrl: String? = null
)

class AppLauncherManager(private val context: Context) {

    companion object {
        val KNOWN_APPS: List<KnownAppDescriptor> = listOf(
            KnownAppDescriptor(
                canonicalName = "TikTok",
                aliases = listOf("tiktok", "tik tok", "tik-tok", "ٹک ٹاک", "ٹکٹاک", "musically"),
                packageCandidates = listOf(
                    "com.zhiliaoapp.musically",
                    "com.ss.android.ugc.trill",
                    "com.zhiliaoapp.musically.go"
                ),
                primaryStorePackage = "com.zhiliaoapp.musically",
                officialWebsiteUrl = "https://www.tiktok.com"
            ),
            KnownAppDescriptor(
                canonicalName = "Facebook",
                aliases = listOf("facebook", "fb", "face book", "فیس بک", "فیسبک"),
                packageCandidates = listOf(
                    "com.facebook.katana",
                    "com.facebook.lite"
                ),
                primaryStorePackage = "com.facebook.katana",
                officialWebsiteUrl = "https://www.facebook.com"
            ),
            KnownAppDescriptor(
                canonicalName = "YouTube",
                aliases = listOf("youtube", "you tube", "yt", "یوٹیوب"),
                packageCandidates = listOf(
                    "com.google.android.youtube",
                    "com.google.android.youtube.tv",
                    "com.google.android.apps.youtube.kids"
                ),
                primaryStorePackage = "com.google.android.youtube",
                officialWebsiteUrl = "https://www.youtube.com"
            ),
            KnownAppDescriptor(
                canonicalName = "WhatsApp",
                aliases = listOf("whatsapp", "whats app", "واٹس ایپ", "wa"),
                packageCandidates = listOf("com.whatsapp", "com.whatsapp.w4b"),
                primaryStorePackage = "com.whatsapp",
                officialWebsiteUrl = "https://web.whatsapp.com"
            ),
            KnownAppDescriptor(
                canonicalName = "Instagram",
                aliases = listOf("instagram", "insta", "انسٹاگرام"),
                packageCandidates = listOf("com.instagram.android", "com.instagram.lite"),
                primaryStorePackage = "com.instagram.android",
                officialWebsiteUrl = "https://www.instagram.com"
            ),
            KnownAppDescriptor(
                canonicalName = "Spotify",
                aliases = listOf("spotify", "اسپاٹیفائی"),
                packageCandidates = listOf("com.spotify.music", "com.spotify.lite"),
                primaryStorePackage = "com.spotify.music",
                officialWebsiteUrl = "https://open.spotify.com"
            ),
            KnownAppDescriptor(
                canonicalName = "Telegram",
                aliases = listOf("telegram", "ٹیلیگرام"),
                packageCandidates = listOf("org.telegram.messenger", "org.telegram.plus"),
                primaryStorePackage = "org.telegram.messenger",
                officialWebsiteUrl = "https://web.telegram.org"
            ),
            KnownAppDescriptor(
                canonicalName = "X / Twitter",
                aliases = listOf("twitter", "x app", "ایکس"),
                packageCandidates = listOf("com.twitter.android"),
                primaryStorePackage = "com.twitter.android",
                officialWebsiteUrl = "https://x.com"
            ),
            KnownAppDescriptor(
                canonicalName = "Chrome",
                aliases = listOf("chrome", "browser", "کروم"),
                packageCandidates = listOf("com.android.chrome"),
                primaryStorePackage = "com.android.chrome",
                officialWebsiteUrl = "https://www.google.com"
            ),
            KnownAppDescriptor(
                canonicalName = "Maps",
                aliases = listOf("maps", "google maps", "میپس"),
                packageCandidates = listOf("com.google.android.apps.maps"),
                primaryStorePackage = "com.google.android.apps.maps",
                officialWebsiteUrl = "https://maps.google.com"
            ),
            KnownAppDescriptor(
                canonicalName = "Settings",
                aliases = listOf("settings", "سیٹنگز"),
                packageCandidates = listOf("com.android.settings"),
                primaryStorePackage = "com.android.settings",
                officialWebsiteUrl = "https://support.google.com/android"
            ),
            KnownAppDescriptor(
                canonicalName = "Camera",
                aliases = listOf("camera", "کیمرہ"),
                packageCandidates = listOf("com.android.camera2", "com.sec.android.app.camera", "com.google.android.GoogleCamera"),
                primaryStorePackage = "",
                officialWebsiteUrl = ""
            )
        )

        fun normalizeAppQuery(raw: String): String {
            return raw.lowercase()
                .replace("open ", "")
                .replace("launch ", "")
                .replace("start ", "")
                .replace("kholo", "")
                .replace("chalao", "")
                .replace("karo", "")
                .replace("کھولو", "")
                .replace("چلاؤ", "")
                .replace("app", "")
                .trim()
        }

        fun matchKnownApp(query: String): KnownAppDescriptor? {
            val lower = query.lowercase().trim()
            val cleaned = normalizeAppQuery(lower)
            return KNOWN_APPS.firstOrNull { desc ->
                desc.aliases.any { alias ->
                    cleaned == alias ||
                        lower.contains(alias) ||
                        cleaned.contains(alias)
                } || desc.packageCandidates.any { pkg -> pkg.equals(lower, ignoreCase = true) }
            }
        }
    }

    fun findInstalledPackage(query: String): InstalledAppInfo? {
        val pm = context.packageManager
        val known = matchKnownApp(query)
        if (known != null) {
            for (candidate in known.packageCandidates) {
                val info = inspectPackage(pm, candidate)
                if (info != null) return info
            }
        }

        val normalized = normalizeAppQuery(query)
        if (normalized.isBlank()) return null

        // Direct package check if user passed package ID
        inspectPackage(pm, normalized)?.let { return it }

        // Search all launchable activities via PackageManager
        val launchable = getInstalledLaunchableApps(normalized)
        return launchable.firstOrNull {
            it.label.equals(normalized, ignoreCase = true)
        } ?: launchable.firstOrNull()
    }

    fun isAppInstalled(appNameOrPackage: String): Boolean {
        return findInstalledPackage(appNameOrPackage) != null
    }

    fun getInstalledLaunchableApps(searchQuery: String = ""): List<InstalledAppInfo> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            }
        } catch (_: Exception) {
            emptyList()
        }

        val q = searchQuery.trim().lowercase()
        return resolved
            .mapNotNull { info ->
                val activityInfo = info.activityInfo ?: return@mapNotNull null
                val pkg = activityInfo.packageName ?: return@mapNotNull null
                val label = info.loadLabel(pm)?.toString()?.trim().orEmpty().ifBlank { pkg }
                val appInfo = activityInfo.applicationInfo
                val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val ver = runCatching { pm.getPackageInfo(pkg, 0).versionName.orEmpty() }.getOrDefault("")
                InstalledAppInfo(
                    label = label,
                    packageName = pkg,
                    isEnabled = appInfo.enabled && activityInfo.enabled,
                    versionName = ver,
                    isSystemApp = isSystem
                )
            }
            .distinctBy { it.packageName }
            .filter { item ->
                q.isEmpty() || item.label.lowercase().contains(q) || item.packageName.lowercase().contains(q)
            }
            .sortedBy { it.label.lowercase() }
    }

    fun launchApp(
        appNameOrPackage: String,
        openStoreOrWebIfMissing: Boolean = true
    ): AppLaunchResult {
        val pm = context.packageManager
        val known = matchKnownApp(appNameOrPackage)
        val displayLabel = known?.canonicalName ?: normalizeAppQuery(appNameOrPackage).ifBlank { appNameOrPackage }

        // Special-case Settings direct intent if Settings package isn't launchable directly
        if (known?.canonicalName == "Settings" && findInstalledPackage("Settings") == null) {
            return try {
                val i = Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(i)
                AppLaunchResult(
                    success = true,
                    appName = "Settings",
                    resolvedPackage = "com.android.settings",
                    status = AppLaunchStatus.LAUNCHED_NATIVE_APP,
                    message = "Opened Android Settings",
                    verificationDetail = "Settings intent started"
                )
            } catch (e: Exception) {
                AppLaunchResult(
                    success = false,
                    appName = "Settings",
                    status = AppLaunchStatus.NOT_FOUND_OR_FAILED,
                    message = e.localizedMessage ?: "Failed to open Settings"
                )
            }
        }

        val installed = findInstalledPackage(appNameOrPackage)
        if (installed != null) {
            if (!installed.isEnabled) {
                return AppLaunchResult(
                    success = false,
                    appName = installed.label,
                    resolvedPackage = installed.packageName,
                    status = AppLaunchStatus.APP_DISABLED,
                    message = "${installed.label} is installed (${installed.packageName}) but disabled on this device."
                )
            }

            val launchIntent = resolveRobustLaunchIntent(pm, installed.packageName)
            if (launchIntent == null) {
                return if (openStoreOrWebIfMissing && known != null) {
                    launchStoreOrWebFallback(known, reason = "${installed.label} has no exportable launch activity after update.")
                } else {
                    AppLaunchResult(
                        success = false,
                        appName = installed.label,
                        resolvedPackage = installed.packageName,
                        status = AppLaunchStatus.NO_LAUNCHABLE_ACTIVITY,
                        message = "${installed.label} has no launchable activity."
                    )
                }
            }

            return try {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                context.startActivity(launchIntent)
                AppLaunchResult(
                    success = true,
                    appName = installed.label,
                    resolvedPackage = installed.packageName,
                    status = AppLaunchStatus.LAUNCHED_NATIVE_APP,
                    message = "Launched ${installed.label}",
                    verificationDetail = "Verified package ${installed.packageName} via ${launchIntent.component?.className ?: "launch intent"}"
                )
            } catch (e: Exception) {
                AppLaunchResult(
                    success = false,
                    appName = installed.label,
                    resolvedPackage = installed.packageName,
                    status = AppLaunchStatus.NOT_FOUND_OR_FAILED,
                    message = "Failed to launch ${installed.label}: ${e.localizedMessage}"
                )
            }
        }

        if (openStoreOrWebIfMissing && known != null) {
            return launchStoreOrWebFallback(known, reason = "${known.canonicalName} is not installed.")
        }

        return AppLaunchResult(
            success = false,
            appName = displayLabel,
            status = AppLaunchStatus.NOT_FOUND_OR_FAILED,
            message = "$displayLabel is not installed on this device."
        )
    }

    fun openYouTubeSearch(query: String): AppLaunchResult {
        val cleanQuery = query.trim()
        val pm = context.packageManager
        val ytApp = findInstalledPackage("YouTube")
        val searchUrl = "https://www.youtube.com/results?search_query=${Uri.encode(cleanQuery)}"

        // Prefer opening inside the native YouTube app if installed & enabled
        if (ytApp != null && ytApp.isEnabled) {
            val nativeSearchIntent = Intent(Intent.ACTION_SEARCH).apply {
                setPackage(ytApp.packageName)
                putExtra("query", cleanQuery)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (nativeSearchIntent.resolveActivity(pm) != null) {
                try {
                    context.startActivity(nativeSearchIntent)
                    return AppLaunchResult(
                        success = true,
                        appName = "YouTube",
                        resolvedPackage = ytApp.packageName,
                        status = AppLaunchStatus.LAUNCHED_NATIVE_APP,
                        message = "Searching YouTube app for '$cleanQuery'",
                        verificationDetail = "Native ACTION_SEARCH on ${ytApp.packageName}",
                        fallbackUrl = searchUrl
                    )
                } catch (_: Exception) {
                }
            }
        }

        return try {
            val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse(searchUrl)).apply {
                if (ytApp != null && ytApp.isEnabled) {
                    setPackage(ytApp.packageName)
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(webIntent)
            AppLaunchResult(
                success = true,
                appName = "YouTube",
                resolvedPackage = ytApp?.packageName,
                status = if (ytApp != null) AppLaunchStatus.LAUNCHED_NATIVE_APP else AppLaunchStatus.OPENED_WEB_FALLBACK,
                message = "Opened YouTube search for '$cleanQuery'",
                verificationDetail = "Dispatched $searchUrl",
                fallbackUrl = searchUrl
            )
        } catch (e: Exception) {
            // Final browser attempt without package lock
            try {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(searchUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                AppLaunchResult(
                    success = true,
                    appName = "YouTube",
                    status = AppLaunchStatus.OPENED_WEB_FALLBACK,
                    message = "Opened YouTube search in browser",
                    fallbackUrl = searchUrl
                )
            } catch (ex: Exception) {
                AppLaunchResult(
                    success = false,
                    appName = "YouTube",
                    status = AppLaunchStatus.NOT_FOUND_OR_FAILED,
                    message = ex.localizedMessage ?: "Unable to open YouTube search"
                )
            }
        }
    }

    private fun launchStoreOrWebFallback(known: KnownAppDescriptor, reason: String): AppLaunchResult {
        if (known.primaryStorePackage.isNotBlank()) {
            val marketIntent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("market://details?id=${known.primaryStorePackage}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(marketIntent)
                return AppLaunchResult(
                    success = true,
                    appName = known.canonicalName,
                    resolvedPackage = known.primaryStorePackage,
                    status = AppLaunchStatus.OPENED_PLAY_STORE,
                    message = "$reason Opened Google Play Store page for ${known.canonicalName}.",
                    verificationDetail = "market://details?id=${known.primaryStorePackage}",
                    fallbackUrl = "https://play.google.com/store/apps/details?id=${known.primaryStorePackage}"
                )
            } catch (_: ActivityNotFoundException) {
                // Play Store unavailable (e.g., emulator without Play Store) -> try official website
            } catch (_: Exception) {
            }
        }

        if (known.officialWebsiteUrl.isNotBlank()) {
            return try {
                val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse(known.officialWebsiteUrl))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(webIntent)
                AppLaunchResult(
                    success = true,
                    appName = known.canonicalName,
                    status = AppLaunchStatus.OPENED_WEB_FALLBACK,
                    message = "$reason Opened ${known.officialWebsiteUrl}.",
                    verificationDetail = "Browser fallback: ${known.officialWebsiteUrl}",
                    fallbackUrl = known.officialWebsiteUrl
                )
            } catch (e: Exception) {
                AppLaunchResult(
                    success = false,
                    appName = known.canonicalName,
                    status = AppLaunchStatus.NOT_FOUND_OR_FAILED,
                    message = "$reason Unable to open fallback link: ${e.localizedMessage}",
                    fallbackUrl = known.officialWebsiteUrl
                )
            }
        }

        return AppLaunchResult(
            success = false,
            appName = known.canonicalName,
            status = AppLaunchStatus.NOT_FOUND_OR_FAILED,
            message = "$reason No launch or store target available."
        )
    }

    private fun resolveRobustLaunchIntent(pm: PackageManager, packageName: String): Intent? {
        pm.getLaunchIntentForPackage(packageName)?.let { return it }

        // Fallback if app update moved main launcher activity or category differs (LEANBACK/LAUNCHER)
        val mainQuery = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            setPackage(packageName)
        }
        val activities = try {
            pm.queryIntentActivities(mainQuery, 0)
        } catch (_: Exception) {
            emptyList()
        }
        val firstMatch = activities.firstOrNull { it.activityInfo?.enabled == true }?.activityInfo ?: return null
        return Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            component = ComponentName(firstMatch.packageName, firstMatch.name)
        }
    }

    private fun inspectPackage(pm: PackageManager, packageName: String): InstalledAppInfo? {
        return try {
            val pkgInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, 0)
            }
            val appInfo = pkgInfo.applicationInfo ?: return null
            val label = pm.getApplicationLabel(appInfo)?.toString()?.trim().orEmpty().ifBlank { packageName }
            val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            InstalledAppInfo(
                label = label,
                packageName = packageName,
                isEnabled = appInfo.enabled,
                versionName = pkgInfo.versionName.orEmpty(),
                isSystemApp = isSystem
            )
        } catch (_: PackageManager.NameNotFoundException) {
            null
        } catch (_: Exception) {
            null
        }
    }
}
