/* Private producer-side direct PCM lane; included once after the engine's ring and FFmpeg helpers. */
#ifndef ARCHIVETUNE_ENGINE_DIRECT_H
#define ARCHIVETUNE_ENGINE_DIRECT_H

static uint32_t pcm_bytes(uint32_t format) {
    return format == AE_PCM_U8 ? 1u : format == AE_PCM_S16 ? 2u : format == AE_PCM_S24 ? 3u :
        format == AE_PCM_S32 || format == AE_PCM_F32 ? 4u : 0u;
}

static ae_pcm_format decoded_format(int format, int rate, int channels, int bits) {
    int packed = av_get_packed_sample_fmt(format);
    uint32_t type = packed == AV_SAMPLE_FMT_U8 ? AE_PCM_U8 : packed == AV_SAMPLE_FMT_S16 ? AE_PCM_S16 :
        packed == AV_SAMPLE_FMT_S32 ? (bits == 24 ? AE_PCM_S24 : AE_PCM_S32) : packed == AV_SAMPLE_FMT_FLT ? AE_PCM_F32 : AE_PCM_UNKNOWN;
    uint32_t width = pcm_bytes(type) * 8u;
    return (ae_pcm_format){rate > 0 ? (uint32_t)rate : 0, channels > 0 ? (uint32_t)channels : 0, type,
        bits > 0 && (uint32_t)bits <= width ? (uint32_t)bits : width};
}

void ae_engine_direct_request(ae_engine *e, int requested) {
    if (!e) return;
    pthread_mutex_lock(&e->mutex);
    if (e->direct_requested != !!requested) {
        e->direct_requested = !!requested;
        e->output_revision++;
        pthread_cond_broadcast(&e->changed);
    }
    pthread_mutex_unlock(&e->mutex);
}

void ae_engine_input_format(ae_engine *e, ae_pcm_format *format) {
    memset(format, 0, sizeof *format);
    if (!e) return;
    pthread_mutex_lock(&e->mutex); *format = e->input_pcm; pthread_mutex_unlock(&e->mutex);
}

void ae_engine_output_changed(ae_engine *e) {
    pthread_mutex_lock(&e->mutex); e->output_revision++; pthread_cond_broadcast(&e->changed); pthread_mutex_unlock(&e->mutex);
}

int ae_engine_pending_output(ae_engine *e, ae_pcm_format *format) {
    if (!e) return 0;
    pthread_mutex_lock(&e->mutex);
    int pending = e->format_pending && e->has_source && !e->failed;
    if (pending) *format = e->input_pcm;
    pthread_mutex_unlock(&e->mutex);
    return pending;
}

int ae_engine_configure_output(ae_engine *e, int direct, const ae_pcm_format *transport, uint32_t drain_frames) {
    pthread_mutex_lock(&e->mutex);
    if (!e->format_pending) { pthread_mutex_unlock(&e->mutex); return 0; }
    ae_pcm_format pcm = direct ? e->input_pcm : (ae_pcm_format){e->shared_rate, 2, AE_PCM_F32, 32};
    if (direct && transport) {
        int widening = (e->input_pcm.format == AE_PCM_S16 || e->input_pcm.format == AE_PCM_S24) && transport->format == AE_PCM_S32;
        if (transport->rate != pcm.rate || transport->channels != pcm.channels ||
            (transport->format != pcm.format && !widening)) { pthread_mutex_unlock(&e->mutex); return 0; }
        pcm = *transport;
    }
    uint32_t stride = pcm_bytes(pcm.format) * pcm.channels;
    if (!stride || pcm.rate < 8000 || pcm.rate > 384000 || pcm.channels > 8) {
        pthread_mutex_unlock(&e->mutex); return 0;
    }
    uint64_t capacity = (uint64_t)pcm.rate * e->ring_ms / 1000u;
    if (capacity < AE_MIN_RING_FRAMES) capacity = AE_MIN_RING_FRAMES;
    if (capacity > (16u * 1024u * 1024u) / stride) capacity = (16u * 1024u * 1024u) / stride;
    size_t bytes = (size_t)capacity * stride;
    if (bytes > e->ring_bytes) {
        void *ring = calloc(1, bytes);
        if (!ring) { pthread_mutex_unlock(&e->mutex); return 0; }
        free(e->ring); e->ring = ring; e->ring_bytes = bytes;
    }
    ring_clear_locked(e);
    e->capacity = (uint32_t)capacity;
    e->high_water = e->capacity - e->capacity / 4u;
    uint64_t prebuffer = (uint64_t)pcm.rate * e->prebuffer_ms / 1000u;
    e->prebuffer = (uint32_t)(prebuffer < e->capacity / 2u ? prebuffer : e->capacity / 2u);
    e->rate = pcm.rate;
    e->output_pcm = pcm;
    e->direct_enabled = !!direct;
    e->output_drain_frames = direct ? drain_frames : 0;
    e->format_pending = 0;
    pthread_cond_broadcast(&e->changed);
    pthread_mutex_unlock(&e->mutex);
    return 1;
}

