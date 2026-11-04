// MINIMAL typedefs so the AOSP effect headers compile outside the AOSP tree (NDK/host builds). Not AOSP code.
#pragma once
#include <stdint.h>
#include <stdbool.h>
#include <string.h>
#include <sys/types.h>
typedef uint32_t audio_channel_mask_t;
typedef uint32_t audio_devices_t;
typedef int32_t  audio_session_t;
typedef int32_t  audio_io_handle_t;
typedef int32_t  audio_source_t;
typedef int32_t  audio_mode_t;
typedef int32_t  audio_format_t;
#define AUDIO_CHANNEL_OUT_STEREO 0x3u
#define AUDIO_CHANNEL_OUT_MONO   0x1u
#define AUDIO_FORMAT_PCM_16_BIT  0x1
#define AUDIO_FORMAT_PCM_FLOAT   0x5
#define AUDIO_FORMAT_PCM_32_BIT  0x3
typedef struct audio_uuid_s {
    uint32_t timeLow; uint16_t timeMid; uint16_t timeHiAndVersion; uint16_t clockSeq; uint8_t node[6];
} audio_uuid_t;
