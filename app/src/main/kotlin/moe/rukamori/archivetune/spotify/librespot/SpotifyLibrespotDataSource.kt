package moe.rukamori.archivetune.spotify.librespot

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import moe.rukamori.archivetune.shared.domain.source.SpotifyLibrespotFailure
import moe.rukamori.archivetune.shared.domain.source.SpotifyLibrespotAudioPolicy
import moe.rukamori.archivetune.spotify.echo.EchoDecryptingDataSource

/** Admission wraps the cache too: logout must fail the next read even for already cached bytes. */
internal class SpotifyLibrespotDataSource(
    private val audio: SpotifyLibrespotAudio,
    private val delegateFactory: DataSource.Factory,
    private val guardOnlyLibrespot: Boolean = false,
) : DataSource {
    private val listeners = mutableListOf<TransferListener>()
    private var delegate: DataSource? = null
    private var stream: SpotifyLibrespotAudio.Stream? = null
    private var currentUri: Uri? = null
    private var guarded = false

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
        delegate?.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        guarded = !guardOnlyLibrespot || dataSpec.uri.scheme == SpotifyLibrespotAudioPolicy.SCHEME
        stream = if (guarded) audio.stream(dataSpec.uri)
            ?: throw SpotifyLibrespotAudioException(SpotifyLibrespotFailure.ACCOUNT_CHANGED) else null
        currentUri = dataSpec.uri
        val selected = delegateFactory.createDataSource()
        delegate = selected
        listeners.forEach(selected::addTransferListener)
        return try {
            selected.open(dataSpec)
        } catch (error: Exception) {
            close()
            throw error
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (guarded && stream?.authorized() != true) throw SpotifyLibrespotAudioException(SpotifyLibrespotFailure.ACCOUNT_CHANGED)
        return checkNotNull(delegate).read(buffer, offset, length)
    }

    override fun getUri(): Uri? = if (guarded) currentUri else delegate?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = delegate?.responseHeaders ?: emptyMap()

    override fun close() {
        try { delegate?.close() } finally { delegate = null; stream = null; currentUri = null; guarded = false }
    }

    class Factory(audio: SpotifyLibrespotAudio, upstreamFactory: DataSource.Factory, cache: Cache? = null) : DataSource.Factory {
        private val factory = EchoDecryptingDataSource.Factory(upstreamFactory, { audio.stream(it)?.ref }, 32 * 1024)
        private val delegateFactory: DataSource.Factory = if (cache == null) factory else CacheDataSource.Factory().setCache(cache)
            .setUpstreamDataSourceFactory(factory)
            .setCacheWriteDataSinkFactory(CacheDataSink.Factory().setCache(cache).setFragmentSize(C.LENGTH_UNSET.toLong()))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        private val owner = audio

        override fun createDataSource(): DataSource = SpotifyLibrespotDataSource(owner, delegateFactory)
    }

    /** Used after URI resolution, outside both playback caches. Other providers retain their source. */
    class GuardFactory(private val audio: SpotifyLibrespotAudio, private val delegateFactory: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = SpotifyLibrespotDataSource(audio, delegateFactory, guardOnlyLibrespot = true)
    }
}
