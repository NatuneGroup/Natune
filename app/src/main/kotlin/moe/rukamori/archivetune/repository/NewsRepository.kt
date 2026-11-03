/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.repository

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import moe.rukamori.archivetune.BuildConfig
import moe.rukamori.archivetune.models.NewsItem
import moe.rukamori.archivetune.shared.data.news.NatuneNewsClient
import moe.rukamori.archivetune.shared.data.news.invalidNewsResponse
import moe.rukamori.archivetune.shared.domain.repository.ContentNews
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NewsRepository
    @Inject
    constructor() {
        // A process-lifetime singleton, like the original repository: its OkHttp client is never closed because the
        // feed is read for as long as the app runs and the process exit releases the pool.
        private val feed =
            NatuneNewsClient(
                client =
                    HttpClient(OkHttp) {
                        engine {
                            config { retryOnConnectionFailure(false) }
                        }
                    },
                baseUrl = BuildConfig.NATUNE_NEWS_URL,
            )

        /** Newest first; a failed refresh answers the last index that loaded and throws only before one has. */
        suspend fun fetchNews(): List<NewsItem> = feed.list().map(::toNewsItem)

        suspend fun fetchNewsContent(id: String): String = feed.content(id)

        fun getCachedItem(id: String): NewsItem? = feed.cachedItem(id)?.let(::toNewsItem)

        internal companion object {
            fun invalidReason(
                statusCode: Int,
                contentType: String?,
                body: String?,
            ): String? = invalidNewsResponse(statusCode, contentType, body)

            private fun toNewsItem(news: ContentNews): NewsItem =
                NewsItem(
                    id = news.id,
                    title = news.title,
                    description = news.description,
                    imageUrls = news.imageUrls,
                    important = news.important,
                    author = news.author,
                    timestamp = news.timestamp,
                )
        }
    }
