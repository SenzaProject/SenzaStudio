#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <vector>

#include "../src/senzafx_dsp.h"

using namespace senzafx;
static int g_fail = 0, g_pass = 0;
#define CHECK(cond, ...) do { if (cond) { ++g_pass; } else { ++g_fail; printf("FAIL %s:%d  %s  ", __FILE__, __LINE__, #cond); printf(__VA_ARGS__); printf("\n"); } } while (0)

static const int SR = 48000;
static std::vector<float> sine(double hz, double amp, int frames, bool stereo_same = true) {
    std::vector<float> b((size_t)frames * 2);
    for (int i = 0; i < frames; ++i) { float v = (float)(amp * std::sin(2 * M_PI * hz * i / SR)); b[2 * i] = v; b[2 * i + 1] = stereo_same ? v : -v; }
    return b;
}
static double rmsDb(const std::vector<float>& b, int from) {
    double s = 0; size_t n = 0;
    for (size_t i = (size_t)from * 2; i < b.size(); ++i) { s += (double)b[i] * b[i]; ++n; }
    return 10 * std::log10(s / n + 1e-30);
}
static double goertzel(const std::vector<float>& b, double hz, int from) {  // left channel amplitude
    double c = 2 * std::cos(2 * M_PI * hz / SR), s1 = 0, s2 = 0; int n = 0;
    for (size_t i = (size_t)from * 2; i < b.size(); i += 2) { double s = b[i] + c * s1 - s2; s2 = s1; s1 = s; ++n; }
    double p = s1 * s1 + s2 * s2 - c * s1 * s2;
    return 2 * std::sqrt(std::max(p, 0.0)) / n;
}
static void run(Chain& ch, std::vector<float>& b) {
    int total = (int)(b.size() / 2);
    for (int off = 0; off < total; off += 480) ch.process(b.data() + off * 2, std::min(480, total - off));
}
static Params base() { Params p; p.globalEnabled = 1; return sanitized(p); }

static void testEq() {
    Params p = base(); p.eqEnabled = 1; p.eq[5].gainDb = 6.f; p.eq[5].freqHz = 1000.f; p.eq[5].q = 1.f;
    Chain ch(SR); ch.configure(sanitized(p));
    auto x = sine(1000, 0.2, SR); double in = rmsDb(x, 24000); run(ch, x);
    double gain = rmsDb(x, 24000) - in;
    CHECK(std::fabs(gain - 6.0) < 0.25, "EQ +6dB@1k measured %.3f dB", gain);
    BiquadCoeffs c = designBiquad(kPeaking, SR, 1000.f, 6.f, 1.f);
    CHECK(std::fabs(biquadMagnitudeDb(c, 1000, SR) - 6.0) < 0.01, "analytic");
    // far from the band: ~0 dB
    Chain ch2(SR); ch2.configure(sanitized(p));
    auto y = sine(100, 0.2, SR); double in2 = rmsDb(y, 24000); run(ch2, y);
    CHECK(std::fabs(rmsDb(y, 24000) - in2) < 0.6, "EQ leakage at 100 Hz %.3f dB", rmsDb(y, 24000) - in2);
    // all flat -> transparent (below limiter ceiling)
    Params f = base(); f.eqEnabled = 1; Chain ch3(SR); ch3.configure(sanitized(f));
    auto z = sine(440, 0.3, SR); double in3 = rmsDb(z, 24000); run(ch3, z);
    CHECK(std::fabs(rmsDb(z, 24000) - in3) < 0.01, "flat chain must be transparent");
}

