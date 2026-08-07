# AGENTS.md

ネイティブ Android (Kotlin + Jetpack Compose) 製 radiko クライアント。単一モジュール (`:app`)。

## ビルド環境（最初にやること）

- システムに `java` が無い。`./gradlew` の前に必ず設定する:
  `export JAVA_HOME=/home/yuchi0531/jdk17 ANDROID_HOME=/home/yuchi0531/android-sdk`
- コマンド:
  - ビルド: `./gradlew assembleDebug` → APK: `app/build/outputs/apk/debug/app-debug.apk`
  - 単体テスト: `./gradlew testDebugUnitTest`（44件・JVM のみ・ネットワーク不要）
  - lint: `./gradlew lint`
- フォーマッタ / ktlint / detekt / CI は無い。コードはプロジェクト内の既存スタイルに合わせる。
- スタック: AGP 8.13.1 / Kotlin 2.3.20 / Gradle 9.1.0 / JDK 17 / minSdk 26 / target & compile 36。

## 署名（release）

- release ビルドは git 管理外の `keystore.properties`（ローカルに存在）→ `keystore/radikk-release.jks` を参照する。
- `keystore/` と `keystore.properties` は .gitignore 済み。鍵・パスワードは絶対にコミットしない。

## アーキテクチャ

- DI は手動: `RadikkApplication` で各リポジトリを new して配線（Hilt/Koin 不使用）。リポジトリやマネージャを追加したら必ずここに wire する。
- `com.radikk.app`:
  - `data/api` — OkHttp クライアント、`RadikoApi` 定数・エリア GPS 座標、`FullKeyProvider`
  - `data/auth` — `AuthRepository` (auth1→auth2、シングルフライト、トークン永続化)
  - `data/repository` — `StationRepository` (region/full.xml) / `ProgramRepository` (v4 date)
  - `data/datastore` — 設定・認証情報 (DataStore)
  - `data/programcache` / `data/timefree` / `data/reminder` / `data/download` — 各機能の永続化
  - `player` — `RadikoPlayer` (Media3) / `StreamUrlResolver` (m3u8→medialist) / `PlaybackService` (MediaSessionService)
  - `ui/screen` — Home / ProgramGuide / Timefree / Settings。`ui/component` に共通部品
- 時刻はすべて JST 基準（内部 UTC → 表示時 `Asia/Tokyo`）。UI・コメント・エラーメッセージは日本語。

## radiko API の要点（検証済み仕様）

検証済み API 仕様の正は `docs/radikk-kotlin-rebuild-prompt.md`。API 挙動を変える変更の前に必ず読む。

- **認証**: auth1 → partialkey 生成 → auth2。partialkey は fullKey を base64 デコード → `[KeyOffset, KeyOffset+KeyLength)` を切り出し → **再 base64**。トークンは約90分で失効しエリアに紐づく（エリア変更時は必ず再認証）。
- **`X-Radiko-App-Version` は auth1/auth2 間で同一値**（リクエストごとに変えると 401）。
- **fullKey**: `app/src/main/assets/fullkey.b64`（167KB。Kotlin 文字列定数の 64KB 制限のため asset 化されている）。変更しない。
- **HLS Source Error 回避（最重要・変えないこと）**: マスタープレイリストを ExoPlayer に渡さない。m3u8 を取得し `#EXT-X-STREAM-INF` 直後の medialist URL を `StreamUrlResolver` で抽出して `MediaItem` に直接渡す。認証ヘッダーは `DefaultHttpDataSource.Factory.setDefaultRequestProperties` で全 HLS リクエストに付与。
- **Media3 UnstableApi** は意図的に使用: build.gradle.kts に `-opt-in=androidx.media3.common.util.UnstableApi`、lint は `app/lint.xml` で `UnsafeOptInUsageError` を無視。OptIn アノテーションや lint 設定を外さない。
- **ライブ**: station stream XML の `areafree=1` URL を使う。ただし NHK 等では smartstream (si-c) が 504 を返すため、`getLivePlaylistUrls` は timefree=0 の候補を全列挙し dr-wowza 優先で順にフォールバックする。この優先順位ロジックを壊さない。
- **タイムフリー**: `areafree=0` + `timefree=1`。m3u8 URL は `ft/to/start_at/end_at/type=b/l=300/seek/lsid` を**全て必須**で指定（JST 14桁 `YYYYMMDDHHMMSS`、欠落は 400）。
  - radiko は約5分のスライディングウィンドウ配信のため、**5分以上先へのシークは `seek` パラメータ付きでプレイリストを作り直す**こと（ExoPlayer.seekTo だけでは移動できない）。
- **番組表**: 日付境界は JST 5:00 起点（深夜 0:00-4:59 は前日分に属する）。
- **キャッシュ**: 放送局一覧 1時間、日別番組表 1時間（メモリ上限 300 件）。

## テスト

- 単体テスト (`app/src/test/java`) はネットワーク不要。パース系は実データフィクスチャを使用:
  `app/src/test/resources/full.xml`（実測 110局）/ `tbs_programs.json`（TBS 24番組）。テスト内では `RadikoApiClient()` を直接 new する。
- `unitTests.isReturnDefaultValues = true` 設定済み（`app/build.gradle.kts`）。

## 動作確認

- radiko の認証（GPS/地域判定）はエミュレータで通らないことがある。**再生系の変更は必ず実機（Android 12+）で確認する**: `adb install app/build/outputs/apk/debug/app-debug.apk`
- リリース時は CHANGELOG.md（Keep a Changelog 形式）を更新する慣例。
