package app.natune.constants

// The enum values that preference keys store.

/**
 * How the system back gesture animates the surface it closes.
 *
 * ORIGINAL keeps the classic instant back dispatch (the surface closes with its own close
 * animation, untouched by the gesture). PREDICTIVE routes sheet back handling through
 * androidx's PredictiveBackHandler, so the player sheet, bottom-sheet pages and menus scale
 * away with the gesture and spring back when it is cancelled, and pushed screens slide.
 */
enum class BackAnimationStyle {
    ORIGINAL,
    PREDICTIVE,
}
enum class CompactLayoutChoice {
    DEFAULT,
    COMPACT,
}
enum class AodThumbnailShape {
    ROUNDED,
    SQUARE,
    CIRCLE,
    PILL,
    ARCH,
    SLANTED,
    DIAMOND,
    PENTAGON,
    TRIANGLE,
    HEART,
    FLOWER,
    CLOVER_4,
    COOKIE_6,
    COOKIE_9,
    SUNNY,
    SOFT_BURST,
    GHOSTISH,
    PIXEL_CIRCLE,
}
enum class AodBackgroundStyle {
    PURE_BLACK,
    SOFT_RADIAL,
    TONAL_EDGE,
    AMBIENT_GLOW,
}
enum class AodAccentStyle {
    MONOCHROME,
    THEME,
}
enum class AodContentPosition {
    TOP,
    CENTER,
    BOTTOM,
}
enum class AodTextAlignment {
    START,
    CENTER,
    END,
}
enum class AodControlStyle {
    FILLED,
    TONAL,
    MINIMAL,
}
enum class SliderStyle {
    Standard,
    Wavy,
    Thick,
    Circular,
    Simple,
}
typealias AppFontPreference = app.natune.shared.domain.settings.AppFontPreference
enum class PlaylistSuggestionSource {
    PLAYLIST_TITLE,
    PLAYLIST_CONTENT,
    BOTH,
}
enum class DownloadSource {
    /**
     * Picks the best available source per song: tries Qobuz → Tidal → Deezer
     * (lossless FLAC) and falls back to YouTube Music (lossy MP3/AAC) only
     * when none of the lossless backends can resolve the track. This is the
     * default and the recommended setting — it guarantees FLAC whenever a
     * configured lossless provider has the track, without forcing the user
     * to manually switch sources.
     */
    AUTO,

    QOBUZ,

    /**
     * Community-hosted Qobuz mirror (mlc-ytify.kouzu.in), keyed by YouTube video id
     * rather than a Qobuz catalogue id, so it needs no Source Pool account at all.
     * Mirrors [AudioSourceType.QOBUZ_BACKUP] on the playback side and is gated by the
     * same `QobuzBackupEnabledKey` toggle. Not in [DownloadSourceConfig.REQUIRES_POOL].
     */
    QOBUZ_BACKUP,
    TIDAL,

    /**
     * Deezer lookup — uses Deezer's public catalogue API to resolve a FLAC
     * stream URL. Falls back to the next source in [AUTO] order when the
     * track isn't on Deezer or the API can't resolve a full stream.
     */
    DEEZER,

    /**
     * JioSaavn lookup — uses the public JioSaavn API to resolve a 96/160/320 kbps
     * AAC stream URL. Falls back to the next source in [AUTO] order when the
     * track isn't on JioSaavn. Does NOT require a Source Pool account (the
     * public API is used directly), so it is NOT in [DownloadSourceConfig.REQUIRES_POOL].
     */
    JIOSAAVN,

    MODULES,
    YOUTUBE_MUSIC,
}
enum class AiProvider {
    CHATGPT,
    GEMINI,
    OPENROUTER,
    CUSTOM,
    DEEPL,
    MISTRAL,
    NONE,
}
enum class AiApiValidationStatus {
    UNKNOWN,
    SUCCESS,
    FAILED,
}
enum class LastFmProvider {
    LASTFM,
    LIBREFM,
    CUSTOM,
}
enum class AudioQuality {
    AUTO,
    HIGH,
    HIGHEST,
    LOW,
}
enum class PlayerStreamClient {
    ANDROID_VR,
    WEB_REMIX,
    HI_RES_LOSSLESS,
    IOS,
    TVHTML5,
    ANDROID_MUSIC,
}
/**
 * Automix (the analysis-driven transition engine, ported from 4nx3b): how much CPU the background
 * beat/vocal analysis may use. Crossfade and automix are mutually exclusive — at most one of them
 * shapes a given track boundary.
 */