static int prepare_direct_output(ae_engine *e, ae_source *s, const ae_pcm_format *format, uint64_t epoch,
                                 char *message, size_t len) {
    pthread_mutex_lock(&e->mutex);
    if (e->shutdown || epoch != e->epoch) { pthread_mutex_unlock(&e->mutex); return -1; }
    ae_pcm_format copy = *format;
    s->pcm = copy;
    e->input_pcm = copy;
    uint64_t revision = e->output_revision;
    if (e->direct_requested || e->direct_enabled || revision != s->output_revision) {
        e->format_pending = 1;
        e->ready = 0;
        pthread_cond_broadcast(&e->changed);
        struct timespec deadline;
        deadline_after(&deadline, INT64_C(15000000000));
        while (e->format_pending && !e->shutdown && epoch == e->epoch) {
            if (wait_changed_locked(e, &deadline) == ETIMEDOUT) {
                snprintf(message, len, "native output negotiation timed out");
                pthread_mutex_unlock(&e->mutex); return -1;
            }
        }
    }
    s->direct_pcm = e->direct_enabled;
    s->output_revision = revision;
    s->graph_tempo = s->direct_pcm ? 1.0 : s->graph_tempo;
    s->graph_pitch = 0.0;
    int stale = e->shutdown || epoch != e->epoch;
    pthread_mutex_unlock(&e->mutex);
    if (s->direct_pcm && s->graph) {
        avfilter_graph_free(&s->graph); s->buffer_src = NULL; s->buffer_sink = NULL;
    }
    return stale ? -1 : 0;
}

/* Drain the previous rate/format before reopening the device. A paused live stream waits until resume. */
static int drain_before_output_change(ae_engine *e, uint64_t epoch) {
    pthread_mutex_lock(&e->mutex);
    while (!e->shutdown && epoch == e->epoch && !e->pending_seek && (e->ring_count > 0 || e->output_tail_frames > 0)) {
        struct timespec deadline; deadline_after(&deadline, INT64_C(20000000));
        wait_changed_locked(e, &deadline);
    }
    int stale = e->shutdown || epoch != e->epoch || e->pending_seek;
    if (!stale) e->ready = 0;
    pthread_mutex_unlock(&e->mutex);
    return stale ? 1 : 0;
}

