/* Native device negotiation belongs to the host command thread, never the device callback. */
#ifndef ARCHIVETUNE_MIXER_OUTPUT_H
#define ARCHIVETUNE_MIXER_OUTPUT_H

static void raw_output_pull(void *user, void *out, uint32_t frames) {
    ae_mixer *m = user;
    uint32_t bytes = m->raw_pcm.format == AE_PCM_U8 ? 1u : m->raw_pcm.format == AE_PCM_S16 ? 2u :
        m->raw_pcm.format == AE_PCM_S24 ? 3u : 4u;
    if (pthread_mutex_trylock(&m->mutex) != 0) {
        memset(out, m->raw_pcm.format == AE_PCM_U8 ? 128 : 0, (size_t)frames * m->raw_pcm.channels * bytes); return;
    }
    ae_engine_pull_native(m->deck[m->active], out, frames, &m->raw_pcm);
    if (!m->output_enabled) memset(out, m->raw_pcm.format == AE_PCM_U8 ? 128 : 0, (size_t)frames * m->raw_pcm.channels * bytes);
    m->timeline += frames;
    pthread_mutex_unlock(&m->mutex);
}

static void shared_output_pull(void *user, void *out, uint32_t frames) { mixer_pull(user, out, frames); }

static ae_device *open_shared_output(ae_mixer *m, char *error, size_t len) {
    ae_device_request request = {{m->shared_rate, 2, AE_PCM_F32, 32}, 0, m->backend == AE_BACKEND_NULL,
        m->device_id[0] ? m->device_id : NULL};
    ae_device *device = ae_device_open_pcm(&request, shared_output_pull, m, error, len);
    if (!device && request.device_id) {
        request.device_id = NULL;
        device = ae_device_open_pcm(&request, shared_output_pull, m, error, len);
        if (device) snprintf(m->output_state.reason, sizeof m->output_state.reason,
            "The selected device is unavailable; using the shared system output");
    }
    return device;
}

static void close_output(ae_mixer *m) {
    /* Uninit joins the callback. Taking the mixer lock before this would deadlock its callback. */
    ae_device *device = m->device;
    m->device = NULL;
    ae_device_close(device);
    pthread_mutex_lock(&m->mutex);
    m->raw_output = 0;
    memset(&m->output_state.sink, 0, sizeof m->output_state.sink);
    m->output_state.direct_pcm = 0;
    pthread_mutex_unlock(&m->mutex);
}

void ae_mixer_output_enabled(ae_mixer *m, int enabled) {
    if (!m) return;
    pthread_mutex_lock(&m->mutex); m->output_enabled = !!enabled; pthread_mutex_unlock(&m->mutex);
}

void ae_mixer_set_output(ae_mixer *m, int exclusive, const char *device_id) {
    if (!m) return;
    const char *id = device_id ? device_id : "";
    if (strlen(id) >= sizeof m->device_id) return;
    int changed = m->exclusive_requested != !!exclusive || strcmp(m->device_id, id) != 0;
    if (!changed) return;
    m->exclusive_requested = !!exclusive;
    snprintf(m->device_id, sizeof m->device_id, "%s", id);
    m->output_state.requested = !!exclusive;
    ae_engine_direct_request(m->deck[m->active], exclusive);
    ae_engine_output_changed(m->deck[m->active]);
    /* A device-only change also renegotiates at the current position when the source is seekable. */
    ae_snapshot state;
    ae_engine_snapshot(m->deck[m->active], &state);
    if (state.flags & AE_FLAG_SEEKABLE) ae_engine_seek(m->deck[m->active], (uint64_t)state.position_us);
    else if (state.flags & AE_FLAG_HAS_SOURCE) snprintf(m->output_state.reason, sizeof m->output_state.reason,
        "Output change will apply after buffered live audio drains");
}

void ae_mixer_prepare_track(ae_mixer *m) {
    if (!m) return;
    if (m->exclusive_requested || m->raw_output) close_output(m);
    ae_engine_direct_request(m->deck[m->active], m->exclusive_requested);
    memset(&m->output_state.input, 0, sizeof m->output_state.input);
    m->output_state.requested = m->exclusive_requested;
    if (m->exclusive_requested) snprintf(m->output_state.reason, sizeof m->output_state.reason, "Waiting for the decoded track format");
    else if (!m->device) {
        char error[256] = {0};
        m->device = open_shared_output(m, error, sizeof error);
        if (!m->device) snprintf(m->output_state.reason, sizeof m->output_state.reason, "%s", error);
        m->rate = m->shared_rate;
    }
}

