# Discord application configuration

Android Now Playing uses the Discord Gateway and does not require the proprietary
Social SDK. OAuth linking remains an optional account-linking route.

Set the repository's GitHub Actions **variable** `DISCORD_APPLICATION_ID` to the
public ID of the Natune Discord application. It is an application identifier,
not a secret. Android PR/candidate builds, tests, emulator QA and the reusable
release builder receive it as the Gradle environment variable
`DISCORD_APPLICATION_ID`; Nightly and Stable workflows use the same variable.
Local `DISCORD_APPLICATION_ID` configuration remains supported. An empty or
invalid value falls back to the inherited upstream ID `1165706613961789445`.
That fallback is not a Natune-owned application and will be replaced when the
new Natune ID is provided.

The configured ID sets both the activity application ID and the Android OAuth
callback scheme. For a new ID, the redirect URI becomes exactly:

```text
discord-<new id>:/authorize/callback
```

Register that exact URI in the new application's OAuth2 settings. For example,
ID `1234567890123456789` uses
`discord-1234567890123456789:/authorize/callback`. The Android manifest and OAuth
client derive it from the same Gradle configuration. No confidential Discord
client secret is embedded or supplied by these workflows.