static int append_direct_frame(ae_engine *e, ae_source *s, uint64_t epoch, uint32_t first_sample) {
    uint32_t frames = (uint32_t)s->frame->nb_samples;
    uint32_t bytes = pcm_bytes(e->output_pcm.format), channels = s->pcm.channels;
    uint32_t source_bytes = (uint32_t)av_get_bytes_per_sample(s->frame->format);
    int planar = av_sample_fmt_is_planar(s->frame->format);
    uint32_t done = first_sample;
    pthread_mutex_lock(&e->mutex);
    while (done < frames) {
        while (!e->shutdown && epoch == e->epoch && !e->pending_seek && !room_for_locked(e, 1.0)) wait_for_output_space_locked(e);
        if (e->shutdown || epoch != e->epoch || e->pending_seek) { pthread_mutex_unlock(&e->mutex); return 1; }
        uint32_t count = min_u32(e->capacity - e->ring_count, frames - done);
        uint32_t last = (e->seg_head + e->seg_count - 1) % AE_SEGMENTS;
        if (!e->seg_count || e->seg_tempo[last] != 1.0) {
            last = (e->seg_head + e->seg_count) % AE_SEGMENTS;
            e->seg_frames[last] = 0; e->seg_tempo[last] = 1.0; e->seg_count++;
        }
        e->seg_frames[last] += count;
        for (uint32_t frame = 0; frame < count; frame++) {
            uint32_t offset = (e->ring_head + e->ring_count + frame) % e->capacity;
            uint8_t *target = (uint8_t *)e->ring + (size_t)offset * channels * bytes;
            for (uint32_t channel = 0; channel < channels; channel++) {
                const uint8_t *source = planar ? s->frame->extended_data[channel] + (size_t)(done + frame) * source_bytes :
                    s->frame->extended_data[0] + ((size_t)(done + frame) * channels + channel) * source_bytes;
                uint8_t *sample = target + (size_t)channel * bytes;
                if (e->output_pcm.format == AE_PCM_S24) {
                    uint32_t value; memcpy(&value, source, 4); value >>= 8;
                    sample[0] = (uint8_t)value; sample[1] = (uint8_t)(value >> 8); sample[2] = (uint8_t)(value >> 16);
                } else if (s->pcm.format == AE_PCM_S16 && e->output_pcm.format == AE_PCM_S32) {
                    int16_t value; memcpy(&value, source, 2);
                    uint32_t widened = (uint32_t)(int32_t)value << 16;
                    memcpy(sample, &widened, 4);
                } else memcpy(sample, source, bytes);
            }
        }
        e->ring_count += count; done += count;
        pthread_cond_broadcast(&e->changed);
    }
    pthread_mutex_unlock(&e->mutex);
    return 0;
}

static int synchronize_frame_output(ae_engine *e, ae_source *s, uint64_t epoch, char *message, size_t len) {
    ae_pcm_format format = decoded_format(s->frame->format, s->frame->sample_rate > 0 ? s->frame->sample_rate : s->decoder->sample_rate,
        s->frame->ch_layout.nb_channels > 0 ? s->frame->ch_layout.nb_channels : s->decoder->ch_layout.nb_channels,
        s->decoder->bits_per_raw_sample);
    if (format.format == AE_PCM_S24) {
        int planar = av_sample_fmt_is_planar(s->frame->format);
        for (int frame = 0; !s->precision_unreliable && frame < s->frame->nb_samples; frame++) {
            for (uint32_t channel = 0; channel < format.channels; channel++) {
                const uint8_t *sample = planar ? s->frame->extended_data[channel] + (size_t)frame * 4u :
                    s->frame->extended_data[0] + ((size_t)frame * format.channels + channel) * 4u;
                uint32_t value; memcpy(&value, sample, 4);
                if (value & 0xffu) { s->precision_unreliable = 1; break; }
            }
        }
        if (s->precision_unreliable) { format.format = AE_PCM_S32; format.bits = 32; }
    }
    if (format.rate != s->pcm.rate || format.channels != s->pcm.channels || format.format != s->pcm.format || format.bits != s->pcm.bits) {
        pthread_mutex_lock(&e->mutex);
        int requested = e->direct_requested;
        if (epoch != e->epoch || e->shutdown) { pthread_mutex_unlock(&e->mutex); return 1; }
        if (!requested) { e->input_pcm = format; s->pcm = format; }
        pthread_mutex_unlock(&e->mutex);
        if (!requested) return 0;
        if (s->buffer_src && !s->direct_pcm) {
            int status = av_buffersrc_add_frame(s->buffer_src, NULL);
            if (status < 0 || drain_sink(e, s, epoch, message, len) < 0) return -1;
        }
        int status = drain_before_output_change(e, epoch);
        if (status != 0) return status;
        if (prepare_direct_output(e, s, &format, epoch, message, len) < 0) return -1;
        pthread_mutex_lock(&e->mutex); e->ready = 1; e->filling = 1; pthread_mutex_unlock(&e->mutex);
        if (!s->direct_pcm && build_graph(e, s, 1.0, s->trim_seconds, e->rate, message, len) < 0) return -1;
    }
    return 0;
}

