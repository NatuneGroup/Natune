/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package app.natune.di

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import app.natune.repository.PlaylistCoverRepository

/**
 * Entry point for [PlaylistCoverRepository] at call sites that cannot take constructor injection —
 * the playlist menu deletes a playlist and has to take its cover copy with it.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface PlaylistCoverEntryPoint {
    fun playlistCoverRepository(): PlaylistCoverRepository
}
