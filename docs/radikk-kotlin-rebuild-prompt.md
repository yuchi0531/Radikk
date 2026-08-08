# Radikk ネイティブ Android (Kotlin) 再実装プロンプト

## タスク概要
Flutter 製 radiko クライアント「Radikk」を、**ネイティブ Android (Kotlin)** で作り直す。
既存の Flutter 実装は「ExoPlayer で HLS の Source Error(0)」が発生し再生不能のため、ネイティブで再生エンジンを直接制御して確実に動かすことを最優先とする。
機能スコープは既存の Flutter 版と同等（ライブ再生・タイムフリー再生・番組表・設定）。

## 前提知識（検証済み radiko API 仕様）

以下は実データ検証済みの仕様。実装はこの仕様に厳密に従うこと。

### 認証フロー (auth1 → auth2)
1. **auth1**: `GET https://api.radiko.jp/v2/api/auth1`
   - 必須ヘッダー:
     - `X-Radiko-App: aSmartPhone8`
     - `X-Radiko-App-Version: 8.4.5`（auth1/auth2 間で**同じ値**を使うこと。リクエストごとに変えると 401）
     - `X-Radiko-Device: 33.Pixel 8`（起動間で固定、SharedPreferences に永続化）
     - `X-Radiko-User: {32文字hex}`（起動間で固定、永続化）
   - レスポンスヘッダー: `X-Radiko-AuthToken`, `X-Radiko-KeyOffset`, `X-Radiko-KeyLength`
2. **auth2**: `GET https://api.radiko.jp/v2/api/auth2`
   - auth1 と同じ App/App-Version/Device/User ヘッダー + 追加:
     - `X-Radiko-AuthToken: {auth1で取得したトークン}`
     - `X-Radiko-Partialkey: {fullKeyのoffset:offset+lengthを切り出してbase64}` — **注意**: fullKey を base64 デコードしたバイト列から `[keyOffset, keyOffset+keyLength)` を切り出し、**それを再度 base64 エンコード**する（base64→デコード→スライス→エンコード）
     - `X-Radiko-Location: 35.689488,139.691706,gps`（エリアにより GPS 座標を変える。例: JP13=東京）
   - レスポンスボディ: `JP13,東京都,tokyo Japan`（`OUT` なら地域外エラー）
   - トークンは約90分で期限切れ。有効期限と areaId を永続化し、期限切れなら再認証
   - **fullKey**: 既存の Flutter 実装 `lib/core/constants/app_keys.dart` の `fullKey`（base64 文字列）を流用する（後述）

### ライブ再生
1. **station stream XML**: `GET https://radiko.jp/v3/station/stream/pc_html5/{stationId}.xml`
   - 認証ヘッダー不要
   - 構造: `<urls><url areafree="0|1" timefree="0|1"><playlist_create_url>...`
   - ライブ用: **`areafree="1"`** の `playlist_create_url` を選択（例: `https://si-c-radiko.smartstream.ne.jp/so/playlist.m3u8`）
2. **ライブ m3u8**: `{playlist_create_url}?station_id={id}&l=300&type=b&lsid={lsid}`
   - 必須ヘッダー: `X-Radiko-AuthToken`（欠落で 401 missing token）。`X-Radiko-AreaId` は任意
   - `lsid` は32文字のランダム16進数
   - レスポンス: `#EXT-X-STREAM-INF:...CODECS="mp4a.40.5"` を含む**マスタープレイリスト**で、次の行に medialist URL（`https://si-c-radiko.smartstream.ne.jp/medialist?session=...`）がある
3. **medialist**: マスタープレイリストの `#EXT-X-STREAM-INF` 直後の URL
   - 認証ヘッダーあり/なし両方で取得可能（200）
   - 内容: `#EXTINF:5.035` と **`.aac` セグメント**（`https://si-c-radiko.smartstream.ne.jp/segments/o/B/{stationId}/{date}/...aac`）
   - セグメントのバイナリ構造: **先頭63バイトが ID3v2.4 タグ**（`ID3\x04...` + PRIV フレーム）、**その後が ADTS AAC**（サンプリング周波数 IDX 14 = 22050Hz、HE-AAC `mp4a.40.5`）
   - Content-Type: `application/octet-stream`（`application/x-mpegURL` ではない）

