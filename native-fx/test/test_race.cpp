// Control thread hammers SET_PARAM while the "audio thread" processes. Run under ThreadSanitizer.
#include <atomic>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <thread>
#include <vector>
#include <hardware/audio_effect.h>
extern "C" audio_effect_library_t AELI;
static const effect_uuid_t kImpl = {0xeda64964, 0x49db, 0x43f9, 0x9629, {0xa5, 0x7e, 0x65, 0x34, 0x24, 0x78}};
static int32_t cmd(effect_handle_t h, uint32_t c, void* d, uint32_t n, void* r, uint32_t* rs) { return (*h)->command(h, c, n, d, rs, r); }
int main() {
    effect_handle_t fx, ctl; AELI.create_effect(&kImpl, 0, 0, &fx); AELI.create_effect(&kImpl, 0, 0, &ctl);
    effect_config_t c; memset(&c, 0, sizeof(c));
    for (buffer_config_t* b : {&c.inputCfg, &c.outputCfg}) { b->samplingRate = 48000; b->channels = AUDIO_CHANNEL_OUT_STEREO; b->format = AUDIO_FORMAT_PCM_FLOAT; b->mask = EFFECT_CONFIG_ALL; }
    int32_t st; uint32_t rs = 4; cmd(fx, EFFECT_CMD_SET_CONFIG, &c, sizeof(c), &st, &rs); rs = 4; cmd(fx, EFFECT_CMD_ENABLE, nullptr, 0, &st, &rs);
    std::atomic<bool> stop{false}; std::atomic<int> sets{0};
    std::thread ctrl([&] {
        char buf[sizeof(effect_param_t) + 8]; int i = 0;
        while (!stop) {
            effect_param_t* p = (effect_param_t*)buf; p->psize = 4; p->vsize = 4; int32_t key = (i & 1) ? 0x02 : 0x01; float val = (float)((i % 13) - 6);
            memcpy(p->data, &key, 4); if (key == 0x01) { int32_t on = (i / 2) & 1; memcpy(p->data + 4, &on, 4); } else memcpy(p->data + 4, &val, 4);
            int32_t s; uint32_t r = 4; cmd(ctl, EFFECT_CMD_SET_PARAM, buf, sizeof(buf), &s, &r); ++sets; ++i;
        }
    });
    std::vector<float> b(480 * 2, 0.2f); audio_buffer_t ab; ab.frameCount = 480; ab.f32 = b.data(); bool ok = true;
    for (int n = 0; n < 20000; ++n) { if ((*fx)->process(fx, &ab, &ab) != 0) ok = false; for (float v : b) if (!std::isfinite(v)) ok = false; for (auto& v : b) v = 0.2f; }
    stop = true; ctrl.join();
    printf("race test: %s (%d param writes during 20000 blocks)\n", ok ? "OK" : "FAIL", sets.load());
    return ok ? 0 : 1;
}
