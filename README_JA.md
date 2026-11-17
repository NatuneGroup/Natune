<div align="center">

  <img src="assets/ic_launcher-playstore.png" width="160" height="160" alt="Natune アプリアイコン">

  <h1>Natune</h1>

  <p><strong>Android、Windows、Linux、macOS、iOS 向けの Kotlin Multiplatform 音楽プレイヤーです。</strong></p>

  <p><a href="README.md">English</a> · <a href="README_JA.md">日本語</a></p>
</div>

Natune（旧 ArchiveTune）は、Tidal、Qobuz、Deezer、Amazon、QQ Music、JioSaavn、Telegram、
ローカルファイルから音楽を再生し、最後の手段として YouTube Music を使用します。

## 機能

- **再生:** 音源ごとに順序と有効・無効を設定できる音源チェーン、複数アカウント、ダウンロード、バックグラウンド再生、Cast。
- **オーディオ:** EBU R128 ラウドネス正規化、クロスフェードと automix、テンポ・ピッチ・再生速度、イコライザー、USB ビットパーフェクト出力。
- **歌詞:** 多数のプロバイダーによる同期歌詞と単語単位の歌詞、翻訳、ローマ字表記。
- **ライブラリとソーシャル:** Spotify プレイリストのインポート、YouTube Music アカウント同期、Last.fm と ListenBrainz、Discord presence、Listen Together。
- **インターフェース:** アルバムアートの色を使う Material 3、17 種類のプレイヤースタイル、カスタマイズ可能なレイアウト。

## 対応プラットフォーム

| プラットフォーム | 状況 |
|---|---|
| Android | 全機能を備えたアプリ。Stable と Nightly を配布中。 |
| Windows、Linux、macOS | 開発中。共通 UI で動作し、機能は Android より少なめです。 |
| iOS | 開発中。 |

Android の画面を共通の Kotlin Multiplatform モジュールへ移行中で、完了後はすべてのプラットフォームで同じアプリが動作します。

## ダウンロード

Android 版は [ArchiveTune リポジトリ](https://github.com/NatuneGroup/ArchiveTune/releases)で配布しています。
**Stable** は `main` から、**Nightly** プレリリースは `dev` からビルドされます。どちらも同じ署名鍵を使い、アプリ内アップデーターは選択したチャンネルに従います。
Stable は [Obtainium](https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/NatuneGroup/ArchiveTune/)、
[IzzyOnDroid](https://apt.izzysoft.de/fdroid/index/apk/moe.rukamori.archivetune)、
[OpenAPK](https://www.openapk.net/archivetune/moe.rukamori.archivetune/)、
[Unclouded](https://unclouded.app/apps/archivetune/) にも掲載されています。それ以外の配布元は検証されていません。

> [!IMPORTANT]
> YouTube Music が利用できない地域では、最初のデータ取得に対応地域の VPN またはプロキシが必要です。

## ビルド

JDK 21 とサブモジュール（`git clone --recursive`）が必要です。Android ではさらに compile SDK 37 と NDK `30.0.16248370` が必要です。

```sh
./gradlew assembleGmsMobileUniversalDebug                                   # Android デバッグ APK
./gradlew :app:testGmsMobileUniversalDebugUnitTest                          # Android ユニットテスト
./gradlew :desktopApp:run                                                   # デスクトップアプリ
./gradlew :shared-domain:allTests :shared-data:jvmTest :shared-ui:desktopTest  # 共通モジュール
python3 scripts/check_repository.py --worktree                              # 高速な静的チェック
```

詳しくは [CONTRIBUTING.md](CONTRIBUTING.md) を参照してください。

## クレジット

Natune は Zion Huang の [InnerTune](https://github.com/z-huang/InnerTune)、
Mostafa Alagamy の [Metrolist](https://github.com/mostafaalagamy/Metrolist)、
Rukamori の [ArchiveTune](https://github.com/rukamori/ArchiveTune)、および 4nx3b の ArchiveTune フォークを基にしています。
また [SimpMusic](https://github.com/maxrave-dev/SimpMusic)、
[BetterLyrics](https://better-lyrics.boidu.dev/)、
[Material Color Utilities](https://github.com/material-foundation/material-color-utilities)、
[Read You](https://github.com/Ashinch/ReadYou)、[Seal](https://github.com/JunkFood02/Seal)、
そして翻訳者、テスター、コントリビューターの皆さんの成果を利用・参考にしています。
各プロジェクトとライセンスの一覧は [docs/CREDITS.md](docs/CREDITS.md) にあります。

## ライセンスとプライバシー

Natune は GPL-3.0 で公開されています。詳細は [LICENSE](LICENSE) を参照してください。
[プライバシー](PRIVACY.md) · [Issues](https://github.com/NatuneGroup/ArchiveTune/issues) ·
[Telegram](https://t.me/ArchiveTuneGC) · [Discord](https://discord.gg/XF2fpb9rTq) ·
[翻訳](https://translate.codeberg.org/engage/archivetune/)

Natune は独立したクライアントです。Google LLC や YouTube とは提携しておらず、YouTube の技術的保護を回避するものではありません。

Copyright © vossgraves / Natune and contributors; earlier portions © their respective authors（[docs/CREDITS.md](docs/CREDITS.md) を参照）.
