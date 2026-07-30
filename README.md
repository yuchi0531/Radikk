# radikk

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

**radiko クライアントアプリ** — 地域制限なしでradikoのライブ放送とタイムフリーを楽しむことができるFlutterアプリです。

## 特徴

- **ライブ放送視聴** — 全国約100の放送局のライブストリームを再生
- **タイムフリー対応** — 過去7日間の番組をオンデマンドで視聴
- **エリア選択** — 47都道府県のエリアから自由に選択して認証・視聴
- **ミニプレーヤー** — 画面下部に常駐するミニプレーヤーで操作可能
- **バックグラウンド再生** — アプリを閉じても再生を継続可能
- **番組表表示** — 週間・日別の番組表を確認

## スクリーンショット

| ライブ放送 | タイムフリー | 設定 |
|---|---|---|
| ライブ画面でエリア選択と放送局一覧 | 過去7日の番組を日付選択で閲覧 | エリア・再生設定の変更 |

## 動作要件

- **Flutter**: 3.12.2+
- **Android**: API 24+ (Android 7.0+)
- **iOS**: 13.0+

## インストール・セットアップ

```bash
# リポジトリのクローン
git clone <repository-url>
cd radikk

# 依存関係の取得
flutter pub get

# アプリの実行
flutter run
```

## プロジェクト構造

```
lib/
├── app.dart                          # アプリのルート（GoRouter設定、テーマ）
├── main.dart                         # エントリーポイント
├── core/                             # コア層（共通機能）
│   ├── constants/                    # 定数定義
│   │   ├── api_endpoints.dart        # radiko APIエンドポイント
│   │   ├── app_keys.dart             # radiko認証キー
│   │   ├── area_map.dart             # エリア情報マップ（47都道府県）
│   │   ├── device_config.dart        # デバイス設定（User-Agent等）
│   │   ├── gps_coords.dart           # 都道府県別GPS座標
│   │   └── station_map.dart          # 局名マップ
│   ├── models/                       # データモデル
│   │   ├── area.dart
│   │   ├── auth_token.dart           # 認証トークンモデル
│   │   ├── program.dart              # 番組情報モデル
│   │   └── station.dart              # 放送局モデル
│   ├── network/                      # ネットワーク層
│   │   ├── dio_client.dart           # Dio HTTPクライアント
│   │   └── radiko_api_client.dart    # radiko APIクライアント
│   └── utils/                        # ユーティリティ
│       ├── device_info_generator.dart # デバイス情報生成
│       ├── gps_generator.dart        # GPS座標生成
│       └── key_generator.dart        # 認証キー生成
├── features/                         # 機能層（Featureモジュール）
│   ├── auth/                         # 認証機能
│   │   ├── auth_provider.dart        # Riverpod Provider
│   │   └── auth_service.dart         # auth1/auth2認証フロー
│   ├── player/                       # プレーヤー機能
│   │   ├── player_provider.dart      # プレーヤー状態管理
│   │   ├── player_service.dart       # just_audioラッパー
│   │   └── stream_resolver.dart      # ストリームURL解決
│   ├── stations/                     # 放送局機能
│   │   └── station_repository.dart   # 放送局データ取得
│   ├── programs/                     # 番組機能
│   │   └── program_repository.dart   # 番組表データ取得
│   ├── timefree/                     # タイムフリー機能
│   │   └── timefree_repository.dart  # タイムフリー番組取得
│   └── settings/                     # 設定機能（未実装）
└── ui/                               # UI層
    ├── screens/                      # 画面
    │   ├── live_screen.dart          # ライブ放送画面
    │   ├── program_guide_screen.dart # 番組表画面
    │   ├── timefree_screen.dart      # タイムフリー画面
    │   └── settings_screen.dart      # 設定画面
    ├── widgets/                      # 再利用可能なウィジェット
    │   ├── area_selector.dart        # エリア選択ドロップダウン
    │   ├── mini_player.dart          # ミニプレーヤー
    │   ├── station_card.dart         # 放送局カード
    │   └── program_tile.dart         # 番組タイル
    └── theme/                        # テーマ
        └── app_theme.dart            # アプリテーマ（radikoカラー）

```

## アーキテクチャ

このプロジェクトは **Feature-based + Clean Architecture** の構造を採用しています。

| 層 | 役割 | 主要技術 |
|---|---|---|
| **core** | アプリ全体で共有する共通機能 | モデル、ネットワーク、定数、ユーティリティ |
| **features** | 機能ごとのビジネスロジック | Riverpod (Provider / StateNotifier / AsyncNotifier) |
| **ui** | プレゼンテーション層 | Flutter Widgets, GoRouter |

### 主要パッケージ

| パッケージ | 用途 |
|---|---|
| `flutter_riverpod` | 状態管理 |
| `go_router` | ルーティング |
| `just_audio` | 音声再生（HLSストリーム） |
| `audio_session` | オーディオセッション管理 |
| `dio` | HTTPクライアント |
| `xml` | XMLレスポンスパース |
| `shared_preferences` | ローカルキャッシュ |
| `intl` | 国際化対応 |
| `equatable` | 値比較 |

## 認証について

radikoのAPIを利用するには認証（auth1 → auth2）が必要です。このアプリは以下のフローで認証を行います。

1. **auth1**: `X-Radiko-AuthToken` とキーオフセットを取得
2. **Partial Key 生成**: 取得したオフセットと長さからradikoの固定キーから部分キーを抽出
3. **auth2**: トークン、Partial Key、エリアのGPS座標を送信して認証完了
4. **キャッシュ**: 取得したトークンは `SharedPreferences` に保存（90分間有効）

### エリア認証の仕組み

- 47都道府県のエリアID（JP1〜JP47）をサポート
- 選択したエリアに対応するGPS座標をランダムオフセット付きで生成
- エリアが一致しない場合（`OUT`判定）は再認証を促す

## 使い方

### ライブ放送視聴

1. アプリを起動するとライブ画面が表示されます
2. エリア選択ドロップダウンから視聴地域を選択します
3. 放送局カードの「聴く」ボタンをタップして再生を開始します
4. 下部のミニプレーヤーから再生制御が可能です

### タイムフリー視聴

1. タイムフリータブから過去7日間の番組を日付選択で閲覧
2. 番組タイルの再生ボタンから指定した時間範囲のストリームを再生
3. シークバーによる時間移動や10秒シークに対応

### 設定

- **デフォルトエリア**: アプリ起動時のデフォルトエリア（東京/JP13）を変更
- **バックグラウンド再生**: アプリを閉じても音声再生を継続するか設定
- **認証キャッシュクリア**: トークンとエリア情報を削除

## 開発

### コード生成

このプロジェクトでは以下のコード生成ツールを使用しています。

```bash
# Riverpodプロバイダーの生成
flutter pub run build_runner build --delete-conflicting-outputs
```

### テスト

```bash
# 全テスト実行
flutter test

# ウィジェットテスト
flutter test test/widget_test.dart

# ユニットテスト
flutter test test/core/
```

### 静的解析

```bash
# コードフォーマット
dart format .

# 静的解析
flutter analyze
```

## ライセンス

MIT License — 詳細は [LICENSE](LICENSE) を参照してください。

## 謝辞

- [radiko](https://radiko.jp/) — ラジカセ開発元
- [just_audio](https://pub.dev/packages/just_audio) — 音声再生ライブラリ
- このアプリは学習目的で作成された非公式クライアントです。