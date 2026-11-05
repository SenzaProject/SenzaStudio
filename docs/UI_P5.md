# Senza Studio - Phase 5 (Live UI, meters, presets)

Every control edits the engine through one path: the UI sends a transformation of the current
`EngineParameters`, the engine sanitizes the result, and the screens redraw from the sanitized state.
So a slider can never show a value the DSP does not use (for example "Mono below" snaps to Off below 20 Hz).

## Screens
| Screen | Contents |
|---|---|
| Studio | Engine status, built-in player, **output meters** (L/R peak + RMS bars, momentary/short-term LUFS, true-peak estimate, phase correlation, compressor reduction, normalizer gain), **32-band spectrum**, Master (A/B bypass, master gain, limiter ceiling), Dynamic master (compressor, loudness normalizer), output device, live diagnostics, module availability. |
| Equalizer | On/off, **live response graph** (same filter design as the real chain), 10 band sliders (+-12 dB), Reset to flat, Bass (sub-bass, harmonics, punch, low-band edge). |
| Spatial | Spatial 360 (on/off, 6 scenes with descriptions, intensity, width) and Stereo imaging (mid gain, side gain, balance, mono below). |
| Presets | 9 factory presets, save current settings under a name, apply, delete (with confirmation), "Active" marker when the current settings equal a preset, files that could not be read are listed. |
| Settings | Compatibility report, device facts, output devices, device profiles, experimental session effects, reset all settings, about. |

## Persistence
- Current settings are autosaved 0.5 s after the last change and restored at start-up (restore happens before autosave starts, so defaults never overwrite saved data).
- Per-output-type profiles (speaker, wired, Bluetooth, USB, HDMI, other) are saved too.
- Format: versioned `key=value` text (`PresetCodec`, format 1). Missing keys use defaults, unknown keys are ignored, invalid values fall back with a warning, values are clamped, files from a newer format are rejected. Writes are atomic (temp file + rename).

## Errors shown to the user
A banner above the navigation bar reports: playback errors, engine/DSP failure, session-effect errors, settings that cannot be restored or saved, preset save/delete failures. Errors stay until dismissed; confirmations fade after 3.5 s.

## Metering details
- Meters are computed on the output of the chain (after the limiter), published about 25 times per second.
- Spectrum: mono sum, 2048-point Hann FFT every 1024 frames, 32 log bands 20 Hz - 20 kHz, each band shows its strongest bin as sine amplitude in dBFS (a full-scale sine reads about 0 dB, within 1.4 dB window scalloping). Bands rise instantly and fall at 30 dB/s.
- Peak bars fall at 20 dB/s; RMS is a 300 ms mean.
- Only the meter cards recompose at 25 Hz; the rest of the UI does not.

## Parameter schema v4
Adds `bypass` (A/B switch: skips everything except the always-on limiter). Presets written by earlier builds are not affected because the preset format is new in this phase.

## Known P5 limitations
- Bypass and module switches are not cross-faded (a small click is possible).
- The EQ has band-gain sliders only; frequency and Q are fixed to the 10 graphic bands.
- No preset import/export or renaming yet.
- Settings and meters cover the built-in player only; other apps' audio is not touched.
- The Compose UI and Android classes were written without a compiler or device in the authoring environment; the CI build and a manual pass are the first real test.

## Manual checklist
1. Studio: play a file; meters and spectrum move; a 1 kHz test tone lights the matching spectrum band.
2. Bypass on/off while playing: sound changes; the Studio banner warns that only the limiter is active.
3. Equalizer: move a band: the graph curve and the sound change; "Reset to flat" zeroes all bands.
4. Spatial: switch scenes; Studio phase correlation stays above 0 for normal music; "Mono below" at 120 Hz keeps bass centred.
5. Presets: apply "Bass Boost": EQ, Bass and master gain controls update; "Active" marker shows; save a custom preset, close the app, reopen: settings and preset are still there; delete the preset.
6. Reset all settings (Settings): everything returns to defaults, saved presets stay.
7. Try opening a non-audio file: an error banner appears and the app keeps running.
