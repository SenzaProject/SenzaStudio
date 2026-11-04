// SenzaStudio native DSP — C++ port of the Kotlin chain in audio-dsp (EQ, bass, compressor, imaging,
// master gain, limiter). Spatial and loudness-normalizer are NOT ported yet.
// Real-time rules: no allocation, no locks, no I/O in Chain::process / Chain::configure.
#pragma once
#include <cmath>
#include <cstdint>
#include <vector>

namespace senzafx {

constexpr int kMaxBands = 16;
enum FilterType : int32_t { kPeaking = 0, kLowShelf = 1, kHighShelf = 2, kHighPass = 3, kLowPass = 4 };

struct BiquadCoeffs { double b0 = 1, b1 = 0, b2 = 0, a1 = 0, a2 = 0; };
BiquadCoeffs designBiquad(int32_t type, int sampleRate, float freqHz, float gainDb, float q);
double biquadMagnitudeDb(const BiquadCoeffs& c, double freqHz, double sampleRate);

struct EqBandParams { int32_t type = kPeaking; float freqHz = 1000.f; float gainDb = 0.f; float q = 1.f; };
struct BassParams { int32_t enabled = 0; float subBassDb = 0.f, harmonics = 0.f, punch = 0.f, crossoverHz = 90.f; };
struct CompParams {
    int32_t enabled = 0;
    float thresholdDb = -18.f, ratio = 2.f, attackMs = 10.f, releaseMs = 120.f, kneeDb = 6.f, makeupDb = 0.f;
};
struct LimiterParams { float ceilingDb = -0.3f, releaseMs = 80.f; };
struct ImagingParams { int32_t enabled = 0; float midGainDb = 0.f, sideGainDb = 0.f, balance = 0.f, monoBelowHz = 0.f; };

struct Params {
    int32_t globalEnabled = 0;
    float masterGainDb = 0.f;
    int32_t eqEnabled = 0;
    uint32_t eqBandMask = 0x3FFu;  // bit i = band i in use (default: 10 graphic bands)
    EqBandParams eq[kMaxBands];
    BassParams bass;
    CompParams comp;
    LimiterParams limiter;
    ImagingParams imaging;
    Params();
};

/** Same ranges as EngineParameters.sanitized() in Kotlin; NaN/Inf fall back to defaults. */
Params sanitized(const Params& p);

class StereoBiquad {
public:
    void set(const BiquadCoeffs& c) { co_ = c; active_ = true; }
    void bypass() { active_ = false; reset(); }
    void reset() { z1l_ = z2l_ = z1r_ = z2r_ = 0.0; }
    bool active() const { return active_; }
    void process(float* buf, int frames);
private:
    BiquadCoeffs co_;
    bool active_ = false;
    double z1l_ = 0, z2l_ = 0, z1r_ = 0, z2r_ = 0;
};

class MonoBiquad {
public:
    void set(const BiquadCoeffs& c) { co_ = c; }
    void reset() { z1_ = z2_ = 0.0; }
    double tick(double x) {
        const double y = co_.b0 * x + z1_;
        z1_ = co_.b1 * x - co_.a1 * y + z2_;
        z2_ = co_.b2 * x - co_.a2 * y;
        return y;
    }
    void flush() { if (std::fabs(z1_) < 1e-30) z1_ = 0; if (std::fabs(z2_) < 1e-30) z2_ = 0; }
private:
    BiquadCoeffs co_;
    double z1_ = 0, z2_ = 0;
};

class BassEnhancer {
public:
    explicit BassEnhancer(int sampleRate);
    void configure(const BassParams& p);
    void reset();
    void process(float* buf, int frames);
private:
    double timeCoef(double ms) const { return std::exp(-1.0 / (sr_ * ms / 1000.0)); }
    int sr_;
    StereoBiquad shelf_;
    MonoBiquad lowL_, lowR_, hp1_, hp2_, lp_;
    bool active_ = false, useHarm_ = false, usePunch_ = false;
    double harmonics_ = 0, punch_ = 0, drive_ = 0.5;
    double harmEnv_ = 0, fast_ = 0, slow_ = 0;
    double harmAttack_, harmRelease_, fastAttack_, fastRelease_, slowAttack_, slowRelease_;
};

class Compressor {
public:
    explicit Compressor(int sampleRate) : sr_(sampleRate) {}
    void configure(const CompParams& p);
    void reset() { grDb_ = 0; }
    void process(float* buf, int frames);
    double gainReductionDb() const { return grDb_; }
    double computeOutputDb(double levelDb) const;
private:
    int sr_;
    double threshold_ = -18, ratio_ = 2, knee_ = 6, makeup_ = 0, attack_ = 0, release_ = 0, grDb_ = 0;
};

class StereoImager {
public:
    explicit StereoImager(int sampleRate);
    void configure(const ImagingParams& p, int sampleRate);
    void process(float* buf, int frames);
private:
    double smoothing_;
    MonoBiquad hp1_, hp2_;
    bool active_ = false, monoOn_ = false;
    double tMid_ = 1, tSide_ = 1, tL_ = 1, tR_ = 1, cMid_ = 1, cSide_ = 1, cL_ = 1, cR_ = 1;
};

class SmoothedGain {
public:
    explicit SmoothedGain(int sampleRate) : coef_(1.0 - std::exp(-1.0 / (sampleRate * 0.010))) {}
    void setDb(double db) { target_ = std::pow(10.0, db / 20.0); }
    void process(float* buf, int frames);
private:
    double current_ = 1, target_ = 1, coef_;
};

/** Look-ahead brick-wall peak limiter (2 ms). Never exceeds the ceiling. */
class Limiter {
public:
    explicit Limiter(int sampleRate, double lookaheadMs = 2.0);
    void configure(double ceilingDb, double releaseMs);
    void reset();
    void process(float* buf, int frames);
    int latencySamples() const { return latency_; }
private:
    int sr_, latency_, size_;
    std::vector<float> delay_, rRing_, mRing_;
    double mSum_;
    int pos_ = 0, delayPos_ = 0;
    double smoothed_ = 1, ceiling_, releaseCoef_;
};

/** scrub -> EQ -> bass -> compressor -> imaging -> master gain -> limiter. Interleaved stereo float. */
class Chain {
public:
    explicit Chain(int sampleRate);
    void configure(const Params& p);  // p must be sanitized; allocation free
    void reset();
    void process(float* buf, int frames);
    int latencySamples() const { return limiter_.latencySamples(); }
    double gainReductionDb() const { return comp_.gainReductionDb(); }
private:
    int sr_;
    StereoBiquad eq_[kMaxBands];
    BassEnhancer bass_;
    Compressor comp_;
    StereoImager imager_;
    SmoothedGain master_;
    Limiter limiter_;
    bool eqEnabled_ = false, compEnabled_ = false;
};

}  // namespace senzafx
