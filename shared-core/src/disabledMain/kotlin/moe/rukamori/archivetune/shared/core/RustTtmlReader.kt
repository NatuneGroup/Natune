package moe.rukamori.archivetune.shared.core

import moe.rukamori.archivetune.shared.domain.lyrics.TtmlDocumentReader

/** A build without native artifacts cannot select the experimental reader. */
fun rustTtmlReaderOrNull(): TtmlDocumentReader? = null
