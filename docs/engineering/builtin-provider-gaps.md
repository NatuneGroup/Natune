# Bundled-provider gaps: lean removal PR 2

The downloadable system will be removed in later PRs. This PR adds explicit Qobuz
email/password exchange and opt-in JioSaavn web-player and portable TIDAL account
quality fallback to the existing compiled provider owners. The old module route
remains enabled/compiled through its existing owners. No registry, runtime, JS
implementation, profile store or migration journal is introduced.

Base: `f1dbbbad4bcc40e98982c24ba554773a9322ccab` (`origin/dev` at worktree creation),
including PR 1 merge `4bda653e`. PR 1's finite provider/profile contracts and default
attempt plan remain unchanged; profiles are data-only and are not yet persisted or
consumed by playback. This is deliberately narrower than the original PR sequence.

Evidence abbreviations, all repository-relative:

- `A/` = `app/src/main/kotlin/moe/rukamori/archivetune/`
- `S/` = `shared-data/src/commonMain/kotlin/moe/rukamori/archivetune/shared/data/`
- `D/` = `shared-domain/src/commonMain/kotlin/moe/rukamori/archivetune/shared/domain/`
- `U/` = `shared-ui/src/commonMain/kotlin/moe/rukamori/archivetune/shared/ui/`
- `UI/` = `shared-ui/src/iosMain/kotlin/moe/rukamori/archivetune/shared/ui/ios/`
- `X/` = `desktopApp/src/jvmMain/kotlin/moe/rukamori/archivetune/desktop/`
- `J/` = `addons/modules/extension/`

The **real?** column cites the module and pre-change built-in code at the base
above. The **where implemented** column refers to this PR. The seven audio catalog
entries are at `addons/modules/registry.json:6`, `:22`, `:38`, `:54`, `:72`, `:93`
and `:109`; the LRCLIB package is at `addons/modules/natune-extensions.json:6`.
Catalog membership is not evidence of an enabled installation or live service.

