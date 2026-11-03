// Senza FX parameter block (schema v2). Plain-old-data on purpose: it can be memcpy'd
// through an Android effect command (effect_param_t), JNI or a binder blob unchanged.
// Ranges mirror the Kotlin model in :audio-engine (EngineParameters.sanitized()).
#pragma once
#include <cmath>
#include <cstdint>

namespace senzafx {

constexpr int kMaxBands = 16;
constexpr uint32_t kParamsSchema = 2;

enum FilterType : int32_t { kPeaking = 0, kLowShelf = 1, kHighShelf = 2, kHighPass = 3, kLowPass = 4 };

struct EqBand {
  float freq_hz;
  float gain_db;
  float q;
  int32_t type;
};

struct Params {
  uint32_t schema;
  float master_gain_db;
  int32_t eq_enabled;
  int32_t band_count;
  EqBand bands[kMaxBands];
  int32_t comp_enabled;
  float comp_threshold_db;
  float comp_ratio;
  float comp_attack_ms;
  float comp_release_ms;
  float comp_knee_db;
  float comp_makeup_db;
  float limiter_ceiling_db;
  float limiter_release_ms;
};

namespace detail {
inline float FiniteOr(float v, float fallback) { return std::isfinite(v) ? v : fallback; }
inline float Clamp(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }
}  // namespace detail

inline Params DefaultParams() {
  static const float kIso[10] = {31.25f, 62.5f, 125.f, 250.f, 500.f, 1000.f, 2000.f, 4000.f, 8000.f, 16000.f};
  Params p{};
  p.schema = kParamsSchema;
  p.master_gain_db = 0.f;
  p.eq_enabled = 0;
  p.band_count = 10;
  for (int i = 0; i < kMaxBands; ++i) p.bands[i] = EqBand{1000.f, 0.f, 1.f, kPeaking};
  for (int i = 0; i < 10; ++i) p.bands[i].freq_hz = kIso[i];
  p.comp_enabled = 0;
  p.comp_threshold_db = -18.f;
  p.comp_ratio = 2.f;
  p.comp_attack_ms = 10.f;
  p.comp_release_ms = 120.f;
  p.comp_knee_db = 6.f;
  p.comp_makeup_db = 0.f;
  p.limiter_ceiling_db = -0.3f;
  p.limiter_release_ms = 80.f;
  return p;
}

// Clamp every field into its legal range; NaN/Inf fall back to defaults.
inline void Sanitize(Params& p) {
  using detail::Clamp;
  using detail::FiniteOr;
  p.schema = kParamsSchema;
  p.master_gain_db = Clamp(FiniteOr(p.master_gain_db, 0.f), -24.f, 6.f);
  p.eq_enabled = p.eq_enabled != 0;
  if (p.band_count < 0) p.band_count = 0;
  if (p.band_count > kMaxBands) p.band_count = kMaxBands;
  for (int i = 0; i < kMaxBands; ++i) {
    EqBand& b = p.bands[i];
    b.freq_hz = Clamp(FiniteOr(b.freq_hz, 1000.f), 20.f, 20000.f);
    b.gain_db = Clamp(FiniteOr(b.gain_db, 0.f), -15.f, 15.f);
    b.q = Clamp(FiniteOr(b.q, 1.f), 0.1f, 18.f);
    if (b.type < kPeaking || b.type > kLowPass) b.type = kPeaking;
  }
  p.comp_enabled = p.comp_enabled != 0;
  p.comp_threshold_db = Clamp(FiniteOr(p.comp_threshold_db, -18.f), -60.f, 0.f);
  p.comp_ratio = Clamp(FiniteOr(p.comp_ratio, 2.f), 1.f, 20.f);
  p.comp_attack_ms = Clamp(FiniteOr(p.comp_attack_ms, 10.f), 0.1f, 200.f);
  p.comp_release_ms = Clamp(FiniteOr(p.comp_release_ms, 120.f), 5.f, 2000.f);
  p.comp_knee_db = Clamp(FiniteOr(p.comp_knee_db, 6.f), 0.f, 24.f);
  p.comp_makeup_db = Clamp(FiniteOr(p.comp_makeup_db, 0.f), 0.f, 24.f);
  p.limiter_ceiling_db = Clamp(FiniteOr(p.limiter_ceiling_db, -0.3f), -12.f, 0.f);
  p.limiter_release_ms = Clamp(FiniteOr(p.limiter_release_ms, 80.f), 10.f, 1000.f);
}

}  // namespace senzafx