static void testLimiter() {
    for (float ceil : {-0.3f, -6.f, -12.f}) {
        Params p = base(); p.limiter.ceilingDb = ceil; p.masterGainDb = 6.f;
        Chain ch(SR); ch.configure(sanitized(p));
        std::vector<float> b((size_t)SR * 2); srand(1);
        for (auto& v : b) v = (float)(rand() / (double)RAND_MAX * 6.0 - 3.0);  // +-3.0 (way above full scale)
        run(ch, b);
        float lim = (float)std::pow(10.0, ceil / 20.0), mx = 0;
        for (float v : b) mx = std::max(mx, std::fabs(v));
        CHECK(mx <= lim + 1e-6f, "limiter ceiling %.1f dB: peak %.6f > %.6f", ceil, mx, lim);
    }
    Params p = base(); Chain ch(SR); ch.configure(sanitized(p));
    std::vector<float> b((size_t)2000 * 2, 0.f); b[10 * 2] = 0.5f; b[10 * 2 + 1] = 0.5f; run(ch, b);
    int L = ch.latencySamples();
    CHECK(std::fabs(b[(10 + L) * 2] - 0.5f) < 1e-4f, "impulse delayed by %d samples: got %f", L, b[(10 + L) * 2]);
    CHECK(L == 96, "2 ms at 48 kHz = 96 samples, got %d", L);
}

static void testBass() {
    Params off = base();
    Params sub = base(); sub.bass.enabled = 1; sub.bass.subBassDb = 12.f;
    Chain a(SR), b(SR); a.configure(sanitized(off)); b.configure(sanitized(sub));
    auto x = sine(40, 0.1, SR * 2), y = x; run(a, x); run(b, y);
    double g = rmsDb(y, 48000) - rmsDb(x, 48000);
    CHECK(g > 8.0 && g < 12.5, "sub-bass shelf +12 dB @40Hz gave %.2f dB", g);

    Params h = base(); h.bass.enabled = 1; h.bass.harmonics = 1.f; h.bass.crossoverHz = 90.f;
    Chain c(SR), d(SR); c.configure(sanitized(off)); d.configure(sanitized(h));
    auto p1 = sine(50, 0.3, SR * 2), p2 = p1; run(c, p1); run(d, p2);
    double h0 = goertzel(p1, 150, 48000), h1 = goertzel(p2, 150, 48000), h2 = goertzel(p2, 100, 48000);
    CHECK(h0 < 1e-4, "no harmonics when off (%.6f)", h0);
    CHECK(h1 > 2e-3 || h2 > 2e-3, "harmonics generated: 100Hz=%.5f 150Hz=%.5f", h2, h1);
    double f0 = goertzel(p2, 50, 48000), f1 = goertzel(p1, 50, 48000);
    CHECK(std::fabs(f0 - f1) / f1 < 0.2, "fundamental kept (%.4f vs %.4f)", f0, f1);
}

static void testCompressor() {
    Params p = base(); p.comp.enabled = 1; p.comp.thresholdDb = -30.f; p.comp.ratio = 4.f; p.comp.attackMs = 1.f; p.comp.releaseMs = 50.f;
    Chain ch(SR); ch.configure(sanitized(p));
    auto x = sine(220, 0.5, SR); run(ch, x);
    double red = 20 * std::log10(0.5 / (std::sqrt(2.0) * std::sqrt(std::pow(10, rmsDb(x, 24000) / 10)) / std::sqrt(2.0) * 1.0));
    (void)red;
    double outPeak = 0; for (size_t i = 48000; i < x.size(); ++i) outPeak = std::max(outPeak, (double)std::fabs(x[i]));
    double redDb = 20 * std::log10(0.5 / outPeak);
    CHECK(redDb > 12.0 && redDb < 20.5, "compressor reduction %.2f dB (expected ~18)", redDb);
    Compressor c(SR); CompParams cp; cp.thresholdDb = -20; cp.ratio = 4; cp.kneeDb = 0; c.configure(cp);
    CHECK(std::fabs(c.computeOutputDb(-40) + 40) < 1e-9, "below threshold unity");
    CHECK(std::fabs(c.computeOutputDb(0) - (-20 + 20 / 4.0)) < 1e-9, "static curve above threshold");
}