enum class AutomixPerformanceMode(val inferenceThreads: Int) {
    EFFICIENT(1),
    BALANCED(2),
    PERFORMANCE(4),
}
enum class LibraryStyle {
    DEFAULT,
    APPLE_MUSIC,
}
enum class LibraryViewType {
    LIST,
    GRID,
    ;

    fun toggle() =
        when (this) {
            LIST -> GRID
            GRID -> LIST
        }
}
enum class QuickPicksDisplayMode {
    CARD,
    LIST,
}
enum class SongFilter {
    LIBRARY,
    LIKED,
    DOWNLOADED,
}
enum class ArtistFilter {
    LIBRARY,
    LIKED,
}
enum class AlbumFilter {
    LIBRARY,
    LIKED,
    DOWNLOADED,
    DOWNLOADED_FULL,
}
enum class SongSortType {
    CREATE_DATE,
    NAME,
    ARTIST,
    PLAY_TIME,
}
enum class PlaylistSongSortType {
    CUSTOM,
    CREATE_DATE,
    NAME,
    ARTIST,
    PLAY_TIME,
}
enum class AutoPlaylistSongSortType {
    CREATE_DATE,
    NAME,
    ARTIST,
    PLAY_TIME,
}
enum class ArtistSortType {
    CREATE_DATE,
    NAME,
    SONG_COUNT,
    PLAY_TIME,
}
enum class ArtistSongSortType {
    CREATE_DATE,
    NAME,
    PLAY_TIME,
}
enum class AlbumSortType {
    CREATE_DATE,
    NAME,
    ARTIST,
    YEAR,
    SONG_COUNT,
    LENGTH,
    PLAY_TIME,
}
enum class PlaylistSortType {
    CREATE_DATE,
    NAME,
    SONG_COUNT,
    LAST_UPDATED,
    CUSTOM,
}
enum class MixSortType {
    CREATE_DATE,
    NAME,
    LAST_UPDATED,
}
enum class GridItemSize {
    BIG,
    SMALL,
}
enum class MyTopFilter {
    ALL_TIME,
    DAY,
    WEEK,
    MONTH,
    YEAR,
}
enum class QuickPicks {
    QUICK_PICKS,
    LAST_LISTEN,
    DONT_SHOW,
}
enum class PreferredLyricsProvider {
    BUNDLED,
    BETTER_LYRICS,
    BETTER_LYRICS_PORTATO,
    YOULY_PLUS,
    LRCLIB,
    KUGOU,
    MEGALOBIZ,
    SIMPMUSIC,
    UNISON,
    PAXSENIX_APPLE_MUSIC,
    APPLE_MUSIC,
    PAXSENIX_NETEASE,
    PAXSENIX_SPOTIFY,
    PAXSENIX_MUSIXMATCH,
    PAXSENIX_YOUTUBE,
    TIDAL,
    DEEZER,
    MUSIXMATCH_EXPERIMENTAL,
}
/**
 * Artwork providers that can be prioritised by the user. The order in this enum is NOT the priority
 * — the user-configured order (stored in [ArtworkProviderOrderKey]) determines which provider is
 * tried first when resolving artwork for a song.
 */
enum class PreferredArtworkProvider {
    LOCAL_EMBEDDED,
    ORIGINAL_METADATA,
    TIDAL,
    SPOTIFY_CANVAS,
    ARCHIVETUNE_CANVAS,
}
enum class PlayerButtonsStyle {
    DEFAULT,
    SECONDARY,
}
/**
 * Home screen layout style. DEFAULT = this fork's feed; RUKAMORI = the upstream rukamori layout.
 *
 * The enum owns its own default. It used to be written out at each `rememberEnumPreference` call
 * instead, which meant changing it was a hunt through every reader and a miss left two screens
 * disagreeing about what a fresh install shows.
 */
enum class HomeScreenStyle {
    DEFAULT,
    RUKAMORI,
    ;

    companion object {
        val Default = RUKAMORI
    }
}
/**
 * Which service the Home tab is showing. The homes are separate pages you switch between, not one
 * feed with the others stacked into it — signing into Spotify must not cost you the YouTube home,
 * and vice versa. [ActiveHomeSourcesKey] holds the set the user made available; this key holds the
 * one currently on screen.
 */
