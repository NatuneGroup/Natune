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
int main(int argc, char **argv) {
    (void)checked_malloc; /* Keep the allocator tripwire even while the core uses calloc only. */
    CHECK(argc == 2);
    ring_fixture();
    decoder_fixture(argv[1]);
    return 0;
}