| Gap | Real? (evidence) | Decision | Where implemented | Test name | User impact / limit |
| --- | --- | --- | --- | --- | --- |
| TIDAL supplied OAuth client/profile | Yes: `J/tidal/account.js:56` accepts client ID and optional secret; `S/provider/TidalAccountAuth.kt:27` requires a pair and `:164` selects a build client. Hosts supply build credentials (`X/DesktopSessionOwners.kt:140`, `A/tidal/TidalAccountManager.kt:156`). | DROP custom-client profiles: secure per-profile client storage, token rotation and three host account lifecycles exceed the contained slice. | None | None | A token issued to a different client cannot refresh through that client after removal. Re-enter through built-in sign-in or use an existing native instance configuration. |
| TIDAL 5003 quality downgrade and playback 429 cooldown | Yes on desktop/iOS: `J/tidal/account.js:112` recognizes 5003; `:215` walks lower tiers; `:71` latches 429. `S/provider/KtorTidalRepository.kt:96` requested one tier through generic HTTP. Android already calls `resolveTidalQuality` (`A/tidal/TidalAccountManager.kt:441`). | IMPLEMENT opt-in portable account policy | `S/provider/KtorTidalRepository.kt:95`; shared setting `tidalAccountQualityFallback`; personal and Pool wiring in `X/provider/DesktopProviderSources.kt:76` and `UI/IosQueueLocatorResolver.kt:271`; Pool failure classification in `S/provider/PoolDeadReportPolicy.kt:38`. | `TidalBundledAccountPolicyTest.accountPolicyDowngrades5003AndKeepsReturnedManifestQuality`; `rateLimitStopsQualityRetriesAndRebuiltAccountRepositoriesUntilCooldown` | Default off preserves existing portable results. Only quality restrictions downgrade; auth/rate failures do not. Uses existing delta-seconds cooldown capped at 10 minutes; module HTTP-date/6-hour cooldown and search-response latch are not ported. |
| Qobuz email/password account exchange | Yes: `J/qobuz/account.js:85` exchanges an MD5 password for `user_auth_token`; built-in `U/settings/QobuzLoginScreen.kt:167` accepted a pasted token, and `S/provider/QobuzWebLogin.kt:184` only verifies one. | IMPLEMENT explicit sign-in | `S/provider/QobuzAccountExchange.kt:17`, `U/settings/QobuzPasswordLogin.kt:19`, `U/settings/QobuzLoginScreen.kt:164`, `A/ui/screens/settings/QobuzLoginScreen.kt:217`; desktop/iOS supply the existing graph HTTP client. | `QobuzAccountExchangeTest.emailExchangeProducesSavedCredentialsThatReallySignBuiltInPlayback`; `refusalMalformedAndRedirectRepliesNeverYieldAnAccountOrFollowCredentials` | Available on all three hosts. Explicit dialog accepts email, password, app ID and hex app secret. Existing verifiers run before saving into the existing token list. Password clears on submission and is never persisted. No automatic import of module settings. |
| Qobuz automatic password re-login, format fallback and cooldown | Yes: `J/qobuz/account.js:102` re-logins once on 401; `:184` downgrades format; `:71` latches 429/503. `S/provider/KtorQobuzRepository.kt:43` uses the supplied token and `:83` selects a single format; no password owner. | DROP from this PR | None | None | Rejected saved tokens require explicit sign-in again. A refused format requires choosing a lower quality or falling through to another source. No module-style persistent password/retry state is carried over. |
| Scoped authenticated Deezer instance | Yes: `J/deezer/main.js:15` adds bearer to probe and `:29` to media; `S/provider/DeezerInstanceResolver.kt:279` probes with range/UA/accept only. | DROP custom deployments by owner decision | None | None | An instance requiring its module bearer becomes unusable through the built-in instance path. Existing anonymous native instance and ARL paths remain. |
| Bearer credentials for TIDAL/Qobuz instances | No bundled gap: `J/tidal/instance.js:3`/`:12` and `J/qobuz/instance.js:3`/`:10` send no bearer. Native `S/provider/TidalInstanceResolver.kt:351`/`:1112` and `S/provider/QobuzInstanceResolver.kt:351`/`:372` issue the same public dialects. | DROP proposed extra credential profile work; keep existing native paths | Existing resolvers | Existing `SourceProviderProtocolTest` and `TidalInstanceCooldownTest` | User endpoints configured only inside Modules are not imported. No additional shipped instance-token capability was found for these two modules. |
| Key-free JioSaavn | Yes for iOS builds without decoder material: `J/jiosaavn/provider.js:69` calls `song.generateAuthToken`; `S/provider/KtorJioSaavnRepository.kt:11` required a decoder; `UI/IosQueueLocatorResolver.kt:966` omitted the source without a key. Desktop already supplied a default decoder (`X/provider/DesktopProviderSources.kt:422`; JVM decoder `shared-data/src/jvmMain/kotlin/moe/rukamori/archivetune/shared/data/provider/JvmJioSaavnUrlDecoder.kt:15`). | IMPLEMENT explicit web-player mode | `S/provider/KtorJioSaavnRepository.kt:76`, `UI/IosJioSaavnSource.kt:12`, desktop provider factory; shared `jioSaavnWebPlayer` option defaults false. Existing `enableSaavnStreaming` and `saavnAudioQuality` stay unchanged. | `JioSaavnWebPlayerTest.keyFree320ExchangeAcceptsKeyedDetailsAndCarriesRefererAndExpiry`; `unavailable320AndLowerQualityChoicesMint96WithoutInventingCdnVariants`; `DesktopJioSaavnWebPlayerTest.savedWebPlayerOptionIsReadByTheBuiltInDesktopSource` | Desktop/iOS can select key-free mode. Android already has a built-in decoder and is unchanged. Web mode requests 320 only when offered, otherwise 96; a native 160 choice uses 96 in web mode. No guessed CDN variants or fixed sample-rate claim. |
| JioSaavn web metadata decoding/featured credits | Yes: `J/jiosaavn/provider.js:32` decodes entities and combines primary/featured credits; `S/provider/KtorJioSaavnRepository.kt:66` previously used raw titles and primary-or-all artists. | IMPLEMENT in web-player mode only | `S/provider/KtorJioSaavnRepository.kt:100` | `JioSaavnWebPlayerTest.webSearchDecodesEntitiesAndKeepsFeaturedArtistForRecordingMatch` | Native decoder metadata remains unchanged. Web-player matching can use decoded titles and featured artists. |
| JioSaavn alternate `(From "…")` search-title alias | Yes: `J/jiosaavn/provider.js:48` emits a second candidate; the built-in parser emits one provider track. | DROP duplicate-candidate presentation | None | None | Some decorated-title searches can match differently. Shared recording/version safety and provider pagination are retained; no second queue/catalog identity is introduced. |
| Custom ArchivePool deployment/read key/TIDAL client | Yes: `J/archivepool/main.js:4` reads `poolUrl`/`readKey`, `:30` reads custom TIDAL client fields. `X/provider/DesktopPlaybackPool.kt:91` uses build defaults; `A/utils/PoolAccountManager.kt:486` reads the build key. | DROP custom deployments by owner decision | None | None | The custom Pool is no longer contacted after removal. Its lease credentials and any custom refresh client are not transferred into the built-in Pool or personal account. Built-in Pool retains its existing toggle, secure cache and deployment policy. |
| Own/Pool priority and source-specific gates | Yes for non-default module choices: `D/settings/ModuleAccountSettings.kt:17` and `A/modules/ModuleAccountRoutes.kt:35` compose separate lanes. Native `X/provider/DesktopProviderSources.kt:143` and `UI/IosQueueLocatorResolver.kt:317` put personal accounts before Pool; Qobuz uses personal + Pool + instances (`UI/IosQueueLocatorResolver.kt:636`). | DROP per-installation ordering/gates from this lean PR | None | None | Pool-first and per-module own/Pool disable choices are not converted. Native personal-first defaults and the global Pool switch apply; users must review that switch before cutover. Account/instance order is not own/Pool priority. |
| Account-free custom Amazon endpoint | Yes: `J/amazon/main.js:12` allows a blank operator/JWT and calls v1; `S/provider/KtorAmazonRepository.kt:135` requires `AmazonInstanceAccess`, whose `:13` requires operator or JWT. | DROP custom deployment support | None | None | A custom unauthenticated Amazon module endpoint is not automatically usable via native source admission. Existing operator/Turnstile native paths already implement those settings/protocols. |
| QQ UIN/music key/login dialect and clear/HLS playback | No missing basic capability: `J/qq/main.js:5` sends credentials/dialect, `:25` rejects QMC; `S/provider/KtorQqMusicRepository.kt:13` already holds dialect/credentials, `:44` sends login type, `:169` returns the HLS recipe and also supports QMC. | DROP redundant implementation; retain native owners | Existing repository/account settings | Existing `SourceProviderProtocolTest` and desktop source suites | Module-only credentials require re-entry in ordinary QQ account settings. No claim of live login or encrypted playback verification. |
| Clear Amazon/QQ module downloads | Yes: module stream route is download-eligible (`A/playback/ModuleSelectingDownloader.kt:48`; clear answers `J/amazon/main.js:31`, `J/qq/main.js:28`). Native Android mapping explicitly returns null for AMAZON/QQ (`A/constants/PreferenceKeys.kt:492`). | DROP from PR 2; PR 3 follow-up | None | None | Native playback alone does not make these sources downloadable. Clear progressive/HLS admission, cache descriptors and offline ownership need PR 3 fixtures; this PR does not claim to implement downloads. |
| LRCLIB exact lookup/extension preference | Yes for lookup semantics: `addons/modules/natune/lrclib/provider.js:9` sends exact `/api/get` with album/duration; `S/lyrics/LrcLibLyricsProvider.kt:116` searches without album and ranks duration. Both already provide native lyric text. | DROP duplicate lookup/order semantics; retain native LRCLIB | Existing native LRCLIB | Existing native lyrics suites; unchanged module Node fixtures | Native search can choose a different lyric result. Module preference/cache/order is not merged here. Existing lyric documents/parsers/renderers remain; do not conflate this with the separate lyrics audit. |
| Arbitrary installed plugins/custom catalog pages | Yes: `A/modules/ModulePlaybackUseCases.kt:170` executes installed source; PR 1 `D/provider/ProviderContracts.kt:49` configures finite IDs only. No finite parity inventory exists for third-party scripts. | DROP by owner decision | None | None | Third-party audio/lyrics/metadata/playback behavior and custom catalog pages stop at runtime removal. No compatibility execution layer is promised. |

