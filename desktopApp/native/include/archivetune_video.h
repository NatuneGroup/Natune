/*
 * C ABI of the desktop video artwork reader.
 *
 * A reader decodes the video-only stream of a music video on its own thread and presents the frame that matches
 * the clock its host sets, so the picture follows the audio engine rather than a clock of its own. It never makes
 * a sound and never holds more than two converted frames. Every function is safe to call from any thread.
 */
#ifndef ARCHIVETUNE_VIDEO_H
#define ARCHIVETUNE_VIDEO_H

#include <stddef.h>
#include <stdint.h>

#include "archivetune_engine.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef struct ae_video ae_video;

typedef enum ae_video_state {
    AE_VIDEO_OPENING = 0,
    AE_VIDEO_READY = 1,
    AE_VIDEO_ENDED = 2,
    AE_VIDEO_FAILED = 3,
} ae_video_state;

typedef struct ae_video_frame_info {
    uint64_t sequence; /* increments on every presented frame, 0 before the first */
    int32_t width;     /* of the presented frame, in pixels */
    int32_t height;
    int32_t state;     /* ae_video_state */
    int64_t pts_us;    /* media time of the presented frame */
} ae_video_frame_info;

/*
 * Starts a reader for an HTTPS video stream. Opening happens on the reader thread, so this returns at once; a
 * failure shows up as AE_VIDEO_FAILED. Frames taller than max_height are scaled down. Returns NULL only when
 * the reader cannot start at all. A reader starts inactive: nothing is downloaded or decoded until
 * ae_video_set_active turns it on.
 */
AE_EXPORT ae_video *ae_video_open(const char *url, const char *user_agent, int32_t max_height);

/* The media position the picture should show, whether it advances, and how fast. */
AE_EXPORT void ae_video_set_clock(ae_video *video, int64_t position_us, int32_t playing, float speed);

/* An inactive reader idles without network or decoding work; it seeks to the clock when it wakes again. */
AE_EXPORT void ae_video_set_active(ae_video *video, int32_t active);

/*
 * Copies the presented frame as BGRA rows of width * 4 bytes when its sequence differs from known_sequence.
 * Returns 1 when it copied, 0 when nothing new is presented, and -1 when capacity is smaller than the frame;
 * info is filled in every case, so the caller can size its buffer and ask again.
 */
AE_EXPORT int32_t ae_video_copy_frame(ae_video *video, uint64_t known_sequence, uint8_t *out, size_t capacity,
                                      ae_video_frame_info *info);

/* Copies the newest failure message and returns its length without the terminating NUL. */
AE_EXPORT size_t ae_video_last_error(ae_video *video, char *buffer, size_t buffer_len);

/* Stops the reader thread, waits for it and frees everything. */
AE_EXPORT void ae_video_close(ae_video *video);

#ifdef __cplusplus
}
#endif

#endif /* ARCHIVETUNE_VIDEO_H */
