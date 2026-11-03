// Core DSP blocks. Header-only, C++17, no exceptions/RTTI required.
// All Process() methods work in place on interleaved stereo float, never allocate and take no locks.
#pragma once
#include <algorithm>
#include <cmath>
#include <vector>

#include "senzafx/params.h"

namespace senzafx {

constexpr double kPi = 3.14159265358979323846;

inline double DbToLinear(double db) { return std::pow(10.0, db / 20.0); }
inline double LinearToDb(double x) { return 20.0 * std::log10(std::max(x, 1e-9)); }

struct BiquadCoeffs {
  double b0 = 1, b1 = 0, b2 = 0, a1 = 0, a2 = 0;  // a0 normalised to 1

  bool IsStable() const { return std::fabs(a2) < 1.0 && std::fabs(a1) < 1.0 + a2; }

  double MagnitudeDb(double freq_hz, double sample_rate) const {
    const double w = 2.0 * kPi * freq_hz / sample_rate;
    const double c1 = std::cos(w), s1 = std::sin(w), c2 = std::cos(2 * w), s2 = std::sin(2 * w);
    const double nr = b0 + b1 * c1 + b2 * c2, ni = -(b1 * s1 + b2 * s2);
    const double dr = 1.0 + a1 * c1 + a2 * c2, di = -(a1 * s1 + a2 * s2);
    return 20.0 * std::log10(std::max(std::sqrt((nr * nr + ni * ni) / (dr * dr + di * di)), 1e-12));
  }
};

// RBJ Audio-EQ-Cookbook designs (Q-based shelves). Frequency clamped below 0.45 * fs.
inline BiquadCoeffs DesignBiquad(int type, int sample_rate, double freq_hz, double gain_db, double q) {
  const double fs = sample_rate;
  const double f = std::min(std::max(freq_hz, 1.0), fs * 0.45);
  const double qq = std::max(q, 0.05);
  const double a = std::pow(10.0, gain_db / 40.0);
  const double w0 = 2.0 * kPi * f / fs, cosw = std::cos(w0), alpha = std::sin(w0) / (2.0 * qq);
  const double s = 2.0 * std::sqrt(a) * alpha;
  double b0, b1, b2, a0, a1, a2;
  switch (type) {
    case kLowShelf:
      b0 = a * ((a + 1) - (a - 1) * cosw + s);
      b1 = 2 * a * ((a - 1) - (a + 1) * cosw);
      b2 = a * ((a + 1) - (a - 1) * cosw - s);
      a0 = (a + 1) + (a - 1) * cosw + s;
      a1 = -2 * ((a - 1) + (a + 1) * cosw);
      a2 = (a + 1) + (a - 1) * cosw - s;
      break;
    case kHighShelf:
      b0 = a * ((a + 1) + (a - 1) * cosw + s);
      b1 = -2 * a * ((a - 1) + (a + 1) * cosw);
      b2 = a * ((a + 1) + (a - 1) * cosw - s);
      a0 = (a + 1) - (a - 1) * cosw + s;
      a1 = 2 * ((a - 1) - (a + 1) * cosw);
      a2 = (a + 1) - (a - 1) * cosw - s;
      break;
    case kHighPass:
      b0 = (1 + cosw) / 2; b1 = -(1 + cosw); b2 = (1 + cosw) / 2;
      a0 = 1 + alpha; a1 = -2 * cosw; a2 = 1 - alpha;
      break;
    case kLowPass:
      b0 = (1 - cosw) / 2; b1 = 1 - cosw; b2 = (1 - cosw) / 2;
      a0 = 1 + alpha; a1 = -2 * cosw; a2 = 1 - alpha;
      break;
    case kPeaking:
    default:
      b0 = 1 + alpha * a; b1 = -2 * cosw; b2 = 1 - alpha * a;
      a0 = 1 + alpha / a; a1 = -2 * cosw; a2 = 1 - alpha / a;
      break;
  }
  BiquadCoeffs c;
  c.b0 = b0 / a0; c.b1 = b1 / a0; c.b2 = b2 / a0; c.a1 = a1 / a0; c.a2 = a2 / a0;
  return c;
}

// Transposed direct form II, double-precision state.
class StereoBiquad {
 public:
  bool active() const { return active_; }
  void Set(const BiquadCoeffs& c) { c_ = c; active_ = true; }
  void Bypass() { active_ = false; Reset(); }
  void Reset() { z1l_ = z2l_ = z1r_ = z2r_ = 0.0; }

  void Process(float* buf, int frames) {
    const BiquadCoeffs c = c_;
    for (int i = 0; i < frames * 2; i += 2) {
      const double xl = buf[i];
      const double yl = c.b0 * xl + z1l_;
      z1l_ = c.b1 * xl - c.a1 * yl + z2l_;
      z2l_ = c.b2 * xl - c.a2 * yl;
      buf[i] = static_cast<float>(yl);
      const double xr = buf[i + 1];
      const double yr = c.b0 * xr + z1r_;
      z1r_ = c.b1 * xr - c.a1 * yr + z2r_;
      z2r_ = c.b2 * xr - c.a2 * yr;
      buf[i + 1] = static_cast<float>(yr);
    }
    if (std::fabs(z1l_) < 1e-30) z1l_ = 0;
    if (std::fabs(z2l_) < 1e-30) z2l_ = 0;
    if (std::fabs(z1r_) < 1e-30) z1r_ = 0;
    if (std::fabs(z2r_) < 1e-30) z2r_ = 0;
  }

 private:
  BiquadCoeffs c_;
  bool active_ = false;
  double z1l_ = 0, z2l_ = 0, z1r_ = 0, z2r_ = 0;
};

// Feed-forward stereo-linked compressor with soft knee.
class Compressor {
 public:
  explicit Compressor(int sample_rate) : fs_(sample_rate) {}

