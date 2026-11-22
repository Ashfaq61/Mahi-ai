# Mahi AI v3.3.0 — Honest Feature Status Audit

Status Legend:
- **ACTIVE (UNIT/ROBOLECTRIC VERIFIED)**: Real code path verified by automated Robolectric/JUnit tests in this workspace.
- **IMPLEMENTED NOT DEVICE-VERIFIED**: Real Android API/SDK/HTTPS integration implemented in Kotlin, awaiting live hardware verification on a physical Android phone (e.g., Motorola Moto G Stylus 2025) because this cloud build container has no Android emulator or ADB.
- **PARTIAL (OS RESTRICTED)**: Core integration built; specific sub-behavior restricted by Android platform security policy (honest fallback implemented and disclosed in UI).
- **BLOCKED (REQUIRES USER SETUP / DOWNLOAD)**: Waiting on user-supplied credentials, Android system permission grant, or external model weights.

| Domain | Feature | Status | Details & Verification Notes |
|---|---|---|---|
| **Voice & TTS** | TTS Speech Speed (`0.5x–2.0x`) + Clarity Balance | ACTIVE (UNIT VERIFIED) | `MahiPreferencesStore`, `MahiSpeechSynthesizer.computeEffectiveTtsParams()`, `SettingsModal` slider + voice preview button, and voice command routing (`"speech speed 1.35x"`). |
| **Voice & TTS** | Cyber Glow Orb, Waveform, Subtitles & Moods | ACTIVE (UI VERIFIED) | `MahiOrb`, `LiveWaveformVisualizer`, `SubtitleOverlay`, `MahiStatusBanner`, light/dark themes. |
| **Voice & Live** | Gemini Live Bidirectional WebSocket (`BidiGenerateContent`) | IMPLEMENTED NOT DEVICE-VERIFIED | Real WebSocket audio capture (`16 kHz AudioRecord` + AEC) & playback (`24 kHz AudioTrack`), 20 tool declarations unit-tested; live mic/cloud session requires Gemini API key & phone hardware. |
| **Voice & Live** | Opt-in Wake Up Detection & Single-Mic Service | IMPLEMENTED NOT DEVICE-VERIFIED | `MahiVoiceForegroundService` (`foregroundServiceType="microphone"`), `WakeModelManager` (~40 MB Vosk download), single-mic arbitration pausing wake before active voice. |
| **AI Brain** | Encrypted Multi-Slot API Store (`Gemini`, `Groq`, `OpenRouter`, `OpenAI`, `HF`) | ACTIVE (UNIT VERIFIED) | `SecureApiKeyStore` (`EncryptedSharedPreferences` / AES-GCM, masked previews, slot rotation, never logged). Live API calls tested via real HTTPS client on device. |
| **AI Brain** | Dynamic `ModelRegistry` & Room Cache (`24h`) | ACTIVE (UNIT VERIFIED) | Real `/models` & `v1beta/models` discovery parsers, `CHAT/LIVE/VISION/TOOLS/EMBEDDINGS` capability classifier & Room persistence unit-tested. |
| **Screen Automation** | Screen Reader V2 (`MAX_DEPTH=30`, Privacy Redaction, IME-safe bounds) | ACTIVE (UNIT VERIFIED) | Masks `[PASSWORD_HIDDEN]`, `[REDACTED_OTP]`, `[REDACTED_CARD_NUMBER]`, sorts topmost dialog nodes. Live accessibility tree requires enabling Accessibility Service on Android. |
| **Screen Automation** | `AutonomousAgentEngine` (Observe-Think-Act-Verify Loop) | ACTIVE (UNIT VERIFIED) | Like/Comment/Save-No-Share/CAPTCHA wall/Spam blocking/30-day `AppLabelMemoryStore` verified via mock driver in Robolectric tests. |
| **Screen Automation** | Set-of-Mark Visual Grounding (`GeminiGroundingProvider` & `RemoteHttpGroundingProvider`) | IMPLEMENTED NOT DEVICE-VERIFIED | Password bounding-box black-box redaction & JSON coordinate parsing implemented cleanly; requires Accessibility screenshot permission (Android 11+). |
| **Camera & Vision** | CameraX Live Preview, Photo/Video `MediaStore` Capture & Torch/Zoom | IMPLEMENTED NOT DEVICE-VERIFIED | Uses CameraX `ProcessCameraProvider`, zero-permission `PickVisualMedia` Photo Picker & `NeuralLensAnalyzer`. Hardware camera test requires device. |
| **System Control** | Volume Streams, Torch, Quick Panels, Battery/Storage/Network/Device Info | ACTIVE (UNIT & UI VERIFIED) | Verified via `SmartphoneControlManager`, `VolumeManager`, `SmartControlScreen` Compose test. `WRITE_SETTINGS` brightness permission checked explicitly. |
| **Calls** | `MahiInCallService`, `MahiCallScreeningService` & Speakerphone AI Bridge | PARTIAL (OS RESTRICTED) | Android prohibits unprivileged apps from injecting AI audio into cellular uplink. Uses explicit **Speakerphone Bridge Consent**, disclosure prompt, urgency escalation & Manual Takeover button. |
| **17-Agent Bus** | Modular 17-Node Coordinator + RAM-Aware LLM Semaphore | ACTIVE (UNIT VERIFIED) | Typed `SharedFlow` bus, Room collaboration log & heartbeat sweep tested. |
| **Research & Lab** | Wikipedia/DuckDuckGo Search, GitHub, Hugging Face, Nearby Places | IMPLEMENTED NOT DEVICE-VERIFIED | Real TLS endpoints (`en.wikipedia.org`, `api.duckduckgo.com`, `api.github.com`, `huggingface.co`, `overpass-api.de`). Offline LLM readiness accurately reports `NOT_INSTALLED` until GGUF weights exist. |
| **Marketplace & Sandbox** | Internal Verified Plugins & Isolated Patch Security Scanner | ACTIVE (UNIT VERIFIED) | Zero dynamic code loading (Play Policy compliant), static security checks (`AIza`, cleartext `http://`, `DexClassLoader`), SHA-256 release ledger. |
| **CI/CD & Build** | GitHub Actions (`android-build.yml`), Release Signing Config, Debug Build | ACTIVE | Compiles clean with AGP 9.1.1 / Kotlin 2.2.10 / SDK 36; signing keys externalized via environment/secrets. |
