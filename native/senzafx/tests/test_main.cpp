// Dependency-free test suite for the Senza FX core. Mirrors the Kotlin reference tests and adds
// real-time guarantees (zero heap allocations in Process()).
#include <atomic>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <new>
#include <random>
#include <thread>
#include <vector>

#include "senzafx/chain.h"
#include "senzafx/dsp.h"
#include "senzafx/params.h"

// ---- allocation counter -------------------------------------------------------------------------
static std::atomic<long> g_allocs{0};
static std::atomic<bool> g_count{false};
void* operator new(std::size_t n) {
  if (g_count.load(std::memory_order_relaxed)) g_allocs.fetch_add(1, std::memory_order_relaxed);
  void* p = std::malloc(n ? n : 1);
  if (!p) throw std::bad_alloc();
  return p;
}
void operator delete(void* p) noexcept { std::free(p); }
void operator delete(void* p, std::size_t) noexcept { std::free(p); }

// ---- mini framework -----------------------------------------------------------------------------
static int g_failed = 0, g_checks = 0;
#define CHECK(cond)                                                       \
  do {                                                                    \
    ++g_checks;                                                           \
    if (!(cond)) { ++g_failed; std::printf("  FAIL %s:%d  %s\n", __FILE__, __LINE__, #cond); } \
  } while (0)
#define CHECK_NEAR(a, b, tol)                                                                    \
  do {                                                                                           \
    ++g_checks;                                                                                  \
    const double va_ = (a), vb_ = (b);                                                           \
    if (!(std::fabs(va_ - vb_) <= (tol))) {                                                      \
      ++g_failed;                                                                                \
      std::printf("  FAIL %s:%d  %s=%.6f vs %s=%.6f (tol %g)\n", __FILE__, __LINE__, #a, va_, #b, vb_, (double)(tol)); \
    }                                                                                            \
  } while (0)
#define TEST(name) static void name(); static struct Reg_##name { Reg_##name() { Tests().push_back({#name, name}); } } reg_##name; static void name()
struct Case { const char* name; void (*fn)(); };
static std::vector<Case>& Tests() { static std::vector<Case> t; return t; }

using namespace senzafx;
constexpr int FS = 48000;

static std::vector<float> Sine(double f, double amp, double sec) {
  const int n = static_cast<int>(sec * FS);
  std::vector<float> v(static_cast<size_t>(n) * 2);
  for (int i = 0; i < n; ++i) v[2 * i] = v[2 * i + 1] = static_cast<float>(amp * std::sin(2 * kPi * f * i / FS));
  return v;
}
static std::vector<float> Noise(float amp, int frames, unsigned seed = 42) {
  std::mt19937 rng(seed);
  std::uniform_real_distribution<float> d(-1.f, 1.f);
  std::vector<float> v(static_cast<size_t>(frames) * 2);
  for (auto& x : v) x = d(rng) * amp;
  return v;
}
static float Peak(const std::vector<float>& v, size_t from = 0) {
  float p = 0; for (size_t i = from; i < v.size(); ++i) p = std::max(p, std::fabs(v[i])); return p;
}
static double RmsLeft(const std::vector<float>& v, int from_frame) {
  double s = 0; int n = 0;
  for (size_t i = static_cast<size_t>(from_frame); i * 2 < v.size(); ++i, ++n) s += double(v[i * 2]) * v[i * 2];
  return std::sqrt(s / n);
}
static void RunBlocks(Chain& c, std::vector<float>& v, int block = 480) {
  const int total = static_cast<int>(v.size() / 2);
  for (int f = 0; f < total; f += block) c.Process(v.data() + 2 * f, std::min(block, total - f));
}

// ---- params -------------------------------------------------------------------------------------
TEST(params_defaults_and_sanitize) {
  Params d = DefaultParams();
  CHECK(d.band_count == 10);
  CHECK_NEAR(d.bands[0].freq_hz, 31.25, 0);
  CHECK_NEAR(d.bands[9].freq_hz, 16000, 0);
  Params w = d;
  w.master_gain_db = 99; w.bands[0].freq_hz = 5; w.bands[0].gain_db = -80; w.bands[0].q = 500;
  w.comp_ratio = 100; w.comp_attack_ms = NAN; w.limiter_ceiling_db = 6; w.band_count = 99; w.bands[1].type = 77;
  Sanitize(w);
  CHECK_NEAR(w.master_gain_db, 6, 0);
  CHECK_NEAR(w.bands[0].freq_hz, 20, 0);
  CHECK_NEAR(w.bands[0].gain_db, -15, 0);
  CHECK_NEAR(w.bands[0].q, 18, 0);
  CHECK_NEAR(w.comp_ratio, 20, 0);
  CHECK_NEAR(w.comp_attack_ms, 10, 0);
  CHECK_NEAR(w.limiter_ceiling_db, 0, 0);
  CHECK(w.band_count == kMaxBands);
  CHECK(w.bands[1].type == kPeaking);
  CHECK(w.schema == 2);
}

// ---- biquad -------------------------------------------------------------------------------------
TEST(biquad_peaking_gain) {
  auto c = DesignBiquad(kPeaking, FS, 1000, 6, 1);
  CHECK_NEAR(c.MagnitudeDb(1000, FS), 6.0, 0.05);
  CHECK_NEAR(c.MagnitudeDb(30, FS), 0.0, 0.2);
  CHECK_NEAR(c.MagnitudeDb(18000, FS), 0.0, 0.3);
  auto cut = DesignBiquad(kPeaking, FS, 1000, -6, 1);
  for (double f : {200., 1000., 5000.}) CHECK_NEAR(c.MagnitudeDb(f, FS) + cut.MagnitudeDb(f, FS), 0.0, 0.01);
}
TEST(biquad_shelves_and_passes) {
  CHECK_NEAR(DesignBiquad(kLowShelf, FS, 200, 6, .707).MagnitudeDb(20, FS), 6.0, 0.2);
  CHECK_NEAR(DesignBiquad(kLowShelf, FS, 200, 6, .707).MagnitudeDb(10000, FS), 0.0, 0.2);
  CHECK_NEAR(DesignBiquad(kHighShelf, FS, 4000, -6, .707).MagnitudeDb(18000, FS), -6.0, 0.3);
  CHECK_NEAR(DesignBiquad(kHighPass, FS, 100, 0, 0.70710678).MagnitudeDb(100, FS), -3.01, 0.05);
  CHECK_NEAR(DesignBiquad(kLowPass, FS, 8000, 0, 0.70710678).MagnitudeDb(8000, FS), -3.01, 0.05);
  CHECK(DesignBiquad(kHighPass, FS, 100, 0, 0.70710678).MagnitudeDb(20, FS) < -20);
}
TEST(biquad_stable_everywhere) {
  const float fs_[] = {20, 31.25f, 500, 5000, 16000, 20000}, gs[] = {-15, 0, 15}, qs[] = {0.1f, 0.707f, 4, 18};
  for (int t = kPeaking; t <= kLowPass; ++t)
    for (float f : fs_) for (float g : gs) for (float q : qs) {
      auto c = DesignBiquad(t, FS, f, g, q);
      CHECK(c.IsStable());
      CHECK(std::isfinite(c.b0) && std::isfinite(c.b1) && std::isfinite(c.b2) && std::isfinite(c.a1) && std::isfinite(c.a2));
    }
}
TEST(biquad_measured_matches_analytic) {
  auto c = DesignBiquad(kPeaking, FS, 1000, 6, 1);
  StereoBiquad f; f.Set(c);
  auto in = Sine(1000, 0.1, 1.0), out = in;
  f.Process(out.data(), FS);
  CHECK_NEAR(20 * std::log10(RmsLeft(out, FS / 2) / RmsLeft(in, FS / 2)), c.MagnitudeDb(1000, FS), 0.05);
}
TEST(biquad_state_survives_block_splits) {
  auto c = DesignBiquad(kLowPass, FS, 2000, 0, .707);
  auto whole = Sine(500, .5, .1); StereoBiquad a; a.Set(c); a.Process(whole.data(), static_cast<int>(whole.size() / 2));
  auto split = Sine(500, .5, .1); StereoBiquad b; b.Set(c);
  const int total = static_cast<int>(split.size() / 2);
  b.Process(split.data(), 1234); b.Process(split.data() + 2468, total - 1234);
  for (size_t i = 0; i < whole.size(); ++i) CHECK_NEAR(whole[i], split[i], 1e-6);
}

// ---- dynamics -----------------------------------------------------------------------------------
TEST(compressor_gain_computer) {
  Compressor c(FS); c.Configure(-20, 4, 1, 50, 0, 0);
  CHECK_NEAR(c.ComputeOutputDb(-30), -30, 1e-9);
  CHECK_NEAR(c.ComputeOutputDb(-20), -20, 1e-9);
  CHECK_NEAR(c.ComputeOutputDb(-6), -16.5, 1e-9);
  Compressor k(FS); k.Configure(-20, 4, 1, 50, 6, 0);
  CHECK_NEAR(k.ComputeOutputDb(-23), -23, 1e-9);
  CHECK_NEAR(k.ComputeOutputDb(-17), -19.25, 1e-9);
  CHECK_NEAR(k.ComputeOutputDb(-16.9999), -20.0 + 3.0001 / 4.0, 1e-3);
}
TEST(compressor_steady_state_and_makeup) {
  Compressor c(FS); c.Configure(-20, 4, 1, 50, 0, 0);
  std::vector<float> b(FS * 2, 0.5f); c.Process(b.data(), FS);
  CHECK_NEAR(20 * std::log10(b[b.size() - 2] / 0.5), -10.485, 0.05);
  Compressor u(FS); u.Configure(-10, 4, 1, 50, 0, 0);
  std::vector<float> q(4800 * 2, 0.05f); u.Process(q.data(), 4800);
  for (float x : q) CHECK_NEAR(x, 0.05, 1e-6);
  Compressor m(FS); m.Configure(0, 1, 1, 50, 0, 6);
  std::vector<float> d(FS * 2, 0.1f); m.Process(d.data(), FS);
  CHECK_NEAR(20 * std::log10(d[d.size() - 2] / 0.1), 6.0, 0.05);
}
TEST(limiter_never_exceeds_ceiling) {
  const float c = static_cast<float>(DbToLinear(-1.0));
  Limiter lim(FS); lim.Configure(-1.0, 80);
  auto s = Sine(440, 4.0, 1.0); lim.Process(s.data(), static_cast<int>(s.size() / 2));
  CHECK(Peak(s) <= c + 1e-6f);
  const float c2 = static_cast<float>(DbToLinear(-0.3));
  Limiter l2(FS); l2.Configure(-0.3, 80);
  auto n = Noise(1.0f, FS);
  for (int k = 0; k < 20; ++k) { n[k * 4000] = 10; n[k * 4000 + 1] = -10; }
  l2.Process(n.data(), FS);
  CHECK(Peak(n) <= c2 + 1e-6f);
}
TEST(limiter_is_transparent_and_delays_exactly_L) {
  Limiter lim(FS);
  auto in = Noise(0.5f, 10000), out = in;
  lim.Process(out.data(), 10000);
  const int d = lim.latency_samples();
  for (int i = d; i < 10000; ++i) { CHECK_NEAR(in[(i - d) * 2], out[i * 2], 1e-6); CHECK_NEAR(in[(i - d) * 2 + 1], out[i * 2 + 1], 1e-6); }
}
TEST(limiter_is_block_size_independent) {
  auto in = Sine(100, 2.0, 0.5);
  auto a = in; Limiter(FS).Process(a.data(), static_cast<int>(a.size() / 2));
  auto b = in; Limiter l(FS);
  const int total = static_cast<int>(b.size() / 2);
  for (int f = 0; f < total; f += 333) l.Process(b.data() + 2 * f, std::min(333, total - f));
  for (size_t i = 0; i < a.size(); ++i) CHECK_NEAR(a[i], b[i], 1e-6);
}
TEST(limiter_uses_smooth_gain_not_hard_clipping) {
  Limiter lim(FS); lim.Configure(-1.0, 80);
  const float c = static_cast<float>(DbToLinear(-1.0));
  auto s = Sine(440, 4.0, 1.0); lim.Process(s.data(), static_cast<int>(s.size() / 2));
  int at_clamp = 0; const int from = static_cast<int>(FS * 0.02), total = static_cast<int>(s.size() / 2);
  for (int i = from; i < total; ++i) if (std::fabs(s[i * 2]) >= c) ++at_clamp;
  CHECK(at_clamp < (total - from) / 50);
}

// ---- chain --------------------------------------------------------------------------------------
TEST(chain_default_is_transparent_apart_from_latency) {
  Chain ch(FS);
  auto in = Noise(0.5f, 20000), out = in; RunBlocks(ch, out);
  const int d = ch.latency_samples();
  for (int i = d; i < 20000; ++i) { CHECK_NEAR(in[(i - d) * 2], out[i * 2], 1e-6); CHECK_NEAR(in[(i - d) * 2 + 1], out[i * 2 + 1], 1e-6); }
}
TEST(chain_eq_applies_designed_gain) {
  Chain ch(FS); Params p = DefaultParams();
  p.eq_enabled = 1; p.bands[5] = EqBand{1000, 6, 1, kPeaking};
  ch.Configure(p);
  auto in = Sine(1000, 0.1, 1.0), out = in; RunBlocks(ch, out);
  CHECK_NEAR(20 * std::log10(RmsLeft(out, FS / 2) / RmsLeft(in, FS / 2)), 6.0, 0.1);
}
TEST(chain_disabled_eq_ignores_bands) {
  Chain ch(FS); Params p = DefaultParams();
  for (int i = 0; i < 10; ++i) p.bands[i].gain_db = 12;
  ch.Configure(p);
  auto in = Noise(0.3f, 10000), out = in; RunBlocks(ch, out);
  const int d = ch.latency_samples();
  for (int i = d; i < 10000; ++i) CHECK_NEAR(in[(i - d) * 2], out[i * 2], 1e-6);
}
TEST(chain_master_gain) {
  Chain ch(FS); Params p = DefaultParams(); p.master_gain_db = -6; ch.Configure(p);
  std::vector<float> v(FS * 2, 0.5f); RunBlocks(ch, v);
  CHECK_NEAR(20 * std::log10(v[v.size() - 2] / 0.5), -6.0, 0.05);
}
TEST(chain_worst_case_never_clips_or_nan) {
  Params p = DefaultParams(); p.master_gain_db = 6; p.eq_enabled = 1;
  for (int i = 0; i < 10; ++i) { p.bands[i].gain_db = 15; p.bands[i].q = 4; }
  p.comp_enabled = 1; p.comp_makeup_db = 24; p.limiter_ceiling_db = -0.3f;
  Chain ch(FS); ch.Configure(p);
  auto out = Noise(1.0f, FS * 2); RunBlocks(ch, out);
  const float c = static_cast<float>(DbToLinear(-0.3));
  bool finite = true; for (float x : out) finite = finite && std::isfinite(x);
  CHECK(finite); CHECK(Peak(out) <= c + 1e-6f);
}
TEST(chain_nan_inf_input_does_not_poison_state) {
  Chain ch(FS); Params p = DefaultParams(); p.eq_enabled = 1;
  for (int i = 0; i < 10; ++i) p.bands[i].gain_db = 6;
  ch.Configure(p);
  auto bad = Noise(0.2f, 4800); bad[10] = NAN; bad[11] = INFINITY; bad[100] = -INFINITY;
  RunBlocks(ch, bad);
  auto out = Noise(0.2f, 4800, 7); RunBlocks(ch, out);
  bool finite = true; for (float x : out) finite = finite && std::isfinite(x);
  CHECK(finite);
}
TEST(chain_live_parameter_changes_stay_bounded) {
  Chain ch(FS); auto v = Noise(0.9f, FS); const float c = static_cast<float>(DbToLinear(-0.3));
  int step = 0;
  for (int f = 0; f < FS; f += 480, ++step) {
    if (step % 10 == 0) {
      Params p = DefaultParams(); p.eq_enabled = 1;
      for (int i = 0; i < 10; ++i) p.bands[i].gain_db = ((step / 10) % 2 == 0) ? 12.f : -12.f;
      ch.Configure(p);
    }
    ch.Process(v.data() + 2 * f, std::min(480, FS - f));
  }
  bool finite = true; for (float x : v) finite = finite && std::isfinite(x);
  CHECK(finite); CHECK(Peak(v) <= c + 1e-6f);
}
TEST(chain_process_never_allocates) {
  Chain ch(FS); Params p = DefaultParams(); p.eq_enabled = 1; p.comp_enabled = 1;
  for (int i = 0; i < 10; ++i) p.bands[i].gain_db = 5;
  ch.Configure(p);
  auto v = Noise(0.8f, 4800);
  ch.Process(v.data(), 480);  // pick up config
  g_allocs = 0; g_count = true;
  for (int f = 0; f < 4800; f += 480) ch.Process(v.data() + 2 * f, 480);
  g_count = false;
  CHECK(g_allocs.load() == 0);
}
TEST(chain_concurrent_configure_and_process_is_safe) {
  Chain ch(FS); std::atomic<bool> stop{false};
  std::thread ctl([&] {
    unsigned n = 0;
    while (!stop.load()) {
      Params p = DefaultParams(); p.eq_enabled = 1; p.master_gain_db = float(int(n++ % 20) - 14);
      for (int i = 0; i < 10; ++i) p.bands[i].gain_db = float(int(n % 25) - 12);
      ch.Configure(p);
    }
  });
  auto v = Noise(0.9f, 480);
  const float c = static_cast<float>(DbToLinear(-0.3));
  bool ok = true;
  for (int i = 0; i < 3000; ++i) {
    auto b = v; ch.Process(b.data(), 480);
    for (float x : b) ok = ok && std::isfinite(x) && std::fabs(x) <= c + 1e-6f;
  }
  stop = true; ctl.join();
  CHECK(ok);
}

int main() {
  for (const auto& t : Tests()) {
    const int before = g_failed;
    t.fn();
    std::printf("%s %s\n", g_failed == before ? "PASS" : "FAIL", t.name);
  }
  std::printf("\n%d checks, %d failed\n", g_checks, g_failed);
  return g_failed == 0 ? 0 : 1;
}
