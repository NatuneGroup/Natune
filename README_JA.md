<div align="center">

  <img src="assets/ic_launcher-playstore.png" width="160" height="160" alt="Natune アプリアイコン">

  <h1>Natune</h1>

  <p><strong>Android、Windows、Linux、macOS、iOS 向けの Kotlin Multiplatform 音楽プレイヤーです。</strong></p>

  <p><a href="README.md">English</a> · <a href="README_JA.md">日本語</a></p>
</div>

## プロジェクト概要

Natune（旧 ArchiveTune）は、Android、Windows、Linux、macOS、iOS を対象とする音楽プレイヤーです。この非公開リポジトリでは、既存の Android アプリを Kotlin Multiplatform に移植しています。移植と各プラットフォームの統合は未完了です。現在の実装状況と未接続項目は、この文書の末尾に記載しています。

画面上のアプリ名は Natune です。互換性のため、既存の Android アプリケーション ID、iOS bundle ID、Kotlin package 名、永続データの識別子は維持しています。

## 既存 Android アプリの機能

以下は移植元である Android 製品の機能です。KMP 対応プラットフォームすべてでの機能提供を示すものではありません。

- 複数アカウント、バックグラウンド再生、ローカルファイル、プレイリスト、いいねした曲、登録チャンネルに対応します。音源は選択した順に Tidal、Qobuz、Deezer、Amazon、QQ Music、JioSaavn、Telegram などを試し、最後に YouTube を使用します。無効にした音源は再生、先読み、クロスフェード、Cast、ダウンロードでスキップされます。
- EBU R128 ラウドネス正規化、テンポ・ピッチ・再生速度、クロスフェード、automix、システムイコライザー、空間オーディオ連携に対応します。
- 同期歌詞、単語単位のカラオケ表示、TTML、翻訳、AI 翻訳、ローマ字表記、音楽認識、リスニング統計を含みます。
- Spotify プレイリストのインポート、YouTube Music アカウント、Last.fm scrobbling、ListenBrainz 履歴同期、Discord rich presence を含みます。
- Material 3、アルバムアート由来の dynamic color、17 種類のプレイヤースタイル、8 種類のプレイヤー背景、liquid glass、予測型戻る操作、システムアニメーション設定、レスポンシブレイアウト、再生/UI のカスタマイズに対応します。

## Android 版の配布

