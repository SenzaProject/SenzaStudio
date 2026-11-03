// Stereo processing chain: scrub -> EQ -> compressor -> master gain -> limiter (always on).
//
// Threading: Configure() may be called from control threads (serialised by a mutex, never taken on the
// audio thread). Process() runs on exactly one audio thread; it is lock-free and allocation-free and picks
// up the newest configuration through a triple buffer.
#pragma once
#include <atomic>
#include <cmath>
#include <mutex>

#include "senzafx/dsp.h"
#include "senzafx/params.h"

namespace senzafx {

class Chain {
 public:
  explicit Chain(int sample_rate)
      : fs_(sample_rate), compressor_(sample_rate), master_(sample_rate), limiter_(sample_rate) {
    Build(slots_[0], DefaultParams());
    Apply(slots_[0]);
  }

  int latency_samples() const { return limiter_.latency_samples(); }
  double gain_reduction_db() const { return compressor_.gain_reduction_db(); }

  // Control thread. Sanitises a copy of the parameters.
  void Configure(const Params& params) {
    std::lock_guard<std::mutex> lock(mu_);
    Params p = params;
    Sanitize(p);
    Build(slots_[back_], p);
    const int old = mailbox_.exchange(back_ | kDirty, std::memory_order_acq_rel);
    back_ = old & 3;
  }

  // Audio thread. Interleaved stereo float, in place.
  void Process(float* buf, int frames) {
    if (mailbox_.load(std::memory_order_acquire) & kDirty) {
      const int old = mailbox_.exchange(front_, std::memory_order_acq_rel);
      front_ = old & 3;
      Apply(slots_[front_]);
    }
    for (int i = 0; i < frames * 2; ++i) {
      if (!std::isfinite(buf[i])) buf[i] = 0.f;
    }
    if (eq_enabled_) {
      for (int b = 0; b < kMaxBands; ++b) {
        if (eq_[b].active()) eq_[b].Process(buf, frames);
      }
    }
    if (comp_enabled_) compressor_.Process(buf, frames);
    master_.Process(buf, frames);
    limiter_.Process(buf, frames);
  }

 private:
  struct Config {
    bool eq_enabled = false;
    bool band_active[kMaxBands] = {};
    BiquadCoeffs band[kMaxBands];
    bool comp_enabled = false;
    double threshold = -18, ratio = 2, attack = 10, release = 120, knee = 6, makeup = 0;
    double master_db = 0, ceiling_db = -0.3, limiter_release = 80;
  };
  static constexpr int kDirty = 4;

  void Build(Config& c, const Params& p) const {
    c.eq_enabled = p.eq_enabled != 0;
    for (int i = 0; i < kMaxBands; ++i) {
      c.band_active[i] = false;
      if (i >= p.band_count) continue;
      const EqBand& b = p.bands[i];
      const bool pass = b.type == kHighPass || b.type == kLowPass;
      if (!pass && std::fabs(b.gain_db) < 0.01f) continue;  // flat band: bypass
      c.band[i] = DesignBiquad(b.type, fs_, b.freq_hz, b.gain_db, b.q);
      c.band_active[i] = true;
    }
    c.comp_enabled = p.comp_enabled != 0;
    c.threshold = p.comp_threshold_db; c.ratio = p.comp_ratio; c.attack = p.comp_attack_ms;
    c.release = p.comp_release_ms; c.knee = p.comp_knee_db; c.makeup = p.comp_makeup_db;
    c.master_db = p.master_gain_db; c.ceiling_db = p.limiter_ceiling_db; c.limiter_release = p.limiter_release_ms;
  }

  void Apply(const Config& c) {
    eq_enabled_ = c.eq_enabled;
    for (int i = 0; i < kMaxBands; ++i) {
      if (c.band_active[i]) eq_[i].Set(c.band[i]); else eq_[i].Bypass();
    }
    if (!c.comp_enabled && comp_enabled_) compressor_.Reset();
    comp_enabled_ = c.comp_enabled;
    compressor_.Configure(c.threshold, c.ratio, c.attack, c.release, c.knee, c.makeup);
    master_.SetDb(c.master_db);
    limiter_.Configure(c.ceiling_db, c.limiter_release);
  }

  int fs_;
  StereoBiquad eq_[kMaxBands];
  Compressor compressor_;
  SmoothedGain master_;
  Limiter limiter_;
  bool eq_enabled_ = false, comp_enabled_ = false;

  Config slots_[3];
  int front_ = 0;                 // audio thread
  int back_ = 1;                  // control thread (under mu_)
  std::atomic<int> mailbox_{2};   // spare slot index, bit 2 = new data
  std::mutex mu_;
};

}  // namespace senzafx
