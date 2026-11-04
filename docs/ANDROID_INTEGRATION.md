# Senza Studio - Phase 4 (Android integration)

## What is processed, and where
| Path | Status | What it means |
|---|---|---|
| Built-in player | Experimental | Files opened in the app are decoded (MediaCodec), run through the full `DspChain` (EQ, bass, compressor, spatial, imaging, loudness, limiter) and played through a float `AudioTrack`. The audio session belongs to this app, so every module really applies. "Experimental" until device QA (Phase 6/7). |
| Other apps' sessions | Experimental, opt-in | `DynamicsProcessing` is attached to sessions that other players announce with `ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION`. Needs a cooperating player, the app running, and applies only an EQ approximation, input gain and a limiter. Bass, spatial, imaging, compressor and loudness are listed in Settings as "enabled but NOT applied". |
| Global mix (session 0) | Not available | Deprecated and ignored on modern Android. Never used. |
| System-wide (OEM/root) | Not available | Needs privileges a normal app does not have. |

Availability is decided by `CompatibilityEvaluator` (pure Kotlin, unit-tested) from real facts: API level, audio feature flags, native sample rate/buffer, the connected outputs and a real attempt to create a `DynamicsProcessing` instance on a throw-away session.

## Audio session strategy (`LiveAudioEngine`)
- Requests `AUDIOFOCUS_GAIN` before playing. Permanent loss stops playback; transient loss pauses and resumes only if it was playing; "can duck" lowers the volume to 20 %.
- Pauses on `ACTION_AUDIO_BECOMING_NOISY` (headphones unplugged / Bluetooth output gone).
- Output is float stereo; mono sources are duplicated; more than 2 channels is rejected with a clear message.
- A DSP exception restores the unprocessed block, bypasses DSP for the rest of the track, counts a failure and flips the engine to `FAILED` so the UI says so.

## Device routing and profiles
- `DeviceMonitor` lists connected outputs and updates on plug/unplug (`AudioDeviceCallback`).
- Android does not tell an app which device the system mix uses. While the built-in player runs, its route is **confirmed** (`AudioTrack.getRoutedDevice`); otherwise the UI shows a **likely** route (USB > Bluetooth > wired > HDMI > other > speaker > earpiece) and labels it as a guess.
- `DeviceProfiles` keeps one parameter set per output type (speaker, wired, Bluetooth, USB, HDMI, other) and switches when the active type changes (toggle in Settings). Profiles live in memory for now; saving across restarts arrives with presets.

## Diagnostics shown while playing
Sample rate, output buffer, estimated latency (buffer + DSP look-ahead only; hardware/driver/Bluetooth delay is not measurable and is stated as excluded), underruns, clipped blocks, DSP failures, output peak, momentary LUFS, phase correlation.

## Known P4 limitations
- No background playback service: playback is meant for the foreground app. A foreground service, notification and media session are Phase 6 (lifecycle).
- Decode, DSP and `AudioTrack.write` share one worker thread; a decoupled ring buffer and callback-based path are Phase 6.
- No seeking, no playlist, one audio format per file.
- Session effects need cooperating players and do not survive the app process being killed.
- Only Kotlin pure logic (compatibility, device mapping, profiles, session plan, PCM conversion) has unit tests; the Android classes were written without a compiler or device in the authoring environment and need the CI build plus the checklist in `DEVICE_TEST_CHECKLIST.md`.