static void testImaging() {
    Params p = base(); p.imaging.enabled = 1; p.imaging.sideGainDb = -12.f;
    Chain ch(SR); ch.configure(sanitized(p));
    auto x = sine(1000, 0.2, SR, false); double in = rmsDb(x, 24000); run(ch, x);
    double d = rmsDb(x, 24000) - in;
    CHECK(std::fabs(d + 12.0) < 0.3, "pure-side signal with side -12 dB gave %.2f dB", d);
    Params q = base(); q.imaging.enabled = 1; q.imaging.balance = 1.f;  // right only: left muted
    Chain c2(SR); c2.configure(sanitized(q));
    auto y = sine(1000, 0.2, SR); run(c2, y);
    double lmax = 0; for (size_t i = 48000; i < y.size(); i += 2) lmax = std::max(lmax, (double)std::fabs(y[i]));
    CHECK(lmax < 1e-3, "balance +1 must silence left, got %.5f", lmax);
    // mono-below: low freq side content removed
    Params m = base(); m.imaging.enabled = 1; m.imaging.monoBelowHz = 200.f;
    Chain c3(SR); c3.configure(sanitized(m));
    auto z = sine(50, 0.2, SR * 2, false); run(c3, z);
    double mx = 0; for (size_t i = 96000; i < z.size(); i += 2) mx = std::max(mx, (double)std::fabs(z[i]));
    CHECK(mx < 0.01, "side at 50 Hz below mono-below 200 Hz remains %.5f", mx);
}

static void testRobustness() {
    Params p; p.globalEnabled = 1; p.masterGainDb = NAN; p.eqEnabled = 1; p.eqBandMask = 0xFFFFFFFFu;
    for (int i = 0; i < kMaxBands; ++i) { p.eq[i].freqHz = INFINITY; p.eq[i].gainDb = 1e9f; p.eq[i].q = -5.f; p.eq[i].type = 99; }
    p.bass.enabled = 1; p.bass.harmonics = 1e9f; p.bass.punch = 1.f; p.bass.subBassDb = 1e9f;
    p.comp.enabled = 1; p.comp.ratio = NAN; p.imaging.enabled = 1; p.imaging.monoBelowHz = 1e9f;
    p.limiter.ceilingDb = NAN;
    Params s = sanitized(p);
    CHECK(s.masterGainDb == 0.f && s.eq[0].gainDb == 15.f && s.eq[0].q == 0.1f && s.eq[0].type == kPeaking, "sanitize clamps");
    Chain ch(44100); ch.configure(s);
    std::vector<float> b((size_t)44100 * 2); srand(7);
    for (size_t i = 0; i < b.size(); ++i) b[i] = (float)(rand() / (double)RAND_MAX * 4 - 2);
    b[100] = NAN; b[101] = INFINITY; b[5000] = -INFINITY;
    run(ch, b);
    float lim = (float)std::pow(10.0, s.limiter.ceilingDb / 20.0), mx = 0; bool finite = true;
    for (float v : b) { if (!std::isfinite(v)) finite = false; else mx = std::max(mx, std::fabs(v)); }
    CHECK(finite, "output finite");
    CHECK(mx <= lim + 1e-6f, "peak %.6f <= %.6f under abuse", mx, lim);
    for (int sr : {8000, 11025, 22050, 32000, 44100, 48000, 88200, 96000, 192000}) {
        Chain c(sr); c.configure(s); std::vector<float> t((size_t)sr / 10 * 2, 0.3f); c.process(t.data(), (int)t.size() / 2);
        bool ok = true; for (float v : t) if (!std::isfinite(v)) ok = false;
        CHECK(ok, "sample rate %d finite", sr);
    }
}

int main() {
    testEq(); testLimiter(); testBass(); testCompressor(); testImaging(); testRobustness();
    printf("DSP tests: %d passed, %d failed\n", g_pass, g_fail);
    return g_fail ? 1 : 0;
}
