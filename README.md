# EyeU — Vision-First Voice-Controlled Android Assistant

EyeU is a floating, always-available voice assistant that operates your phone by *looking* at
the screen: it takes a fresh screenshot before every single action, sends it (plus a supporting
accessibility-tree element list) to a vision-capable LLM — Groq, Gemini, or a local Ollama vision
model — and executes the model's next step, then looks again. This is the same core loop used by
vision-first Android agent projects like OpenDroid: screenshot → reason → act → re-screenshot,
repeated until the goal is done.

If no AI backend is configured at all, EyeU falls back to an on-device, offline rule-based
interpreter that reads the accessibility tree directly for simple one-shot commands ("go back",
"open camera", "scroll down", "tap send") — no network call, no API key required, though it can't
plan multi-step or conditional tasks the way the vision loop can.

## What it can handle

- Simple: *"scroll down"*, *"go back"*, *"open camera"*
- App-directed: *"open Chrome and search for cricket scores"* — opens exactly the app you named,
  not a different app the search term happens to match.
- Multi-step, cross-app, conditional: *"check if it's going to rain tomorrow, and if so, text my
  wife that I'll be late and set an alarm for 6 PM"* — the agent checks the weather first, reads
  the result from the screenshot, decides whether the condition holds, and only then chains into
  Messages and a reliable system "set alarm" action if needed.

## Architecture

```
 Mic (SpeechRecognizer) → spoken command (possibly multi-step / conditional)
        │
        ▼
 AgentController  ── loop, up to 25 steps ──────────────────────────────┐
        │                                                               │
        │ 1. ScreenshotCapture.capture()      → fresh screenshot        │
        │ 2. ScreenTreeExtractor.extract()    → numbered element list   │
        │                                                               │
        ▼                                                               │
 backend configured? ──NO──► RuleBasedFallback (offline, single-shot)   │
        │YES                                                            │
        ▼                                                               │
 LlmProvider.nextStep(goal, history, screenText, screenshotBase64Jpeg)  │
   ├─ GroqProvider   → api.groq.com/openai/v1/chat/completions (vision) │
   ├─ GeminiProvider → generativelanguage.googleapis.com generateContent│
   └─ OllamaProvider → http://<host>:11434/api/chat (local, vision)     │
        │ network/parse error? ──► RuleBasedFallback                   │
        ▼                                                               │
 LlmStepResponse { thought, speak, actions[], done }                    │
        │                                                               │
        ▼                                                               │
 ActionExecutor → click/type/scroll/swipe/tap_xy/set_alarm/open_app/    │
                  back/home/recents, then loop back to step 1 ──────────┘
```

The floating bar (`OverlayService`) renders a small pill docked near the bottom of the screen on
top of every app (`TYPE_APPLICATION_OVERLAY`), draggable along the screen edge, that expands
upward into a status/transcript card when tapped. Tapping the mic starts `SpeechRecognizer`; the
final transcript is handed to `EyeUAccessibilityService.runVoiceCommand(...)`, which drives the
loop above and speaks results back via `TextToSpeech`.

## Project layout

```
app/src/main/java/com/eyeu/assistant/
├── EyeUApp.kt                        notification channels
├── data/Models.kt                    ScreenElement, LlmAction/StepResponse, Prefs (SharedPreferences)
├── automation/
│   ├── ScreenTreeExtractor.kt        walks AccessibilityNodeInfo tree → indexed element list
│   ├── ScreenshotCapture.kt          AccessibilityService.takeScreenshot() → base64 JPEG helper
│   ├── ActionExecutor.kt             click/type/scroll/swipe/back/home/open_app/set_alarm
│   ├── RuleBasedFallback.kt          offline keyword-based command interpreter (no-API path)
│   └── AgentController.kt            the screenshot→think→act loop (max 25 steps per command)
├── llm/
│   ├── LlmProvider.kt                interface + shared vision system prompt / JSON action schema
│   ├── GroqProvider.kt               Groq (OpenAI-compatible) vision chat completions client
│   ├── GeminiProvider.kt             Google Generative Language API (Gemini) vision client
│   ├── OllamaProvider.kt             local Ollama /api/chat vision client
│   └── LlmJsonParser.kt              tolerant JSON → LlmStepResponse parser
├── service/
│   ├── EyeUAccessibilityService.kt   the AccessibilityService itself
│   ├── OverlayService.kt             draggable floating bar + expandable panel (foreground service)
│   └── VoiceIO.kt                    SpeechRecognizer (STT) + TextToSpeech (spoken feedback)
└── ui/
    ├── MainActivity.kt               home screen: onboarding + start/stop + read-only backend summary
    └── SettingsActivity.kt           the ONLY place API keys/models are edited — reached via the
                                       bottom "Settings" tab, never shown on the home screen
```

## Build & run

1. Open the `EyeU/` folder in Android Studio (Koala+ recommended), let Gradle sync.
2. Run on Android 8.0 (API 26) or newer. Screenshot capture (used every step) requires API 30+;
   below that, EyeU has no screen signal to send the model and effectively only the offline
   rule-based fallback will work.
3. In the app:
   - Step 1: grant "Display over other apps".
   - Step 2: enable the **EyeU** accessibility service in Settings → Accessibility.
   - Step 3: grant microphone (and notification, on Android 13+) permission.
4. Tap **Settings** in the bottom bar (not shown on the home screen) and configure one backend:
   - **Groq** — key from console.groq.com, plus a vision-capable model id (check
     console.groq.com/docs/vision for the current list — Groq's lineup changes).
   - **Gemini** — key from aistudio.google.com/apikey, a flash-class model is a good default.
   - **Ollama** — a reachable host (`http://10.0.2.2:11434` from an emulator talking to your host
     machine, or a LAN IP from a real device) and a **mid-sized vision model** — roughly 7B–11B,
     e.g. `qwen2.5vl:7b` or `llama3.2-vision:11b`. Very small vision models read screenshots
     unreliably; very large ones are too slow for a per-step loop on typical hardware. Pull it
     first: `ollama pull qwen2.5vl:7b`.
5. Back on the home screen, tap **Start EyeU** — the floating bar appears. Tap the mic and speak
   a command. EyeU takes a screenshot, reasons about one safe next step, acts, and repeats —
   reacting to what's actually on screen — until the goal is done or it hits the 25-step cap.

## Notes on safety & scope

- Every step is grounded in a fresh screenshot rather than a single upfront plan, so the agent
  reacts to what actually happened (a dialog appearing, a login screen, a typo) instead of
  blindly executing a pre-committed sequence.
- `set_alarm` uses Android's standard `AlarmClock.ACTION_SET_ALARM` intent (opens the system
  alarm-set UI for confirmation) rather than tapping through the Clock app by hand — far more
  reliable across different clock apps and Android skins.
- `type_text` uses the accessibility `ACTION_SET_TEXT` API directly; EyeU does not use the
  clipboard, so nothing you dictate touches clipboard history.
- Screenshots are sent to whichever cloud provider you configure (Groq or Gemini) unless you
  choose Ollama, which can run entirely on your own local network with nothing leaving your
  infrastructure.
- You are responsible for reviewing what EyeU does on your device before granting it
  accessibility control — treat it like any other automation tool with system-level reach.
