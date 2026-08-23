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

## 前提

- SSBProがインストールされ、接続先などの初期設定が完了していること
- PC版を利用する場合、SSBProの共通ブックマーク内に「新Desknets(PC版)」が登録されていること
- 利用者自身がソースコードを確認し、Android StudioでAPKをビルドできること

ビルド済みAPKは配布していません。

## ビルド

継続して利用するAPKは、Android Studioで署名付きリリースAPKとして作成します。

1. `Build` → `Generate Signed App Bundle or APK` を選択します。
2. `APK`、モジュール `app` の順に選択します。
3. 自分のキーストアと署名鍵を指定します。初回は新規作成できます。
4. ビルド種類に `release` を指定してAPKを生成します。

署名鍵とキーストアのパスワードは安全に保管してください。更新版も必ず同じ鍵で署名します。異なる鍵で署名したAPKは既存アプリへ上書きインストールできません。

一時的な動作確認だけであれば、`Build` → `Build App Bundle(s) or APK(s)` → `Build APK(s)` でデバッグAPKを生成できます。通常の生成先は `app/build/outputs/apk/debug/app-debug.apk` です。

## スマートフォンへのインストール

1. 作成した署名付きAPKをスマートフォンへコピーします。
2. スマートフォンのファイルアプリなどからAPKを開きます。
3. 必要に応じて、そのファイルアプリに「不明なアプリのインストール」を許可します。
4. 画面の案内に従ってインストールします。

同じ署名鍵で作成した更新版は、同じ方法でAPKを開くと既存アプリを更新できます。Android Studioから直接動作確認する場合や、開発用途ではUSBデバッグとADBも使用できます。

```text
adb install path/to/app.apk
```

## Chromebookへのインストール

1. ChromeOSの設定でLinux開発環境を有効にします。
2. Linuxの設定から「Androidアプリの開発」を開き、ADBデバッグを有効にします。端末の再起動と警告への同意が必要です。
3. 作成した署名付きAPKをChromebookの「Linuxファイル」へコピーします。
4. LinuxターミナルでADBをインストールします。

```text
sudo apt install adb
```

5. 同じChromebook内のLinux環境からAndroid環境へ接続し、表示されたデバッグ許可ダイアログを承認します。

```text
adb connect arc
```

6. Linux環境からAPKをインストールします。

```text
adb install ~/app-release.apk
```

ファイル名や保存場所が異なる場合は、実際のAPKのパスへ置き換えてください。同じ署名鍵の更新版は `adb install -r ~/app-release.apk` で上書きできます。

参考: [Android Developers - ChromeOSの開発環境を準備する](https://developer.android.com/develop/devices/chromeos/learn/development-environment)

## アプリの初期設定

1. SSB Login Helperを起動します。
2. ログインIDとパスワードを入力して保存します。
3. 「ユーザー補助設定を開く」を押します。
4. Androidのユーザー補助設定で「SSB Login Helper」を有効にし、警告内容を確認して許可します。
5. SSB Login Helperへ戻り、「スマホ版を開く」または「PC版を開く」を押します。

## セキュリティ

- 認証情報はAndroid KeystoreのAES-256/GCM鍵で暗号化して保存します。
- 認証情報と自動操作要求は、クラウドバックアップおよび端末間転送から除外します。
- 通常のAndroid端末では、認証情報入力画面のスクリーンショットと最近使ったアプリ画面への表示を防ぎます。
- ChromeOSでは仮想キーボードとの互換性を優先するため、画面キャプチャ禁止設定を使用しません。
- 署名鍵、APK、端末固有の設定ファイルはGitの管理対象外です。

## 注意

このアプリはSSBProおよび対象Webサイトの画面要素に依存します。SSBProやWebサイト側の画面構成、表示文字、View IDが変更された場合は、自動操作の調整が必要になることがあります。

## トラブルシューティング

### Chromebookでアプリのウィンドウサイズを変更できない

Chromebook上でAndroidアプリのウィンドウサイズが固定される場合は、対象アプリのタイトルバーにあるサイズ設定から「サイズ変更可能」を選択してください。これはChromeOS側のウィンドウ設定であり、SSB Login Helperの初期設定ではありません。
