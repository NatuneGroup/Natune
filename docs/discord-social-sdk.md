# Discord Social SDK on Android

Now Playing uses the **official Discord Social SDK with OAuth PKCE**, scope
`sdk.social_layer_presence`. The licensed Android AAR is optional and stays in
the private `NatuneGroup/discord-social-sdk` repository. There is no user-token
login or direct Gateway authentication.

## Default builds (SDK absent)

Nothing in the default build references the SDK:

- no AAR dependency, no `discord_partner_sdk` prefab package, no
  `natune_discord_social` JNI library and no AndroidX Browser dependency;
- `DiscordSocialPresenceClient` uses the no-op `NoDiscordPresenceBackend`, so
  playback never tries to send presence;
- Discord settings keeps browser PKCE account linking and shows an
  "unavailable" notice instead of a broken SDK login;
- Android CI skips the private checkout when `DISCORD_SDK_TOKEN` is unset or the
  event is a pull request. These builds stay SDK-free with unchanged APK size.

When enabled, the AAR adds its native libraries to the APK for the selected ABIs.

## Developer Portal and private repository

1. In the Discord Developer Portal, enable Social SDK access for the Natune
   application **1569030685010821170** and turn on **Public Client** under OAuth2.
2. Register `discord-1569030685010821170:/authorize/callback` for Android and
   `http://127.0.0.1/callback` for desktop. For another application ID, the Android
   redirect becomes `discord-<APP_ID>:/authorize/callback`.
3. Download the licensed Android Social SDK and upload
   `android/discord_partner_sdk.aar` to the private repository's `main` branch,
   together with a README and version information. The current API is 1.10.19337.
4. Create a fine-grained PAT with **Contents: read-only** access only to
   `NatuneGroup/discord-social-sdk`. Store it as the Natune repository Actions
   secret **DISCORD_SDK_TOKEN**; never commit it or a Discord client secret.
5. Set the public Actions variable **DISCORD_APPLICATION_ID** to
   `1569030685010821170`.

## Android CI

The shared `prepare-discord-sdk` action checks out only the private Android
directory and version/README files with credential persistence disabled. Build,
unit-test, emulator and release-candidate jobs use it; Stable and Nightly share
the Android job in `_release-artifacts.yml` and already inherit repository secrets.
The token is passed only to checkout, never to Gradle or the app.

Trusted builds pass `-Pnatune.discordSdk=true` only when the checkout contains the
AAR. Missing AARs use the no-SDK build. Pull requests always receive an empty
token and do not fetch the SDK, including same-repository PRs. Invalid configured
credentials fail the checkout instead of silently hiding a configuration error.

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
condition is met. CI uses the same property after its optional checkout.

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

**Verification status:** JNI signatures were checked against SDK 1.10.19337's
`discordpp.h`, including `Activity.AddButton` and the callback-free
`Client.ClearRichPresence`. No Gradle/JVM or native build is run on the constrained
development host. SDK-enabled CI compilation remains pending until the token is
configured; device authorization and presence still need verification.

References: [mobile account linking](https://docs.discord.com/developers/discord-social-sdk/development-guides/account-linking-on-mobile),
[mobile SDK guide](https://discord.com/developers/docs/social-sdk/md_pages_2mobile.html),
[C++ getting started](https://discord.com/developers/docs/social-sdk/getting_started.html).