  void Configure(double threshold_db, double ratio, double attack_ms, double release_ms, double knee_db,
                 double makeup_db) {
    threshold_ = threshold_db;
    ratio_ = std::max(ratio, 1.0);
    knee_ = std::max(knee_db, 0.0);
    makeup_ = makeup_db;
    attack_coef_ = std::exp(-1.0 / (fs_ * std::max(attack_ms, 0.01) / 1000.0));
    release_coef_ = std::exp(-1.0 / (fs_ * std::max(release_ms, 0.01) / 1000.0));
  }
  void Reset() { gr_db_ = 0.0; }
  double gain_reduction_db() const { return gr_db_; }

  double ComputeOutputDb(double level_db) const {
    const double over = level_db - threshold_;
    if (2.0 * over < -knee_) return level_db;
    if (knee_ > 0.0 && 2.0 * std::fabs(over) <= knee_) {
      const double t = over + knee_ / 2.0;
      return level_db + (1.0 / ratio_ - 1.0) * t * t / (2.0 * knee_);
    }
    return threshold_ + over / ratio_;
  }

  void Process(float* buf, int frames) {
    for (int i = 0; i < frames * 2; i += 2) {
      const float l = buf[i], r = buf[i + 1];
      const double level_db = LinearToDb(std::max(std::fabs(l), std::fabs(r)));
      const double target = ComputeOutputDb(level_db) - level_db;  // <= 0
      const double coef = target < gr_db_ ? attack_coef_ : release_coef_;
      gr_db_ = coef * gr_db_ + (1.0 - coef) * target;
      const float g = static_cast<float>(DbToLinear(gr_db_ + makeup_));
      buf[i] = l * g;
      buf[i + 1] = r * g;
    }
  }

 private:
  int fs_;
  double threshold_ = -18, ratio_ = 2, knee_ = 6, makeup_ = 0, attack_coef_ = 0, release_coef_ = 0, gr_db_ = 0;
};

// One-pole (~10 ms) smoothed gain to avoid zipper noise.
class SmoothedGain {
 public:
  explicit SmoothedGain(int sample_rate) : coef_(1.0 - std::exp(-1.0 / (sample_rate * 0.010))) {}
  void SetDb(double db) { target_ = DbToLinear(db); }
  void Process(float* buf, int frames) {
    for (int i = 0; i < frames * 2; i += 2) {
      current_ += (target_ - current_) * coef_;
      const float g = static_cast<float>(current_);
      buf[i] *= g;
      buf[i + 1] *= g;
    }
  }

 private:
  double current_ = 1.0, target_ = 1.0, coef_;
};

// Look-ahead brick-wall peak limiter. With L = latency_samples(): g[j] = mean(m[j..j+L]),
// m[t] = min(r[t-L..t]), r[k] = min(1, ceiling/peak[k])  =>  g[j] <= r[j]  =>  |out| <= ceiling.
// The release one-pole only lowers gain relative to g; a final clamp covers rounding and
// ceiling changes while samples are in flight.
class Limiter {
 public:
  explicit Limiter(int sample_rate, double lookahead_ms = 2.0)
      : latency_(std::max(1, static_cast<int>(sample_rate * lookahead_ms / 1000.0))),
        size_(latency_ + 1),
        fs_(sample_rate),
        delay_(static_cast<size_t>(latency_) * 2, 0.f),
        r_ring_(static_cast<size_t>(size_), 1.f),
        m_ring_(static_cast<size_t>(size_), 1.f),
        m_sum_(size_),
        ceiling_(DbToLinear(-0.3)),
        release_coef_(std::exp(-1.0 / (sample_rate * 0.080))) {}

  int latency_samples() const { return latency_; }

  void Configure(double ceiling_db, double release_ms) {
    ceiling_ = DbToLinear(std::min(ceiling_db, 0.0));
    release_coef_ = std::exp(-1.0 / (fs_ * std::max(release_ms, 1.0) / 1000.0));
  }

  void Process(float* buf, int frames) {
    const float c = static_cast<float>(ceiling_);
    for (int i = 0; i < frames; ++i) {
      const float l = buf[i * 2], r = buf[i * 2 + 1];
      const float peak = std::max(std::fabs(l), std::fabs(r));
      r_ring_[pos_] = peak > c ? c / peak : 1.f;
      float m = 1.f;
      for (int k = 0; k < size_; ++k) m = std::min(m, r_ring_[k]);
      m_sum_ += static_cast<double>(m) - m_ring_[pos_];
      m_ring_[pos_] = m;
      const double g = m_sum_ / size_;
      const double rising = release_coef_ * smoothed_ + (1.0 - release_coef_);
      const double g_out = std::min(g, rising);
      smoothed_ = g_out;

      const size_t d = static_cast<size_t>(delay_pos_) * 2;
      const float out_l = delay_[d] * static_cast<float>(g_out);
      const float out_r = delay_[d + 1] * static_cast<float>(g_out);
      delay_[d] = l;
      delay_[d + 1] = r;
      buf[i * 2] = std::min(std::max(out_l, -c), c);
      buf[i * 2 + 1] = std::min(std::max(out_r, -c), c);

      if (++pos_ == size_) pos_ = 0;
      if (++delay_pos_ == latency_) delay_pos_ = 0;
    }
  }

 private:
  int latency_, size_, fs_;
  std::vector<float> delay_, r_ring_, m_ring_;
  double m_sum_;
  double ceiling_, release_coef_;
  int pos_ = 0, delay_pos_ = 0;
  double smoothed_ = 1.0;
};

}  // namespace senzafx