enum class HomeSource {
    YOUTUBE,
    SPOTIFY,
    QQ,
}
/**
 * A player look, and what it does about its own backdrop.
 *
 * The two facts below used to live as hand-written lists in two files — one in Player.kt deciding
 * what to render, one in AppearanceSettings.kt deciding what to offer — and they had drifted apart:
 * three styles showed a background picker the player then ignored, and two hid a picker the player
 * would have honoured. Keeping both on the entry means a new style declares its behaviour once and
 * neither screen can disagree with it.
 *
 * @property nativeBackground the backdrop the style draws for itself, or null when it has no
 *   opinion and simply renders whatever the reader picked. A style with one still renders that
 *   backdrop when the reader has expressed no preference.
 * @property supportsBackgroundChoice whether the picker is offered at all. Native media can still cover a selected background; decorative native
 *   backdrops yield to an explicit choice without changing stored preferences.
 * @property forcesMonochromeChrome whether the controls are pinned white-on-black instead of
 *   following the theme. True for the styles that sit on a full-bleed black-backed sleeve, where
 *   theme-coloured chrome would be illegible over the artwork half the time.
 */
enum class PlayerDesignStyle(
    val nativeBackground: PlayerBackgroundStyle? = null,
    val supportsBackgroundChoice: Boolean = true,
    val forcesMonochromeChrome: Boolean = false,
) {
    V1,
    V2,
    V3,
    V4,
    V5,
    V6,
    V7(forcesMonochromeChrome = true),
    V8(
        nativeBackground = PlayerBackgroundStyle.DEFAULT,
        supportsBackgroundChoice = false,
        forcesMonochromeChrome = true,
    ),
    V9(nativeBackground = PlayerBackgroundStyle.DEFAULT, supportsBackgroundChoice = false),

    /** The blurred, expanded cover is the whole look. */
    APPLE_MUSIC(nativeBackground = PlayerBackgroundStyle.BLUR),
    V10,

    /**
     * Self-contained styles: their layout, controls and lyrics surface live in their own package
     * and share nothing with the numbered styles above. Their backdrops, however, are the shared
     * ones under another name — BitChord and TikTok both draw MeshBackdrop, which is the animated
     * glow, and SimpMusic draws a diagonal palette wash, which is the gradient.
     */
    BITCHORD(nativeBackground = PlayerBackgroundStyle.GLOW_ANIMATED),

    /** The vertical pager sits on top of the backdrop, so a different one would never be seen. */
    TIKTOK(nativeBackground = PlayerBackgroundStyle.GLOW_ANIMATED),
    SIMPMUSIC(nativeBackground = PlayerBackgroundStyle.GRADIENT),

    /**
     * The SpatialFlow player (github.com/MythicalSHUB/SpatialFlow, GPL-3.0) — artwork pager, pill
     * control row, wavy seek bar, sliding queue drawer, circular-reveal lyrics and music haptics.
     * Its backdrop is a blurred, expanded sleeve, so it declares BLUR rather than drawing something
     * only it understands.
     */
    SPATIALFLOW(nativeBackground = PlayerBackgroundStyle.BLUR),

    /**
     * The Looper player (4nx3b/ArchiveTune dev) — typography-forward layout, its own slider and
     * seek-step gesture, sleeve artwork that drags to change track. It paints no backdrop of its
     * own, so unlike the other self-contained styles it leaves the background choice to the reader.
     */
    LOOPER,

    /**
     * The Flamingo player (4nx3b/ArchiveTune dev, after Flamingo's NowPlaying) — album, lyrics and
     * queue pages in one surface, with the artwork morphing between them. Its floating-light backdrop
     * is a blurred sleeve drawn edge to edge, so it declares BLUR: that also zeroes the shared queue
     * peek, which Flamingo does not need because its own queue page replaces the sheet.
     */
    FLAMINGO(nativeBackground = PlayerBackgroundStyle.BLUR),

    // Metrolist's clean column player (MetrolistGroup/Metrolist, GPL-3.0); like LOOPER it
    // leaves the backdrop to the reader instead of painting one of its own.
    METROLIST_KMP,
    ;

    /** DEFAULT preserves the native recipe; explicit choices never rewrite storage. */
    fun resolveBackground(stored: PlayerBackgroundStyle): PlayerBackgroundStyle =
        when {
            !supportsBackgroundChoice -> nativeBackground ?: stored
            stored == PlayerBackgroundStyle.DEFAULT -> nativeBackground ?: stored
            else -> stored
        }

    val supportsSharedButtons: Boolean
        get() = this in SharedTransportStyles

    val supportsSharedSeekStyle: Boolean
        get() = this in SharedTransportStyles

    val hasVolumeRow: Boolean
        get() = this in VolumeRowStyles

    val hasOrdinarySleeve: Boolean
        get() = this in OrdinarySleeveStyles

    val hasHorizontalArtworkSkip: Boolean
        get() = this in HorizontalArtworkSkipStyles

    val hasArtworkCanvasSurface: Boolean
        get() = this in ArtworkCanvasStyles

    fun usesSharedBackground(stored: PlayerBackgroundStyle): Boolean =
        supportsBackgroundChoice && stored != PlayerBackgroundStyle.DEFAULT

    fun rendersSharedBackground(stored: PlayerBackgroundStyle): Boolean =
        usesSharedBackground(stored) || this in SharedBackgroundHosts

    /**
     * Whether the style paints its own artwork-driven backdrop rather than a neutral one.
     *
     * Derived from [nativeBackground] rather than listed, so a new style qualifies the moment it
     * declares one — the call sites in Player.kt used to spell the same five entries out by hand,
     * and a style added to one list but not the other read as a different look in each place.
     *
     * Note it is not a synonym for "hides the queue peek": V5 does that without painting a backdrop,
     * so that call site names V5 separately. Deriving this from the declared backdrop keeps the two
     * questions distinct instead of collapsing them into one list that answers neither exactly.
     */
    val hasCustomBackdrop: Boolean
        get() = nativeBackground != null && nativeBackground != PlayerBackgroundStyle.DEFAULT

    companion object {
        private val SharedTransportStyles = setOf(V1, V2, V3, V4, V6)
        private val VolumeRowStyles = setOf(V7, V8, BITCHORD, APPLE_MUSIC, FLAMINGO)
        private val OrdinarySleeveStyles = setOf(BITCHORD, SIMPMUSIC, SPATIALFLOW, LOOPER, FLAMINGO, APPLE_MUSIC)
        private val HorizontalArtworkSkipStyles = setOf(SIMPMUSIC, SPATIALFLOW, LOOPER)
        private val ArtworkCanvasStyles = setOf(APPLE_MUSIC, SPATIALFLOW, LOOPER, FLAMINGO)
        private val SharedBackgroundHosts = setOf(V1, V2, V3, V4, V6, V10, LOOPER, METROLIST_KMP)

        /**
         * What a fresh install opens with. Seven readers — the player, its thumbnail, the queue, the
         * menu, the Apple Music experience toggle, MainActivity and the appearance screen — each
         * wrote this out for themselves, so the default could only be changed by finding all seven,
         * and any one missed would render a different style than the rest of the app believed.
         */
        val Default = V4
    }
}
enum class PlayerBackgroundStyle {
    DEFAULT,
    GRADIENT,
    CUSTOM,
    BLUR,
    COLORING,
    BLUR_GRADIENT,
    GLOW,
    GLOW_ANIMATED,
}
enum class LyricsBackgroundStyle {
    DEFAULT,
    FOLLOW_THEME,
    COLORING,
    MOVING_BLUR,
    CUSTOM;

