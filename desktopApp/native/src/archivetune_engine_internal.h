/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

/*
 * Engine entry points shared by translation units of libarchivetune_engine but kept out of the public
 * ABI. Symbols here stay hidden from the shared library's dynamic table.
 */
#ifndef ARCHIVETUNE_ENGINE_INTERNAL_H
#define ARCHIVETUNE_ENGINE_INTERNAL_H

#include <stdint.h>

#include "archivetune_engine.h"
#include "archivetune_device.h"

/* Called only by a mixer owner, off the audio callback, before loads or when settings change. */
void ae_engine_direct_request(ae_engine *engine, int requested);
void ae_engine_output_changed(ae_engine *engine);
int ae_engine_pending_output(ae_engine *engine, ae_pcm_format *format);
int ae_engine_configure_output(ae_engine *engine, int direct, const ae_pcm_format *transport, uint32_t drain_frames);
void ae_engine_input_format(ae_engine *engine, ae_pcm_format *format);
uint32_t ae_engine_pull_native(ae_engine *engine, void *out, uint32_t frames, const ae_pcm_format *format);

/*
 * Pulls exactly `frames` interleaved stereo frames from a manual engine for the two-deck mixer. Frames
 * the deck cannot supply are silence and do not advance its position. Returns the number of decoded
 * frames actually consumed. Only a deck owned by a mixer may call this.
 */
uint32_t ae_engine_pull_mixed(ae_engine *engine, float *out, uint32_t frames);

#endif /* ARCHIVETUNE_ENGINE_INTERNAL_H */
