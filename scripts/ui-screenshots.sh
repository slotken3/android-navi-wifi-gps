#!/usr/bin/env bash
# エミュレーター(1024×600・Android 10)で各画面を開き、スクリーンショットを撮る。
# あわせて、開いた直後に落ちていないかを確かめる。
# (ビルドが通っても起動した瞬間に落ちる不具合は、CIでは見つからなかったため)
#
# 使い方: scripts/ui-screenshots.sh <APK> <出力先フォルダ>
# 前提  : エミュレーターを起動済み(adb から見える状態)。system image は root 可能な "default"
set -euo pipefail

APK=$1
OUT=$2
PKG=com.livraison.wifiwatchdog
FAILED=0
mkdir -p "$OUT"

wait_boot() {
  adb wait-for-device
  timeout 420 bash -c \
    'until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d "\r")" = "1" ]; do sleep 3; done'
}

echo "== 起動を待っています"
wait_boot

# 実機に合わせて日本語・日本時間にする(漢字の字形と日付の表示のため)
echo "== 日本語・日本時間に切り替えて再起動"
adb root >/dev/null
sleep 3
adb wait-for-device
adb shell setprop persist.sys.locale ja-JP
adb shell setprop persist.sys.timezone Asia/Tokyo
adb reboot
sleep 20
wait_boot
adb root >/dev/null
sleep 3
adb wait-for-device

adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
adb shell svc power stayon true
adb shell input keyevent 82
adb shell wm dismiss-keyguard || true
adb shell wm size
adb shell wm density

echo "== インストール"
adb install -r -g "$APK"
adb logcat -c

# shot <名前> <画面> [am start の追加引数...]
# 表示確認(デモ)の値は毎回すべて指定し、前の撮影の状態を持ち越さない。
shot() {
  local name=$1 activity=$2
  shift 2
  adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1 || true
  adb shell am start -W -S -n "$PKG/$activity" "$@" >/dev/null
  sleep 3
  adb exec-out screencap -p > "$OUT/$name.png"
  if adb shell pidof "$PKG" >/dev/null; then
    echo "撮影: $name"
  else
    echo "::error::$name: 開いた直後にアプリが終了しました"
    FAILED=1
  fi
}

REAL=(--es demo_route real --ez demo_driving false --ei demo_usage 62)

echo "== ホーム"
shot 01_home                  .home.HomeActivity "${REAL[@]}"
shot 02_home_sim_driving      .home.HomeActivity --es demo_route sim --ez demo_driving true --ei demo_usage 85
shot 03_home_wifi_no_internet .home.HomeActivity --es demo_route wifi_nonet --ez demo_driving false --ei demo_usage 62
shot 04_home_offline          .home.HomeActivity --es demo_route none --ez demo_driving false --ei demo_usage 100
shot 05_picker_video          .home.HomeActivity "${REAL[@]}" --es open_panel video
shot 06_picker_meeting_driving .home.HomeActivity --es demo_route wifi --ez demo_driving true --ei demo_usage 62 --es open_panel meeting

echo "== 接続状態"
shot 07_connection_real       .home.ConnectionActivity "${REAL[@]}"
shot 08_connection_sim        .home.ConnectionActivity --es demo_route sim --ez demo_driving false --ei demo_usage 85

echo "== 設定"
for c in apps network devices home survey demo about; do
  shot "10_settings_$c" .home.SettingsActivity "${REAL[@]}" --es category "$c"
done
shot 18_settings_driving_locked .home.SettingsActivity --es demo_route real --ez demo_driving true --ei demo_usage 62 --es category network

echo "== その他"
shot 20_app_list              .home.AppListActivity "${REAL[@]}"
shot 21_legacy_main           .MainActivity

# 実機の画面密度は未確認。240dpi(683×400dp相当)でも崩れないかを見る
echo "== 240dpi"
adb shell wm density 240
sleep 3
shot 30_home_240dpi           .home.HomeActivity "${REAL[@]}"
shot 34_home_240dpi_sim_driving .home.HomeActivity --es demo_route sim --ez demo_driving true --ei demo_usage 85
shot 31_picker_meeting_240dpi .home.HomeActivity "${REAL[@]}" --es open_panel meeting
shot 32_connection_240dpi     .home.ConnectionActivity "${REAL[@]}"
shot 33_settings_apps_240dpi  .home.SettingsActivity "${REAL[@]}" --es category apps
adb shell wm density reset

# 実機(Joying)はステータスバーが高く、タイルがエミュレーターより低い。
# 上端を削って高さを再現し、入っているアプリを割り当てて補足を2行にした状態で撮る。
# (2026-10-01、実機でタイルの補足の2行目が切れた。エミュレーターでは補足が1行で気づけなかった)
echo "== 実機に近い高さ"
SLOTS=(--es slot_navi com.android.settings
       --es slot_video com.android.gallery3d,com.android.deskclock,com.android.calendar
       --es slot_music com.android.documentsui,com.android.email,com.android.messaging
       --es slot_meeting com.android.contacts,com.android.dialer
       --es slot_carplay com.android.messaging
       --es slot_dashcam com.android.deskclock)
adb shell wm overscan 0,42,0,0 || echo "::warning::wm overscan が使えません"
sleep 3
shot 40_home_tall_bar         .home.HomeActivity "${REAL[@]}" "${SLOTS[@]}"
shot 41_home_tall_bar_sim     .home.HomeActivity --es demo_route sim --ez demo_driving false --ei demo_usage 85 "${SLOTS[@]}"
shot 42_picker_video_tall_bar .home.HomeActivity "${REAL[@]}" "${SLOTS[@]}" --es open_panel video
adb shell wm density 240
sleep 3
shot 43_home_tall_bar_240dpi  .home.HomeActivity "${REAL[@]}" "${SLOTS[@]}"
adb shell wm density reset
shot 44_settings_survey_tall_bar .home.SettingsActivity "${REAL[@]}" --es category survey
adb shell wm overscan reset || true

echo "== クラッシュの確認"
adb logcat -d -b crash > "$OUT/crash.txt" || true
adb logcat -d > "$OUT/logcat.txt" || true
if grep -q "FATAL EXCEPTION" "$OUT/crash.txt"; then
  echo "::error::クラッシュを検出しました"
  cat "$OUT/crash.txt"
  FAILED=1
fi

exit $FAILED