    fun resolveFor(playerBackgroundStyle: PlayerBackgroundStyle): LyricsBackgroundStyle =
        when {
            playerBackgroundStyle == PlayerBackgroundStyle.CUSTOM -> CUSTOM
            this == CUSTOM -> DEFAULT
            else -> this
        }
}
enum class MiniPlayerBackgroundStyle {
    THEME,
    GRADIENT,
    GLOW,
    FROSTED,
    LIQUID_GLASS,
}
// Bottom navigation bar look: DEFAULT keeps the docked full-width bar; FLOATING detaches it into
// a pill with larger margins that never pairs with the mini player. SUKISU renders the SukiSU
// liquid glass bar (ui/component/sukisu/), which fixes its own dimensions and ignores the bar
// customization sliders; without Liquid Glass effects it falls back to a solid pill.
enum class NavigationBarStyle {
    DEFAULT,
    FLOATING,

    YUMA_PLAYER,
    APPLE_MUSIC,
    SUKISU,
}
enum class NavigationBarCompactBehavior {
    ADAPTIVE,
    ALWAYS_EXPANDED,
    ALWAYS_COMPACT,
}
enum class SearchBarPosition {
    TOP,
    BOTTOM,
}
enum class LyricsAnimationStyle {
    NONE,
    FADE,
    GLOW,
    SLIDE,
    KARAOKE,
    APPLE,
}
enum class LyricsMode {
    V2,
    ENHANCED,
    SPOTIFY,

