<div align="center">

<h1><sub><img src="docs/assets/logo.svg" width="36" height="36" alt=""></sub> AniCome（Beta）</h1>

[![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=flat-square&logo=android&logoColor=white)](https://www.android.com/)
[![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?style=flat-square&logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![JavaScript](https://img.shields.io/badge/JavaScript-F7DF1E?style=flat-square&logo=javascript&logoColor=black)](https://developer.mozilla.org/ja/docs/Web/JavaScript)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack_Compose-4285F4?style=flat-square&logo=jetpackcompose&logoColor=white)](https://developer.android.com/compose)
[![License GPL v3](https://img.shields.io/badge/License-GPL--3.0-green?style=flat-square)](LICENSE)

Androidのアニメ再生にコメントが欲しい！<br>
Android純正の動画再生アプリに合わせてコメントを重ねて表示するAndroidアプリです。

</div>

## なんのためにつくった...?

Androidの公式アプリでコメントを流したかっただけの私用プロジェクト。
[NCOverlay](https://github.com/Midra429/NCOverlay)の非公式のAndroid向け実装のようなものです。

## 特徴など...

- **自動取得:** 作品名・話数・再生位置を認識。公式・dアニメ・実況・ニコ生アーカイブなどのコメントを取得。
- **事前ダウンロード:** 作品・話数ごとに複数の取得元をまとめて保存。全話の予約・並列保存にも対応。
- **表示の調整:** 文字サイズ・不透明度・速度・表示位置を視聴アプリごとに設定。NGワードにも対応。
- **手動操作:** 自動で見つからない作品は手動取得、動画とのずれは時刻補正で調整。

### 対応・対応予定視聴アプリ

Netflix / Prime Video / U-NEXT / Hulu / ABEMA / DMM TV / dアニメストア / TVer / NHK ONE / FOD / バンダイチャンネル

作品名・再生位置を取得できないアプリでは、手動取得・手動再生を使ってください。

## 使い方

1. APKをインストールし、初回セットアップで権限の許可とニコニコへのログイン。
2. ホームで「視聴アプリ」を選び、右側の開くマークから動画を再生。
3. 「コメント表示」をオンにすると、認識した作品のコメントを表示。

先に保存したいときは、ホーム右下の「事前ダウンロード」→「＋ 事前ダウンロード」から作品を選択。
再生時の自動キャッシュは別に管理し、初期設定では48時間で削除します。

## 開発

Android 8.0以降 / JDK 17 / Android SDK 36。Android Studioで開くか、Gradle Wrapperでビルドできます。

```powershell
.\gradlew.bat :app:assembleDebug
```

出力先は`app/build/outputs/apk/debug/app-debug.apk`。
構成や検証手順は[ドキュメント](docs/README.md)、開発への参加は[CONTRIBUTING](CONTRIBUTING.md)を参照してください。
不具合報告・機能提案は[Issues](https://github.com/y-exe/AniCome/issues)へ。

## 参考・ライセンス

[NCOverlay](https://github.com/Midra429/NCOverlay)・[nco-utils](https://github.com/Midra429/nco-utils)の検索・取得処理、[AniSync](https://github.com/Marco-9456/AniSync)のUIを参考にしています。コメント描画は[niconicomments](https://github.com/xpadev-net/niconicomments)を使用。

本体は[GPL-3.0](LICENSE)。同梱ライブラリ・素材の表記はアプリ内のOSS一覧と[素材の出典](docs/reference/assets.md)に記載しています。
NCOverlay・ニコニコ・各動画配信サービスの公式アプリではありません。動画本体の再生・保存は行いません。

---

© 2026 yexe