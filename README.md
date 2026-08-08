# Radikk

ネイティブ Android (Kotlin + Jetpack Compose) 製の [radiko](https://radiko.jp/) クライアント。

Flutter 版で発生した「ExoPlayer の HLS Source Error(0)（`SampleQueueMappingException`、audio/mp4a-latm）」を解決するため、再生エンジン (Media3/ExoPlayer) を直接制御するネイティブ実装として作り直した。

## 機能

- **ホーム**: エリア選択 + 放送局一覧（各局の現在放送中番組名を表示）。放送中番組タップで詳細（出演者・説明文・通知設定）。ダウンロード番組（最大5件）を下部に表示し、「すべて見る」でタイムフリーのダウンロードタブへ遷移
- **ライブ再生**: 放送局タップ → 認証 → 再生。NHK など smartstream が 504 を返す局は dr-wowza へ自動フォールバック
- **タイムフリー再生**: 「検索」/「局から選ぶ」/「ダウンロード」のタブ構成。過去7日分の番組を再生（シーク・一時停止・再開）。選択エリア全局×7日分を起動時にプリロードしてキャッシュし、全局横断検索を即時利用可能に（検索準備中インジケーター・日付フィルター「今日/昨日/3日以内/すべて」付き）
- **ダウンロード**: タイムフリー番組を単一 .aac ファイルとして保存（SAF フォルダ選択でダウンロード先を指定）。フォアグラウンドサービス（dataSync）でアプリを閉じても継続し、進捗通知 + 通知からのキャンセルに対応。ダウンロード一覧からそのまま再生・削除でき、放送日時/ダウンロード日時で並び替え可能
- **番組表**: 局一覧 × 時間（JST 5:00 起点24時間）のグリッド。日付チップ（今日〜7日分）、放送中ハイライト、「今すぐ」ボタンで現在時刻へジャンプ。番組タップで詳細、長押しで開始通知の設定
- **番組開始通知（リマインダー）**: 放送開始時刻に通知。端末再起動後も自動で再登録され、通知タップでそのまま再生
- **ミニプレイヤー / 全画面プレイヤー**: 画面下部のミニプレイヤー（停止ボタン + シークバー）。タップで全画面プレイヤー（番組詳細・局ロゴ・シークバー）
- **バックグラウンド再生**: Media3 `MediaSessionService`（常時有効）。メディア通知・ロック画面操作・通知タップでの復帰に対応
- **設定**: エリア選択（47都道府県）、テーマ（自動/ライト/ダーク + ダイナミックカラー）、ダウンロード先フォルダ、番組開始通知の一覧管理、認証キャッシュ削除、バージョン表示

## 技術スタック

| 項目 | 内容 |
|---|---|
| 言語 | Kotlin 2.3.20 |
| UI | Jetpack Compose (Material 3) |
| 再生 | androidx.media3 1.9.4 (exoplayer-hls) |
| HTTP | OkHttp 4.12.0 |
| JSON | kotlinx.serialization |
| 画像 | Coil 2.7.0 |
| 永続化 | DataStore Preferences |
| minSdk / targetSdk | 26 / 36 |

## ビルド

```bash
export JAVA_HOME=/home/yuchi0531/jdk17
export ANDROID_HOME=/home/yuchi0531/android-sdk
./gradlew assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

テスト（単体・ネットワーク不要）:
```bash
./gradlew testDebugUnitTest
```

lint:
```bash
./gradlew lint
```

## 実機インストール手順

エミュレータでは radiko の認証（GPS/地域判定）が通らない場合があるため、**必ず実機（Android 12+）で確認すること**。

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

1. アプリ起動 → ホームタブで東京の局（例: TBSラジオ）をタップ → 音が出ることを確認
2. 番組表タブで局を選択 → 番組表が表示されることを確認
3. 放送中番組をタップ → ライブ再生
4. 過去の番組をタップ → タイムフリー再生（シーク・一時停止・再開）
5. タイムフリーの番組をダウンロード → 進捗通知 → 一覧から再生できることを確認
6. 番組を長押し → 開始通知を設定 → 通知が届き、タップで再生されることを確認
7. 設定タブでエリア変更 → 再認証され、局一覧が切り替わることを確認

## アーキテクチャ

```
com.radikk.app
├── MainActivity.kt          # ボトムナビ 4 タブ（ホーム/番組表/タイムフリー/設定）、通知タップ再生
├── RadikkApplication.kt     # 手動 DI ワイヤリング
├── data/
│   ├── api/                 # OkHttp クライアント、RadikoApi 定数・エリア GPS 座標、fullKey 読み込み
│   ├── auth/                # AuthRepository (auth1→auth2、シングルフライト、トークン永続化)
│   ├── model/               # Station, Program, AuthSession
│   ├── repository/          # StationRepository (full.xml), ProgramRepository (v4 date)
│   ├── datastore/           # SettingsRepository (設定・認証情報)
│   ├── programcache/        # 番組表の日別キャッシュ
│   ├── timefree/            # タイムフリー検索用キャッシュ
│   ├── download/            # ダウンロード (DownloadRepository / DownloadManager / DownloadService FGS)
│   ├── reminder/            # 番組開始通知 (Repository / Scheduler / Receiver / BootReceiver)
├── player/
│   ├── RadikoPlayer.kt      # Media3 ラッパー、medialist 直接再生、エラー分類
│   ├── StreamUrlResolver.kt # m3u8→medialist URL 抽出、NHK フォールバック、シーク対応
│   └── PlaybackService.kt   # MediaSessionService
├── ui/
│   ├── theme/               # Material3 + ダイナミックカラー
│   ├── navigation/          # BottomTab (4 タブ定義)
│   ├── screen/              # Home / ProgramGuide / Timefree / Settings
│   └── component/           # StationCard, AreaSelector, MiniPlayer, FullPlayerScreen, ProgramDetailDialog
└── util/                    # RadikoTimeUtil (JST 14桁変換、5時起点)、HtmlText
```

## HLS Source Error 回避策

1. **medialist URL を直接 `MediaItem` に渡す**（マスタープレイリストを介さない）
   - `StreamUrlResolver` が m3u8 を取得し、`#EXT-X-STREAM-INF` 直後の medialist URL を抽出
2. **`DefaultHttpDataSource.Factory.setDefaultRequestProperties` で認証ヘッダーを全適用**
   - `X-Radiko-AuthToken` / `X-Radiko-AreaId` が HLS の全リクエストに付与される
3. **ID3 タグ付き ADTS AAC セグメント**
   - Media3 の `DefaultHlsExtractorFactory` は `AdtsExtractor` を既定の候補に含み、抽出器は**メディア種別ではなくスニッフィングで判定**する
   - radiko のセグメント URL は `.aac` で終わるため `FileTypes.inferFileTypeFromUri` が ADTS を返し、`AdtsExtractor.sniff()` が ID3v2 ヘッダー（10バイト + synchsafe サイズ）を読み飛ばし、先頭 8KB 以内の連続 ADTS フレーム（4フレーム以上）で検出する（ソースで確認済み・Media3 1.9.4）
   - 再生時は `AdtsReader` が ID3 をメタデータ出力として消費し、音声は ADTS のみ出力する。Content-Type application/octet-stream も検出に関係しない
4. **NHK など 504 を返す局のフォールバック**
   - `areafree=1` の smartstream 系 (si-c) が medialist で HTTP 504 を返す場合、timefree=0 の候補を優先順位（dr-wowza → smartstream）で順に試行する

## 認証フロー

1. **auth1**: `GET /v2/api/auth1`（`X-Radiko-App: aSmartPhone8` 等）
2. **partialkey**: fullKey（assets/fullkey.b64, 167KB base64）をデコード → `[KeyOffset, KeyOffset+KeyLength)` をスライス → 再 base64
3. **auth2**: `GET /v2/api/auth2`（partialkey + GPS 座標）→ `JP13,東京都,tokyo Japan`
4. トークンは約90分で期限切れ。有効期限と areaId を DataStore に永続化し、エリア一致+有効期限内なら再利用（認証はシングルフライト化）

## 注意点

- **fullKey**: `assets/fullkey.b64` に配置（Kotlin 文字列定数の 64KB 制限を超えるため）
- **エリア変更**: トークンはエリアに紐づくため、変更時は必ず再認証
- **タイムフリーのシーク**: radiko は約5分のスライディングウィンドウ配信のため、5分以上先へのシークは `seek` パラメータ付きでプレイリストを作り直す（ExoPlayer.seekTo だけでは移動できない）
- **ダウンロード**: 同じくスライディングウィンドウの制約のため、`DownloadManager` が seek 位置を 300 秒ずつ進めて medialist を取り直し、全セグメントを ID3 タグ除去して単一 .aac に連結する。ダウンロード中にトークンが失効した場合（約90分）は自動で再取得して再試行する
- **時刻**: すべて JST 基準。内部は UTC で保持し表示時に `Asia/Tokyo` で変換。番組表の日付境界は JST 5:00 起点（深夜 0:00-4:59 は前日分）
- **キャッシュ**: 放送局一覧 1時間、日別番組表 1時間（メモリ上限 300 件）。タイムフリーは過去7日分を全局プリロード（12時間スロットル）
