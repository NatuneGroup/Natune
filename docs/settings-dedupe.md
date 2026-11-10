# Settings de-duplication

Several settings were reachable from more than one page, and the Integrations hub carried account and sign-in
controls as well as links to the pages that own them. Every setting now has exactly one home. Duplicates are matched
by the preference key, the account manager or the action a control changes, never by its title.

## What moved

| Setting (key / action) | Where it appeared | Single home now | What the other spots became |
| --- | --- | --- | --- |
| Spotify catalog account: sign-in, playlist switches, playlist reload, logout (`spotify_account`, `spotify_show_playlists`, `spotify_sync_history`, `spotify_reload`, `spotify_logout`, Spotify metadata extension switch) | Integrations hub (shared `IntegrationSettings`, Android `IntegrationScreen`), inline under an "External sources" group | Spotify catalog page (`settings/spotify`), new shared `SpotifyCatalogSettings` and Android `SpotifyCatalogSettings` | The hub keeps one navigation row, "Spotify catalog account" |
| Use pool accounts (`use_pool_accounts`) | Integrations hub switch, Sources page switch | Sources page | Hub row removed; the Android search result for the pool now opens Sources |
| ListenBrainz scrobbling and token (`listenbrainz`, `listenbrainz_token`) | Integrations hub switch and token editor, Last.fm / Libre.fm page | Last.fm / Libre.fm / ListenBrainz page | Hub rows removed; Android search results point at the Last.fm page |
| Qobuz audio quality (`qobuz_audio_quality`) | Sources page picker, Qobuz page picker | Qobuz page picker | Sources picker removed; the Android search result moved to the Qobuz item |
| Qobuz source switch (`qobuz_enable`) | Sources page switch, Qobuz page switch | Sources page switch | Qobuz page switch removed (the shared source policy still reads the key) |
| JioSaavn audio quality (`jiosaavn_audio_quality`) | Sources page picker, JioSaavn page picker | JioSaavn page picker | Sources picker removed; the Android search result stays under the JioSaavn item |
| JioSaavn source switch (`jiosaavn_enable`) | Sources page switch, JioSaavn page switch (shared and desktop hosts) | Sources page switch | JioSaavn page switch removed |
| Amazon Music source switch (`amazon_source_enable` on the Amazon page, `amazon_enable` on Sources) | Sources page switch, Amazon page switch | Sources page switch | Amazon page switch removed |
| QQ Music source switch (`qq_music_enabled` on the QQ page, `qq_music_enable` on Sources) | Sources page switch, QQ Music page switch | Sources page switch | QQ Music page switch removed |
| Spotify streaming source switch (`spotify_stream_enabled`) | Sources page switch, Spotify streaming page switch | Sources page switch | Spotify streaming page switch removed; its sign-in, resolve method and risk note stay on that page |
| Tidal and Qobuz instance management (`tidal_manage_instances`, `qobuz_manage_instances`) | Sources rows that also duplicated the service pages' instance lists | Tidal page and Qobuz page | Sources rows are plain navigation rows to `settings/tidal` and `settings/qobuz`; the "manage in Integrations" descriptions are gone |
| Canvas cache limit and clear action (`max_cache_size`, `clear_canvas_cache`) | Storage page, Canvas page | Storage page, with the other three caches | Canvas page shows the used bytes and one navigation row to Storage |
| Hide AI mix (`hide_ai_mix`) | Content page toggle, AI integration page toggle | Content page toggle | AI integration page toggle removed |
| Android search-result switches for the settings above | Search results offered an inline switch for keys that also had a row elsewhere | The one row that owns the key | Those search results are navigation rows again, so search never becomes a third editor for the same key |

## Checked and left alone

* **Low data mode** (`network_metered` / `low_data_mode`) has one control, on the Privacy page. The Android player
  page and the Playback settings item only carry the anchor the search result scrolls to, and the AI integration
  test fixture (`privacyInlineSettingsRows`) is not mounted by any host.
* **Account page** keeps the main YouTube account only; Spotify, Tidal, Qobuz, Deezer, Telegram, Apple Music and QQ
  Music accounts live on their service pages, and the Integrations hub links to those pages.
* **No preference key, route-independent stored data or host wiring changed.** Rows were removed from one page or
  turned into navigation rows; every key is still written by exactly one control and read by the same readers.

## Where the accounts live

| Account | Page |
| --- | --- |
| YouTube / YouTube Music (main account) | Account (`settings/account`) |
| Spotify catalog (playlists, metadata import) | Spotify catalog (`settings/spotify`) |
| Spotify streaming (separate account) | Spotify streaming (`settings/spotify_streaming`) |
| Tidal, Qobuz, Deezer, Amazon Music, QQ Music, JioSaavn | Each service page (`settings/tidal`, `settings/qobuz`, ...) |
| Apple Music | Apple Music (`settings/applemusic`) |
| Telegram | Telegram (`settings/telegram`) |
| Discord | Discord (`settings/discord`) |
| Last.fm / Libre.fm / ListenBrainz | Scrobbling page (`settings/lastfm`) |

The Integrations hub (`settings/integration`) is navigation only: every row links to the page that owns the
account, the switch or the token.