### タイムフリー再生
1. station stream XML で **`areafree="0"` かつ `timefree="1"`** の `playlist_create_url` を選択（例: `https://tf-c-rpaa-radiko.smartstream.ne.jp/tf/playlist.m3u8`）
2. **TF m3u8**: `{playlist_create_url}?station_id={id}&ft={from}&to={to}&start_at={from}&end_at={to}&type=b&l=300&seek={from}&lsid={lsid}`
   - `ft`/`to` は **JST の 14桁** `YYYYMMDDHHMMSS` 形式
   - 全パラメータ必須（`ft`/`to` だけや `seek` 欠落は 400）
   - 認証ヘッダー `X-Radiko-AuthToken` 必須
   - ライブ同様、`#EXT-X-STREAM-INF` 直後の medialist URL を抽出して再生

### 番組表
- 日別番組表: `GET https://api.radiko.jp/program/v4/date/{YYYYMMDD}/station/{stationId}.json`
  - `YYYYMMDD` は JST 基準。`X-Radiko-AuthToken` ヘッダー必須
  - レスポンス: `stations[].station_id / .station_name / .programs.program[]`
  - `program[]`: `ft`/`to`（JST 14桁）、`title`, `description`, `performer`, `episode_id`, `img`
  - **時間軸は JST 5:00 起点**（5時起点で1日を表現する。深夜 0:00-4:59 は前日分）
- 放送局一覧: `GET https://radiko.jp/v3/station/region/full.xml`
  - `<station>` の子要素: `id`, `name`, `area_id`（複数）など
  - 1時間キャッシュ

## 最優先事項: HLS 再生の Source Error 回避

