#include "ATAudioFormat.h"
#include <stdio.h>
#include <stdlib.h>

#define CHECK(value) do { if (!(value)) { fprintf(stderr, "CHECK failed at %s:%d: %s\n", __FILE__, __LINE__, #value); exit(1); } } while (0)

static void format_validation(void) {
    at_audio_format format = {123.0, 9};
    CHECK(!at_audio_canonical_format(0.0, 2, 0, &format));
    CHECK(!at_audio_canonical_format(-48000.0, 2, 0, &format));
    CHECK(!at_audio_canonical_format(NAN, 2, 0, &format));
    CHECK(!at_audio_canonical_format(INFINITY, 2, 0, &format));
    CHECK(!at_audio_canonical_format(48000.0, 0, 0, &format));
    CHECK(!at_audio_canonical_format(48000.0, 6, 0, &format));
    CHECK(!at_audio_canonical_format(48000.0, 6, 2, &format));
    CHECK(!at_audio_canonical_format(48000.0, 2, 0, NULL));
    CHECK(format.sample_rate == 123.0 && format.channels == 9); // Refusal never leaves a guessed format.
    CHECK(at_audio_canonical_format(44100.0, 2, 0, &format));
    CHECK(format.sample_rate == 44100.0 && format.channels == 2);
    CHECK(at_audio_canonical_format(16000.0, 1, 1, &format));
    CHECK(format.sample_rate == 16000.0 && format.channels == 1);
    CHECK(at_audio_canonical_format(96000.0, 6, 6, &format));
    CHECK(format.sample_rate == 96000.0 && format.channels == 2);
    puts("PASS invalid/zero/nonfinite rates, mono/stereo routes, validated multichannel layout");
}

static void planar_conversion(void) {
    const float interleaved[] = {0.1f, -0.2f, 0.3f, -0.4f, 0.5f, -0.6f, 0.0f, 0.0f};
    float left[] = {9, 9, 9, 9, 9}, right[] = {9, 9, 9, 9, 9};
    at_audio_deinterleave(interleaved, left + 1, right + 1, 4);
    CHECK(left[0] == 9 && right[0] == 9);
    for (uint32_t i = 0; i < 4; ++i) {
        CHECK(left[i + 1] == interleaved[i * 2]);
        CHECK(right[i + 1] == interleaved[i * 2 + 1]);
    }
    at_audio_deinterleave(NULL, NULL, NULL, 0);
    // A real render quantum can exceed scratch: conversion must work over bounded chunks without losing a channel.
    float pcm[AT_AUDIO_RENDER_CHUNK * 2];
    float planar_left[AT_AUDIO_RENDER_CHUNK + 7], planar_right[AT_AUDIO_RENDER_CHUNK + 7];
    for (uint32_t i = 0; i < AT_AUDIO_RENDER_CHUNK; ++i) { pcm[i * 2] = (float)i; pcm[i * 2 + 1] = -(float)i; }
    at_audio_deinterleave(pcm, planar_left, planar_right, AT_AUDIO_RENDER_CHUNK);
    at_audio_deinterleave(pcm, planar_left + AT_AUDIO_RENDER_CHUNK, planar_right + AT_AUDIO_RENDER_CHUNK, 7);
    for (uint32_t i = 0; i < AT_AUDIO_RENDER_CHUNK + 7; ++i) {
        const float expected = (float)(i % AT_AUDIO_RENDER_CHUNK);
        CHECK(planar_left[i] == expected && planar_right[i] == -expected);
    }
    puts("PASS interleaved-to-planar conversion, channel separation, silent tail, bounded chunk offsets");
}

int main(void) { format_validation(); planar_conversion(); return 0; }
