# リリース

署名付きの変更コミットをmainへpushした後、Releaseワークフローにバージョンを指定して実行します。バージョン更新はGitHub Actionsが署名付きの`vX.X.X`コミットとして作成します。

実行前に`docs/releases/X.X.X.md`へ本文を用意します。GitHubで編集したv0.25.2と同じく、「更新内容」の見出し、簡潔な日本語の箇条書き、universal APKの案内、前版との変更履歴リンクの順に記載します。リリース名は`vX.X.X`です。自動生成のコミット一覧は使用しません。

APKの署名・ZIP・DEX・16KB配置を検証し、各ABI版・universal版・F-Droid用universal版とSHA256SUMSを公開します。端末へのインストール成功はPackageManagerで確認します。