**Flutter 版の失敗原因**: ExoPlayer が「マスタープレイリスト(#EXT-X-STREAM-INF) → medialist」の2層 HLS を処理する際、HE-AAC (`audio/mp4a-latm`) の `SampleQueueMappingException`（Unable to bind a sample queue to TrackGroup with MIME type audio/mp4a-latm）で Source Error(0) になる。

**Kotlin/Media3 での対策（両方実施すること）**:

1. **medialist URL を直接 `MediaItem` に渡す**（マスタープレイリストを介さない）
   - m3u8 をアプリ側で取得し、`#EXT-X-STREAM-INF` 直後の medialist URL を抽出して、その URL を ExoPlayer に渡す
   - これによりマスタープレイリストのバリアント選択処理をスキップ
   - medialist URL はセッション付きなので、取得直後に再生を開始すること（長時間放置で失効の可能性）

2. **`DefaultHttpDataSource.Factory` に認証ヘッダーを設定**
   ```kotlin
   val dataSourceFactory = DefaultHttpDataSource.Factory()
       .setUserAgent("Mozilla/5.0 (Linux; Android 10; Pixel 4 XL) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/80.0.3987.87 Mobile Safari/537.36")
       .setDefaultRequestProperties(mapOf(
           "X-Radiko-AuthToken" to authToken,
           "X-Radiko-AreaId" to areaId,
       ))
       .setAllowCrossProtocolRedirects(true)
   ```
   - Media3 の `setDefaultRequestProperties` は HLS の全リクエスト（マスター・メディア・セグメント）に適用される
   - User-Agent は radiko 公式 Android アプリと同等のものを設定

3. **ID3 タグ付き ADTS AAC セグメントの処理**
   - radiko の `.aac` セグメントは「ID3v2 タグ + ADTS」で、Content-Type が `application/octet-stream`
   - medialist 直接指定では CODECS 属性が無いため mime 判定は行われず、`DefaultHlsExtractorFactory` の `AdtsExtractor.sniff()` が ID3v2 ヘッダー（10バイト + synchsafe サイズ）を読み飛ばして先頭 8KB 以内の連続 ADTS フレームで検出する。セグメント URL が `.aac` 終端なので `FileTypes.inferFileTypeFromUri` により優先的に試される
   - **もし Source Error が再発する場合**のフォールバックとして、以下のいずれか：
     a. **セグメントをアプリ側で取得して ID3 タグを除去した ADTS を MediaItem に流す**（`DataSource` をカスタム実装して ID3 をスキップするか、`TsExtractor` ではなく `AdtsExtractor` を明示指定）
     b. **`DefaultHlsExtractorFactory` に `AdtsExtractor` を追加登録**して、`application/octet-stream` セグメントを ADTS として確実に抽出させる
     c. 各セグメントを個別に `AudioItem` として順次再生（ID3 除去済みの生 ADTS を Media3 に流す）
   - まずは「medialist 直接 + setDefaultRequestProperties + AdtsExtractor のスニッフィングによる ADTS 抽出」で試し、ダメなら (b) の extractor 明示指定にフォールバック

4. **デバッグログの充実**:
   - `Player.Listener` の `onPlayerError` で `PlaybackException.errorCode` と `cause` を必ずログ出力
   - ExoPlayer のデバッグ用に `ExoPlayer.Builder` にログレベル設定
   - m3u8/medialist 取得の HTTP ステータスと body 先頭をログ出力

## アーキテクチャ

- **言語**: Kotlin。最小 SDK 26 以上（HE-AAC デコードのため）。targetSdk は最新安定
- **UI**: Jetpack Compose（Material 3）
- **依存**:
  - `androidx.media3:media3-exoplayer` / `media3-exoplayer-hls` / `media3-ui` / `media3-datasource-okhttp`（最新安定版。OkHttp でヘッダー制御を確実にするため）
  - `com.squareup.okhttp3:okhttp`（API クライアント用 + media3-datasource-okhttp）
  - `kotlinx.serialization` または `org.json`（JSON パース）
  - `androidx.datastore-preferences` または `SharedPreferences`（設定・トークン永続化）
- **パッケージ構成**:
  ```
  com.radikk.app
  ├── MainActivity.kt
  ├── data/
  │   ├── api/          (OkHttp クライアント、RadikoApiClient)
  │   ├── auth/         (AuthRepository: auth1/auth2、トークン永続化)
  │   ├── model/        (Station, Program, AuthToken データクラス)
  │   └── repository/   (StationRepository, ProgramRepository)
  ├── player/
  │   ├── RadikoPlayer.kt  (Media3 ラッパー、medialist 直接再生、エラーハンドリング)
  │   └── StreamUrlResolver.kt  (m3u8→medialist URL 抽出)
  ├── ui/
  │   ├── theme/        (Material3 テーマ、Monet ダイナミックカラー)
  │   ├── screen/       (Home, TimefreeScreen, ProgramGuideScreen, SettingsScreen)
  │   └── component/
  └── util/             (RadikoTimeUtil: JST 14桁変換、jstOffset 等)
  ```

## 必須実装機能（Flutter版と同等）

1. **ライブ再生**: 放送局一覧 → 局をタップ → 認証 → medialist 取得 → 再生
2. **タイムフリー再生**: 過去7日分の番組リスト → タップ → medialist 取得 → 再生（シーク可能）
3. **番組表**: 局一覧 × 時間（JST 5:00起点24時間）のグリッド。日付チップ（今日〜7日分）。放送中ハイライト・現在時刻ライン。タップでライブ/TF再生
4. **設定**: エリア選択（47都道府県、永続化）、テーマ（ライト/ダーク/自動 + ダイナミックカラー）、バックグラウンド再生、認証キャッシュ削除、バージョン表示
5. **エリア選択**: 上部ドロップダウン。変更時に再認証（トークンはエリアに紐づく）
6. **ミニプレイヤー**: 画面下部に固定表示、再生/停止、タップでフルプレイヤー
7. **バックグラウンド再生**: Media3 の `MediaSessionService` + フォアグラウンドサービス。設定でON/OFF。OFF時は `mixWithOthers` を外してオーディオフォーカスを要求

## 重要事項

- **認証の並行実行防止**: 認証はシングルフライト化（同時呼び出しは1つに集約）
- **エリア変更と認証**: エリア変更時は必ず再認証。キャッシュトークンは `areaId` が一致する場合のみ再利用
- **JST 時刻**: radiko の時刻はすべて JST 基準。UTC 保持 → 表示時 JST 変換でなく、内部は UTC で持ち表示時 `TimeZone.getTimeZone("Asia/Tokyo")` で変換
- **キャッシュ**: 放送局一覧は1時間、日別番組表は1時間キャッシュ（メモリ上限300件でクリア）
- **エラーメッセージ**: 日本語。認証失敗・地域外・ネットワークエラーを区分
- **実機確認**: 開発中は実機（Android 12+）でライブ/TF の再生を必ず確認すること。エミュレータでは radiko の認証が通らない場合がある（GPS/地域判定）

## 完了条件
1. ライブ再生が実機で正常に動作する（音が出る）
2. タイムフリー再生が実機で正常に動作する（シーク・一時停止・再開）
3. 番組表が表示され、放送中番組のタップで再生できる
4. 設定（エリア・テーマ）が機能し、永続化される
5. `./gradlew assembleDebug` が成功し、APK が生成される

## 既存 Flutter 実装からの流用
- 認証キー (fullKey): `lib/core/constants/app_keys.dart` から流用
- 放送局・番組データの構造は上記 API 仕様どおり
- 既存実装の UI レイアウト（番組表グリッド、ミニプレイヤー等）は参考にしてよいが、Kotlin/Compose で再実装すること
