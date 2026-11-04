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
