# WifiWatchdog — 車載機(Joying)Wi-Fi自動復旧 & GPS(みちびき)確認アプリ

個人所有の車載Androidカーナビ(Joying製)向けのAndroidアプリ。**クライアント案件ではない。**

## 概要

1. `WifiMonitorService`(常駐フォアグラウンドサービス)による車内Wi-Fiの自動復旧
2. `SatelliteInfoActivity` による衛星捕捉状況(みちびき/QZSS含む)の確認画面

パッケージ名: `com.livraison.wifiwatchdog`

## ビルド上の注意

- `app/build.gradle` の `targetSdkVersion` は **28固定**。29以上にすると Android 10 の
  Wi-Fi API 制限で `setWifiEnabled` / `enableNetwork` 等が事実上機能しなくなる。
- 本リポジトリに Gradle Wrapper は含めていない。`.github/workflows/build-apk.yml` は
  `gradle/actions/setup-gradle` で Gradle 7.6.4 を都度セットアップしてビルドする
  (AGP 7.4.2 と組み合わせ)。ローカルでビルドする場合は Android Studio で開くか、
  同バージョンの Gradle を別途用意すること。
- push すると Actions が自動でデバッグ署名APKをビルドする。
  Actionsタブ → 対象の実行 → Artifacts から `WifiWatchdog-debug-apk` を取得。
- 現状デバッグ署名のみ。正式配布するならリリース署名を別途用意する。

## 実機・現地調査に関する詳細

型番・root有無・GNSSチップの型番など、実機側の未確定事項と調査手順の詳細は
`HANDOVER.md`(gitignore対象・ローカルのみ)を参照。
