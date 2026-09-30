# Luma Subtitles — Native Android app

Luma captures permitted media-playback audio, recognizes **US English locally** with Vosk, and translates it **to Arabic only** with the on-device ML Kit translation model. It displays translated text as a passive floating subtitle line over other apps.

## Features

- A **single centered Arabic subtitle line** near the bottom of the screen, with a transparent background, RTL text direction, a readability shadow, and ellipsis clipping instead of wrapping.
- Only translated Arabic text is rendered. English recognition text and status messages are never used as overlay fallbacks; when translation is unavailable or fails, the caption stays blank.
- **Live partial updates:** changed Vosk partials (including one-word hypotheses) are coalesced to the latest snapshot and submitted no more often than once every 350 ms. Only one ML Kit request runs at a time. Final recognition results bypass the partial throttle and take priority over interim updates; up to 32 final segments are queued, with the oldest adjacent finals combined rather than discarded if the queue fills. A failed final translation is retried once and remains queued if the retry also fails. Older translation results never overwrite newer speech.
- Persistent caption styling: **18, 22, or 26 sp** size; **sans-serif, serif, or monospace** typeface; and **white, gold, cyan, or mint** text color. Choices are stored locally and applied when the next caption session starts.
- Android `AudioPlaybackCapture` for media, game, and unknown usages. Android requires fresh playback-capture approval for each session, and the source player must permit capture. See [Android's playback-capture requirements](https://developer.android.com/media/platform/av-capture).
- Streaming **US English** recognition with the Vosk small Android model, downloaded on demand (about 40 MB; not bundled in the APK).
- On-device English-to-Arabic translation using [Google ML Kit](https://developers.google.com/ml-kit/language/translation/android). Its model must be prepared over Wi-Fi before translation works; translation then runs on-device.

The overlay itself is text-only and does not provide drag, pause, or hide buttons. Pause/resume and stop controls are available in the foreground notification/system task controls.

## Latency and streaming limits

This is near-live, not zero-latency. Audio is read in 1,024-sample blocks at 16 kHz (about 64 ms per block), with a four-second Android capture buffer to absorb brief recognizer or scheduling stalls; each positive `AudioRecord.read` result is passed to Vosk. The scheduler accepts one-word partials and enforces a 350 ms minimum interval between partial translation starts. One in-flight ML Kit task, one replaceable latest partial, and a FIFO of at most 32 final segments bound queued work; final phrases are serialized ahead of partials. Translation/model speed, Vosk hypothesis timing, and rendering add device-dependent delay; interim hypotheses can be superseded, and the single subtitle line displays only the newest recognized text. Final recognition results bypass the partial cadence but wait for the active ML Kit task. There is no guaranteed end-to-end latency, and sustained recognizer overload can still exhaust Android's capture buffer.

## Install and use

1. Build and install the ARM64 debug APK, or open this folder in Android Studio and run the `app` configuration.
2. Open **Luma Subtitles** and download the English speech model (about 40 MB). Permit Wi-Fi for the first Arabic translation-model download.
3. Tap **Start floating subtitles**. Grant Android's audio permission, allow display over other apps, and approve the system playback-capture prompt. Android asks for playback-capture approval again for every new session.
4. Start an English video in another app. Captions appear as translated partials are ready. Choose size, typeface, and color in Luma before starting a session.

Luma does not silently fall back to microphone capture. If no audio is detected, check that playback is active and unmuted; the source app may block capture. Protected or otherwise restricted streams cannot be bypassed.

## Build

Requirements: JDK 17 or newer, Android SDK Platform 35, and an internet connection for Gradle dependencies.

```bash
export ANDROID_HOME="$HOME/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
./gradlew clean assembleDebug
```

The project targets Android SDK 35 and supports Android 10 (API 29) or later. ABI-specific APKs are produced under `app/build/outputs/apk/debug/`; `app-arm64-v8a-debug.apk` is the ARM64 build for most current Android phones.

## Known limits

- Speech recognition supports **English input only**. Accuracy depends on the recording, accent, background music/noise, and device performance. The Vosk model is roughly 40 MB on disk and uses more memory while running.
- Playback capture requires `RECORD_AUDIO`, user-approved `MediaProjection` consent, and a source player that allows capture in the same Android user profile. Apps may opt out or apply stricter policies; DRM/protected playback and some conferencing or streaming apps may yield silence. Luma never switches to microphone capture.
- Translation requires an initial Arabic model download over Wi-Fi and sufficient storage. Until it is ready, or if translation fails, the overlay stays blank rather than displaying English.
- Long Arabic translations may be clipped with an ellipsis to preserve the one-line layout.
- Captured audio is processed locally and is not saved or uploaded. Luma does not inspect video frames, on-screen text, or embedded subtitles.
- This workspace has no connected Android handset or emulator. Permission prompts, device-specific overlay behavior, actual speech/translation latency, recognition quality, whether sustained load overruns the four-second audio buffer, and battery use still require real-device testing.

## Implementation notes

The app is plain Java with native Android views and services. A foreground `mediaProjection` service owns playback capture; Vosk consumes local PCM and emits partial/final English hypotheses; ML Kit translates bounded partial snapshots and final segments on-device; a `TYPE_APPLICATION_OVERLAY` window renders the single transparent Arabic line. The speech and translation models are downloaded only when needed and stored by the app/libraries.
