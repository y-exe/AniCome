# コードの構成

Kotlin・Compose・Material 3・Navigation 3を使います。操作と状態はViewModel・StateFlow、非同期処理はCoroutines、データ操作はRepositoryを経由します。  
設定はDataStore、保存メタデータはRoom、通信はRetrofit、DIはKoinです。

## アプリのコード

`app/src/main/java/net/yexe/anicome/` を責務別に分けています。

| 場所 | 内容 |
| --- | --- |
| `application/` | Application、Activity、DI、AppCoordinator、起動状態 |
| `core/network/` | API・プロキシ・通信量の基盤 |
| `core/storage/` | Room、DataStore、バックアップ、保存制約 |
| `core/ui/` | テーマ・書体・ロゴ・共通の行や入力部品 |
| `core/diagnostics/` | 診断 |
| `feature/catalog/` | 作品・各話情報と検索・照合 |
| `feature/comments/` | コメント取得・合成・表示設定・描画との橋渡し |
| `feature/sources/` | ニコニコ各取得元の検索・解析 |
| `feature/playback/` | 再生状態、オーバーレイ、自動同期・時刻補正 |
| `feature/downloads/` | 事前保存・キャッシュ・保存予約・一覧 |
| `feature/floating/` | フローティング表示と操作 |
| `feature/login/` | ニコニコ認証とプロフィール |
| `feature/settings/` | 設定画面・設定状態・検索 |
| `feature/setup/` | セットアップ・説明図・起動演出 |
| その他の `feature/` | 通知、履歴、試し表示、About、更新など |

コードの名前空間とインストール用アプリIDは、どちらも`net.yexe.anicome`です。設定・コメント・Roomのデータベースはアプリ専用領域に保存します。

## テスト・スクリプト

- `app/src/test/java/net/yexe/anicome/`：本体と同じ機能別配置。`support/`は共通Fixture、`visual/`は画面スナップショット。
- `app/src/test/resources/`：再現用の固定データ。
- 画面の比較画像・生成レポートはローカル生成し、Gitでは管理しません。
- `app/src/androidTest/java/net/yexe/anicome/integration/`：端末上の統合テスト。
- `tests/rendering/`：描画JavaScriptの回帰テストと診断。依存関係はpackage-lock.jsonで固定。
- `tests/storage/`：SQLiteでRoomの移行を検証。
- `tests/packaging/`：APKの署名ブロック検査の回帰テスト。
- `scripts/checks/`：翻訳・APK・同梱データの検査。
- `scripts/assets/`：試し表示コメントの素材取得と圧縮。取得済みデータや認証情報は同梱しません。
- `scripts/device/`：実機の表示性能計測。

## ローカルの作業領域

`artifacts/`はAPK・プレビュー・検証結果、`.tools/`はローカルのSDK・JDK・調査用データです。いずれもGit対象外。  
既に共有した成果物や開発ツールは維持します。`build/`、`.gradle/`、`.kotlin/`、`node_modules/`、PythonのキャッシュもGit対象外です。

ルートにはREADME、LICENSE、AGENTS.mdとビルド設定を置き、詳細な文書は`docs/`へ追加してください。

公開対象はソース・固定テストデータ・必要な開発手順・ライセンスに絞ります。過去の仕様メモや検証ログ、プレビューの比較画像はリポジトリへ追加しません。