    /**
     * SimpMusic's renderer. Used to be a boolean of its own that only the SimpMusic player style's
     * lyrics CARD read, so picking it changed a 300dp preview and left the actual lyrics page on
     * whatever this enum said — "it just shows the same one". It is one of the modes now, so it
     * applies wherever lyrics are drawn, under any player style.
     */
    SIMPMUSIC,
}
enum class MetadataSource {
    YOUTUBE,
    SPOTIFY,
    APPLE_MUSIC,
}
enum class SearchProvider {
    YOUTUBE,
    SPOTIFY,
    APPLE_MUSIC,
}
enum class SearchSource {
    LOCAL,
    ONLINE,
    ;

    fun toggle() =
        when (this) {
            LOCAL -> ONLINE
            ONLINE -> LOCAL
        }
}
enum class TidalSubscriptionStatus {
    UNKNOWN,
    PREMIUM,
    FREE,
}
enum class TidalAudioQuality {
    AAC_320,
    FLAC,
    HI_RES_LOSSLESS,
}
/** Apple Music streaming quality for the account path (web ALAC pipeline). */
enum class AppleMusicQuality {
    AAC,
    LOSSLESS,
    HI_RES_LOSSLESS,
}
// Qobuz quality maps to the proxy/Qobuz format_id: FLAC=6 (CD 16-bit), HI_RES=7 (≤96kHz),
// MAX=27 (>96kHz). MP3 (5) is intentionally omitted — this is a lossless source.
enum class QobuzAudioQuality {
    FLAC,
    HI_RES,
    MAX,
}
enum class SpotifyStreamResolveMethod {
    // Web-player pipeline: Connect device + manifest + Widevine-protected AAC 128.
    STOPIFY,

    // Desktop pipeline: PlayPlay audio key + AES-CTR decrypted Ogg Vorbis/FLAC; falls back to
    // STOPIFY when it cannot resolve.
    ECHO,
    ;

    companion object {
        val Default = STOPIFY
    }
}
enum class QqAudioQuality {
    LOSSLESS,
    HIGH,
    STANDARD,
    ;

    companion object {
        val Default = HIGH
    }
}
enum class AmazonAudioQuality {
    ULTRA_HD,
    HD,
    STANDARD,
    ;

    companion object {
        val Default = HD
    }
}
enum class SaavnAudioQuality {
    QUALITY_320,
    QUALITY_160,
    QUALITY_96,
    ;

    fun toApiValue(): String =
        when (this) {
            QUALITY_320 -> "320kbps"
            QUALITY_160 -> "160kbps"
            QUALITY_96 -> "96kbps"
        }

    fun toLabel(): String =
        when (this) {
            QUALITY_320 -> "320 kbps"
            QUALITY_160 -> "160 kbps"
            QUALITY_96 -> "96 kbps"
        }

    companion object {
        fun fromStoredName(name: String?): SaavnAudioQuality =
            runCatching { valueOf(name?.uppercase() ?: "") }.getOrDefault(QUALITY_320)
    }
}
typealias UpdateChannel = app.natune.shared.domain.settings.UpdateChannel
// ──────────────────────────────────────────────────────────────────────────────
// Inline / fullscreen music-video player preferences
// ──────────────────────────────────────────────────────────────────────────────
/**
 * Aspect-ratio mode for the inline + fullscreen video surface. Maps to
 * ExoPlayer's AspectRatioFrameLayout resize modes:
 *  - FIT     → RESIZE_MODE_FIT     (letterbox; whole video visible)
 *  - CROP    → RESIZE_MODE_ZOOM    (fill screen, crop edges)
 *  - STRETCH → RESIZE_MODE_FILL    (fill screen, distort if needed)
 *  - FILL    → RESIZE_MODE_FILL    (alias kept for UX parity with YT)
 */
enum class VideoAspectRatio {
    FIT,
    CROP,
    STRETCH,
    FILL,
}
