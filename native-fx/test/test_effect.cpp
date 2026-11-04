#include <errno.h>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <vector>

#include <hardware/audio_effect.h>

extern "C" audio_effect_library_t AELI;
static int g_fail = 0, g_pass = 0;
#define CHECK(cond, ...) do { if (cond) { ++g_pass; } else { ++g_fail; printf("FAIL %s:%d  %s  ", __FILE__, __LINE__, #cond); printf(__VA_ARGS__); printf("\n"); } } while (0)

static const effect_uuid_t kImpl = {0xeda64964, 0x49db, 0x43f9, 0x9629, {0xa5, 0x7e, 0x65, 0x34, 0x24, 0x78}};
enum { K_GLOBAL_ENABLE = 0x01, K_MASTER_GAIN = 0x02, K_EQ_ENABLE = 0x10, K_EQ_BAND = 0x11, K_BASS = 0x20,
       K_LIMITER = 0x40, K_PROTOCOL = 0xF0, K_STATS = 0xF1 };

static std::vector<char> mkParam(std::vector<int32_t> param, std::vector<char> value) {
    uint32_t ps = (uint32_t)param.size() * 4, pad = ((ps + 3) / 4) * 4;
    std::vector<char> b(sizeof(effect_param_t) + pad + value.size());
    effect_param_t* p = (effect_param_t*)b.data(); p->status = 0; p->psize = ps; p->vsize = (uint32_t)value.size();
    memcpy(p->data, param.data(), ps); if (!value.empty()) memcpy(p->data + pad, value.data(), value.size());
    return b;
}
static std::vector<char> f32s(std::vector<float> v) { std::vector<char> b(v.size() * 4); memcpy(b.data(), v.data(), b.size()); return b; }
static std::vector<char> i32(int32_t v) { std::vector<char> b(4); memcpy(b.data(), &v, 4); return b; }

static int32_t cmd(effect_handle_t h, uint32_t c, void* data, uint32_t size, void* reply, uint32_t* rsize) {
    return (*h)->command(h, c, size, data, rsize, reply);
}
static int32_t setParam(effect_handle_t h, std::vector<int32_t> p, std::vector<char> v) {
    auto b = mkParam(p, v); int32_t st = 12345; uint32_t rs = 4;
    int32_t r = cmd(h, EFFECT_CMD_SET_PARAM, b.data(), (uint32_t)b.size(), &st, &rs);
    return r != 0 ? r : st;
}
static effect_config_t mkCfg(uint32_t sr, uint32_t ch, uint8_t fmt, uint8_t acc = EFFECT_BUFFER_ACCESS_WRITE) {
    effect_config_t c; memset(&c, 0, sizeof(c));
    for (buffer_config_t* b : {&c.inputCfg, &c.outputCfg}) { b->samplingRate = sr; b->channels = ch; b->format = fmt; b->mask = EFFECT_CONFIG_ALL; }
    c.outputCfg.accessMode = acc; return c;
}
static effect_handle_t create() { effect_handle_t h = nullptr; int32_t r = AELI.create_effect(&kImpl, 1, 1, &h); return r == 0 ? h : nullptr; }
static bool configure(effect_handle_t h, effect_config_t c) {
    int32_t st = 99; uint32_t rs = 4; int32_t r = cmd(h, EFFECT_CMD_SET_CONFIG, &c, sizeof(c), &st, &rs); return r == 0 && st == 0;
}
static bool enable(effect_handle_t h) { int32_t st = 9; uint32_t rs = 4; return cmd(h, EFFECT_CMD_ENABLE, nullptr, 0, &st, &rs) == 0 && st == 0; }
static bool disable(effect_handle_t h) { int32_t st = 9; uint32_t rs = 4; return cmd(h, EFFECT_CMD_DISABLE, nullptr, 0, &st, &rs) == 0 && st == 0; }

