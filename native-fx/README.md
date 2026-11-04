# libsenzastudiofx — efek audio global SenzaStudio

Legacy effect library (`audio_effects.xml`). UUID type `5999534d-94c0-45f5-804f-20ae5f93ec2e`,
UUID impl `eda64964-49db-43f9-9629-a57e65342478`. Stereo, PCM 16-bit atau float, 8–192 kHz.

Chain: scrub NaN → EQ (16 band) → bass → compressor → imaging (M/S) → master gain → limiter 2 ms (selalu aktif).
BELUM diport: spatial, loudness normalizer.

Semua instance di satu proses berbagi satu parameter store. SET_PARAM ke instance mana pun mengubah semuanya.
Default `globalEnabled = 0` → bypass bit-exact.

## Protokol parameter (little-endian). param = int32 key [+ int32 index]
| key | nama | param | value |
|---|---|---|---|
| 0x01 | GLOBAL_ENABLE | key | int32 0/1 |
| 0x02 | MASTER_GAIN | key | float dB (−24..+6) |
| 0x10 | EQ_ENABLE | key | int32 |
| 0x11 | EQ_BAND | key, idx(0..15) | int32 type(0 peak,1 lowshelf,2 highshelf,3 hp,4 lp), float Hz, float dB, float Q (16 B) |
| 0x12 | EQ_MASK | key | uint32 bit band terpakai |
| 0x20 | BASS | key | int32 on, float subDb, harmonics, punch, crossoverHz (20 B) |
| 0x30 | COMP | key | int32 on, thr, ratio, attackMs, releaseMs, kneeDb, makeupDb (28 B) |
| 0x40 | LIMITER | key | float ceilingDb, releaseMs (8 B) |
| 0x50 | IMAGING | key | int32 on, midDb, sideDb, balance, monoBelowHz (20 B) |
| 0xF0 | PROTOCOL (GET) | key | int32 = 1 |
| 0xF1 | STATS (GET) | key | uint32 instance aktif, uint32 jumlah blok diproses |

Semua nilai di-sanitize (NaN/Inf/di luar rentang) di sisi native, rentangnya sama dengan Kotlin.

## Uji host
`cmake -S . -B build-host && cmake --build build-host && ctest --test-dir build-host --output-on-failure`
(test_dsp: ASAN+UBSAN, test_effect: ASAN+UBSAN, test_race: TSAN).

## Build Android
Lewat GitHub Actions (`.github/workflows/build-libsenzastudiofx.yml`): arm64-v8a + armeabi-v7a, varian `normal`
dan `smoketest` (mulai aktif dengan sub-bass +9 dB agar terdengar tanpa aplikasi).
Atau di source tree ROM: `Android.bp` (pakai header AOSP asli).
