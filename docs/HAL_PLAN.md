# System-wide processing via HAL effect library (plan for Phase 4)

Chosen target: ship `libsenzafx.so` inside SenzaOS so audioserver applies Senza FX to all output.

## Pieces
1. **Effect library** (`senzafx_effect`): legacy audio-effect interface (`audio_effect_library_t`, exported
   `AUDIO_EFFECT_LIBRARY_INFO_SYM`), one effect with its own UUID. `process()` calls `senzafx::Chain::Process`;
   `command()` handles `EFFECT_CMD_INIT/CONFIGURE/ENABLE/DISABLE/RESET/SET_PARAM`.
2. **Parameter transport**: the app sends `senzafx::Params` (POD blob) as an effect parameter; the effect copies
   it and calls `Chain::Configure` on the control thread. `schema` is checked first; unknown schema is rejected.
3. **Registration**: `audio_effects.xml` entry (library + effect UUID), attached as a global output
   (`postprocess`) so it applies after other effects.
4. **Packaging/SELinux**: library under the vendor/system soundfx directory with the correct file context,
   plus `file_contexts` and `fs_config` entries (same procedure as the Dolby port).

## Unknowns that must be checked on the real ROM before coding the wrapper
- Android version and where the active `audio_effects.xml` is read from on X6882 (vendor vs system, MTK overrides).
- Whether the Transsion/Dolby (DAX) chain is also a global postprocess effect and how ordering is decided.
- Sample formats actually delivered to the effect (float vs 16-bit, channel mask); the chain is stereo float now.
- Whether SELinux allows the app process to bind to the effect (needs a policy rule if not).

Not implemented yet: no AOSP headers are available in this workspace, so the wrapper is written and tested on the
device/CI against the real headers in Phase 4, not guessed here.
