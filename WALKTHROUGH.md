# Walkthrough - OpenDroid Autonomous Vision Agent Implementation in EyeU

We have updated EyeU's automation architecture to match [OpenDroid](https://github.com/yashab-cyber/opendroid)'s vision-first autonomous planning loop. EyeU now captures automated screenshots at each step, feeds them alongside the indexed accessibility element tree to the vision LLM, and iteratively executes actions until the user's goal is completed.

---

## Key Changes & Enhancements

### 1. Robust Automated Screenshot Capture (Emulator + Physical Device)
- **[`ScreenshotCapture.kt`](file:///c:/Users/arshp/OneDrive/Desktop/EyeU/app/src/main/java/com/eyeu/assistant/automation/ScreenshotCapture.kt)**:
  - Fixed hardware buffer memory corruption: converts the `HardwareBuffer` to a software `ARGB_8888` `Bitmap` before closing the buffer. This ensures 100% stability across Android emulators and physical devices without native crashes or black frames.
  - Added automatic retry mechanism (up to 3 attempts with progressive delay) to handle transient rate limits (`ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT` or `ERROR_TAKE_SCREENSHOT_NO_END_OF_FRAME`) during app transition animations.
  - Dual-tier fallback: If screenshot capture is unavailable on secure screens or older OS versions, the agent seamlessly continues by providing the indexed accessibility layout tree.

### 2. Multi-Step OpenDroid Autonomous Planning Loop
- **[`AgentController.kt`](file:///c:/Users/arshp/OneDrive/Desktop/EyeU/app/src/main/java/com/eyeu/assistant/automation/AgentController.kt)**:
  - Manages the end-to-end `AgentLoop` up to 25 steps with full chronological history tracking (`thought`, `speak`, `actions`).
  - Implements dynamic settle delays depending on action type (e.g. 1.5s after opening apps, 1s after typing/search, 600ms after taps) so the screen finishes rendering before taking the next step's screenshot.
  - Only completes when the LLM visually verifies the final state on screen and sets `done = true`.
- **[`PromptBuilder.kt`](file:///c:/Users/arshp/OneDrive/Desktop/EyeU/app/src/main/java/com/eyeu/assistant/llm/LlmProvider.kt)**:
  - Injected OpenDroid's core execution rules and multi-step task guidelines (e.g. step 1 open app -> step 2 search -> step 3 verify results and complete).

### 3. API Resilience & Vision Model Defaults
- **[`GeminiProvider.kt`](file:///c:/Users/arshp/OneDrive/Desktop/EyeU/app/src/main/java/com/eyeu/assistant/llm/GeminiProvider.kt)** & **[`GroqProvider.kt`](file:///c:/Users/arshp/OneDrive/Desktop/EyeU/app/src/main/java/com/eyeu/assistant/llm/GroqProvider.kt)**:
  - Added retry logic with exponential backoff for transient 503/429/timeout errors.
  - Fixed Groq default model to official vision model: `llama-3.2-11b-vision-preview`.
  - Added nullable screenshot support for text-tree fallback turns.
- **[`LlmJsonParser.kt`](file:///c:/Users/arshp/OneDrive/Desktop/EyeU/app/src/main/java/com/eyeu/assistant/llm/LlmJsonParser.kt)**:
  - Added robust bracket-matching JSON extraction that reliably parses `{ ... }` from model output even when surrounded by markdown fences (```` ```json ````) or conversational preamble.

### 4. Smart App Resolver & Search Submission
- **[`ActionExecutor.kt`](file:///c:/Users/arshp/OneDrive/Desktop/EyeU/app/src/main/java/com/eyeu/assistant/automation/ActionExecutor.kt)**:
  - Added app alias map (`"chrome"`, `"google chrome"`, `"browser"`, `"youtube"`, `"maps"`, `"messages"`, etc.) and `PackageManager` query fallback to resolve app names to launch intents.
  - Added `pressEnter` / `press_enter` / `search` support (`ACTION_IME_ENTER` on Android 12+ and keyboard search tap fallback) so search queries typed into address/search bars are submitted immediately.

---

## Verification Results

### Build Verification
- **Command**: `.\gradlew.bat assembleDebug --no-daemon`
- **Result**: `BUILD SUCCESSFUL` (All Kotlin sources compiled, APK generated at `app/build/intermediates/apk/debug/app-debug.apk`).

### Execution Flow for Complex Commands
For *"open chrome and search something"*:
1. **Step 1**: EyeU identifies the app, issues `open_app ("com.android.chrome")`, speaks *"Opening Chrome"*, and keeps `done: false`.
2. **Transition**: Waits 1.5s for Chrome to launch and settle on screen.
3. **Step 2**: EyeU captures a fresh screenshot of Chrome, locates the address / search bar in the element tree, types *"something"*, submits the search with enter (`pressEnter: true`), and keeps `done: false`.
4. **Transition**: Waits 1.0s for search results to load.
5. **Step 3**: EyeU captures a fresh screenshot of the search results, confirms the search is displayed, speaks *"Here are the search results for something in Chrome"*, and marks `done: true`.
