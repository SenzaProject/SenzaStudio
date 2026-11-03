# Senza Studio - Architecture (Phase 1)

```
app  ->  feature-*  ->  core-design  ->  audio-engine  <-  audio-dsp
```

| Module | Type | Responsibility |
|---|---|---|
| `audio-engine` | pure Kotlin/JVM | `AudioEngine` interface, parameter model (schema v1), thread-safe `ParameterStore`, capability reporting. No Android or Compose imports (enforced by `ArchitectureTest`). |
| `audio-dsp` | pure Kotlin/JVM | Phase 2 DSP: RBJ biquad EQ, compressor, look-ahead limiter, master gain, `DspChain` (scrub -> EQ -> compressor -> gain -> limiter). Float stereo-interleaved, no allocation or locks in `process`. Not yet connected to a live audio path (Phase 4). |
| `core-design` | Android lib | Theme, tokens, shared components. Depends on `audio-engine` only to render `ModuleCapability` honestly. |
| `feature-*` | Android lib | Stateless screens: Studio, Equalizer, Spatial, Presets, Settings. |
| `app` | Android app | Activity, navigation (`Destination`), `EngineViewModel`, manual DI (`AppContainer`). |

## Rules
- UI never touches DSP; it reads `AudioEngine.state/parameters` and sends `updateParameters`.
- All parameters pass through `sanitized()` (range + NaN/Inf protection) inside `ParameterStore`.
- Phase 1 engine is `NoOpAudioEngine`: no DSP, no audio touched, reports every module as unavailable.
- No controls that pretend to process audio; screens are read-only until the DSP exists.

## Planned modules (not created yet, to avoid empty placeholders)
`native-dsp` (only if profiling in P6 justifies C++), `audio-device` (P4), `audio-visualizer` (P5), `audio-presets` / `audio-profiles` (with the DSP phases).

## DSP parameter reference (Phase 2)
| Module | Parameter | Range | Default |
|---|---|---|---|
| Master | gain | -24 .. +6 dB | 0 |
| EQ | band freq / gain / Q | 20-20000 Hz / +-15 dB / 0.1-18 | 10 ISO bands, flat, EQ disabled |
| Compressor | threshold / ratio / attack / release / knee / makeup | -60..0 dB / 1..20 / 0.1-200 ms / 5-2000 ms / 0-24 dB / 0-24 dB | disabled, -18 dB, 2:1, 10 ms, 120 ms, 6 dB, 0 |
| Limiter (always on) | ceiling / release | -12..0 dB / 10-1000 ms | -0.3 dB, 80 ms, look-ahead 2 ms |

Parameter schema is now **v2** (adds dynamics). Nothing is persisted yet, so no migration is needed.

## Known P2 limitations
- Coefficient changes are not cross-faded (possible small clicks on abrupt changes).
- Compressor uses per-sample log/pow; fine on JVM tests, to be profiled/optimised in Phase 6.
- Stereo interleaved float only; mono and other layouts are handled in Phase 4.
