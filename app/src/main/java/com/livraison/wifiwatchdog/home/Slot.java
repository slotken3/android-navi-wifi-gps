package com.livraison.wifiwatchdog.home;

import com.livraison.wifiwatchdog.R;

/**
 * ホーム画面のタイル・下段ボタンと、それぞれに割り当てるアプリの初期値。
 *
 * group=false: 候補のうち最初に入っているアプリを1タップで開く。
 * group=true : タイルを押すと選択パネルを出し、2タップ目で開く(要件U1・U11)。
 *
 * ナビ・CarPlay・ドラレコ・ラジオ・電話の初期値は、2026-10-01 に実機で確かめた名前。
 * 違えば 設定 → ホーム画面のアプリ から選び直せる。
 */
enum Slot {
    NAVI("navi", "ナビ", false, R.drawable.ic_navigation,
            "com.google.android.apps.maps", "jp.co.yahoo.android.apps.navi"),
    VIDEO("video", "動画", true, R.drawable.ic_play,
            "tv.abema", "com.amazon.avod.thirdpartyclient", "com.google.android.youtube"),
    MUSIC("music", "音楽", true, R.drawable.ic_music,
            "com.syu.music", "com.google.android.apps.youtube.music", "com.amazon.mp3"),
    MEETING("meeting", "会議", true, R.drawable.ic_meeting,
            "com.microsoft.teams", "us.zoom.videomeetings", "com.google.android.apps.tachyon"),
    // 実機(Joying)は Car Link 2.0。ZLINK は他のFYT機で使われる名前
    CARPLAY("carplay", "CarPlay", false, R.drawable.ic_car,
            "com.syu.carlink", "com.zjinnova.zlink"),
    // 実機のドラレコ表示アプリ「HD Car DVR」(2026-10-01 確認)
    DASHCAM("dashcam", "ドラレコ", false, R.drawable.ic_videocam,
            "com.williexing.android.apps.xcdvr1"),
    RADIO("radio", "ラジオ", false, R.drawable.ic_radio,
            "com.syu.radio"),
    PHONE("phone", "電話", false, R.drawable.ic_phone,
            "com.syu.bt");

    final String key;
    final String title;
    final boolean group;
    final int icon;
    final String[] defaults;

    Slot(String key, String title, boolean group, int icon, String... defaults) {
        this.key = key;
        this.title = title;
        this.group = group;
        this.icon = icon;
        this.defaults = defaults;
    }

    static Slot fromKey(String key) {
        if (key == null) return null;
        for (Slot s : values()) {
            if (s.key.equals(key)) return s;
        }
        return null;
    }
}