static int direct_frame(ae_engine *e, ae_source *s, uint64_t epoch, char *message, size_t len) {
    (void)message; (void)len;
    uint32_t first = 0;
    if (s->trim_seconds >= 0 && s->frame->pts != AV_NOPTS_VALUE) {
        int64_t start = av_rescale_q(s->frame->pts, s->time_base, (AVRational){1, (int)s->pcm.rate});
        int64_t target = (int64_t)llround(s->trim_seconds * s->pcm.rate);
        if (target > start) first = (uint32_t)((target - start) < s->frame->nb_samples ? target - start : s->frame->nb_samples);
    }
    return append_direct_frame(e, s, epoch, first);
}

uint32_t ae_engine_pull_native(ae_engine *e, void *out, uint32_t frames, const ae_pcm_format *format) {
    uint32_t stride = pcm_bytes(format->format) * format->channels;
    if (e) atomic_store(&e->advancing, 0);
    memset(out, format->format == AE_PCM_U8 ? 128 : 0, (size_t)frames * stride);
    if (!e || pthread_mutex_trylock(&e->mutex) != 0) return 0;
    e->advancing = 0;
    /* Each following callback lets previously submitted PCM leave the hardware queue. This also
       runs during prebuffer starvation/pauses, when the device keeps consuming silence. */
    e->output_tail_frames = e->output_tail_frames > frames ? e->output_tail_frames - frames : 0;
    if (!e->direct_enabled || e->output_pcm.format != format->format || e->output_pcm.channels != format->channels ||
        e->output_pcm.rate != format->rate || !e->has_source || e->failed || !e->play_intent || !e->ready || e->ended ||
        (e->filling && e->ring_count < e->prebuffer && !e->eof)) {
        pthread_mutex_unlock(&e->mutex); return 0;
    }
    e->filling = 0; e->buffering = 0;
    uint32_t remaining = min_u32(frames, e->ring_count), taken = remaining;
    uint8_t *output = out;
    while (remaining > 0) {
        uint32_t segment = e->seg_head;
        uint32_t count = min_u32(remaining, e->seg_frames[segment]);
        uint32_t first = min_u32(count, e->capacity - e->ring_head);
        memcpy(output, (uint8_t *)e->ring + (size_t)e->ring_head * stride, (size_t)first * stride);
        if (count > first) memcpy(output + (size_t)first * stride, e->ring, (size_t)(count - first) * stride);
        e->ring_head = (e->ring_head + count) % e->capacity; e->ring_count -= count;
        e->position_us += frames_to_us(count, 1.0, e->rate);
        e->seg_frames[segment] -= count;
        if (!e->seg_frames[segment]) { e->seg_head = (segment + 1) % AE_SEGMENTS; e->seg_count--; }
        remaining -= count; output += (size_t)count * stride;
    }
    if (taken < frames && !e->eof) { e->filling = 1; e->buffering = 1; }
    e->advancing = taken > 0;
    if (taken && e->output_drain_frames) e->output_tail_frames = (uint64_t)e->output_drain_frames + frames;
    if (e->eof && !e->ring_count && !e->output_tail_frames) e->ended = 1;
    pthread_mutex_unlock(&e->mutex);
    return taken;
}
#endif
