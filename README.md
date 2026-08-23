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

Android Studioでプロジェクトを開き、デバッグ用APKの場合は次を選択します。

`Build` → `Build App Bundle(s) or APK(s)` → `Build APK(s)`

生成先は通常 `app/build/outputs/apk/debug/app-debug.apk` です。継続して利用・更新する場合は、同じ署名鍵で署名してください。異なる鍵で署名したAPKは既存アプリへ上書きインストールできません。

## Android端末へのインストール

開発者向けオプションとUSBデバッグを有効にした端末をAndroid Studioへ接続し、対象端末を選んで実行します。APKを直接インストールする場合は、Android SDKのADBを使用できます。

```text
adb install path/to/app.apk
```

同じ署名鍵で作成した更新版を上書きする場合は次のようにします。

```text
adb install -r path/to/app.apk
```

## Chromebookへのインストール

1. ChromeOSの設定でLinux開発環境を有効にします。
2. Linuxの設定から「Androidアプリの開発」を開き、ADBデバッグを有効にします。端末の再起動と警告への同意が必要です。
3. LinuxターミナルでADBをインストールします。

```text
sudo apt install adb
```

4. ChromebookのAndroid環境へ接続し、表示されたデバッグ許可ダイアログを承認します。

```text
adb connect arc
```

5. Linux環境からAPKをインストールします。

```text
adb install path/to/app.apk
```

同じ署名鍵の更新版は `adb install -r path/to/app.apk` で上書きできます。

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