void ae_mixer_output_tick(ae_mixer *m) {
    if (!m) return;
    ae_engine *engine = m->deck[m->active];
    ae_snapshot snapshot;
    ae_engine_snapshot(engine, &snapshot);
    ae_engine_input_format(engine, &m->output_state.input);
    if (!(snapshot.flags & AE_FLAG_HAS_SOURCE) || snapshot.flags & AE_FLAG_ENDED || snapshot.error_code) {
        if (m->raw_output) close_output(m);
        return;
    }
    ae_pcm_format format;
    if (!ae_engine_pending_output(engine, &format)) {
        if (m->device) ae_device_state_read(m->device, &m->output_state.sink);
        return;
    }
    int compatible = m->raw_pcm.format == format.format ||
        ((format.format == AE_PCM_S16 || format.format == AE_PCM_S24) && m->raw_pcm.format == AE_PCM_S32);
    int same = m->raw_output && m->exclusive_requested && m->raw_pcm.rate == format.rate &&
        m->raw_pcm.channels == format.channels && compatible && m->raw_pcm.bits >= format.bits;
    if (same && ae_engine_configure_output(engine, 1, &m->raw_pcm, ae_device_buffer_frames(m->device))) return;
    close_output(m);
    m->output_state.input = format;
    char refusal[256] = {0};
    if (m->exclusive_requested) {
        m->raw_pcm = format;
        ae_device_request request = {format, 1, m->backend == AE_BACKEND_NULL, m->device_id[0] ? m->device_id : NULL};
        m->device = ae_device_open_pcm(&request, raw_output_pull, m, refusal, sizeof refusal);
        if (!m->device && (format.format == AE_PCM_S16 || format.format == AE_PCM_S24)) {
            m->raw_pcm.format = AE_PCM_S32;
            request.pcm = m->raw_pcm;
            m->device = ae_device_open_pcm(&request, raw_output_pull, m, refusal, sizeof refusal);
        }
        if (m->device && ae_engine_configure_output(engine, 1, &m->raw_pcm, ae_device_buffer_frames(m->device))) {
            m->raw_output = 1;
            m->rate = format.rate;
            m->output_state.direct_pcm = 1;
            ae_device_state_read(m->device, &m->output_state.sink);
            m->output_state.reason[0] = '\0';
            return;
        }
        if (m->device) { close_output(m); snprintf(refusal, sizeof refusal, "Direct PCM buffers could not be allocated"); }
    }
    char error[256] = {0};
    m->device = open_shared_output(m, error, sizeof error);
    m->rate = m->shared_rate;
    if (!ae_engine_configure_output(engine, 0, NULL, 0)) snprintf(error, sizeof error, "Shared PCM buffers could not be configured");
    if (m->device) ae_device_state_read(m->device, &m->output_state.sink);
    if (m->exclusive_requested) {
#if defined(__linux__)
        snprintf(m->output_state.reason, sizeof m->output_state.reason,
            "ALSA exclusive output refused or device busy (PipeWire/Pulse may hold it): %.220s. Shared fallback: %.200s", refusal, error);
#else
        snprintf(m->output_state.reason, sizeof m->output_state.reason, "Exclusive output refused: %.240s. Shared fallback: %.200s", refusal, error);
#endif
    } else if (error[0]) snprintf(m->output_state.reason, sizeof m->output_state.reason, "%s", error);
    else m->output_state.reason[0] = '\0';
}

void ae_mixer_output_snapshot(ae_mixer *m, ae_mixer_output_state *out) {
    memset(out, 0, sizeof *out);
    if (!m) return;
    pthread_mutex_lock(&m->mutex); *out = m->output_state; out->muted = !m->output_enabled; pthread_mutex_unlock(&m->mutex);
}

uint32_t ae_mixer_output_devices(ae_device_description *out, uint32_t capacity, char *error, size_t len) {
    return ae_device_list(out, capacity, error, len);
}
#endif
