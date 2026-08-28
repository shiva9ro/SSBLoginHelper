# SSB Login Helper

[English](README.md) | 日本語

SSBPro（Soliton SecureBrowser Pro）のログイン操作を補助するAndroidアプリです。
登録したログインIDとパスワードを端末内で暗号化して保存し、ユーザーがボタンを押したときだけユーザー補助サービスによる自動操作を1回実行します。

## 対象範囲

このアプリは、開発者が所属する特定の組織のSSBPro接続環境に合わせて作っています。SSBProを利用するすべての環境で動く汎用ツールではなく、現在のコードをそのまま利用できるのは、この特定の接続環境に限られます。

SSBProで認証した後の確認画面、共通ブックマークの構成、接続先サイトの入力欄やボタン、ログイン後の画面を判定する条件をコード内に実装しています。別の組織や接続環境へ対応させるには、実際の画面と操作手順を確認し、状態遷移や検出条件をコードから変更する必要があります。

## 動作

- スマホ版: SSBProへログインし、スマホ版サイトへログイン
- PC版: SSBProへログインし、共通ブックマークからPC版サイトを開いてログイン
- メール: SSBProへログインし、共通ブックマークの「事務処理用PCメール」を開いてログイン
- PC版またはメールを選んだ場合、スマホ版サイトへのログインは行いません
- 低速回線ではPC版ログイン画面の更新が止まるまで待ち、入力内容を再確認してからログインします
- 再接続時に同じ内容の証明書警告が複数回表示された場合も、各ウィンドウを1回ずつ処理します

## 対応環境

- Android 12（API 31）以上
- SSBProのパッケージ名: `jp.co.soliton.securebrowserpro`
- 動作確認済み: Pixel 7 Pro、Lenovo IdeaPad Duet Gen 9（ChromeOS）

## 前提

- SSBProがインストールされ、接続先などの初期設定が完了していること
- 利用者自身がソースコードを確認し、Android StudioでAPKをビルドできること

ビルド済みAPKは配布していません。

## ビルド

継続して利用するAPKは、Android Studioで署名付きリリースAPKとして作成します。

1. 更新版を作る場合は、`app/build.gradle.kts` の `versionCode`を前回より大きい整数へ変更し、利用者向けの `versionName` も更新します。
2. `Build` → `Generate Signed App Bundle or APK` を選択します。
3. `APK` を選択して次へ進み、モジュールに `app` を指定します。
4. 自分のキーストア、鍵のエイリアス、各パスワードを指定します。初回は `Create new` からキーストアと署名鍵を作成できます。
5. 次の画面で出力先とビルド種類 `release` を指定し、必要なAPK Signature Versionsを選択します。特別な互換性要件がなければAndroid Studioの既定の選択を使用します。
6. `Create` を押して署名付きAPKを生成します。

署名鍵とキーストアのパスワードは安全に保管してください。更新版も必ず同じ鍵で署名します。異なる鍵で署名したAPKは既存アプリへ上書きインストールできません。

`versionCode` と `versionName` の初期値は次の場所にあります。

```kotlin
// app/build.gradle.kts
defaultConfig {
    versionCode = 2
    versionName = "1.1"
}
```

一時的な動作確認だけであれば、ビルドバリアントに `debug` を選び、`Build` → `Generate Bundle(s) / APK(s)` → `Generate APK(s)` でデバッグAPKを生成できます。デバッグAPKはAndroid SDKのデバッグ鍵で自動的に署名され、通常は `app/build/outputs/apk/debug/app-debug.apk` に生成されます。

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

このLinux環境からADBデバッグを有効にする方法は、Googleの公式案内では2020年以降に発売されたChromebookが対象です。設定項目が表示されない機種や管理対象端末では利用できない場合があります。

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

### Chromebookでの表示サイズ

Androidアプリのウィンドウサイズが固定される場合は、対象アプリのタイトルバーから「サイズ変更可能」を選択してください。これはChromeOS側の表示設定です。

## アプリの初期設定

1. SSB Login Helperを起動します。
2. ログインIDとパスワードを入力して保存します。
3. 「ユーザー補助設定を開く」を押します。
4. Androidのユーザー補助設定で「SSB Login Helper」を有効にし、警告内容を確認して許可します。
5. SSB Login Helperへ戻り、「スマホ版を開く」「PC版を開く」または「メールを開く」を押します。

## セキュリティ

- 認証情報はAndroid KeystoreのAES-256/GCM鍵で暗号化して保存します。
- 認証情報と自動操作要求は、クラウドバックアップおよび端末間転送から除外します。
- 通常のAndroid端末では、認証情報入力画面のスクリーンショットと最近使ったアプリ画面への表示を防ぎます。
- ChromeOSでは仮想キーボードとの互換性を優先するため、画面キャプチャ禁止設定を使用しません。
- 署名鍵、APK、端末固有の設定ファイルはGitの管理対象外です。
- ユーザー補助サービスには画面内容を読み取り、操作する強い権限があります。ソースコードとAndroidの警告を確認したうえで有効にしてください。

## 注意

このアプリは特定の組織の接続環境専用であり、別の組織向けに接続先を設定画面から変更する仕組みはありません。

同じ接続環境でも、SSBProや対象Webサイトの画面構成、表示文字、View IDが変更された場合は、自動操作の調整が必要になることがあります。

これは非公式ツールであり、株式会社ソリトンシステムズによる提供、承認、推奨を受けたものではありません。所属組織の規則と対象サービスの利用条件を確認し、自己責任で使用してください。

## テスト

ローカルの単体テストは、同梱のGradle Wrapperで実行できます。

```powershell
.\gradlew.bat test
```

macOSまたはLinuxでは `./gradlew test` を使用します。GitHub Actionsでもpushとpull requestのたびに同じタスクを実行します。Android端末またはエミュレーターが必要な計装テストは、このワークフローには含まれません。

## 関連記事

- [SSBProのログイン操作をワンタップにするAndroidアプリ「SSBLoginHelper」を作った（Qiita）](https://qiita.com/shiva9ro/items/95baf4d98abb0e5cdb5d)

## ライセンス

[MIT License](LICENSE)
