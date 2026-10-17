# 画像の出典

言語選択の国旗は[Twemoji v17.0.3](https://github.com/jdecked/twemoji/tree/v17.0.3/assets/svg)を使用しています。SVGをAndroidのVectorDrawableへ変換しています。© Twitter, Inc. and contributors / CC-BY-4.0。ライセンスと出典はアプリ内のOSS一覧にも同梱しています。

作品画像とサービスロゴの権利は各権利者に帰属します。作品全体の画像はKitsu・AniList・Jikan、保存した各話の画像はニコニコの動画サムネイルを使用します。

## AniComeのロゴ

静止ロゴはアプリ内でVectorDrawable、READMEではSVGを使用します。ランチャーは白背景で、通知には白いモノクロ版を使用します。

起動演出はLottieのロゴアニメーションと文字の表示を組み合わせています。

## 配信サービス

配信サービスのアイコンは各提供元のGoogle Play掲載ページから実行時に取得し、Coilの端末キャッシュで再利用します。APKには同梱しません。未取得・通信失敗時は画像を表示しません。

- [DMM TV](https://play.google.com/store/apps/details?id=com.dmm.premium&hl=ja)
- [Netflix](https://play.google.com/store/apps/details?id=com.netflix.mediaclient&hl=ja)
- [Prime Video](https://play.google.com/store/apps/details?id=com.amazon.avod.thirdpartyclient&hl=ja)
- [U-NEXT](https://play.google.com/store/apps/details?id=jp.unext.mediaplayer&hl=ja)
- [Hulu](https://play.google.com/store/apps/details?id=jp.happyon.android&hl=ja)
- [ABEMA](https://play.google.com/store/apps/details?id=tv.abema&hl=ja)
- [dアニメストア](https://play.google.com/store/apps/details?id=com.nttdocomo.android.danimeapp&hl=ja)
- [TVer](https://play.google.com/store/apps/details?id=jp.hamitv.hamiand1&hl=ja)
- [NHK ONE](https://play.google.com/store/apps/details?id=nhk.app.tep&hl=ja)
- [FOD](https://play.google.com/store/apps/details?id=air.jp.co.fujitv.fodviewer&hl=ja)
- [バンダイチャンネル](https://play.google.com/store/apps/details?id=com.bch.sp.bchmembershipapp&hl=ja)

## ニコニコのアイコン

[ニコニコ公式サイト](https://www.nicovideo.jp/)で指定された144pxアイコン `https://resource.video.nimg.jp/web/images/favicon_uni/144.png?re` を実行時に取得して使用。

## 書体

日本語は[Kosugi Maru](https://fonts.google.com/specimen/Kosugi+Maru)を使用し、600の合成太字を適用。英数字は[Google Sans Flex](https://github.com/googlefonts/googlesans-flex)のROND=100を維持します。同じ文中でも文字の範囲ごとに書体を指定します。

韓国語は[Jua](https://fonts.google.com/specimen/Jua)、中国語は[Chiron GoRound TC](https://fonts.google.com/specimen/Chiron+GoRound+TC)を使用します。
Chironの可変フォントからfontToolsでwght=500の固定インスタンスを生成し、必要な全文字を保持して同梱しています。各書体のライセンスをlicensesおよびアプリ内OSS一覧に同梱しています。

## 試し表示のコメント

ニコニコ連携後、so23335421のOP前後を含む128〜228秒のコメントをニコニコから直接取得し、端末内へ圧縮保存します。最大30,000件を新しい投稿から選び、本文・空白・改行・表示コマンドを保持します。取得はセットアップと並行して進み、失敗時は試し表示から再試行できます。投稿コメントや動画本体はAPKへ同梱しません。

## セットアップの背景

ようこそ画面の画像は、公式サイトの画像URLから実行時に取得します。取得前・通信失敗時は画像を表示しません。APKには画像本体を同梱しません。取得元の利用条件と権利は別途確認が必要です。
