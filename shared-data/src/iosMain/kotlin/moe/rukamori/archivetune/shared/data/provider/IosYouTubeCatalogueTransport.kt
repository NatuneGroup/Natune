package moe.rukamori.archivetune.shared.data.provider

import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.models.YouTubeClient
import com.metrolist.innertubex.models.response.BrowseResponse
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException

/** The browse id of the home feed, as the Android `YouTube.home` request uses it. */
private const val HOME_BROWSE_ID = "FEmusic_home"

/** The browse id and params of the charts sheet; the params select the chart page rather than the base tab. */
private const val CHARTS_BROWSE_ID = "FEmusic_charts"
private const val CHARTS_PARAMS = "ggMGCgQIgAQ%3D"

/** The explore page, and the new-release album page the release chips read. */
private const val EXPLORE_BROWSE_ID = "FEmusic_explore"
private const val NEW_RELEASES_BROWSE_ID = "FEmusic_new_releases_albums"

/**
 * The iOS shelf transport: the home feed, charts, the explore page and new releases over the InnerTubeX session the
 * catalogue already uses, so a signed-in home feed and the account listings read the same session. The renderer JSON
 * is mapped by the shared section-list parsers in commonMain, which keeps the shelf vocabulary identical to the
 * desktop host's.
 *
 * The home feed asks for the signed-in session, as the Android home screen does. A region-pinned request is not
 * offered: InnerTubeX sends the locale the session carries, and this host has no separate region preference.
 */
class IosYouTubeCatalogueTransport(
    private val innerTube: InnerTube,
) : YouTubeCatalogueTransport {

    override suspend fun home(continuation: String?, params: String?): YouTubeShelfPage {
        val response =
            if (continuation == null) {
                innerTube.browse(
                    YouTubeClient.WEB_REMIX,
                    browseId = HOME_BROWSE_ID,
                    params = params,
                    setLogin = true,
                )
            } else {
                innerTube.browse(YouTubeClient.WEB_REMIX, continuation = continuation, setLogin = true)
            }.body<BrowseResponse>()
        if (continuation != null) {
            // A continuation page carries its own section list and no chips.
            return youTubeHomeShelfPage(response.continuationContents?.sectionListContinuation)
        }
        val contents = response.contents
        return youTubeHomeShelfPage(sectionListOf(contents))
    }

    override suspend fun charts(continuation: String?): YouTubeShelfPage {
        val response =
            innerTube.browse(
                YouTubeClient.WEB_REMIX,
                browseId = CHARTS_BROWSE_ID,
                params = CHARTS_PARAMS,
                continuation = continuation,
            ).body<BrowseResponse>()
        val contents = response.contents
        return youTubeChartsShelfPage(
            sectionList = sectionListOf(contents),
            continuationSectionList = response.continuationContents?.sectionListContinuation,
        )
    }

    override suspend fun explore(): YouTubeExplore {
        val response = innerTube.browse(YouTubeClient.WEB_REMIX, browseId = EXPLORE_BROWSE_ID).body<BrowseResponse>()
        return youTubeExplorePage(sectionListOf(response.contents))
    }

    /**
     * The new-release album page, with the explore carousel as the fallback. The Android `newReleaseAlbums` falls back
     * only when the dedicated page does not exist (400/404); any other failure is reported instead of hidden.
     */
    override suspend fun newReleases(): List<YouTubeRelease> {
        val direct =
            try {
                val response =
                    innerTube.browse(YouTubeClient.WEB_REMIX, browseId = NEW_RELEASES_BROWSE_ID).body<BrowseResponse>()
                youTubeNewReleasesPage(sectionListOf(response.contents))
            } catch (failure: ClientRequestException) {
                val status = failure.response.status.value
                if (status != 400 && status != 404) throw failure
                null
            }
        if (!direct.isNullOrEmpty()) return direct
        return explore().releases
    }
}

/** The section list under whichever browse-results wrapper the response carries. */
private fun sectionListOf(contents: BrowseResponse.Contents?) =
    youTubeSectionListRenderer(
        singleColumn = contents?.singleColumnBrowseResultsRenderer,
        sectionList = contents?.sectionListRenderer,
        twoColumn = contents?.twoColumnBrowseResultsRenderer,
    )

/** The shelf transport over this host's one InnerTubeX session, so both transports read the same sign-in. */
fun IosYouTubeBrowseTransport.catalogueTransport(): YouTubeCatalogueTransport =
    IosYouTubeCatalogueTransport(innerTubeSession)
