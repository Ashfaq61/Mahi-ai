# Changelog

## v3.3.0 (versionCode 16) — Complete Autonomous Assistant & TTS Clarity Upgrade
- **TTS Speech Speed & Clarity Compensation**: Added `0.5x–2.0x` voice speed slider, quick presets, clarity balance toggle (inverse pitch compensation above `1.30x` + articulated pause formatting), live preview button, and voice commands (`"speech speed 1.5x"`).
- **Project Recovery & Build Modernization**: Restored complete `settings.gradle.kts`, `gradle/libs.versions.toml`, `app/build.gradle.kts`, `AndroidManifest.xml`, Cyber Glow adaptive launcher icon, and Firebase App Check integration (`compileSdk 36`, `targetSdk 36`).
- **Multi-Provider AI Brain & ModelRegistry**: Added AES-GCM encrypted multi-slot keys (`Gemini`, `Groq`, `OpenRouter`, `OpenAI-Compatible`, `Hugging Face`), real `/models` capability classification (`CHAT`, `LIVE`, `VISION`, `TOOLS`, `EMBEDDINGS`), and 24h Room cache.
- **Screen Reader V2 & Autonomous Agent Engine**: Full Observe–Think–Act–Verify loop, OTP/Password/Card redaction, `AppSkills` per-app recipes, Set-of-Mark pixel fallback, `TestPlaygroundActivity`, and floating Emergency STOP button.
- **Telecom, CameraX, Controls, Research & CI/CD**: Implemented `MahiInCallService`/`CallScreeningService`, CameraX photo/video/torch/zoom, System telemetry & brightness/volume controls, 17-agent typed message bus, verified plugin marketplace, isolated sandbox patch scanner, and `.github/workflows/android-build.yml`.

## v1.3.0 — Screen Engine V2 & Call Console
- Introduced `CallsAndSettingsScreen`, `WakeModelManager`, `MahiVoiceForegroundService` single-mic arbitration, and `AppLabelMemoryStore` expiry logic.

## v1.2.0 — Initial Cyber Glow & Screen Tools Baseline
- Established Cyber Glow theme, `MahiV120UpgradeTest` test contracts, `UserMemoryStore`, and `SmartphoneControlManager`.
