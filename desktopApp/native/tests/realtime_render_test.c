/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#define CHECK(value) do { if (!(value)) { fprintf(stderr, "CHECK failed at %s:%d: %s\n", __FILE__, __LINE__, #value); exit(1); } } while (0)
static _Thread_local int in_callback;
static void outside_callback(void) { CHECK(!in_callback); }
static int checked_lock(pthread_mutex_t *mutex) { outside_callback(); return pthread_mutex_lock(mutex); }
static int checked_wait(pthread_cond_t *condition, pthread_mutex_t *mutex) {
    outside_callback(); return pthread_cond_wait(condition, mutex);
}
static int checked_timedwait(pthread_cond_t *condition, pthread_mutex_t *mutex, const struct timespec *time) {
    outside_callback(); return pthread_cond_timedwait(condition, mutex, time);
}
static int checked_broadcast(pthread_cond_t *condition) { outside_callback(); return pthread_cond_broadcast(condition); }
static void *checked_malloc(size_t size) { outside_callback(); return malloc(size); }
static void *checked_calloc(size_t count, size_t size) { outside_callback(); return calloc(count, size); }
static void checked_free(void *pointer) { outside_callback(); free(pointer); }
static char *checked_strdup(const char *string) { outside_callback(); return strdup(string); }

/* White-box access locks the actual engine mutex; no test hooks or substitute engine enter the ABI. */
#define pthread_mutex_lock checked_lock
#define pthread_cond_wait checked_wait
#define pthread_cond_timedwait checked_timedwait
#define pthread_cond_broadcast checked_broadcast
#define malloc checked_malloc
#define calloc checked_calloc
#define free checked_free
#define strdup checked_strdup
#include "../src/archivetune_engine.c"
#undef pthread_mutex_lock
#undef pthread_cond_wait
#undef pthread_cond_timedwait
#undef pthread_cond_broadcast
#undef malloc
#undef calloc
#undef free
#undef strdup

