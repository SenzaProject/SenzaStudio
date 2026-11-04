#include "senzafx_dsp.h"

#include <algorithm>
#include <cstring>

namespace senzafx {
namespace {
constexpr double kPi = 3.14159265358979323846;
constexpr float kButterworthQ = 0.70710678f;

inline float finiteOr(float v, float fb) { return std::isfinite(v) ? v : fb; }
inline float clampf(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }
inline double dbToLin(double db) { return std::pow(10.0, db / 20.0); }
inline double linToDb(double x) { return 20.0 * std::log10(std::max(x, 1e-9)); }
}  // namespace

Params::Params() {
    static const float kGraphic[10] = {31.25f, 62.5f, 125.f, 250.f, 500.f, 1000.f, 2000.f, 4000.f, 8000.f, 16000.f};
    for (int i = 0; i < 10; ++i) eq[i].freqHz = kGraphic[i];
}

Params sanitized(const Params& in) {
    Params p = in;
    p.globalEnabled = in.globalEnabled ? 1 : 0;
    p.masterGainDb = clampf(finiteOr(in.masterGainDb, 0.f), -24.f, 6.f);
    p.eqEnabled = in.eqEnabled ? 1 : 0;
    p.eqBandMask = in.eqBandMask & ((1u << kMaxBands) - 1u);
    for (int i = 0; i < kMaxBands; ++i) {
        EqBandParams& b = p.eq[i];
        if (b.type < kPeaking || b.type > kLowPass) b.type = kPeaking;
        b.freqHz = clampf(finiteOr(b.freqHz, 1000.f), 20.f, 20000.f);
        b.gainDb = clampf(finiteOr(b.gainDb, 0.f), -15.f, 15.f);
        b.q = clampf(finiteOr(b.q, 1.f), 0.1f, 18.f);
    }
    BassParams& s = p.bass;
    s.enabled = s.enabled ? 1 : 0;
    s.subBassDb = clampf(finiteOr(s.subBassDb, 0.f), -12.f, 12.f);
    s.harmonics = clampf(finiteOr(s.harmonics, 0.f), 0.f, 1.f);
    s.punch = clampf(finiteOr(s.punch, 0.f), 0.f, 1.f);
    s.crossoverHz = clampf(finiteOr(s.crossoverHz, 90.f), 40.f, 200.f);
    CompParams& c = p.comp;
    c.enabled = c.enabled ? 1 : 0;
    c.thresholdDb = clampf(finiteOr(c.thresholdDb, -18.f), -60.f, 0.f);
    c.ratio = clampf(finiteOr(c.ratio, 2.f), 1.f, 20.f);
    c.attackMs = clampf(finiteOr(c.attackMs, 10.f), 0.1f, 200.f);
    c.releaseMs = clampf(finiteOr(c.releaseMs, 120.f), 5.f, 2000.f);
    c.kneeDb = clampf(finiteOr(c.kneeDb, 6.f), 0.f, 24.f);
    c.makeupDb = clampf(finiteOr(c.makeupDb, 0.f), 0.f, 24.f);
    p.limiter.ceilingDb = clampf(finiteOr(p.limiter.ceilingDb, -0.3f), -12.f, 0.f);
    p.limiter.releaseMs = clampf(finiteOr(p.limiter.releaseMs, 80.f), 10.f, 1000.f);
    ImagingParams& im = p.imaging;
    im.enabled = im.enabled ? 1 : 0;
    im.midGainDb = clampf(finiteOr(im.midGainDb, 0.f), -12.f, 12.f);
    im.sideGainDb = clampf(finiteOr(im.sideGainDb, 0.f), -12.f, 12.f);
    im.balance = clampf(finiteOr(im.balance, 0.f), -1.f, 1.f);
    const float mono = finiteOr(im.monoBelowHz, 0.f);
    im.monoBelowHz = mono < 20.f ? 0.f : std::min(mono, 300.f);
    return p;
}

BiquadCoeffs designBiquad(int32_t type, int sampleRate, float frequencyHz, float gainDb, float q) {
    const double fs = sampleRate > 0 ? sampleRate : 48000;
    const double f = std::min(std::max((double)frequencyHz, 1.0), fs * 0.45);
    const double qq = std::max((double)q, 0.05);
    const double a = std::pow(10.0, (double)gainDb / 40.0);
    const double w0 = 2.0 * kPi * f / fs;
    const double cosw = std::cos(w0);
    const double alpha = std::sin(w0) / (2.0 * qq);
    const double sqrtA2alpha = 2.0 * std::sqrt(a) * alpha;
    double b0, b1, b2, a0, a1, a2;
    switch (type) {
        case kLowShelf:
            b0 = a * ((a + 1) - (a - 1) * cosw + sqrtA2alpha);
            b1 = 2 * a * ((a - 1) - (a + 1) * cosw);
            b2 = a * ((a + 1) - (a - 1) * cosw - sqrtA2alpha);
            a0 = (a + 1) + (a - 1) * cosw + sqrtA2alpha;
            a1 = -2 * ((a - 1) + (a + 1) * cosw);
            a2 = (a + 1) + (a - 1) * cosw - sqrtA2alpha;
            break;
        case kHighShelf:
            b0 = a * ((a + 1) + (a - 1) * cosw + sqrtA2alpha);
            b1 = -2 * a * ((a - 1) + (a + 1) * cosw);
            b2 = a * ((a + 1) + (a - 1) * cosw - sqrtA2alpha);
            a0 = (a + 1) - (a - 1) * cosw + sqrtA2alpha;
            a1 = 2 * ((a - 1) - (a + 1) * cosw);
            a2 = (a + 1) - (a - 1) * cosw - sqrtA2alpha;
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

double biquadMagnitudeDb(const BiquadCoeffs& c, double freqHz, double sampleRate) {
    const double w = 2.0 * kPi * freqHz / sampleRate;
    const double c1 = std::cos(w), s1 = std::sin(w), c2 = std::cos(2 * w), s2 = std::sin(2 * w);
    const double nr = c.b0 + c.b1 * c1 + c.b2 * c2, ni = -(c.b1 * s1 + c.b2 * s2);
    const double dr = 1.0 + c.a1 * c1 + c.a2 * c2, di = -(c.a1 * s1 + c.a2 * s2);
    const double mag = std::sqrt((nr * nr + ni * ni) / (dr * dr + di * di));
    return 20.0 * std::log10(std::max(mag, 1e-12));
}

void StereoBiquad::process(float* buf, int frames) {
    const double b0 = co_.b0, b1 = co_.b1, b2 = co_.b2, a1 = co_.a1, a2 = co_.a2;
    for (int i = 0; i < frames * 2; i += 2) {
        const double xl = buf[i];
        const double yl = b0 * xl + z1l_;
        z1l_ = b1 * xl - a1 * yl + z2l_;
        z2l_ = b2 * xl - a2 * yl;
        buf[i] = (float)yl;
        const double xr = buf[i + 1];
        const double yr = b0 * xr + z1r_;
        z1r_ = b1 * xr - a1 * yr + z2r_;
        z2r_ = b2 * xr - a2 * yr;
        buf[i + 1] = (float)yr;
    }
    if (std::fabs(z1l_) < 1e-30) z1l_ = 0;
    if (std::fabs(z2l_) < 1e-30) z2l_ = 0;
    if (std::fabs(z1r_) < 1e-30) z1r_ = 0;
    if (std::fabs(z2r_) < 1e-30) z2r_ = 0;
}

// ---------------- Bass ----------------
namespace {
constexpr double kDriveBase = 0.5, kDriveSlope = 5.0, kBias = 0.8, kHarmonicGain = 1.5;
constexpr double kEnvFloor = 0.02, kSlowFloor = 1e-5, kMaxPunchRatio = 2.0;
}  // namespace

BassEnhancer::BassEnhancer(int sampleRate)
    : sr_(sampleRate),
      harmAttack_(timeCoef(2.0)), harmRelease_(timeCoef(80.0)),
      fastAttack_(timeCoef(1.0)), fastRelease_(timeCoef(20.0)),
      slowAttack_(timeCoef(20.0)), slowRelease_(timeCoef(100.0)) {}

void BassEnhancer::configure(const BassParams& s) {
    const bool was = active_;
    active_ = s.enabled != 0;
    if (std::fabs(s.subBassDb) < 0.05f) shelf_.bypass();
    else shelf_.set(designBiquad(kLowShelf, sr_, 60.f, s.subBassDb, kButterworthQ));
    const float xo = s.crossoverHz;
    const BiquadCoeffs lp = designBiquad(kLowPass, sr_, xo, 0.f, kButterworthQ);
    const BiquadCoeffs hp = designBiquad(kHighPass, sr_, xo, 0.f, kButterworthQ);
    lowL_.set(lp); lowR_.set(lp);
    hp1_.set(hp); hp2_.set(hp);
    lp_.set(designBiquad(kLowPass, sr_, xo * 4.f, 0.f, kButterworthQ));
    harmonics_ = s.harmonics;
    punch_ = s.punch;
    drive_ = kDriveBase + kDriveSlope * harmonics_;
    useHarm_ = harmonics_ > 0.0;
    usePunch_ = punch_ > 0.0;
    if (active_ && !was) reset();
}

void BassEnhancer::reset() {
    lowL_.reset(); lowR_.reset(); hp1_.reset(); hp2_.reset(); lp_.reset();
    harmEnv_ = fast_ = slow_ = 0;
}

void BassEnhancer::process(float* buf, int frames) {
    if (!active_) return;
    if (shelf_.active()) shelf_.process(buf, frames);
    if (!useHarm_ && !usePunch_) return;
    const double tanhBias = std::tanh(kBias);
    for (int i = 0; i < frames * 2; i += 2) {
        const double l = buf[i], r = buf[i + 1];
        const double lowL = lowL_.tick(l), lowR = lowR_.tick(r);
        double addL = 0, addR = 0;
        if (useHarm_) {
            const double mid = (lowL + lowR) * 0.5;
            const double a = std::fabs(mid);
            harmEnv_ = a > harmEnv_ ? harmAttack_ * harmEnv_ + (1.0 - harmAttack_) * a
                                    : harmRelease_ * harmEnv_ + (1.0 - harmRelease_) * a;
            const double den = std::max(harmEnv_, kEnvFloor);
            const double shaped = (std::tanh(drive_ * mid / den + kBias) - tanhBias) * den;
            const double h = lp_.tick(hp2_.tick(hp1_.tick(shaped)));
            const double g = kHarmonicGain * harmonics_ * h;
            addL += g; addR += g;
        }
        if (usePunch_) {
            const double x = std::max(std::fabs(lowL), std::fabs(lowR));
            fast_ = x > fast_ ? fastAttack_ * fast_ + (1.0 - fastAttack_) * x : fastRelease_ * fast_ + (1.0 - fastRelease_) * x;
            slow_ = x > slow_ ? slowAttack_ * slow_ + (1.0 - slowAttack_) * x : slowRelease_ * slow_ + (1.0 - slowRelease_) * x;
            if (slow_ > kSlowFloor) {
                const double extra = std::min(std::max(fast_ / slow_ - 1.0, 0.0), kMaxPunchRatio) * punch_;
                addL += extra * lowL; addR += extra * lowR;
            }
        }
        buf[i] = (float)(l + addL);
        buf[i + 1] = (float)(r + addR);
    }
    lowL_.flush(); lowR_.flush(); hp1_.flush(); hp2_.flush(); lp_.flush();
}

// ---------------- Compressor ----------------
void Compressor::configure(const CompParams& p) {
    threshold_ = p.thresholdDb;
    ratio_ = std::max((double)p.ratio, 1.0);
    knee_ = std::max((double)p.kneeDb, 0.0);
    makeup_ = p.makeupDb;
    attack_ = std::exp(-1.0 / (sr_ * std::max((double)p.attackMs, 0.01) / 1000.0));
    release_ = std::exp(-1.0 / (sr_ * std::max((double)p.releaseMs, 0.01) / 1000.0));
}

double Compressor::computeOutputDb(double levelDb) const {
    const double over = levelDb - threshold_;
    if (2.0 * over < -knee_) return levelDb;
    if (knee_ > 0.0 && 2.0 * std::fabs(over) <= knee_) {
        const double t = over + knee_ / 2.0;
        return levelDb + (1.0 / ratio_ - 1.0) * t * t / (2.0 * knee_);
    }
    return threshold_ + over / ratio_;
}

void Compressor::process(float* buf, int frames) {
    for (int i = 0; i < frames * 2; i += 2) {
        const float l = buf[i], r = buf[i + 1];
        const double peak = std::max(std::fabs(l), std::fabs(r));
        const double levelDb = linToDb(peak);
        const double target = computeOutputDb(levelDb) - levelDb;
        const double coef = target < grDb_ ? attack_ : release_;
        grDb_ = coef * grDb_ + (1.0 - coef) * target;
        const float g = (float)dbToLin(grDb_ + makeup_);
        buf[i] = l * g;
        buf[i + 1] = r * g;
    }
}

// ---------------- Imaging ----------------
StereoImager::StereoImager(int sampleRate) : smoothing_(1.0 - std::exp(-1.0 / (sampleRate * 0.010))) {}

void StereoImager::configure(const ImagingParams& p, int sampleRate) {
    const bool was = active_;
    active_ = p.enabled != 0;
    tMid_ = dbToLin(p.midGainDb);
    tSide_ = dbToLin(p.sideGainDb);
    tL_ = std::min(1.0, 1.0 - (double)p.balance);
    tR_ = std::min(1.0, 1.0 + (double)p.balance);
    const bool nowMono = p.monoBelowHz > 0.f;
    if (nowMono) {
        const BiquadCoeffs hp = designBiquad(kHighPass, sampleRate, p.monoBelowHz, 0.f, kButterworthQ);
        hp1_.set(hp); hp2_.set(hp);
        if (!monoOn_) { hp1_.reset(); hp2_.reset(); }
    }
    monoOn_ = nowMono;
    if (active_ && !was) { cMid_ = tMid_; cSide_ = tSide_; cL_ = tL_; cR_ = tR_; hp1_.reset(); hp2_.reset(); }
}

void StereoImager::process(float* buf, int frames) {
    if (!active_) return;
    for (int i = 0; i < frames * 2; i += 2) {
        cMid_ += (tMid_ - cMid_) * smoothing_;
        cSide_ += (tSide_ - cSide_) * smoothing_;
        cL_ += (tL_ - cL_) * smoothing_;
        cR_ += (tR_ - cR_) * smoothing_;
        const double l = buf[i], r = buf[i + 1];
        const double mid = (l + r) * 0.5 * cMid_;
        double side = (l - r) * 0.5 * cSide_;
        if (monoOn_) side = hp2_.tick(hp1_.tick(side));
        buf[i] = (float)((mid + side) * cL_);
        buf[i + 1] = (float)((mid - side) * cR_);
    }
    hp1_.flush(); hp2_.flush();
}

void SmoothedGain::process(float* buf, int frames) {
    for (int i = 0; i < frames * 2; i += 2) {
        current_ += (target_ - current_) * coef_;
        const float g = (float)current_;
        buf[i] *= g;
        buf[i + 1] *= g;
    }
}

// ---------------- Limiter ----------------
Limiter::Limiter(int sampleRate, double lookaheadMs)
    : sr_(sampleRate),
      latency_(std::max(1, (int)(sampleRate * lookaheadMs / 1000.0))),
      size_(latency_ + 1),
      delay_((size_t)latency_ * 2, 0.f), rRing_((size_t)size_, 1.f), mRing_((size_t)size_, 1.f),
      mSum_((double)size_), ceiling_(dbToLin(-0.3)), releaseCoef_(std::exp(-1.0 / (sampleRate * 0.080))) {}

void Limiter::configure(double ceilingDb, double releaseMs) {
    ceiling_ = dbToLin(std::min(ceilingDb, 0.0));
    releaseCoef_ = std::exp(-1.0 / ((double)sr_ * std::max(releaseMs, 1.0) / 1000.0));
}

void Limiter::reset() {
    std::fill(delay_.begin(), delay_.end(), 0.f);
    std::fill(rRing_.begin(), rRing_.end(), 1.f);
    std::fill(mRing_.begin(), mRing_.end(), 1.f);
    mSum_ = (double)size_;
    pos_ = delayPos_ = 0;
    smoothed_ = 1.0;
}

void Limiter::process(float* buf, int frames) {
    const float c = (float)ceiling_;
    for (int i = 0; i < frames; ++i) {
        const float l = buf[i * 2], r = buf[i * 2 + 1];
        const float peak = std::max(std::fabs(l), std::fabs(r));
        const float req = peak > c ? c / peak : 1.f;
        rRing_[pos_] = req;
        float m = 1.f;
        for (int k = 0; k < size_; ++k) m = std::min(m, rRing_[k]);
        mSum_ += (double)(m - mRing_[pos_]);
        mRing_[pos_] = m;
        const float g = (float)(mSum_ / size_);
        const double rising = releaseCoef_ * smoothed_ + (1.0 - releaseCoef_);
        const double gOut = std::min((double)g, rising);
        smoothed_ = gOut;
        const int d = delayPos_ * 2;
        const float outL = delay_[d] * (float)gOut;
        const float outR = delay_[d + 1] * (float)gOut;
        delay_[d] = l;
        delay_[d + 1] = r;
        buf[i * 2] = std::min(std::max(outL, -c), c);
        buf[i * 2 + 1] = std::min(std::max(outR, -c), c);
        if (++pos_ == size_) pos_ = 0;
        if (++delayPos_ == latency_) delayPos_ = 0;
    }
}

// ---------------- Chain ----------------
Chain::Chain(int sampleRate)
    : sr_(sampleRate > 0 ? sampleRate : 48000),
      bass_(sr_), comp_(sr_), imager_(sr_), master_(sr_), limiter_(sr_) {
    configure(sanitized(Params()));
}

void Chain::reset() {
    for (auto& b : eq_) b.reset();
    bass_.reset(); comp_.reset(); limiter_.reset();
}

void Chain::configure(const Params& p) {
    eqEnabled_ = p.eqEnabled != 0;
    for (int i = 0; i < kMaxBands; ++i) {
        const EqBandParams& b = p.eq[i];
        const bool used = (p.eqBandMask >> i) & 1u;
        const bool flat = b.type != kHighPass && b.type != kLowPass && std::fabs(b.gainDb) < 0.01f;
        if (!used || flat) eq_[i].bypass();
        else eq_[i].set(designBiquad(b.type, sr_, b.freqHz, b.gainDb, b.q));
    }
    bass_.configure(p.bass);
    if (!p.comp.enabled && compEnabled_) comp_.reset();
    compEnabled_ = p.comp.enabled != 0;
    comp_.configure(p.comp);
    imager_.configure(p.imaging, sr_);
    master_.setDb(p.masterGainDb);
    limiter_.configure(p.limiter.ceilingDb, p.limiter.releaseMs);
}

void Chain::process(float* buf, int frames) {
    for (int i = 0; i < frames * 2; ++i) if (!std::isfinite(buf[i])) buf[i] = 0.f;
    if (eqEnabled_) for (auto& b : eq_) if (b.active()) b.process(buf, frames);
    bass_.process(buf, frames);
    if (compEnabled_) comp_.process(buf, frames);
    imager_.process(buf, frames);
    master_.process(buf, frames);
    limiter_.process(buf, frames);
}

}  // namespace senzafx
