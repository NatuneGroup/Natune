# Discord Social SDK on Android

Now Playing uses the **official Discord Social SDK with OAuth PKCE**. There is no
user-token WebView login and no direct Gateway IDENTIFY transport.

## Shipping builds (SDK bundled)

Stable, Nightly and signed release-candidate APKs always include the SDK. Before
assembling, `.github/actions/fetch-discord-sdk` downloads the AAR from the private
`NatuneGroup/discord-social-sdk` mirror at a pinned commit, checks its pinned
SHA-256 and builds with `-Pnatune.discordSdk=true`. The step fails when the
`DISCORD_SDK_TOKEN` Actions secret is missing, and
`scripts/check_discord_sdk_apk.py` fails the build when an APK lacks
`libdiscord_partner_sdk.so` or `libnatune_discord_social.so` for any packaged
ABI, so a shipping build can no longer fall back to the "unavailable" notice.
The SDK adds roughly 3.7 MB per ABI; the signed-candidate size gate is
24,000,000 bytes.

To update the SDK, push the new AAR to the mirror and change `SDK_COMMIT` and
`SDK_SHA256` in the action together.

## Pull-request and local default builds (SDK absent)

Pull-request and debug CI builds never receive the token. Without the SDK:

- no AAR dependency, no `discord_partner_sdk` prefab package, no
  `natune_discord_social` JNI library and no AndroidX Browser dependency;
- `DiscordSocialPresenceClient` uses the no-op `NoDiscordPresenceBackend`, so
  playback never tries to send presence;
- Discord settings keeps browser PKCE account linking and shows an
  "unavailable" notice instead of a broken SDK login;
- pull-request jobs never fetch the private SDK repository or see
  `DISCORD_SDK_TOKEN`.

## Building locally with the SDK

Put the Android AAR from the private `NatuneGroup/discord-social-sdk` repository
at this exact path (it is ignored by Git):

```text
third_party/discord-social-sdk/android/discord_partner_sdk.aar
```

Then build with the Gradle property:

```bash
./gradlew :app:assembleDebug -Pnatune.discordSdk=true
```

Both conditions are required: `natune.discordSdk=true` **and** the AAR file being
present. An absent property or AAR leaves the integration off, even if the other
condition is met.

## Application ID and redirects

The Natune application ID is **1569030685010821170** and the application is a
Public Client. Android Gradle reads `DISCORD_APPLICATION_ID` from local properties
or its environment, with this ID as the default. Android CI passes the repository
Actions **variable** `vars.DISCORD_APPLICATION_ID`, not a secret.

The registered external Android PKCE redirect is:

```text
discord-1569030685010821170:/authorize/callback
```

For another configured ID it becomes `discord-<new id>:/authorize/callback`. The
desktop redirect is `http://127.0.0.1/callback`.

## SDK-enabled build wiring

SDK-enabled builds add:

- the AAR as a local dependency and AndroidX Browser `1.8.0` (required by the
  SDK's built-in mobile auth flow);
- `-DNATUNE_DISCORD_SOCIAL_SDK=ON`, which makes CMake resolve
  `find_package(discord_partner_sdk REQUIRED CONFIG)` and builds the
  `natune_discord_social` JNI target. The existing native-audio CMake/prefab
  target is untouched;
- a generated manifest overlay that declares the SDK's
  `com.discord.socialsdk.AuthenticationActivity` with the official scheme-only
  intent filter and removes the SDK's
  `com.discord.socialsdk.ForegroundService` (Natune uses auth and rich presence,
  not voice calls). The app's browser callback activity keeps an inert scheme in
  SDK builds, so it never competes with the SDK callback for the real redirect.

`DiscordOAuthRepository` retains browser PKCE account linking and scope
`sdk.social_layer_presence`. SDK-enabled builds authorize with
`CreateAuthorizationCodeVerifier`, `AuthorizationArgs`/default presence scopes,
`Authorize`, `GetToken`, `UpdateToken(Bearer, ...)` and `Connect`. Token
persistence and refresh use the existing OAuth session repository, with SDK
`RefreshToken` when the SDK is available.

### Permissions

The app manifest already declares `RECORD_AUDIO`, `MODIFY_AUDIO_SETTINGS`,
`FOREGROUND_SERVICE_MICROPHONE` and the Bluetooth permissions for Natune's own
music recognition, spatial-audio and playback features. The SDK overlay therefore
does **not** remove them: doing so would break those features. Only the SDK's
voice `ForegroundService` is removed.

## Backend and verification

The SDK backend sits behind `DiscordPresenceBackend`; SDK-free builds use the
unavailable/no-op backend. MainActivity calls the AAR's Java
`DiscordSocialSdkInit.setEngineActivity`. One C++ translation unit defines
`DISCORDPP_IMPLEMENTATION` and includes `discordpp.h`, linking
`discord_partner_sdk::discord_partner_sdk`. JNI callback pumping runs every
200 ms. Updates map the existing activity DTO into a listening activity with
song/artist, album-art URL/caption, timestamps and up to two buttons. Playback
policy still owns update and clear/stop decisions.

**Verification status:** the JNI and manifest wiring are written against SDK
1.10.19337's `discordpp.h`. Shipping and signed-candidate CI builds compile
and link the SDK target; pull-request builds stay SDK-free.

References: [mobile account linking](https://docs.discord.com/developers/discord-social-sdk/development-guides/account-linking-on-mobile),
[mobile SDK guide](https://discord.com/developers/docs/social-sdk/md_pages_2mobile.html),
[C++ getting started](https://discord.com/developers/docs/social-sdk/getting_started.html).