Android の Stable / Nightly は、公開中の [Natune Android プロジェクト](https://github.com/NatuneGroup/ArchiveTune/releases)で配布されています。この非公開 KMP リポジトリには、検証済みの KMP リリースはありません。

- Android Stable は [Obtainium](https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/NatuneGroup/ArchiveTune/)、[IzzyOnDroid](https://apt.izzysoft.de/fdroid/index/apk/moe.rukamori.archivetune)、[OpenAPK](https://www.openapk.net/archivetune/moe.rukamori.archivetune/)、[Unclouded](https://unclouded.app/apps/archivetune/) からも入手できます。
- 公開 Android プロジェクトの [GitHub Stable releases](https://github.com/NatuneGroup/ArchiveTune/releases/latest) と [Nightly pre-releases](https://github.com/NatuneGroup/ArchiveTune/releases) も利用できます。
- **Stable** は `main` からビルドし、通常のリリースとして公開します。
- **Nightly** は `dev` からビルドし、`N` タグ付きプレリリースとして公開します。
- Stable と Nightly は同じ署名鍵を使用します。旧 Canary は終了しており、最終版 `C202610141722` は `a1a04ea02` で公開済みです。`canary` ブランチは凍結され、保存済み Canary 選択は Nightly に移行します。
- アプリ内更新確認は、まず `update-feed` リリースの `stable.json` / `nightly.json` を読みます。このマニフェスト取得に GitHub キーは不要です。取得できない場合のみ GitHub API にフォールバックします。

> [!IMPORTANT]
> **利用可能地域:** YouTube Music がサポートされていない地域では、初回データ取得に対応地域の VPN または proxy が必要です。

上記の公式配布元を利用してください。その他の配布元はプロジェクトによる確認対象外です。

### Android 版の過去のリリース結果

PR #198 は `643a35fd09888769a4a2d588324398d68f0d6fb5` でマージされ、Nightly **N202610151104 / build 6833** として公開されました。同一コミットの Android build/unit-test ゲートは **835 成功、0 失敗、0 スキップ**でした。継承された lint は 318 errors、745 warnings、43 hints で、lint-clean ではありません。実機 QA は実施したと主張していません。これらは Android 版の過去の結果であり、KMP 移植や後続ソース変更の検証ではありません。

### N202610151104 で提供された Android 機能

- Stable / Nightly は各ビルドでユーザーが選択するチャンネルです。ビルド種別は未設定時の既定値のみを決めます。更新候補はチャンネルと既知の新しい version code に結び付きます。
- 表示中で長い設定説明は有限回だけ自動スクロールし、画面状態とアニメーション無効設定を尊重します。再起動が必要な変更は保存後に共通の **Restart now / Later** dialog を表示します。「後で」を選んでも自動再起動しません。
- 音響 heat graph は Developer 設定と共通 player/song menu から利用できます。開いただけでは解析せず、Run/Cancel/Retry により時間と対象を制限した同一ソースの excerpt を処理します。seek 可能な local file/content、対応する clear stream、完全な同一ソース cache が対象です。DRM またはアクセス不能な入力は利用不可になり得ます。スペクトル上の疑いは heuristic であり、原音の lossless 性や物理 DAC 出力の証明ではありません。
- 既存 DSP の Oboe を 1.11.0、Signalsmith Linear を 0.6.5、保持する libusb を 1.0.30 に更新しました。Stretch は 1.4.0、soxr は 0.1.3 のままです。新しい engine や既定の音声方針は追加していません。libusb/soxr はアプリの CMake/Gradle 経由で再構築する Android 用部分ツリーです。稼働中の USB 出力を維持し、到達不能な旧 USB module のみを削除して MIT license を保持しています。
- 歌詞変更は意味を持つ Unicode を保持し、保守的な文字体系除外と metadata/kana に基づく読みを使います。SimpMusic は共通の組み込み/provider/AI 方針と、整形・source-spaced な時刻付きテキストを使います。読みと単語時刻が一致しない場合は行単位に戻し、正確な言語判定や全言語の発音を保証しません。
- AI 翻訳は完全で検証済みの結果のみを受け入れ、失敗を再試行可能にし、設定と現在の歌詞対象に cache/result を結び付けます。DeepL は明示した翻訳先コードを使い、ローマ字変換には対応すると主張しません。翻訳画面では対象変更時に結果を無効化し、自動実行には除外条件を適用します。Downloader の一時領域を要求ごとに分離し、Listen Together の再試行を接続・要求に結び付け、Spotify の更新/ログイン/ログアウトを直列化します。
- 日本語辞書の自動導入は端末の主システムロケールの region `JP` を使用し、IP geolocation は行いません。永続化された foreground 検討を一度だけ行い、Wi-Fi またはモバイル回線を利用できます。offline の場合も検討済みとなりますが、手動再試行は可能です。明示的な無効化・削除は自動導入を止めます。download/checksum/tokenizer 処理は startup/render thread を止めません。固定 13,343,016 byte の IPADIC pack は optional な offline-romaji データであり、UI 翻訳や全言語 engine ではありません。

このリリースには production tokenizer/IPADIC fixture が含まれます。実機、表示、USB、全言語の検証は行っていません。

### Android 版の後続変更（未コンパイル・未リリース）

現在の YouTube 後続変更はローカルソースであり、未コンパイル・未リリースです。

- 通常のプレイリストは RD/Mix や明示的な radio と分離した有限の browse queue を使用します。ページングでは項目の出現順、選択位置、手動追加、既存のフィルター・repeat 方針を保持します。おすすめの追加には有限リストの完了確認が必要で、旧状態が不明な場合やページ取得失敗時に radio とみなしません。
- プレイリスト全体の追加、次に再生、インポート、同期は、完全な一覧の読み込み後にのみ追加・置換します。失敗、停滞、上限到達は不完全であり、部分成功ではありません。通常の Shuffle は有限リスト全体を読み込み、クライアント側で並べ替えます。サーバー側と同じ shuffle 順序とは主張しません。
- ログイン復旧は Music store intent で観測した埋め込み WebView の `net::ERR_UNKNOWN_URL_SCHEME` に対応します。renderer crash が確認されたという意味ではありません。限定された公式 install target から、同一 WebView 内で一度だけ HTTPS に戻す制御を行います。候補のアカウント情報は検証成功まで非公開のまま保持し、現在の試行だけが確定できます。古い callback は新しいセッションを上書きできず、logout は YouTube の範囲に限定されます。
- 匿名 MetrolistGroup InnerTubeX `v0.11.2` のソース pin は `c863ddf15be01a1abfa4630329bdc0a1e95f1531` です。これはプレイリストやログイン変更とは別です。primary client の手動選択と `YTPlayerUtils → InnerTubeX → NewPipe → Echo` の順序を維持し、個人/Pool cookie bridge や新たな HLS/SABR/bounded-range transport は有効にしていません。
- 旧版 README には以前のチェックポイントとして新規テストメソッド 113 件が記録されていました。後続の review-fix 統合チェックポイントでは、134 件（元の121件と回帰13件）が作成・統合済みです。どちらも作成数であり、成功数ではなく、ローカル実行もされていません。確認済みのソースレビュー修正5件は、対象を絞った再確認と別々の exact-head remote BUILD/TESTS を待っています。Google ログイン、YouTube page envelope、描画済み WebView、音声再生の実動作確認はしていません。過去の成功結果は新しい変更の成功を示しません。

## 開発・検証コマンド

Android の文書化された環境は JDK 21、Android compile SDK 37、NDK `30.0.16248370`、同梱の Gradle 9.6.1 wrapper、再帰的 submodule です。Android の build/lint と単体テストのコマンドは次のとおりです。

```sh
./gradlew assembleGmsMobileUniversalDebug lintGmsMobileUniversalDebug
./gradlew :app:testGmsMobileUniversalDebugUnitTest
```

KMP desktop の起動と shared module の検証コマンドは、以下のポート状況に記載しています。**現在のコード専用フェーズの検証は、ユーザー指示により延期されています（`deferred_by_user`）。** この branding 変更では build、test、lint、repository checker、アプリ起動、スクリーンショット、署名、upgrade check を実施していません。この延期は制約付きフェーズに限られ、通常の開発者によるローカル作業を禁止するものではありません。

## クレジット

- **InnerTune** — Zion Huang: [InnerTune](https://github.com/z-huang/InnerTune)。
- **Metrolist** — Mostafa Alagamy: [Metrolist](https://github.com/mostafaalagamy/Metrolist)。
- **ArchiveTune** — Rukamori: [ArchiveTune](https://github.com/rukamori/ArchiveTune)。元になった ArchiveTune プロジェクトです。
- **SimpMusic** — [maxrave-dev](https://github.com/maxrave-dev/SimpMusic)。歌詞 API provider。
- **4nx3b ArchiveTune** — upstream base、および Bini、Genius、LRCRed、Megalobiz の歌詞 provider。
- **Tryptify** と [tryptify-audio-core](https://github.com/tryptz/tryptify-audio-core) — DSP chain、parametric/AutoEq、USB 出力、pitch/time stretch で使用する audio engine。
- **Clash-Projects LastWave-Native** — native audio engine、bit-perfect USB 出力、Last.fm dashboard。
- **Marcelo Silva** — 以前同梱されていた decent-usb-audio-driver（MIT）。ライセンスと attribution は保持しています。
- [libusb](https://libusb.info)、[SoX Resampler](https://sourceforge.net/projects/soxr/)、[Signalsmith Stretch](https://github.com/Signalsmith-Audio/signalsmith-stretch) — USB access、resampling、pitch shifting。
- [echo-spotify-extension](https://github.com/brahmkshatriya/echo-spotify-extension) — Spotify の recently played list 読み取りの参考。
- [BetterLyrics](https://better-lyrics.boidu.dev/)、[Material Color Utilities](https://github.com/material-foundation/material-color-utilities)、[Read You](https://github.com/Ashinch/ReadYou)、[Seal](https://github.com/JunkFood02/Seal)、翻訳者、beta tester、contributor、community の皆様。

プロジェクトごとの attribution、ライセンス、対象ファイルは [docs/CREDITS.md](docs/CREDITS.md) を参照してください。

## プライバシーとライセンス

- [プライバシー情報](PRIVACY.md)
- [ビルドと貢献ガイド](CONTRIBUTING.md)
- [公開プロジェクトのサポート](https://github.com/NatuneGroup/ArchiveTune/issues)

Natune は独立したサードパーティークライアントです。Google LLC または YouTube とは無関係であり、YouTube の技術的保護を回避しません。ソースコードは GPLv3 でライセンスされています。条件は [LICENSE](LICENSE) を参照してください。

Copyright © vossgraves / Natune and contributors; earlier portions © their respective authors (see docs/CREDITS.md)

## KMP ポートの進捗（開発中）

この非公開 NatuneGroup/ArchiveTune-KMP workspace は、NatuneGroup/ArchiveTune の `dev` commit `b7e4dd444fea202a5c32e96f7b1199a74fe3ae70` から始まっています。公式 Metrolist KMP release までは非公開です。復元した Metrolist build は参照資料であり、元の KMP source を完全に取得したものではありません。Metrolist と既存 contributor の credit、copyright、license は維持します。

**現在（コード統合のみ、未検証）:** 非公開 remote の `main` と `dev` は、独立した親なし KMP foundation `c047a2002a7c35eb9ed30b6a8eec72951f7f2f48` を指しています。ローカル `dev` では port history と foundation の両方を保持する merge commit を作成しています。push はしていないため、remote `dev` と `main` は変更していません。foundation の `:shared` は `shared/` に保持していますが active build には含めず、承認済みの `:shared-domain` clock contract を有効な契約として維持しています。

Shared Compose root `ArchiveTuneRoot` は完全な `ArchiveTuneHostBindings` 一式を受け取ります。desktop entry point `desktopApp/src/jvmMain/kotlin/moe/rukamori/archivetune/desktop/Main.kt` は single-instance lock の後に `DesktopSessionGraph` を1つだけ開きます。そこでは通常設定ファイルと platform credential vault をまとめる `VaultSettingsStore`、provider account store、portable database v5、library、artist-block、listening-history repository、playback port/player、checkpoint store を持つ queue owner、listen-history driver を構成します。`DesktopHostBindings` を通じて `ArchiveTuneRoot` を表示します。

Window の visibility/resume を root に伝えます。window を隠しても再生は pause しません。hide-to-tray 設定が有効で tray を利用できる場合は閉じる操作で tray に隠し、それ以外は終了します。library と playlist page は shell から graph の library/block/listen owner を使い、liked songs と My Top は route、deep link、Library の More menu から開けます。Alt+Left は戻る操作です。Playlists の作成 dialog は local playlist を保存し、保存完了後に開きます。行の再生、play next、queue 追加、shuffle copy、player の previous/next/repeat/seek/like は同じ queue と graph を操作し、like は現在の曲を owner の liked list から読み書きします。

音源は順序付き chain で解決します。公開 Qobuz backup mirror は単一 HTTP client 上に構成され、設定で有効にするまでは停止しています。それ以外の音源は account または decoder owner が接続されるまで unavailable を返します。YouTube と local file は desktop に未接続です。どの音源も応答しない queue load は試行内容とともに失敗します。項目・news・player artwork は同じ client で public HTTP/HTTPS image として取得し、cookie/account header は送信しません。取得失敗やサイズ超過では placeholder frame を維持します。

### 未接続・未実装の機能

desktop では Library の mixed section shortcut cards、spotlight、recent songs、top mixes、Spotify rails（playlist、saved artist、saved album の一覧以外）、queue shuffle toggle、volume control、custom background image、play next/add to queue 以外の song menu、hide-to-tray 設定 row、native sign-in と Telegram owner、Listen Together、Last.fm、Drive backup、custom artwork が未接続です。iOS entry point は host-pending placeholder root を呼び出します。永続 settings store と native owner は未統合です。sample/stub owner は使用していません。

Desktop run（このフェーズでは未実行）:

```sh
./gradlew :desktopApp:run --max-workers=1 --no-daemon
```

JDK 21 を使用します。session data は `~/.archivetune/desktop` に保存します。JVM を直接起動する場合は `-Darchivetune.instance.directory=<dir>` で変更できます。credential は Linux の Secret Service、macOS の Keychain、Windows の DPAPI など platform credential store のみへ保存し、平文には保存しません。credential reference があるのに vault から取得できない場合は unavailable boundary を表示します。保存済み credential がなければ graph は開きますが、vault が必要な credential write は明示的に失敗します。

foundation の手動 readiness workflow は `.github/inherited-workflows/` に保管しています。有効な workflow は手動 dispatch の `.github/workflows/readiness.yml` のみで、継承した13 workflows は隔離したままです。port 用に適合した delivery workflow 11件は `.github/private-workflows/` に無効状態で保管し、import gate の解消と最終 CI の承認後まで `.github/workflows/` に移しません。iOS shared-data cinterop、static `ArchiveTuneShared` framework export、iOS quickjs-kt、iOS native closure producer は source にありますが、desktop/iOS root は前述の owner bindings の統合待ちです。最初の push は import の security gate について正当な人による判断が行われるまで保留します。

**検証はユーザー指示で延期されています（`deferred_by_user`）。** コード専用フェーズでは integrated source を compile、test、lint、launch、screenshot、sign、upgrade していません。Android、iOS、CI、署名、in-place upgrade の結果は未検証です。foundation 単体の CI 結果は port の証拠ではありません。継承された Android の指示や過去の結果も、現在の port を証明しません。

iOS native closure producer `scripts/build_ios_native_core.sh` は source のみです。macOS 以外では実行を拒否し、これまで archive を build していません。出力 `shared-data/build/ios-native/<iphoneos|iphonesimulator>/lib` は Apple Silicon Mac と Xcode 26.4 で実行されるまで未検証です。詳細は [iOS source status](docs/engineering/ios-status.md) を参照してください。in-place upgrade verifier `scripts/verify_inplace_upgrade.py`（UPGRADE-001..005）と release measurement tool `scripts/measure_release_and_perf.py` も source のみで、最終フェーズで承認された入力を使って実行した場合に限り証拠になります。

最終フェーズが承認された後に記載済みコマンドを実行します。

```sh
./gradlew :shared-domain:allTests :shared-data:jvmTest :shared-ui:desktopTest --max-workers=1 --no-daemon
python3 scripts/check_repository.py --worktree
scripts/build_ios_native_core.sh [iphoneos|iphonesimulator|all]
```

日本語ローマ字データ、CC-BY-SA-4.0 icon pack、Metrolist KMP、kotlin-footguns の credit と notice は [docs/CREDITS.md](docs/CREDITS.md) に記載しています。

**正当な gate 解消後の予定:** `main`（Stable/default）と `dev`（integration）を設定し、元の CI/workflow の動作を private-safe に適合します。remote `main` の変更や port 用 branch の作成には別途承認が必要です。署名と package identity を保持し、より大きな versionCode とデータを保った in-place upgrade を検証する必要があります。これは未完了です。Debug `.debug` APK や署名一致だけでは upgrade の証明になりません。承認済みの private APK を手動で取得すれば検証できます。token updater や cloud service の追加は含みません。詳細は [baseline provenance](docs/engineering/kmp-baseline.md) と [AGENTS.md](AGENTS.md) の private workspace guidance を参照してください。

