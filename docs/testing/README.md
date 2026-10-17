# 検証ガイド

コマンドは特記がなければリポジトリのルートで実行します。JDK 17とAndroid SDK 36が必要です。

## Kotlin・UI

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:auditTranslations
.\gradlew.bat :app:compileDebugAndroidTestKotlin
```

JVMテストは`app/src/test/java/net/yexe/anicome/`、固定データは`app/src/test/resources/`にあります。機能別フォルダーへ移動してもテストクラス名は同じです。例えばセットアップのみなら`--tests net.yexe.anicome.SetupInteractionTest`で絞れます。

Paparazziの生成結果は`app/build/reports/paparazzi/debug/`です。比較用画像が必要な場合は`:app:recordPaparazziDebug`でローカル生成し、変更前後を`:app:verifyPaparazziDebug`で比較します。比較用画像をGitへ追加せず、必要な画像だけIssueやPRへ添付してください。通常のテスト実行に過去の比較画像は不要です。

実データを使う`LocalCommentAuditTest`は`ANICOME_COMMENT_AUDIT_DIR`を設定した場合だけ実行します。未設定時のスキップは仕様です。個人の書き出しデータはGitに追加しません。

## JavaScriptの描画

Node.jsとMicrosoft Edgeを使います。初回は依存関係をインストールしてください。

```powershell
Push-Location tests/rendering
npm ci
npm test
Pop-Location
```

`node tests/rendering/run.cjs preview-player`のように名前で絞れます。ランナーは作業ディレクトリをルートへ揃え、失敗した時点で終了します。実データの診断は`node tests/rendering/audit-comment-timeline.cjs コメントJSON 1417`です。

## ストレージ・翻訳

```powershell
python tests/storage/test-library-migrations.py
python scripts/checks/audit-translations.py
```

移行検証は保存済みコメントのメタデータ・キャッシュ・履歴・予約・通信量が保持されることを確認します。翻訳検査は機能別ディレクトリも再帰的に走査します。

## 実機

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
```

権限の未許可・拒否後の復帰、ニコニコ連携、縦横画面、文字拡大、各取得元を重ねた再生、保存済みコメントの再利用、保存予約の取消と再試行を確認します。画面操作・ログイン・インストールはユーザーの指示に合わせて行います。結果や必要な画像はIssue・PRへ記載してください。

## APK

```powershell
python scripts/checks/check-apk.py APKのパス
python scripts/checks/check-bundled-preview.py APKのパス
```

別途apksignerで署名を確認します。ZIP・DEX・16KB配置・署名の検査だけでインストール成功とはせず、実機のPackageManagerの結果で確認します。

## リリース

GitHub Actionsの`Release`をmainから手動実行し、バージョンを指定します。通常はLatestとして公開し、必要な場合のみプレリリースを指定します。`version.properties`を更新する署名済みコミットを作成し、APKとSHA256SUMSを公開します。

APKはarm64-v8a・armeabi-v7a・x86・x86_64・universalの5種類です。一般的な現行スマートフォンはarm64-v8a、種類が不明なら全ABIを含むuniversalを使用します。ABI別は`-PtargetAbi=arm64-v8a`などを指定し、universalは指定せずにビルドします。

署名鍵とパスワードはリポジトリのActions Secrets（`ANDROID_KEYSTORE_BASE64`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD`）に設定し、証明書のSHA-256はActions変数`ANDROID_CERT_SHA256`で照合します。署名鍵は変更せず引き継いでください。

F-Droid向けは`-Pfdroid=true`でビルドします。GitHub経由の自己更新とAPKインストール権限を無効にし、4つのABIを含むAPKを作成します。F-Droidの再ビルドには`-PunsignedRelease=true`も指定します。Actionsの`fdroid-universal`成果物を開発者署名付きの照合対象とします。