static uint32_t callback(ae_engine *e, float *out, uint32_t frames) {
    in_callback = 1;
    uint32_t taken = ae_engine_render_realtime(e, out, frames);
    in_callback = 0;
    return taken;
}
static double seconds(void) {
    struct timespec time;
    clock_gettime(CLOCK_MONOTONIC, &time);
    return time.tv_sec + time.tv_nsec / 1e9;
}
static ae_engine *create(void) {
    ae_config config = {AE_ABI_VERSION, AE_OUTPUT_MANUAL, AE_BACKEND_NULL, 48000, 2000, 200};
    char error[512];
    ae_engine *e = ae_engine_create(&config, error, sizeof error);
    CHECK(e != NULL);
    return e;
}
static void zeroes(const float *out, uint32_t frames) {
    for (uint32_t i = 0; i < frames * 2; ++i) CHECK(out[i] == 0);
}
static void ring_fixture(void) {
    ae_engine *e = create();
    float out[16], pcm[] = {0.1f, 0.1f, 0.2f, 0.2f, 0.3f, 0.3f};
    memset(out, 0x3f, sizeof out);
    CHECK(callback(NULL, out, 8) == 0); zeroes(out, 8);
    CHECK(callback(e, NULL, 8) == 0);
    double start = seconds();
    for (int i = 0; i < 1000; ++i) CHECK(callback(e, out, 8) == 0);
    CHECK(seconds() - start < 0.2);
    zeroes(out, 8);
    pthread_mutex_lock(&e->mutex);
    e->has_source = e->ready = e->play_intent = e->filling = e->seekable = 1;
    uint64_t epoch = e->epoch;
    pthread_mutex_unlock(&e->mutex);
    CHECK(append_frames(e, epoch, pcm, 3, 1.0) == 0);
    CHECK(callback(e, out, 8) == 3); /* Below prebuffer, actual decoded count, silent tail. */
    CHECK(memcmp(out, pcm, sizeof pcm) == 0); zeroes(out + 6, 5);
    ae_snapshot snapshot;
    ae_engine_snapshot(e, &snapshot);
    CHECK(snapshot.position_us == 63 && (snapshot.flags & AE_FLAG_ADVANCING));
    CHECK(callback(e, out, 8) == 0); zeroes(out, 8);
    ae_engine_snapshot(e, &snapshot);
    CHECK(snapshot.position_us == 63 && !(snapshot.flags & AE_FLAG_ADVANCING));
    CHECK(snapshot.flags & AE_FLAG_BUFFERING);
    CHECK(append_frames(e, epoch, pcm, 3, 2.0) == 0);
    pthread_mutex_lock(&e->mutex);
    e->advancing = 1; /* A previous callback must not leave a false advancing flag on contention. */
    start = seconds();
    CHECK(callback(e, out, 8) == 0);
    CHECK(seconds() - start < 0.1);
    CHECK(e->ring_count == 3 && e->position_us == 62.5 && !e->advancing);
    zeroes(out, 8);
    pthread_mutex_unlock(&e->mutex);
    ae_engine_pause(e);
    CHECK(callback(e, out, 8) == 0); zeroes(out, 8);
    ae_engine_play(e);
    CHECK(callback(e, out, 8) == 3);
    ae_engine_snapshot(e, &snapshot);
    CHECK(snapshot.position_us == 188); /* 3*1x + 3*2x frames, not requested or silent frames. */
    ae_engine_seek(e, 1000000);
    CHECK(callback(e, out, 8) == 0); zeroes(out, 8);
    ae_engine_snapshot(e, &snapshot);
    CHECK(snapshot.position_us == 1000000 && !(snapshot.flags & AE_FLAG_ADVANCING));
    pthread_mutex_lock(&e->mutex);
    e->manual = 0;
    pthread_mutex_unlock(&e->mutex);
    CHECK(callback(e, out, 8) == 0); zeroes(out, 8);
    ae_engine_destroy(e);
    puts("PASS realtime invalid/starvation/trylock/partial/prebuffer/tempo/pause/resume/seek accounting");
}
static void decoder_fixture(const char *path) {
    ae_engine *e = create();
    float out[4096 * 2];
    CHECK(ae_engine_load(e, path, 0, 1) != 0);
    uint32_t total = 0;
    double deadline = seconds() + 10;
    ae_snapshot snapshot;
    do {
        uint32_t taken = callback(e, out, 4096);
        for (uint32_t i = 0; i < taken; ++i) {
            float expected = ((int)((total + i) % 4096) - 2048) / 4096.0f;
            CHECK(fabsf(out[i * 2] - expected) <= 1e-6f);
            CHECK(fabsf(out[i * 2 + 1] - expected) <= 1e-6f);
        }
        zeroes(out + taken * 2, 4096 - taken);
        total += taken;
        ae_engine_snapshot(e, &snapshot);
        CHECK(llabs(snapshot.position_us - llround(total * 1e6 / 48000.0)) <= 1);
        if (!taken) {
            CHECK(!(snapshot.flags & AE_FLAG_ADVANCING));
            struct timespec pause = {0, 1000000};
            nanosleep(&pause, NULL); /* Only the fixture waits, never the output callback. */
        }
        CHECK(seconds() < deadline);
    } while (!(snapshot.flags & AE_FLAG_ENDED));
    CHECK(total == 192000 && snapshot.position_us == 4000000);
    CHECK(callback(e, out, 4096) == 0);
    ae_engine_destroy(e);
    puts("PASS realtime actual FFmpeg WAV decode/refill/EOF and exact consumed-frame position");
}
typedef struct pcm_memory { uint8_t bytes[44 + 256 * 8]; size_t length, position; int closes; } pcm_memory;
static int32_t pcm_open(void *opaque, int64_t *size, int32_t *seekable) {
    pcm_memory *memory = opaque; memory->position = 0; *size = (int64_t)memory->length; *seekable = 1; return 0;
}
static int32_t pcm_read(void *opaque, uint8_t *out, uint32_t size) {
    pcm_memory *memory = opaque;
    size_t remaining = memory->length - memory->position;
    size_t count = remaining < size ? remaining : size;
    if (!count) return AE_IO_EOF;
    memcpy(out, memory->bytes + memory->position, count); memory->position += count; return (int32_t)count;
}
static int64_t pcm_seek(void *opaque, int64_t position) {
    pcm_memory *memory = opaque;
    if (position < 0 || (uint64_t)position > memory->length) return AE_IO_FAILED;
    memory->position = (size_t)position; return position;
}
static void pcm_close(void *opaque) { ((pcm_memory *)opaque)->closes++; }
static void pcm_u16(uint8_t *out, uint16_t value) { out[0] = (uint8_t)value; out[1] = (uint8_t)(value >> 8); }
static void pcm_u32(uint8_t *out, uint32_t value) {
    for (int i = 0; i < 4; i++) out[i] = (uint8_t)(value >> (i * 8));
}
static int unexpected_pcm_processor(void *opaque, float *samples, uint32_t frames, uint32_t rate,
    uint64_t generation, double gain, double peak) {
    (void)opaque; (void)samples; (void)frames; (void)rate; (void)generation; (void)gain; (void)peak;
    CHECK(0 && "direct PCM must not enter software processing"); return -1;
}

