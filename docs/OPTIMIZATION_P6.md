# P6 - Optimization (callback profiling, CPU, memory, battery, latency, lifecycle)

Exit criteria from the roadmap: **no blocking/allocation in the critical callback; stable soak tests.**

## 1. Findings in P5 (analysis before changes)

| # | Where | Problem | Cost / risk |
|---|---|---|---|
| 1 | `Limiter.process` | Scanned the whole look-ahead ring (97 values at 48 kHz) for every sample to find the minimum | ~4.7 M comparisons per second of audio, always on |
| 2 | `Compressor.process` | `log10` and `pow` on every sample, even for silence and quiet passages | The most expensive per-sample call in the chain |
| 3 | `TruePeakMeter.push` | 72 multiply-adds per sample (3 branches x 12 taps x 2 channels) even when the held peak can no longer change | Always on (diagnostics depend on it) |
| 4 | `LocalPlayer` | Decode, DSP and the blocking `AudioTrack.write` ran on ONE thread; a slow decode block starved the output | Underruns on slow storage / busy phones |
| 5 | `LocalPlayer.render` | `FloatArray` + `MeterSnapshot` allocated on the audio thread every 40 ms | Violates "no allocation in the callback"; GC pauses |
| 6 | audio thread | Default thread priority | Preemption by UI work |
| 7 | `DspChain` | 2048-point spectrum FFT ran even when the app was in the background | Battery |
| 8 | `SpectrumAnalyzer.reset` | Allocated a new array (and was reachable from the audio path once the spectrum can be toggled) | Allocation |
| 9 | meters, bass, spatial | Exponentially decaying states were never flushed | Denormal slowdowns after minutes of digital silence on some CPUs |
| 10 | `Limiter` | Running mean updated incrementally forever | Rounding drift over hours |
| 11 | `AudioTrack` | Default performance mode (low-latency bias) for music | Battery |

## 2. Changes

**DSP (`:audio-dsp`)**
- Limiter: sliding minimum with a monotonic deque, amortised O(1) per sample. Output is identical to P5 (`OptimizationEquivalenceTest` compares against a verbatim copy, bit for bit over 8 s, within 1e-5 across the periodic re-sum). The running mean is re-summed exactly every 2^19 samples (no drift).
- Compressor: below the knee the target reduction is exactly 0 dB, so `log10` is skipped (linear threshold compare) and, once the reduction has released, the cached make-up gain is used (no `pow`). The reduction snaps to 0 below 1e-12 dB (also removes the denormal tail).
- True-peak meter: the interpolated value can never exceed `GAIN_BOUND x` (largest of the 12 history samples). If that bound cannot beat the held maximum the 72 MACs are skipped; the reading is unchanged.
- `SmoothedGain` (master gain): settled gain at 0 dB is a no-op; other settled gains are a plain multiply.
- Denormal flush at block end for level meter, stereo meter, bass envelopes and spatial filter states.
- `FloatRing`: new lock-free single-producer/single-consumer ring (`FloatRingTest` includes a 3 M-float two-thread integrity test).
- `DspChain.spectrumEnabled`: the FFT only runs while a UI shows it; switching off clears the bars once (allocation-free `reset`).

**Android (`:audio-android`)**
- `LocalPlayer` now has two threads joined by a 16 384-frame (~340 ms at 48 kHz) `FloatRing`: `senza-decode` (extractor, codec, PCM convert -> ring) and `senza-audio` (`THREAD_PRIORITY_URGENT_AUDIO`; ring -> `DspChain` -> blocking write). The ring is upstream of the DSP, so parameter changes still reach the output after only the `AudioTrack` buffer, and the reported latency is still honest (buffer + look-ahead).
- Playback starts after a 4 096-frame pre-buffer; an empty ring mid-stream is counted once per episode as a **decoder starvation**.
- Meters, positions and diagnostics are published by a main-thread ticker that only reads volatile state: no allocation on the audio thread. The state callback is serialised, so the ticker can never overwrite the final ENDED / IDLE / ERROR state.
- Per-block DSP time is measured (`System.nanoTime`, no allocation): `dspLoadPercent` (0.5 s average) and worst block load are shown in Studio -> Live diagnostics.
- While the app is not visible (`MainActivity.onStop`): spectrum off, ticker at 4 Hz / 1 Hz for diagnostics. Returning shows fresh bars immediately. Paused: ticker slows, decoder wakes every 50 ms.
- `AudioTrack.PERFORMANCE_MODE_POWER_SAVING`.

## 3. Tests (all run in CI)

| Test | What it proves |
|---|---|
| `RealtimeSafetyTest` | `DspChain.process` allocates <= 256 bytes over 4 000 blocks with every module on, also while parameters change every 50 blocks and the spectrum toggles (`ThreadMXBean` allocated-bytes, measured inside `process` only) |
| `SoakTest` | 5 min at 48 kHz + 1 min at 44.1 kHz + 1 min at 96 kHz of program material (noise, bass tone, silences, +5 dB over-full-scale bursts) with random full parameter sets every 1-2 s: no NaN/Inf, never above the limiter ceiling, unity gain afterwards; 60 s of digital silence leaves meters at the floor |
| `OptimizationEquivalenceTest` | Optimised limiter/compressor/gain/true-peak match the P5 algorithms |
| `FloatRingTest` | Wrap-around, no overwrite of unread data, exact sequence across threads |
| `P6MeasurementReportTest` | Writes `audio-dsp/build/reports/p6-measurements.md` (CPU per module, P5 vs P6 cost); asserts only that the full chain runs >= 5x real time |

## 4. What this does NOT show (honest limits)

- The CI numbers come from a JVM on a VM. They rank modules and revisions; they are not phone numbers. The phone number is **DSP load** in the diagnostics card (see `DEVICE_TEST_CHECKLIST.md`, P6 section).
- No native (C++/NDK) DSP was added. The roadmap allows it only if profiling justifies it; decide from the on-device DSP-load numbers. If the worst-block load on the target phones stays far below 100 %, the Kotlin path stays.
- There is still no foreground service / media session: playback is meant for the foreground app (`ANDROID_INTEGRATION.md`). This is deferred to P7 together with the release work, because it needs manifest permissions, a notification and a media session that must be tested on devices. Pausing the UI does not stop playback, but the OS may stop the process.
- Tests that need `AudioTrack`/`MediaCodec` cannot run in CI; the threading in `LocalPlayer` is verified by the device checklist.
- Parameter changes and scene switches still apply without cross-fading (known P3 limitation).
