# Senza Studio - Architecture (Phase 1)

```
app  ->  feature-*  ->  core-design  ->  audio-engine

native/senzafx  (C++ DSP core, shared by the HAL effect library; independent of Gradle)
```

| Module | Type | Responsibility |
|---|---|---|
| `audio-engine` | pure Kotlin/JVM | `AudioEngine` interface, parameter model (schema v1), thread-safe `ParameterStore`, capability reporting. No Android or Compose imports (enforced by `ArchitectureTest`). |
| `native/senzafx` | header-only C++17 | DSP core: RBJ biquad EQ, compressor, look-ahead limiter, master gain, `Chain`, POD `Params` (schema v2). Lock-free triple-buffer config hand-off; `Process()` allocation-free (tested). Built/tested by CMake in CI. |
| `core-design` | Android lib | Theme, tokens, shared components. Depends on `audio-engine` only to render `ModuleCapability` honestly. |
| `feature-*` | Android lib | Stateless screens: Studio, Equalizer, Spatial, Presets, Settings. |
| `app` | Android app | Activity, navigation (`Destination`), `EngineViewModel`, manual DI (`AppContainer`). |

## Rules
- UI never touches DSP; it reads `AudioEngine.state/parameters` and sends `updateParameters`.
- All parameters pass through `sanitized()` (range + NaN/Inf protection) inside `ParameterStore`.
- Phase 1 engine is `NoOpAudioEngine`: no DSP, no audio touched, reports every module as unavailable.
- No controls that pretend to process audio; screens are read-only until the DSP exists.

## Planned modules (not created yet, to avoid empty placeholders)
`senzafx_effect` (Android audio effect library wrapper, Phase 4), `audio-device` (P4), `audio-visualizer` (P5), `audio-presets` / `audio-profiles` (with the DSP phases).

## DSP parameter reference (Phase 2)
| Module | Parameter | Range | Default |
|---|---|---|---|
| Master | gain | -24 .. +6 dB | 0 |
| EQ | band freq / gain / Q | 20-20000 Hz / +-15 dB / 0.1-18 | 10 ISO bands, flat, EQ disabled |
| Compressor | threshold / ratio / attack / release / knee / makeup | -60..0 dB / 1..20 / 0.1-200 ms / 5-2000 ms / 0-24 dB / 0-24 dB | disabled, -18 dB, 2:1, 10 ms, 120 ms, 6 dB, 0 |
| Limiter (always on) | ceiling / release | -12..0 dB / 10-1000 ms | -0.3 dB, 80 ms, look-ahead 2 ms |

Parameter schema is now **v2** (adds dynamics). Nothing is persisted yet, so no migration is needed.

## Decision: system-wide processing via a HAL effect library (target X6882 / SenzaOS)
The DSP therefore lives in C++ (it must run inside audioserver as a `.so`), not Kotlin. The Kotlin
`:audio-engine` keeps only the parameter model, state and UI contract; its ranges mirror `senzafx/params.h`
and must be changed together (schema v2). See `docs/HAL_PLAN.md`.

## Known P2 limitations
- Coefficient changes are not cross-faded (possible small clicks on abrupt changes).
- Compressor uses per-sample log/pow; measured 0.8% of one x86 core for EQ+compressor+limiter at 48 kHz, not yet measured on Helio G100.
- Stereo interleaved float only; mono and other layouts are handled in Phase 4.
