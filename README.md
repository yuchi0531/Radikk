# Radikk

ネイティブ Android (Kotlin + Jetpack Compose) 製の [radiko](https://radiko.jp/) クライアント。

Flutter 版で発生した「ExoPlayer の HLS Source Error(0)（`SampleQueueMappingException`、audio/mp4a-latm）」を解決するため、再生エンジン (Media3/ExoPlayer) を直接制御するネイティブ実装として作り直した。

## 機能

- **ライブ再生**: 放送局一覧 → タップ → 認証 → 再生
- **タイムフリー再生**: 過去7日分の番組リスト → タップ → 再生（シーク可能）
- **番組表**: 局一覧 × 時間（JST 5:00 起点24時間）のグリッド。日付チップ（今日〜7日分）、放送中ハイライト
- **設定**: エリア選択（47都道府県）、テーマ（自動/ライト/ダーク + ダイナミックカラー）、バックグラウンド再生、認証キャッシュ削除、バージョン表示
- **ミニプレイヤー**: 画面下部に固定表示
- **バックグラウンド再生**: Media3 `MediaSessionService`

## 技術スタック

| 項目 | 内容 |
|---|---|
| 言語 | Kotlin 2.3.20 |
| UI | Jetpack Compose (Material 3) |
| 再生 | androidx.media3 1.9.4 (exoplayer-hls) |
| HTTP | OkHttp 4.12.0 |
| JSON | kotlinx.serialization |
| 永続化 | DataStore Preferences |
| minSdk / targetSdk | 26 / 36 |

## ビルド

```bash
export JAVA_HOME=/home/yuchi0531/jdk17
export ANDROID_HOME=/home/yuchi0531/android-sdk
./gradlew assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

テスト:
```bash
./gradlew testDebugUnitTest
```

## 実機インストール手順

エミュレータでは radiko の認証（GPS/地域判定）が通らない場合があるため、**必ず実機（Android 12+）で確認すること**。

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

1. アプリ起動 → ライブタブで東京の局（例: TBSラジオ）をタップ → 音が出ることを確認
2. 番組表タブで局を選択 → 番組表が表示されることを確認
3. 放送中番組をタップ → ライブ再生
4. 過去の番組をタップ → タイムフリー再生（シーク・一時停止・再開）
5. 設定タブでエリア変更 → 再認証され、局一覧が切り替わることを確認

## アーキテクチャ

```
com.radikk.app
├── MainActivity.kt          # ボトムナビ 4 タブ
├── RadikkApplication.kt     # DI ワイヤリング
├── data/
│   ├── api/                 # OkHttp クライアント、RadikoApi 定数、fullKey 読み込み
│   ├── auth/                # AuthRepository (auth1→auth2、シングルフライト、トークン永続化)
│   ├── model/               # Station, Program, AuthSession
│   ├── repository/          # StationRepository (full.xml), ProgramRepository (v4 date)
│   └── datastore/           # SettingsRepository (設定・認証情報)
├── player/
│   ├── RadikoPlayer.kt      # Media3 ラッパー、medialist 直接再生、エラー分類
│   ├── StreamUrlResolver.kt # m3u8→medialist URL 抽出
│   └── PlaybackService.kt   # MediaSessionService
├── ui/
│   ├── theme/               # Material3 + ダイナミックカラー
│   ├── screen/              # Live/ProgramGuide/Timefree/Settings
│   └── component/           # StationCard, AreaSelector, MiniPlayer
└── util/                    # RadikoTimeUtil (JST 14桁変換、5時起点)
```

## HLS Source Error 回避策

1. **medialist URL を直接 `MediaItem` に渡す**（マスタープレイリストを介さない）
   - `StreamUrlResolver` が m3u8 を取得し、`#EXT-X-STREAM-INF` 直後の medialist URL を抽出
2. **`DefaultHttpDataSource.Factory.setDefaultRequestProperties` で認証ヘッダーを全適用**
   - `X-Radiko-AuthToken` / `X-Radiko-AreaId` が HLS の全リクエストに付与される
3. **ID3 タグ付き ADTS AAC セグメント**
   - Media3 の `DefaultHlsExtractorFactory` はデフォルトで `AdtsExtractor` を含み、ID3 ヘッダーをスキップして ADTS を検出する（ソースで確認済み）

## 認証フロー

1. **auth1**: `GET /v2/api/auth1`（`X-Radiko-App: aSmartPhone8` 等）
2. **partialkey**: fullKey（assets/fullkey.b64, 167KB base64）をデコード → `[KeyOffset, KeyOffset+KeyLength)` をスライス → 再 base64
3. **auth2**: `GET /v2/api/auth2`（partialkey + GPS 座標）→ `JP13,東京都,tokyo Japan`
4. トークンは約90分で期限切れ。有効期限と areaId を DataStore に永続化し、エリア一致+有効期限内なら再利用

## 注意点

- **fullKey**: `assets/fullkey.b64` に配置（Kotlin 文字列定数の 64KB 制限を超えるため）
- **エリア変更**: トークンはエリアに紐づくため、変更時は必ず再認証
- **時刻**: すべて JST 基準。内部は UTC で保持し表示時に `Asia/Tokyo` で変換
- **キャッシュ**: 放送局一覧 1時間、日別番組表 1時間（メモリ上限 300 件）
