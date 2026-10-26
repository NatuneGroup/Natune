# ArchiveTune Privacy Notice

Last updated: 2026-10-26

## Scope

This notice covers the Android ArchiveTune app in this repository. It explains what the app stores on your device, what it can send to external services when you use specific features, and what Android permissions it requests.

This notice is based on the current source code and build configuration. It does not replace the privacy terms of YouTube or YouTube Music, Last.fm, ListenBrainz, Discord, GitHub, lyrics providers, the ArchiveTune canvas service, the ArchivePool service behind the Playback Pool, the Natune listener presence service, or any Together server you choose to use.

## Privacy Summary

- Most core app data is stored locally on your device.
- ArchiveTune does not secretly harvest, sell, or broker your personal data.
- ArchiveTune does not silently send your data to unrelated third-party services.
- Optional network features send only the data needed to provide those features.
- If data leaves your device, it is because you used a specific online feature or integration that requires that transfer.
- Android backup and device-transfer features may copy part of the app's local data unless excluded by the app's backup rules.
- The app also includes a user-triggered backup export feature.
- The current Android build configuration does not show mobile advertising SDKs, third-party analytics SDKs, or automatic crash-reporting SDKs.
- Listener presence is off by default. If you turn it on, the app sends a random listener ID, its platform and app version, and whether music is playing, roughly once a minute while music plays. The service keeps only a keyed hash of that ID and approximate location, for 150 seconds. See [Listener Presence (Opt-In)](#listener-presence-opt-in).
- The Playback Pool is on by default in builds that configure a pool URL. It downloads shared account and instance lists in the background and reports failing pool accounts. Turning off the Playback Pool stops those requests. See the Playback Pool entries in the tables below.
- The current Android manifest does not request location, contacts, camera, calendar, SMS, or call log permissions.

## Data the App May Store on Your Device

The app stores data locally to provide playback, library, search, lyrics, sync, and customization features.

| Category | Examples visible in the codebase | Why it is stored |
| --- | --- | --- |
| Library and playback data | Song, artist, album, playlist, like state, download state, total play time, audio format metadata | Library management, playback, downloads, and statistics |
| Search and lyrics data | Search queries and cached lyrics | Search history and lyrics features |
| Listening history data | Playback event records with song ID, timestamp, and play time | Listening stats and history-related features |
| App settings | Language, country, UI settings, audio settings, proxy settings, cache settings, history pause toggles, Together settings | Personalization and feature configuration |
| Optional account and session data | YouTube account name, email, channel handle, visitor data, data sync ID, cookie, PO token values | Signed-in YouTube and YouTube Music functionality |
| Optional third-party integration data | Last.fm session and username, ListenBrainz token, Discord OAuth access and refresh tokens plus related profile fields, Together display name, Together client ID, last join link | External integrations you choose to enable |
| Listener presence (opt-in) | Random listener ID (32 hexadecimal characters), on or off state, and consent version, in a separate app data store file named `listener_presence` | Presence on the public live map, only after you turn it on. The file is excluded from Android cloud backup and device transfer |
| Playback Pool cache (when enabled) | Shared account credentials and instance lists downloaded from the pool, kept in a separate local cache encrypted with an Android Keystore key | Playback through pool accounts. This cache is kept separate from your own sign-in data |
| Cached files | Streaming cache, download cache, and other app-managed files | Faster playback, offline use, and feature performance |

## Data the App May Send Off Your Device

ArchiveTune does not silently forward your data to unrelated services. It only contacts external services when you use online features, and the exact payload depends on the feature you use and how you configure it. Two exceptions run without a separate tap: the Playback Pool feed refreshes in the background when it is enabled, and listener presence runs only after you turn it on.

| Service or feature | Data that may be sent | When it happens |
| --- | --- | --- |
| YouTube or YouTube Music | Search terms, media playback requests, library or playlist requests, and signed-in session values such as visitor data, sync identifiers, cookies, or token values | When you browse, stream, sync, or sign in |
| Lyrics providers | Song title, artist name, album identifiers, or similar lookup data needed to fetch lyrics | When lyrics features are enabled or lyrics are requested |
| ArchiveTune canvas service | Song and artist names, album ID, or album URL, plus a bearer token if configured in the app build | When canvas or artwork lookup features are used |
| Last.fm | Now playing and scrobble metadata, plus your Last.fm session information | When Last.fm scrobbling is enabled |
| ListenBrainz | Playback history or scrobble metadata and your ListenBrainz token | When ListenBrainz sync is enabled |
| Discord Rich Presence | Current track, artist, album, images, configured URLs or labels for presence cards, and Discord OAuth tokens required by the official Social SDK | When Discord Rich Presence is enabled |
| GitHub releases | Update-check requests and cached release metadata used to show new versions | When the app checks for updates |
| Together | Display name, client ID, session code or keys, playback state, queue metadata, and room actions | When you host or join a Together session |
| Opt-in feedback service (Natune) | One redacted report per send, at most 200 KiB: app version/revision/variant, device model, OS version, the same redacted error details or crash stack shown to you on screen, the redacted diagnostics described above, and any optional note you type (2,000 characters at most). If the diagnostics exceed the limit, the oldest lines are dropped and the report carries a `[truncated]` marker. The review dialog shows exactly the text that is sent | Only when you press **Send** in a feedback report review dialog; nothing is sent automatically and no analytics run in the background. Listener presence is a separate opt-in feature, described below |
| Playback Pool requests (when enabled) | Requests for the shared account feed (`/api/accounts`, or `/api/sources` on older deployments) and for instance discovery feeds, sent with the build's read key as a bearer token when one is configured. When a pool account fails, a report with the service name, the account kind, the pool account's numeric ID, and a result code (`dead` or `not_premium`). Accounts you add yourself are never reported. As with any network request, the server receives your IP address | Feed refreshes run periodically in the background while the device is connected, and when playback needs accounts. Repeated reports for the same account are suppressed for about 10 minutes. Nothing is sent while the Playback Pool setting is off |
| Listener presence (opt-in, Natune presence service, endpoint set at build time) | A random listener ID (32 hexadecimal characters; the server receives it only as a keyed hash), the platform (`android`, `desktop` or `ios`), the app version, and a `playing` or `stopped` state. The server derives approximate country, city and coordinates from the geolocation headers of the request. No track, playlist, search, account, token or device identifier is sent | Only after you turn it on. While music plays, about once a minute. A `stopped` message is sent on pause, stop, or when you turn the feature off. Builds without an endpoint send nothing |

## Listener Presence (Opt-In)

Listener presence is an optional feature. When you turn it on, the Natune presence service can show on a public live map how many people are listening right now and roughly where they are. The feature is off by default, and nothing is sent until you turn it on.

### Turning it on and off

- The first-run Welcome page has an unchecked switch for listener presence. Settings > Privacy has the same switch.
- Turning it on creates a random listener ID on your device.
- Turning it off stops presence messages, sends one stop message if it can, and deletes the local listener ID. Turning it on again creates a new ID.
- If this description changes in a way that matters, earlier consent no longer counts, and the switch shows as off until you turn it on again.

### What the app sends

While the switch is on and music is playing, the app sends one message about every minute. Each message contains:

- the random listener ID;
- the platform (`android`, `desktop` or `ios`) and the app version;
- a `playing` state.

When playback pauses or stops, or when you turn the switch off, the app sends a `stopped` message with the same ID. The app sends nothing else. It does not send tracks, artists, albums, playlists, searches, lyrics, account names, tokens, or device identifiers.

### What the service keeps

The Natune presence service receives each message over HTTPS. It does not store your IP address or the raw listener ID. It keeps:

- a keyed hash of the listener ID (HMAC-SHA256, using a server-side secret that is not in the app), so a copy of the database does not reveal raw IDs;
- the platform;
- approximate country, city, and coordinates rounded to 0.01 degrees (about 1 km). The service takes these from the geolocation headers that the hosting platform adds to each request.

Each entry expires 150 seconds after its last message, and a stop message removes it at once. The service keeps no history: there is no record of a listener beyond those 150 seconds, and no record of what was played.

### What the public map shows

The map shows the total number of active listeners, the countries they are in, and the cities with at least three active listeners. A city with fewer listeners is not shown; those listeners still count toward the total and their country. The map does not show tracks, accounts, or IP addresses.

### Retention, logs, and backups

- Live entries last 150 seconds. Aggregate counts are cached for a few seconds at the content delivery network and contain no per-listener data.
- The service code writes no logs of its own. The hosting platform keeps its own request logs, which may include IP addresses, under the retention settings of the operator's hosting account.
- The presence settings and the listener ID live in their own app data store file. That file is excluded from Android cloud backup and device transfer, so a restored or transferred device starts with listener presence off and does not reuse another device's ID. The manual backup export does not include it either.

### Operator

The Natune presence service is operated by [operator name and contact details, to be filled in before the first public release]. The endpoint is set at build time through `ARCHIVETUNE_LISTENER_PRESENCE_URL`. A build without an endpoint does not send listener presence at all.

## Android Permissions

The app declares the following Android permissions in the current manifest.

| Permission | Why the app requests it |
| --- | --- |
| `INTERNET` | Connect to YouTube, lyrics services, update endpoints, canvas services, Together, and other network-backed features |
| `POST_NOTIFICATIONS` | Show playback and download notifications |
| `ACCESS_NETWORK_STATE` | Detect connectivity and adapt network behavior |
| `READ_MEDIA_AUDIO` | Read local audio files on supported Android versions |
| `READ_EXTERNAL_STORAGE` on Android 12 and below | Support local audio access on older Android versions |
| `RECORD_AUDIO` | Support music-recognition features |
| `BLUETOOTH_CONNECT` | Integrate with Bluetooth audio devices and playback controls |
| `RECEIVE_BOOT_COMPLETED` | Restore playback-related behavior after a device restart when supported by the app |
| `WAKE_LOCK` | Keep playback-related work running when needed |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `FOREGROUND_SERVICE_DATA_SYNC` | Support background playback, downloads, and related foreground work |

If you deny a permission, the related feature may stop working or provide reduced functionality.

## Backups, Device Transfer, and Local Retention

ArchiveTune currently enables Android backup support. The backup and data-transfer rules exclude some cache and download paths, including the ExoPlayer cache, the download directory, and `exoplayer_internal.db`. Other app data, including local database content and app preferences, may still be included in Android cloud backup or device transfer depending on your Android settings and device behavior. The rules also exclude the listener presence store, so a restored or transferred device starts with listener presence off.

The app also provides a manual backup feature that creates a ZIP archive containing app settings and database files. This is a user-triggered export action. The manual export does not include the listener presence store.

Unless you remove it, app data can remain on your device until one of the following happens:

- you clear app data,
- you uninstall the app,
- you remove or overwrite it through app actions, or
- Android backup or device-transfer systems copy it to another device or restore it.

For Together and other third-party services, remote retention depends on the service you use. This notice focuses on the Android app behavior in this repository.

## User Controls and Choices

You can control a significant amount of privacy-related behavior from the app and from Android itself.

- You can use many core features without enabling optional third-party integrations.
- You can choose whether to sign in to YouTube or YouTube Music.
- You can enable or disable Last.fm scrobbling, ListenBrainz sync, Discord Rich Presence, lyrics providers, and Together features.
- You can grant or deny Android runtime permissions such as media access, notifications, and microphone access.
- You can configure or disable proxy-related settings.
- The codebase includes settings to pause search history and listening history.
- You can create a local backup export.
- Listener presence is off by default. You can turn it on or off on the first-run Welcome page or in Settings > Privacy. Turning it off stops presence messages, sends a stop message when it can, and deletes the local listener ID. Turning it on again creates a new ID.
- Optional feedback reports are fully explicit: the playback-error dialog and the next-launch crash prompt only ask you to **review** a pre-redacted report; a report leaves the device only when you press Send. You can instead delete a stored crash report (never sent), or dismiss the prompt. When a build is not configured with a feedback endpoint, the feature is entirely absent — no prompt, no crash file, no requests. Nothing is uploaded from a crashing process; crash capture only writes a small redacted file that app-private storage holds until you send or delete it. That file is excluded from Android cloud backup and device-to-device transfer.
- You can clear app data or uninstall the app to remove local app storage from your device.
- If you connected external services, you may also need to revoke access or rotate tokens with those external providers.

## Security Notes and Limitations

- The app's network security config (`app/src/main/res/xml/network_security_config.xml`) disallows cleartext (HTTP) traffic in its base configuration, so connections must use HTTPS. The manifest does not override that setting. The listener presence transport also refuses any endpoint that is not HTTPS.
- The current Android manifest also enables Android audio playback capture. Under Android platform rules, compatible system features or authorized apps may be able to capture app audio playback.
- This repository does not clearly document encryption at rest for the app database, preferences, or cache files, so this notice does not promise local encryption.
- This notice is limited to what can be supported from the current repository contents. Third-party services and self-hosted or official Together servers may have their own logging, retention, and security practices.
- If future code changes add new integrations, SDKs, or data flows, this notice should be updated with them.

## Changes to This Notice

This file should be reviewed whenever ArchiveTune changes its permissions, storage model, external integrations, backup behavior, or network architecture.

## Project Contact

For questions or corrections, use the project repository and issue tracker.

- Repository: [https://github.com/rukamori/ArchiveTune](https://github.com/rukamori/ArchiveTune)
- Issues: [https://github.com/rukamori/ArchiveTune/issues](https://github.com/rukamori/ArchiveTune/issues)

## Technical Appendix

This appendix maps the main statements above to concrete implementation surfaces in the codebase.

| Topic | What the code shows | Main files |
| --- | --- | --- |
| Permissions and backup behavior | The manifest declares network, media, microphone, Bluetooth, notification, boot, wake-lock, and foreground-service permissions. It also enables backup and audio playback capture. The network security config it references disallows cleartext traffic. Separate XML files exclude selected caches, internal playback database files, and the listener presence store from Android backup and device transfer. | `app/src/main/AndroidManifest.xml`, `app/src/main/res/xml/network_security_config.xml`, `app/src/main/res/xml/data_extraction_rules.xml`, `app/src/main/res/xml/backup_rules.xml` |
| Local database contents | The Room schema includes songs, artists, albums, playlists, search history, lyrics, audio format metadata, and playback event records. | `app/schemas/moe.rukamori.archivetune.db.InternalDatabase/9.json` |
| Settings and tokens stored locally | DataStore preference keys include UI settings, proxy settings, history toggles, Together values, YouTube session values, account name or email fields, Last.fm session values, ListenBrainz token values, Discord values, and update-cache keys. | `app/src/main/kotlin/moe/rukamori/archivetune/constants/PreferenceKeys.kt` |
| YouTube signed-in state | The Innertube layer exposes visitor data, data sync ID, cookie, PO token values, proxy state, and login-for-browse behavior as part of the current playback auth state. | `core` submodule (InnerTube client; not checked out in this worktree) |
| Manual backup export | The backup view model writes app settings plus database files into a ZIP archive chosen by the user. | `app/src/main/kotlin/moe/rukamori/archivetune/viewmodels/BackupRestoreViewModel.kt` |
| External network integrations | Build configuration defines keys for Last.fm, Together, and canvas services. The updater fetches release information and caches related metadata in app preferences. | `app/build.gradle.kts`, `app/src/main/kotlin/moe/rukamori/archivetune/utils/Updater.kt` |
| Public feature claims | The repository README and store metadata describe privacy, YouTube integration, lyrics, music recognition, Last.fm, ListenBrainz, Discord Rich Presence, and other network-backed features that must stay aligned with this notice. | `README.md`, `fastlane/metadata/android/en-US/full_description.txt` |
| Current dependency posture | The current Android dependency declarations show Compose, Room, Hilt, Ktor, Media3, Coil, Timber, and related libraries. They do not currently show Firebase, Crashlytics, Sentry, mobile ad SDKs, or mobile analytics SDKs in the Android app dependency definitions reviewed for this notice. | `app/build.gradle.kts`, `gradle/libs.versions.toml` |
| Listener presence (opt-in) | The Android host keeps the opt-in in its own `listener_presence` DataStore file, creates a random 32-character hexadecimal ID on opt-in, and posts one JSON payload to `<endpoint>/api/heartbeat` only while it is enabled and music is playing. A blank or non-HTTPS endpoint sends nothing. The presence service validates the five-field payload, stores a keyed HMAC of the ID with approximate geography for 150 seconds, and publishes only an aggregate. | `app/src/main/kotlin/moe/rukamori/archivetune/presence/`, `shared-domain/src/commonMain/kotlin/moe/rukamori/archivetune/shared/domain/presence/`, `shared-data/src/commonMain/kotlin/moe/rukamori/archivetune/shared/data/presence/KtorListenerPresenceTransport.kt`, `services/listener-presence/` |
| Playback Pool | Account and instance feeds are fetched from `SOURCE_PROVIDER_URL` (default `https://archivepool.vercel.app`) with the build's read key when one is configured. Failed pool accounts are reported to `/api/report` with the `X-Pool-Client: v2` header, deduplicated in memory for about 10 minutes. The encrypted pool cache uses an Android Keystore key. | `app/src/main/kotlin/moe/rukamori/archivetune/utils/PoolAccountManager.kt`, `app/src/main/kotlin/moe/rukamori/archivetune/utils/PoolCacheCrypto.kt`, `app/src/main/kotlin/moe/rukamori/archivetune/utils/SourceRefreshWorker.kt`, `app/build.gradle.kts` |
| Opt-in feedback reports | A blank `FEEDBACK_API_URL`/`FEEDBACK_INGEST_KEY` build hides the feature entirely (no prompt, no crash file, no network calls). When configured, sends require an explicit Send press after an on-screen review; the crash path writes a small redacted app-private file (excluded from backup and device transfer) that the next launch offers to review. Reviewed text is bounded to 200 KiB before display, with the oldest log lines dropped and marked `[truncated]`, and it is the exact text sent. | `app/src/main/kotlin/moe/rukamori/archivetune/feedback/`, `app/src/main/res/xml/backup_rules.xml`, `app/build.gradle.kts` |

## Open Documentation Boundaries

The following areas should be documented carefully in the future if the project wants stronger privacy claims.

- Whether any self-hosted or official Together deployment logs IP addresses, user agents, or participant history outside the Android app itself.
- Whether each real deployment's endpoint for an optional feature is HTTPS. The network security config refuses cleartext connections, so an HTTP endpoint is refused rather than used.
- Whether local app storage is encrypted at rest on all supported devices and configurations.
- Whether canvas, lyrics, or future service providers apply their own independent retention or profiling practices.
- How long the hosting platform keeps request logs for the listener presence service. This depends on the operator's hosting settings.
