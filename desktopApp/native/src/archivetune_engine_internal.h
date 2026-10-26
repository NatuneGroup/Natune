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

/*
 * Pulls exactly `frames` interleaved stereo frames from a manual engine for the two-deck mixer. Frames
 * the deck cannot supply are silence and do not advance its position. Returns the number of decoded
 * frames actually consumed. Only a deck owned by a mixer may call this.
 */
uint32_t ae_engine_pull_mixed(ae_engine *engine, float *out, uint32_t frames);

#endif /* ARCHIVETUNE_ENGINE_INTERNAL_H */
