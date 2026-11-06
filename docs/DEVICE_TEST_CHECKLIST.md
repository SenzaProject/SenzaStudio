# P4 device test checklist (exit criterion: supported/unsupported paths reported accurately)

Run on a real phone with the CI debug APK. Note device model and Android version.

1. Settings -> "Processing paths": Built-in player = Experimental; Global mix and System-wide = Not available. Session effects = Experimental or "Not available" with a reason (never silently missing).
2. Device facts match the phone (low-latency flag, native rate/buffer).
3. Studio -> Open file (mp3/aac/flac/ogg, mono and stereo). Playback starts; status shows "Processing". Open a 5.1 file: clear error, no crash.
4. Studio -> output card shows the speaker; plug wired headphones / connect Bluetooth / USB DAC: the list and route update. While playing the chip says "Confirmed route"; when stopped it says "Likely route".
5. Unplug headphones while playing: playback pauses.
6. Start another music app: Senza pauses (focus loss). Play a short notification sound: volume ducks and recovers.
7. Diagnostics: underruns stay 0 on a normal track; "Clipped blocks" stays 0 with default settings.
8. Settings -> turn on session effects, play audio in a player that announces its session: it appears under "Attached sessions". With a player that does not: stays "none yet" (expected). Enabled-but-unsupported modules are listed.
9. Switch output type with auto-switch on: settings follow the type; with it off they do not.
10. Record failures with a screenshot of Settings and the Studio diagnostics card.

# P6 device checklist (exit criterion: stable soak, no audible glitches; numbers for the CPU/battery decision)

Use the CI debug APK. Note device model, Android version and output type for every result.

1. Play a 10-minute track with all modules on (EQ, bass, compressor, spatial Concert Hall, imaging, loudness). Studio -> Live diagnostics: write down **DSP load** and **worst block**; underruns and **Decoder starvations** should stay 0.
2. Same with a big FLAC/high-bitrate file from slow storage (SD card or a cloud-backed file): starvations may rise, but audio must not stutter unless starvations are non-zero.
3. Leave the app (Home) while playing for 5 minutes, come back: audio kept playing as long as the OS kept the process, the spectrum is live again immediately, no crash. (Without a foreground service the OS may stop playback; that is a known limit, not a failure of this item.)
4. Pause for 2 minutes with the screen on: no audible glitch on resume; battery stats show no abnormal drain.
5. 60-minute soak with the screen off if the process survives: no crash, diagnostics clipped blocks = 0 with default settings.
6. Rotate the screen and open/close Settings repeatedly while playing: no underruns, no UI freezes.
7. Change presets quickly while playing (10x): no crash; occasional soft click on abrupt changes is the documented limitation.
8. Record: DSP load (avg / worst) per device. If the worst block stays well below 100 % on all test phones, native DSP is not needed.
