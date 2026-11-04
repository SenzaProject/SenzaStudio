// Legacy audio effect library (audio_effects.xml / EffectsFactory) exposing the SenzaStudio chain.
//
// Parameter model: ALL instances of this library inside one process share one parameter store
// (the "global store"). SET_PARAM on ANY instance — including a throw-away control instance created
// by the app — updates the store; every processing instance (attached by audio_effects.xml to the
// music output) picks the change up at its next block. This is what makes the app able to steer the
// effects that audioserver created for it.
#include <errno.h>
#include <string.h>

#include <atomic>
#include <mutex>
#include <new>

#include <hardware/audio_effect.h>

#include "senzafx_dsp.h"

#define SENZAFX_EXPORT __attribute__((visibility("default")))

namespace {

using senzafx::Chain;
using senzafx::Params;

constexpr effect_uuid_t kTypeUuid = {0x5999534d, 0x94c0, 0x45f5, 0x804f, {0x20, 0xae, 0x5f, 0x93, 0xec, 0x2e}};
constexpr effect_uuid_t kImplUuid = {0xeda64964, 0x49db, 0x43f9, 0x9629, {0xa5, 0x7e, 0x65, 0x34, 0x24, 0x78}};

// ---- parameter keys (first int32 of the parameter) ----
enum : int32_t {
    K_GLOBAL_ENABLE = 0x01,  // value: int32
    K_MASTER_GAIN = 0x02,    // value: float dB
    K_EQ_ENABLE = 0x10,      // value: int32
    K_EQ_BAND = 0x11,        // param: {key, index}; value: {int32 type, float hz, float gainDb, float q}
    K_EQ_MASK = 0x12,        // value: uint32 bit mask of used bands
    K_BASS = 0x20,           // value: {int32 on, float subDb, float harmonics, float punch, float crossoverHz}
    K_COMP = 0x30,           // value: {int32 on, thr, ratio, attackMs, releaseMs, kneeDb, makeupDb}
    K_LIMITER = 0x40,        // value: {float ceilingDb, float releaseMs}
    K_IMAGING = 0x50,        // value: {int32 on, midDb, sideDb, balance, monoBelowHz}
    K_PROTOCOL = 0xF0,       // GET: int32 protocol version
    K_STATS = 0xF1,          // GET: {uint32 processingInstances, uint32 blocksProcessed}
};
constexpr int32_t kProtocolVersion = 1;
constexpr uint32_t kChunkFrames = 256;

struct Store {
    std::mutex m;
    Params p;
    std::atomic<uint32_t> version{1};
    std::atomic<int32_t> processing{0};
    std::atomic<uint32_t> blocks{0};
};
Store& store() {
    static Store s;
#ifdef SENZAFX_SMOKETEST
    // Smoke-test build: starts ENABLED with an obvious +9 dB sub-bass shelf so you can hear that the global
    // path works BEFORE the app is involved. Never ship this variant as the normal build.
    static const bool once = [] {
        Params p;
        p.globalEnabled = 1;
        p.bass.enabled = 1;
        p.bass.subBassDb = 9.f;
        s.p = senzafx::sanitized(p);
        return true;
    }();
    (void)once;
#endif
    return s;
}

struct Ctx {
    const struct effect_interface_s* itfe;
    Chain* chain = nullptr;
    effect_config_t cfg;
    bool configured = false;
    bool enabled = false;
    bool counted = false;  // contributes to Store::processing
    bool active = false;   // globalEnabled as last seen
    uint32_t seen = 0;
};

inline int32_t padded(uint32_t n) { return (int32_t)(((n + 3u) / 4u) * 4u); }

float rdF(const char* p) { float v; memcpy(&v, p, 4); return v; }
int32_t rdI(const char* p) { int32_t v; memcpy(&v, p, 4); return v; }
uint32_t rdU(const char* p) { uint32_t v; memcpy(&v, p, 4); return v; }
void wrF(char* p, float v) { memcpy(p, &v, 4); }
void wrI(char* p, int32_t v) { memcpy(p, &v, 4); }
void wrU(char* p, uint32_t v) { memcpy(p, &v, 4); }

// Returns 0 on success, -EINVAL on any malformed parameter. Never reads outside [data, data+psize+vsize).
int32_t applyParam(const char* data, uint32_t psize, uint32_t vsize) {
    if (psize < 4) return -EINVAL;
    const int32_t key = rdI(data);
    const char* v = data + padded(psize);
    Store& s = store();
    std::lock_guard<std::mutex> lock(s.m);
    Params np = s.p;
    switch (key) {
        case K_GLOBAL_ENABLE: if (psize != 4 || vsize != 4) return -EINVAL; np.globalEnabled = rdI(v); break;
        case K_MASTER_GAIN: if (psize != 4 || vsize != 4) return -EINVAL; np.masterGainDb = rdF(v); break;
        case K_EQ_ENABLE: if (psize != 4 || vsize != 4) return -EINVAL; np.eqEnabled = rdI(v); break;
        case K_EQ_MASK: if (psize != 4 || vsize != 4) return -EINVAL; np.eqBandMask = rdU(v); break;
        case K_EQ_BAND: {
            if (psize != 8 || vsize != 16) return -EINVAL;
            const int32_t idx = rdI(data + 4);
            if (idx < 0 || idx >= senzafx::kMaxBands) return -EINVAL;
            senzafx::EqBandParams& b = np.eq[idx];
            b.type = rdI(v); b.freqHz = rdF(v + 4); b.gainDb = rdF(v + 8); b.q = rdF(v + 12);
            break;
        }
        case K_BASS:
            if (psize != 4 || vsize != 20) return -EINVAL;
            np.bass.enabled = rdI(v); np.bass.subBassDb = rdF(v + 4); np.bass.harmonics = rdF(v + 8);
            np.bass.punch = rdF(v + 12); np.bass.crossoverHz = rdF(v + 16);
            break;
        case K_COMP:
            if (psize != 4 || vsize != 28) return -EINVAL;
            np.comp.enabled = rdI(v); np.comp.thresholdDb = rdF(v + 4); np.comp.ratio = rdF(v + 8);
            np.comp.attackMs = rdF(v + 12); np.comp.releaseMs = rdF(v + 16); np.comp.kneeDb = rdF(v + 20);
            np.comp.makeupDb = rdF(v + 24);
            break;
        case K_LIMITER:
            if (psize != 4 || vsize != 8) return -EINVAL;
            np.limiter.ceilingDb = rdF(v); np.limiter.releaseMs = rdF(v + 4);
            break;
        case K_IMAGING:
            if (psize != 4 || vsize != 20) return -EINVAL;
            np.imaging.enabled = rdI(v); np.imaging.midGainDb = rdF(v + 4); np.imaging.sideGainDb = rdF(v + 8);
            np.imaging.balance = rdF(v + 12); np.imaging.monoBelowHz = rdF(v + 16);
            break;
        default: return -EINVAL;
    }
    s.p = senzafx::sanitized(np);
    s.version.fetch_add(1, std::memory_order_release);
    return 0;
}

// Fills value bytes for GET_PARAM; returns value size or negative errno.
int32_t readParam(const char* data, uint32_t psize, char* out, uint32_t outCap) {
    if (psize < 4) return -EINVAL;
    const int32_t key = rdI(data);
    Store& s = store();
    switch (key) {
        case K_PROTOCOL:
            if (outCap < 4) return -ENOMEM;
            wrI(out, kProtocolVersion);
            return 4;
        case K_STATS:
            if (outCap < 8) return -ENOMEM;
            wrU(out, (uint32_t)std::max<int32_t>(0, s.processing.load(std::memory_order_relaxed)));
            wrU(out + 4, s.blocks.load(std::memory_order_relaxed));
            return 8;
        case K_GLOBAL_ENABLE: {
            if (outCap < 4) return -ENOMEM;
            std::lock_guard<std::mutex> lock(s.m);
            wrI(out, s.p.globalEnabled);
            return 4;
        }
        case K_MASTER_GAIN: {
            if (outCap < 4) return -ENOMEM;
            std::lock_guard<std::mutex> lock(s.m);
            wrF(out, s.p.masterGainDb);
            return 4;
        }
        default: return -EINVAL;
    }
}

void refresh(Ctx* c) {
    Store& s = store();
    const uint32_t v = s.version.load(std::memory_order_acquire);
    if (v == c->seen) return;
    if (!s.m.try_lock()) return;  // never block the audio thread; retry next block
    const Params p = s.p;
    s.m.unlock();
    c->seen = v;
    if (p.globalEnabled && !c->active) c->chain->reset();
    c->active = p.globalEnabled != 0;
    c->chain->configure(p);
}

void setCounted(Ctx* c, bool on) {
    if (on == c->counted) return;
    c->counted = on;
    store().processing.fetch_add(on ? 1 : -1, std::memory_order_relaxed);
}

bool supportedConfig(const buffer_config_t& b) {
    if (b.channels != AUDIO_CHANNEL_OUT_STEREO) return false;
    if (b.format != AUDIO_FORMAT_PCM_16_BIT && b.format != AUDIO_FORMAT_PCM_FLOAT) return false;
    if (b.samplingRate < 8000 || b.samplingRate > 192000) return false;
    return true;
}

int32_t fxProcess(effect_handle_t self, audio_buffer_t* in, audio_buffer_t* out) {
    Ctx* c = (Ctx*)self;
    if (c == nullptr || in == nullptr || out == nullptr || in->raw == nullptr || out->raw == nullptr) return -EINVAL;
    if (!c->configured) return -EINVAL;
    size_t frames = std::min(in->frameCount, out->frameCount);
    const bool is16 = c->cfg.inputCfg.format == AUDIO_FORMAT_PCM_16_BIT;
    const bool accumulate = c->cfg.outputCfg.accessMode == EFFECT_BUFFER_ACCESS_ACCUMULATE;
    const bool outIs16 = c->cfg.outputCfg.format == AUDIO_FORMAT_PCM_16_BIT;
    store().blocks.fetch_add(1, std::memory_order_relaxed);
    refresh(c);
    const bool process = c->active;

    float scratch[kChunkFrames * 2];
    size_t done = 0;
    while (done < frames) {
        const uint32_t n = (uint32_t)std::min<size_t>(kChunkFrames, frames - done);
        const size_t off = done * 2;
        if (is16) {
            const int16_t* src = in->s16 + off;
            for (uint32_t i = 0; i < n * 2; ++i) scratch[i] = (float)src[i] * (1.0f / 32768.0f);
        } else {
            memcpy(scratch, in->f32 + off, n * 2 * sizeof(float));
        }
        if (process) c->chain->process(scratch, (int)n);
        if (outIs16) {
            int16_t* dst = out->s16 + off;
            for (uint32_t i = 0; i < n * 2; ++i) {
                float x = scratch[i] * 32768.0f;
                x = x > 32767.0f ? 32767.0f : (x < -32768.0f ? -32768.0f : x);
                const int32_t q = (int32_t)(x >= 0 ? x + 0.5f : x - 0.5f);
                dst[i] = accumulate ? (int16_t)std::max(-32768, std::min(32767, (int32_t)dst[i] + q)) : (int16_t)q;
            }
        } else {
            float* dst = out->f32 + off;
            if (accumulate) { for (uint32_t i = 0; i < n * 2; ++i) dst[i] += scratch[i]; }
            else memcpy(dst, scratch, n * 2 * sizeof(float));
        }
        done += n;
    }
    return 0;
}

const effect_descriptor_t* descriptor() {
    static effect_descriptor_t d = [] {
        effect_descriptor_t x;
        memset(&x, 0, sizeof(x));
        x.type = kTypeUuid;
        x.uuid = kImplUuid;
        x.apiVersion = EFFECT_CONTROL_API_VERSION;
        x.flags = EFFECT_FLAG_TYPE_INSERT | EFFECT_FLAG_INSERT_LAST;
        x.cpuLoad = 100;  // 10.0 MIPS (rough upper bound, units of 0.1 MIPS)
        x.memoryUsage = 16;
        strncpy(x.name, "Senza Studio FX", EFFECT_STRING_LEN_MAX - 1);
        strncpy(x.implementor, "SenzaProject", EFFECT_STRING_LEN_MAX - 1);
        return x;
    }();
    return &d;
}

bool uuidEq(const effect_uuid_t* a, const effect_uuid_t& b) {
    return a && a->timeLow == b.timeLow && a->timeMid == b.timeMid && a->timeHiAndVersion == b.timeHiAndVersion &&
           a->clockSeq == b.clockSeq && memcmp(a->node, b.node, 6) == 0;
}

bool replyInt(uint32_t* replySize, void* pReply, int32_t v) {
    if (replySize == nullptr || pReply == nullptr || *replySize < sizeof(int32_t)) return false;
    *(int32_t*)pReply = v;
    *replySize = sizeof(int32_t);
    return true;
}

int32_t fxCommand(effect_handle_t self, uint32_t cmd, uint32_t cmdSize, void* pCmd, uint32_t* replySize, void* pReply) {
    Ctx* c = (Ctx*)self;
    if (c == nullptr) return -EINVAL;
    switch (cmd) {
        case EFFECT_CMD_INIT:
            return replyInt(replySize, pReply, 0) ? 0 : -EINVAL;
        case EFFECT_CMD_SET_CONFIG: {
            if (pCmd == nullptr || cmdSize != sizeof(effect_config_t)) return -EINVAL;
            if (replySize == nullptr || pReply == nullptr || *replySize != sizeof(int32_t)) return -EINVAL;
            const effect_config_t* cfg = (const effect_config_t*)pCmd;
            int32_t status = 0;
            if (!supportedConfig(cfg->inputCfg) || !supportedConfig(cfg->outputCfg) ||
                cfg->inputCfg.samplingRate != cfg->outputCfg.samplingRate) {
                status = -EINVAL;
            } else if (cfg->outputCfg.accessMode != EFFECT_BUFFER_ACCESS_WRITE &&
                       cfg->outputCfg.accessMode != EFFECT_BUFFER_ACCESS_ACCUMULATE) {
                status = -EINVAL;
            } else {
                if (c->chain == nullptr || c->cfg.inputCfg.samplingRate != cfg->inputCfg.samplingRate || !c->configured) {
                    Chain* fresh = new (std::nothrow) Chain((int)cfg->inputCfg.samplingRate);
                    if (fresh == nullptr) { status = -ENOMEM; }
                    else { delete c->chain; c->chain = fresh; c->seen = 0; c->active = false; }
                }
                if (status == 0) { c->cfg = *cfg; c->configured = true; }
            }
            *(int32_t*)pReply = status;
            return 0;
        }
        case EFFECT_CMD_GET_CONFIG:
            if (pReply == nullptr || replySize == nullptr || *replySize != sizeof(effect_config_t) || !c->configured) return -EINVAL;
            memcpy(pReply, &c->cfg, sizeof(effect_config_t));
            return 0;
        case EFFECT_CMD_RESET:
            if (c->chain) c->chain->reset();
            return 0;
        case EFFECT_CMD_ENABLE:
            if (!c->configured) return -ENODATA;
            c->enabled = true;
            setCounted(c, true);
            return replyInt(replySize, pReply, 0) ? 0 : -EINVAL;
        case EFFECT_CMD_DISABLE:
            if (!c->configured) return -ENODATA;
            c->enabled = false;
            setCounted(c, false);
            return replyInt(replySize, pReply, 0) ? 0 : -EINVAL;
        case EFFECT_CMD_SET_PARAM: {
            if (pCmd == nullptr || cmdSize < sizeof(effect_param_t) || replySize == nullptr || pReply == nullptr ||
                *replySize != sizeof(int32_t))
                return -EINVAL;
            const effect_param_t* p = (const effect_param_t*)pCmd;
            // Overflow-safe size check: psize/vsize come from the caller.
            if (p->psize > EFFECT_PARAM_SIZE_MAX || p->vsize > EFFECT_PARAM_SIZE_MAX) return -EINVAL;
            const uint64_t need = (uint64_t)sizeof(effect_param_t) + (uint64_t)padded(p->psize) + p->vsize;
            if (need > cmdSize) return -EINVAL;
            *(int32_t*)pReply = applyParam(p->data, p->psize, p->vsize);
            return 0;
        }
        case EFFECT_CMD_GET_PARAM: {
            if (pCmd == nullptr || cmdSize < sizeof(effect_param_t) || replySize == nullptr || pReply == nullptr ||
                *replySize < sizeof(effect_param_t))
                return -EINVAL;
            const effect_param_t* p = (const effect_param_t*)pCmd;
            if (p->psize > EFFECT_PARAM_SIZE_MAX) return -EINVAL;
            if ((uint64_t)sizeof(effect_param_t) + p->psize > cmdSize) return -EINVAL;
            const uint64_t head = (uint64_t)sizeof(effect_param_t) + padded(p->psize);
            if (head > *replySize) return -ENOMEM;
            effect_param_t* r = (effect_param_t*)pReply;
            if (r != p) memmove(r, p, sizeof(effect_param_t) + p->psize);
            const uint32_t psize = r->psize;
            const int32_t got = readParam(r->data, psize, r->data + padded(psize), (uint32_t)(*replySize - head));
            if (got < 0) { r->status = got; r->vsize = 0; *replySize = (uint32_t)head; }
            else { r->status = 0; r->vsize = (uint32_t)got; *replySize = (uint32_t)(head + (uint32_t)got); }
            return 0;
        }
        case EFFECT_CMD_SET_DEVICE:
        case EFFECT_CMD_SET_VOLUME:
        case EFFECT_CMD_SET_AUDIO_MODE:
        case EFFECT_CMD_SET_INPUT_DEVICE:
        case EFFECT_CMD_OFFLOAD:
        case EFFECT_CMD_SET_PARAM_DEFERRED:
        case EFFECT_CMD_SET_PARAM_COMMIT:
            if (replySize != nullptr && pReply != nullptr && *replySize >= sizeof(int32_t)) *(int32_t*)pReply = 0;
            return 0;
        default:
            return -EINVAL;
    }
}

int32_t fxGetDescriptor(effect_handle_t self, effect_descriptor_t* pDescriptor) {
    if (self == nullptr || pDescriptor == nullptr) return -EINVAL;
    *pDescriptor = *descriptor();
    return 0;
}

const struct effect_interface_s kItfe = {fxProcess, fxCommand, fxGetDescriptor, nullptr};

int32_t libCreate(const effect_uuid_t* uuid, int32_t /*sessionId*/, int32_t /*ioId*/, effect_handle_t* pHandle) {
    if (uuid == nullptr || pHandle == nullptr || !uuidEq(uuid, kImplUuid)) return -EINVAL;
    Ctx* c = new (std::nothrow) Ctx();
    if (c == nullptr) return -ENOMEM;
    c->itfe = &kItfe;
    memset(&c->cfg, 0, sizeof(c->cfg));
    *pHandle = (effect_handle_t)c;
    return 0;
}

int32_t libRelease(effect_handle_t handle) {
    Ctx* c = (Ctx*)handle;
    if (c == nullptr) return -EINVAL;
    setCounted(c, false);
    delete c->chain;
    delete c;
    return 0;
}

int32_t libGetDescriptor(const effect_uuid_t* uuid, effect_descriptor_t* pDescriptor) {
    if (uuid == nullptr || pDescriptor == nullptr || !uuidEq(uuid, kImplUuid)) return -EINVAL;
    *pDescriptor = *descriptor();
    return 0;
}

}  // namespace

extern "C" {
SENZAFX_EXPORT audio_effect_library_t AUDIO_EFFECT_LIBRARY_INFO_SYM = {
    AUDIO_EFFECT_LIBRARY_TAG,
    EFFECT_LIBRARY_API_VERSION_3_0,
    "Senza Studio FX library",
    "SenzaProject",
    libCreate,
    libRelease,
    libGetDescriptor,
    nullptr,
};
}
