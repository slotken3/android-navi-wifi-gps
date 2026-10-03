# WifiWatchdog — 車載機(Joying)Wi-Fi自動復旧 & GPS(みちびき)確認アプリ

個人所有の車載Androidカーナビ(Joying製)向けのAndroidアプリ。**クライアント案件ではない。**

## 概要

1. `WifiMonitorService`(常駐フォアグラウンドサービス)による車内Wi-Fiの自動復旧
2. `SatelliteInfoActivity` による衛星捕捉状況(みちびき/QZSS含む)の確認画面
3. 自作ホーム画面「ナビホーム」(`home/` 配下)。要件定義書のフェーズ0として作ったUIの試作。
   設計と、動くもの・デモ値のものの区別は `docs/phase0-ui.md`

パッケージ名(applicationId): **`com.tiantian.ttclock`**(2026-10-01 から。要件O2)。
FYTがスリープ時に止めないアプリの一覧(`com.syu.ms` 内の `protected_app.txt`)にある名前を借りている。
**変えるとスリープで止められるようになるので、変えないこと。**
コード上の名前(namespace・Javaのパッケージ)は `com.livraison.wifiwatchdog` のまま。
adb などで画面を指定するときは `com.tiantian.ttclock/com.livraison.wifiwatchdog.home.HomeActivity` の形になる。

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
- **署名鍵は固定している。** GitHub Secrets の `DEBUG_KEYSTORE_BASE64` から復元し、
  `app/build.gradle` の `signingConfigs.debug` で使う。証明書の指紋はワークフローで照合している。
  鍵が変わると実機で上書きインストールできず、アンインストールで設定がすべて消える。
  鍵の控えはリポジトリの外(利用者PCの `~/.android/navi-debug.keystore`)。鍵をリポジトリに入れないこと。
- 同じワークフローの `ui-check` ジョブが、エミュレーター(1024×600・Android 10)で各画面を撮影し、
  開いた直後のクラッシュを検出する。結果は Artifacts の `ui-screenshots`。
  画面を足したら `scripts/ui-screenshots.sh` にも撮影を足すこと。

## UIの注意

- 配色は暗い色だけ(要件U3)。押せるものは1辺64dp以上(U2)。
  配色・配置はフェーズ0指示書の「案A」(`res/values/colors.xml`)。フォントは BIZ UDPゴシック(OFL、全文を assets に同梱)。
- 地図は osmdroid + OpenStreetMap 公式サーバー。**利用ポリシーを守ること**: 独自の User-Agent・
  7日間の使い回し・保存200MBまで・**まとめ取り(オフライン用の一括取得)はしない**(`home/MapSetup.java`)。
  CI では `map_offline` で地図を取りに行かない。
- 全画面(policy_control)は WRITE_SECURE_SETTINGS を ADB で1回許可したときだけ動く。無くても他は動く。
- ホームの候補(HOME)は `activity-alias` で**初期無効**。有効のまま配ると、ホームボタンで
  選択画面が出て走行中に操作を求めることになる。
- 走行中は設定の変更を受け付けない(U5)。段階0では走行判定がデモ値。

## 実機・現地調査に関する詳細

型番・root有無・GNSSチップの型番など、実機側の未確定事項と調査手順の詳細は
`HANDOVER.md`(gitignore対象・ローカルのみ)を参照。
