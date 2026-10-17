# 実装方針

- Kotlin、Jetpack Compose、Material 3、Navigation 3を基準にする。
- 画面の状態と操作はViewModelとStateFlowで管理し、collectAsStateWithLifecycleで購読する。
- 非同期処理はCoroutines。画面から直接通信・永続化処理を呼ばず、ViewModelとRepositoryを経由する。
- 設定はDataStore、保存メタデータはRoom、API通信はRetrofit、DIはKoinのコンストラクタ注入を使う。
- ApplicationはDIとアプリ全体の処理の起動に限定する。サービスの再生状態はアプリスコープのRepositoryで共有する。
- 旧設定・保存済みコメントを移行し、既存データを削除しない。コメント本体のファイル保存は維持してよい。
- UIはMaterial 3 Expressiveの動きと、現状のフォントを維持する。
- APKは署名・ZIP・DEX・16KB配置の検査を行う。インストール成功は端末のPackageManagerの結果で確認し、署名検証だけで成功と断定しない。