int main() {
    CHECK(AELI.tag == AUDIO_EFFECT_LIBRARY_TAG, "tag");
    CHECK(AELI.version == EFFECT_LIBRARY_API_VERSION_3_0, "version");
    effect_descriptor_t d; CHECK(AELI.get_descriptor(&kImpl, &d) == 0 && d.uuid.timeLow == 0xeda64964, "descriptor");
    effect_uuid_t bad = kImpl; bad.timeLow ^= 1;
    effect_handle_t hh; CHECK(AELI.create_effect(&bad, 0, 0, &hh) == -EINVAL, "wrong uuid rejected");

    effect_handle_t fx = create(); CHECK(fx != nullptr, "create");
    int32_t st = 5; uint32_t rs = 4; CHECK(cmd(fx, EFFECT_CMD_INIT, nullptr, 0, &st, &rs) == 0 && st == 0, "init");

    // config validation
    effect_handle_t tmp = create();
    CHECK(!configure(tmp, mkCfg(48000, AUDIO_CHANNEL_OUT_MONO, AUDIO_FORMAT_PCM_FLOAT)), "mono rejected");
    CHECK(!configure(tmp, mkCfg(48000, AUDIO_CHANNEL_OUT_STEREO, AUDIO_FORMAT_PCM_32_BIT)), "32-bit rejected");
    CHECK(!configure(tmp, mkCfg(1000, AUDIO_CHANNEL_OUT_STEREO, AUDIO_FORMAT_PCM_FLOAT)), "1 kHz rejected");
    { effect_config_t c = mkCfg(48000, AUDIO_CHANNEL_OUT_STEREO, AUDIO_FORMAT_PCM_FLOAT); c.outputCfg.samplingRate = 44100; CHECK(!configure(tmp, c), "rate mismatch rejected"); }
    { effect_config_t c = mkCfg(48000, AUDIO_CHANNEL_OUT_STEREO, AUDIO_FORMAT_PCM_FLOAT); uint32_t r2 = 4; int32_t s2;
      CHECK(cmd(tmp, EFFECT_CMD_SET_CONFIG, &c, sizeof(c) - 1, &s2, &r2) == -EINVAL, "short config rejected"); }
    CHECK((*tmp)->process(tmp, nullptr, nullptr) == -EINVAL, "process null");
    audio_buffer_t nb; float t[8] = {0}; nb.frameCount = 4; nb.f32 = t;
    CHECK((*tmp)->process(tmp, &nb, &nb) == -EINVAL, "process before config rejected");
    AELI.release_effect(tmp);

    CHECK(configure(fx, mkCfg(48000, AUDIO_CHANNEL_OUT_STEREO, AUDIO_FORMAT_PCM_FLOAT)), "float config");
    CHECK(enable(fx), "enable");

    // global disabled -> bit-exact pass-through
    std::vector<float> in(2000 * 2), out(2000 * 2);
    for (size_t i = 0; i < in.size(); ++i) in[i] = 0.3f * std::sin(0.05f * i);
    audio_buffer_t bi, bo; bi.frameCount = bo.frameCount = 2000; bi.f32 = in.data(); bo.f32 = out.data();
    CHECK((*fx)->process(fx, &bi, &bo) == 0 && memcmp(in.data(), out.data(), in.size() * 4) == 0, "bit-exact bypass");

    // control instance (never configured/enabled) steers the processing instance through the shared store
    effect_handle_t ctl = create();
    CHECK(setParam(ctl, {K_GLOBAL_ENABLE}, i32(1)) == 0, "global enable via control instance");
    CHECK(setParam(ctl, {K_EQ_ENABLE}, i32(1)) == 0, "eq enable");
    CHECK(setParam(ctl, {K_EQ_BAND, 5}, [] { std::vector<char> v = i32(0); auto f = f32s({1000.f, 6.f, 1.f}); v.insert(v.end(), f.begin(), f.end()); return v; }()) == 0, "eq band");
    std::vector<float> sine(48000 * 2), outS(48000 * 2);
    for (int i = 0; i < 48000; ++i) sine[2 * i] = sine[2 * i + 1] = 0.2f * (float)std::sin(2 * M_PI * 1000.0 * i / 48000);
    bi.frameCount = bo.frameCount = 48000; bi.f32 = sine.data(); bo.f32 = outS.data();
    CHECK((*fx)->process(fx, &bi, &bo) == 0, "process");
    // process in several blocks so refresh() is exercised after the first block too
    double ri = 0, ro = 0; for (int i = 24000; i < 48000; ++i) { ri += sine[2 * i] * sine[2 * i]; ro += outS[2 * i] * outS[2 * i]; }
    double g = 10 * std::log10(ro / ri); CHECK(std::fabs(g - 6.0) < 0.3, "global EQ via shared store: %.2f dB", g);

    // stats
    { std::vector<char> b = mkParam({K_STATS}, {}); b.resize(sizeof(effect_param_t) + 4 + 8); effect_param_t* p = (effect_param_t*)b.data(); p->vsize = 0;
      std::vector<char> r(b.size()); uint32_t rsz = (uint32_t)r.size();
      CHECK(cmd(ctl, EFFECT_CMD_GET_PARAM, b.data(), sizeof(effect_param_t) + 4, r.data(), &rsz) == 0, "get stats");
      effect_param_t* rp = (effect_param_t*)r.data(); uint32_t procN, blocks; memcpy(&procN, rp->data + 4, 4); memcpy(&blocks, rp->data + 8, 4);
      CHECK(rp->status == 0 && rp->vsize == 8 && procN == 1 && blocks >= 2, "stats status=%d vsize=%u processing=%u blocks=%u", rp->status, rp->vsize, procN, blocks); }
    { std::vector<char> b = mkParam({K_PROTOCOL}, {}); std::vector<char> r(64); uint32_t rsz = 64;
      CHECK(cmd(ctl, EFFECT_CMD_GET_PARAM, b.data(), sizeof(effect_param_t) + 4, r.data(), &rsz) == 0, "get protocol");
      effect_param_t* rp = (effect_param_t*)r.data(); int32_t v; memcpy(&v, rp->data + 4, 4); CHECK(rp->status == 0 && v == 1, "protocol=%d", v); }

    // hostile input
    CHECK(setParam(ctl, {0x7777}, i32(1)) == -EINVAL, "unknown key");
    CHECK(setParam(ctl, {K_GLOBAL_ENABLE}, std::vector<char>(8)) == -EINVAL, "wrong value size");
    CHECK(setParam(ctl, {K_EQ_BAND, 99}, std::vector<char>(16)) == -EINVAL, "band index out of range");
    CHECK(setParam(ctl, {K_EQ_BAND, -1}, std::vector<char>(16)) == -EINVAL, "negative band index");
    { effect_param_t huge; memset(&huge, 0, sizeof(huge)); huge.psize = 0xFFFFFFF0u; huge.vsize = 0xFFFFFFF0u; int32_t s2 = 0; uint32_t r2 = 4;
      CHECK(cmd(ctl, EFFECT_CMD_SET_PARAM, &huge, sizeof(huge), &s2, &r2) == -EINVAL, "overflowing psize/vsize rejected"); }
    { auto b = mkParam({K_GLOBAL_ENABLE}, i32(1)); int32_t s2; uint32_t r2 = 4;
      CHECK(cmd(ctl, EFFECT_CMD_SET_PARAM, b.data(), (uint32_t)b.size() - 1, &s2, &r2) == -EINVAL, "truncated command rejected"); }
    { auto b = mkParam({K_PROTOCOL}, {}); std::vector<char> r(sizeof(effect_param_t)); uint32_t rsz = (uint32_t)r.size();
      CHECK(cmd(ctl, EFFECT_CMD_GET_PARAM, b.data(), sizeof(effect_param_t) + 4, r.data(), &rsz) == -ENOMEM, "reply buffer too small"); }
    CHECK(cmd(ctl, 0x12345, nullptr, 0, nullptr, nullptr) == -EINVAL, "unknown command");
    CHECK(setParam(ctl, {K_MASTER_GAIN}, f32s({NAN})) == 0, "NaN accepted but sanitized");
    CHECK(setParam(ctl, {K_LIMITER}, f32s({-0.3f, 80.f})) == 0, "limiter");
    CHECK(setParam(ctl, {K_BASS}, [] { std::vector<char> v = i32(1); auto f = f32s({6.f, 0.5f, 0.5f, 90.f}); v.insert(v.end(), f.begin(), f.end()); return v; }()) == 0, "bass");
    CHECK((*fx)->process(fx, &bi, &bo) == 0, "process after NaN gain");
    bool finite = true; for (float v : outS) if (!std::isfinite(v)) finite = false; CHECK(finite, "finite after hostile params");

    // 16-bit in/out, in-place
    effect_handle_t f16 = create();
    CHECK(configure(f16, mkCfg(44100, AUDIO_CHANNEL_OUT_STEREO, AUDIO_FORMAT_PCM_16_BIT)) && enable(f16), "s16 config");
    std::vector<int16_t> pcm(1000 * 2); for (size_t i = 0; i < pcm.size(); ++i) pcm[i] = (int16_t)(20000 * std::sin(0.03 * i));
    audio_buffer_t b16; b16.frameCount = 1000; b16.s16 = pcm.data();
    CHECK((*f16)->process(f16, &b16, &b16) == 0, "s16 in-place");
    int mx = 0; for (auto v : pcm) mx = std::max(mx, std::abs((int)v)); CHECK(mx <= 32767, "s16 range");

    // accumulate mode adds on top of existing output
    effect_handle_t acc = create();
    CHECK(configure(acc, mkCfg(48000, AUDIO_CHANNEL_OUT_STEREO, AUDIO_FORMAT_PCM_FLOAT, EFFECT_BUFFER_ACCESS_ACCUMULATE)) && enable(acc), "acc config");
    CHECK(setParam(ctl, {K_GLOBAL_ENABLE}, i32(0)) == 0, "global off");
    std::vector<float> a(200 * 2, 0.1f), o(200 * 2, 0.25f); audio_buffer_t ai, ao; ai.frameCount = ao.frameCount = 200; ai.f32 = a.data(); ao.f32 = o.data();
    CHECK((*acc)->process(acc, &ai, &ao) == 0 && std::fabs(o[10] - 0.35f) < 1e-6f, "accumulate adds (got %f)", o[10]);

    // stats count falls back to 0 after disable/release
    CHECK(disable(fx) && disable(f16) && disable(acc), "disable");
    AELI.release_effect(fx); AELI.release_effect(f16); AELI.release_effect(acc); AELI.release_effect(ctl);
    { effect_handle_t c2 = create(); std::vector<char> b = mkParam({K_STATS}, {}); std::vector<char> r(64); uint32_t rsz = 64;
      cmd(c2, EFFECT_CMD_GET_PARAM, b.data(), sizeof(effect_param_t) + 4, r.data(), &rsz);
      effect_param_t* rp = (effect_param_t*)r.data(); uint32_t procN; memcpy(&procN, rp->data + 4, 4); CHECK(procN == 0, "processing instances back to 0, got %u", procN); AELI.release_effect(c2); }

    printf("Effect tests: %d passed, %d failed\n", g_pass, g_fail);
    return g_fail ? 1 : 0;
}
