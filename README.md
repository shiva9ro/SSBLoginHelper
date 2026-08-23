# SSB Login Helper

SSBPro（Soliton SecureBrowser Pro）のログイン操作を補助するAndroidアプリです。
登録したログインIDとパスワードを端末内で暗号化して保存し、ユーザーがボタンを押したときだけユーザー補助サービスによる自動操作を1回実行します。

## 動作

- スマホ版: SSBProへログインし、スマホ版サイトへログイン
- PC版: SSBProへログインし、共通ブックマークからPC版サイトを開いてログイン
- PC版を選んだ場合、スマホ版サイトへのログインは行いません

## 対応環境

- Android 12（API 31）以上
- SSBProのパッケージ名: `jp.co.soliton.securebrowserpro`
- 動作確認済み: Pixel 7 Pro、Lenovo IdeaPad Duet Gen 9（ChromeOS）

## 初期設定

1. アプリを起動してログインIDとパスワードを保存します。
2. Androidのユーザー補助設定で「SSB Login Helper」を有効にします。
3. アプリの「スマホ版を開く」または「PC版を開く」を押します。

## セキュリティ

- 認証情報はAndroid KeystoreのAES-256/GCM鍵で暗号化して保存します。
- 認証情報と自動操作要求は、クラウドバックアップおよび端末間転送から除外します。
- 通常のAndroid端末では、認証情報入力画面のスクリーンショットと最近使ったアプリ画面への表示を防ぎます。
- ChromeOSでは仮想キーボードとの互換性を優先するため、画面キャプチャ禁止設定を使用しません。
- 署名鍵、APK、端末固有の設定ファイルはGitの管理対象外です。

## ビルド

Android Studioでプロジェクトを開き、デバッグ用APKの場合は次を選択します。

`Build` → `Build App Bundle(s) or APK(s)` → `Build APK(s)`

生成先は通常 `app/build/outputs/apk/debug/app-debug.apk` です。

## 注意

このアプリはSSBProおよび対象Webサイトの画面要素に依存します。SSBProやWebサイト側の画面構成、表示文字、View IDが変更された場合は、自動操作の調整が必要になることがあります。