## Dropped, with user impact

- Custom TIDAL clients/profiles and custom Deezer/Amazon/Pool deployments are not
  converted. Accounts tied to those clients or endpoints can stop working; built-in
  sign-in/settings require re-entry. No module credential import is implemented here.
- Qobuz passwords are not saved for automatic 401 recovery. Users re-enter sign-in
  when a saved token is refused; format downgrade remains manual.
- Per-installed-source Pool gates and Pool-first priority are lost at cutover. Users
  need to review the native global Pool toggle; no migration silently enables it.
- Module-specific longer/date cooldowns and TIDAL search-response cooldown are not
  copied. Portable opt-in playback uses the existing bounded native cooldown policy.
- JioSaavn title aliases and exact LRCLIB lookup/order semantics can choose different
  results under native matching. Web mode offers 96/320; it cannot provide 160.
- Clear Amazon/QQ downloads, completed/partial module downloads, module source pins,
  module order and custom catalog IDs are not converted by PR 2. Download/offline work
  belongs to PR 3 and cutover; these losses remain unresolved, not implemented.
- Unknown third-party plugins and their custom pages will stop working when later
  PRs remove execution. This PR still leaves the old module route present.

## Verification

Local permitted checks: repository contracts **0 findings**, `git diff --check`,
**53** iOS Python tests, **267** scripts Python tests and **13** existing bundled
module Node fixture tests passed. New common/JVM HTTP tests cover exchanges,
recording matching, serialized credentials, returned manifests/URLs, refusal,
redirect isolation and caller cancellation. They are additions; no test is skipped,
weakened or removed. No local Kotlin green/red run was possible. Kotlin compilation/execution is delegated to CI; no Gradle,
Android SDK setup, large artifact download or live account request ran on this VM.

Both new playback options default off; existing source enable/quality keys and
native default attempt order are preserved. Users explicitly select the new mode
or sign-in; a future lean settings carryover may select them for a known bundled
installation. No automatic carryover, upgrade report or cutover is claimed here.

Unverified: live Qobuz/TIDAL account exchange, endpoint availability, entitlement,
Android/desktop/iOS devices, native playback, secure-store upgrade behavior,
module-download migration and the actual installed-plugin population. HTTP fixtures
prove the covered protocol behavior, not those live outcomes.