/* Actual FFmpeg decode through the host-source ABI. Manual output characterizes samples, not a physical device. */
static void direct_pcm_fixture(uint32_t bits, uint32_t transport) {
    const int32_t samples32[] = {INT32_MIN, INT32_MAX, -1, 0, 1, 0x01020304, 0x7ffffffe, (int32_t)0x80000001u};
    const int32_t samples24[] = {-8388608, 8388607, -1, 0, 1, 0x010203, 8388606, -8388607};
    const int32_t samples16[] = {-32768, 32767, -1, 0, 1, 0x0102, 32766, -32767};
    const int32_t *values = bits == 32 ? samples32 : bits == 24 ? samples24 : samples16;
    pcm_memory memory = {0};
    uint32_t bytes = bits / 8u, stride = bytes * 2u, data_size = 256u * stride;
    memory.length = 44u + data_size;
    memcpy(memory.bytes, "RIFF", 4); pcm_u32(memory.bytes + 4, 36u + data_size);
    memcpy(memory.bytes + 8, "WAVEfmt ", 8); pcm_u32(memory.bytes + 16, 16);
    pcm_u16(memory.bytes + 20, 1); pcm_u16(memory.bytes + 22, 2);
    pcm_u32(memory.bytes + 24, 44100); pcm_u32(memory.bytes + 28, 44100u * stride);
    pcm_u16(memory.bytes + 32, (uint16_t)stride); pcm_u16(memory.bytes + 34, (uint16_t)bits);
    memcpy(memory.bytes + 36, "data", 4); pcm_u32(memory.bytes + 40, data_size);
    for (uint32_t frame = 0; frame < 256; frame++) for (uint32_t channel = 0; channel < 2; channel++) {
        uint32_t value = (uint32_t)values[(frame + channel) % 8];
        for (uint32_t byte = 0; byte < bytes; byte++) memory.bytes[44u + frame * stride + channel * bytes + byte] = (uint8_t)(value >> (byte * 8));
    }
    ae_engine *engine = create();
    ae_pcm_processor processor = {NULL, unexpected_pcm_processor, NULL};
    CHECK(ae_engine_set_pcm_processor(engine, &processor) == 0);
    ae_engine_direct_request(engine, 1);
    ae_stream_io io = {AE_STREAM_IO_VERSION, &memory, pcm_open, pcm_read, pcm_seek, NULL, pcm_close};
    CHECK(ae_engine_load_stream(engine, &io, 0, 1) != 0);
    ae_pcm_format format;
    double deadline = seconds() + 10;
    while (!ae_engine_pending_output(engine, &format)) {
        CHECK(seconds() < deadline);
        struct timespec pause = {0, 1000000}; nanosleep(&pause, NULL);
    }
    CHECK(format.rate == 44100 && format.channels == 2 && format.bits == bits);
    format.format = transport;
    CHECK(ae_engine_configure_output(engine, 1, &format));
    uint32_t sample_bytes = pcm_bytes(transport), total = 0;
    uint8_t out[8 * 2 * 4];
    ae_snapshot snapshot;
    do {
        in_callback = 1;
        uint32_t count = ae_engine_pull_native(engine, out, 8, &format);
        in_callback = 0;
        for (uint32_t frame = 0; frame < count; frame++) for (uint32_t channel = 0; channel < 2; channel++) {
            uint32_t expected = (uint32_t)values[(total + frame + channel) % 8];
            if (transport == AE_PCM_S32 && bits < 32) expected <<= 32u - bits;
            uint32_t actual = 0;
            for (uint32_t byte = 0; byte < sample_bytes; byte++) actual |= (uint32_t)out[(frame * 2u + channel) * sample_bytes + byte] << (byte * 8);
            uint32_t mask = sample_bytes == 4 ? UINT32_MAX : (1u << (sample_bytes * 8u)) - 1u;
            CHECK(actual == (expected & mask));
        }
        total += count;
        ae_engine_snapshot(engine, &snapshot);
        CHECK(snapshot.error_code == 0 && seconds() < deadline);
        if (!count) { struct timespec pause = {0, 1000000}; nanosleep(&pause, NULL); }
    } while (!(snapshot.flags & AE_FLAG_ENDED));
    CHECK(total == 256 && llabs(snapshot.position_us - llround(256.0 * 1e6 / 44100.0)) <= 1);
    ae_engine_destroy(engine);
    CHECK(memory.closes == 1);
    printf("PASS direct PCM %ubit through native format %u, exact integers and no software processing\n", bits, transport);
}

int main(int argc, char **argv) {
    (void)checked_malloc; /* Keep the allocator tripwire even while the core uses calloc only. */
    CHECK(argc == 2);
    ring_fixture();
    decoder_fixture(argv[1]);
    direct_pcm_fixture(16, AE_PCM_S16);
    direct_pcm_fixture(16, AE_PCM_S32);
    direct_pcm_fixture(24, AE_PCM_S24);
    direct_pcm_fixture(24, AE_PCM_S32);
    direct_pcm_fixture(32, AE_PCM_S32);
    return 0;
}
