# Mahi AI v3.3.0 (Android Autonomous Voice & Screen Assistant)

Mahi AI is an open, Cyber Glow–styled Android voice, vision, and screen-automation assistant built in **Kotlin**, **Jetpack Compose (Material 3)**, **MVVM**, **Room**, **CameraX**, and **Android Accessibility / Telecom / Notification Listener APIs**.

## Key Architecture & Features
- **AI Brain (`com.example.api`, `com.example.ai`)**: Encrypted multi-slot API key storage (`SecureApiKeyStore`) for Gemini, Groq, OpenRouter, OpenAI-compatible, and Hugging Face endpoints with automatic fallback, masked previews, and dynamic `ModelRegistry` capability classification cached in Room (`24h`).
- **Voice & Speed Clarity (`com.example.audio`, `com.example.ui.components`)**: Gemini Live WebSocket bidirectional audio (`BidiGenerateContent`) plus offline `SpeechRecognizer` & Android `TextToSpeech` featuring a **TTS Speech Speed slider (`0.5x–2.0x`)** with **High-Speed Clarity Balance** (inverse pitch stabilization above `1.30x` and clause phrasing polish).
- **Screen Reader V2 & Autopilot (`com.example.automation`)**: Deep UI tree extraction (`MAX_DEPTH=30`), password/OTP/card redaction, Observe–Think–Act–Verify loop, CAPTCHA/2FA walls, per-app `AppLabelMemoryStore`, floating Emergency STOP overlay, and optional Set-of-Mark (`SetOfMarkGrounding`) fallback.
- **Calls & Telecom (`com.example.calls`)**: `MahiInCallService`, `MahiCallScreeningService`, emergency repeat-caller override, trusted/blocked rules, and disclosed AI Speakerphone Bridge (honestly explaining Android's cellular uplink audio restriction).
- **Neural Lens & CameraX (`com.example.camera`, `com.example.vision`)**: CameraX live preview, photo & video capture to `MediaStore`, zero-permission `PickVisualMedia` gallery inspection, and multimodal Vision Q&A.
- **17-Agent Bus, Marketplace, Research & Sandbox (`com.example.agents`, `com.example.marketplace`, `com.example.research`, `com.example.pipeline`)**: RAM-aware LLM concurrency lock, Wikipedia/DuckDuckGo/GitHub/Hugging Face/Overpass APIs, Play-compliant plugin verifier (zero dynamic code loading), and isolated patch security scanner with SHA-256 release records.

## Build & Application Identity
- **Package / Namespace**: `com.example`
- **Application ID**: `com.aistudio.mahiai.vzrtkp`
- **Version**: `3.3.0` (`versionCode = 16`)
- **Gradle Build & Test**:
  ```bash
  gradle :app:testDebugUnitTest --no-daemon --console=plain
  gradle :app:assembleDebug --no-daemon --console=plain
  ```
- **API Keys**: Enter API keys inside the **APIs** tab in the app (encrypted on device via `EncryptedSharedPreferences`). Never hardcode keys in source files.
