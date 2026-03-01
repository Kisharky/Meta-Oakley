# Meta Oakley – Android AI Middleware

Android middleware that connects to Ray-Ban Meta Smart Glasses (BLE) and adds a wearable AI assistant: wake word, speech-to-text, LLM (Groq), optional translation, text-to-speech, and CameraX vision.

## Setup

1. **Gradle**: Use Android Studio to open the project (it will create the Gradle wrapper if missing), or run `gradle wrapper` from the project root.

2. **API keys** – create `local.properties` in the project root (same folder as `settings.gradle.kts`):

   ```properties
   GROQ_API_KEY=your_groq_api_key
   PICOVOICE_ACCESS_KEY=your_picovoice_access_key
   ```

   - **Groq**: Get an API key from [Groq Console](https://console.groq.com/). Used for LLM chat.
   - **Picovoice**: Get an access key from [Picovoice Console](https://console.picovoice.ai/). Used for Porcupine wake word detection.

3. **Permissions**: The app requests `RECORD_AUDIO`, `BLUETOOTH_CONNECT` (Android 12+), `CAMERA`, and `INTERNET` at runtime. Grant them when prompted.

## Architecture

- **BLEManager (v1)** – BLE connection and device state only. **No BLE audio.** Ray-Ban Meta glasses do not expose raw PCM over BLE; audio uses A2DP. TTS → Android AudioManager → Bluetooth (A2DP when glasses are paired). This class: `connect`, `disconnect`, `connectionState`, optional `sendCommand` for device triggers.
- **WakeWordService** – Porcupine wake word detection; emits when the keyword is heard.
- **SpeechToTextService** – Android `SpeechRecognizer`; not truly continuous (stops after pause). Orchestrator restarts listening each turn and handles `ERROR_NO_MATCH` / `ERROR_SPEECH_TIMEOUT` so the pipeline does not silently die.
- **LLMService** – Chat via `LLMApi` (Groq implementation included).
- **TranslationService** – Optional translation (no-op by default).
- **VisionService** – **Uses the phone’s camera (CameraX), not the glasses camera.** The Ray-Ban Meta glasses camera is not exposed for third-party use. Phone camera is used for preview and optional frame analysis.
- **TextToSpeechService** – TTS to default audio (Bluetooth A2DP when glasses are paired).
- **AIOrchestrator** – Wake word and STT **never run at once** (one mic). Flow: idle → wake word active → on detection **stop wake engine** → start STT → after STT/LLM/TTS → **restart wake word**.

## Pipeline flow

1. Idle: only wake word is active.
2. Wake word detected → **stop wake engine** → start STT.
3. User speaks → STT final (or timeout/no match → back to idle, restart wake word).
4. LLM reply → optional translation → TTS plays to A2DP.
5. **Restart wake word**; loop.

## Custom wake word

To use a custom `.ppn` file from Picovoice, add it to `app/src/main/assets/` and pass the path (e.g. `"wakeword.ppn"`) to `WakeWordService(..., keywordPath = "wakeword.ppn")`.

## Build and run

Open in Android Studio and run on a device or emulator (API 26+). Ensure `local.properties` contains the keys above before building.
