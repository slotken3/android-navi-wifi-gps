package com.livraison.wifiwatchdog.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * ホーム画面の設定値。Wi-Fi監視の設定(Prefs)とはファイルを分けている。
 *
 * 「表示確認」の値は、段階0で未実装の部分(走行判定・通信量)や、
 * 実車でしか起きない状態(SIMへの切替・通信不可)の見た目を確かめるためのもの。
 */
final class HomePrefs {

    private static final String NAME = "home_prefs";

    static final String ROUTE_REAL = "real";
    static final String ROUTE_WIFI = "wifi";
    static final String ROUTE_WIFI_NO_NET = "wifi_nonet";
    static final String ROUTE_SIM = "sim";
    static final String ROUTE_NONE = "none";

    private static final String KEY_SIM_LIMIT_GB = "sim_limit_gb";
    private static final String KEY_DEMO_ROUTE = "demo_route";
    private static final String KEY_DEMO_DRIVING = "demo_driving";
    private static final String KEY_DEMO_USAGE = "demo_usage_percent";

    static final int DEFAULT_SIM_LIMIT_GB = 20;

    private HomePrefs() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    // ---------------------------------------------------------------
    // タイルに割り当てるアプリ
    // ---------------------------------------------------------------
    static List<String> apps(Context c, Slot slot) {
        String v = sp(c).getString("slot_" + slot.key, null);
        if (v == null) return new ArrayList<>(Arrays.asList(slot.defaults));
        List<String> out = new ArrayList<>();
        for (String p : v.split(",")) {
            if (!p.trim().isEmpty()) out.add(p.trim());
        }
        return out;
    }

    static void setApps(Context c, Slot slot, List<String> pkgs) {
        sp(c).edit().putString("slot_" + slot.key, TextUtils.join(",", pkgs)).apply();
    }

    static void resetAllApps(Context c) {
        SharedPreferences.Editor e = sp(c).edit();
        for (Slot s : Slot.values()) e.remove("slot_" + s.key);
        e.apply();
    }

    // ---------------------------------------------------------------
    // SIMの通信量(要件N5)
    // ---------------------------------------------------------------
    static int simLimitGb(Context c) {
        return sp(c).getInt(KEY_SIM_LIMIT_GB, DEFAULT_SIM_LIMIT_GB);
    }

    static void setSimLimitGb(Context c, int gb) {
        sp(c).edit().putInt(KEY_SIM_LIMIT_GB, Math.max(1, Math.min(200, gb))).apply();
    }

    // ---------------------------------------------------------------
    // 表示確認(デモ)
    // ---------------------------------------------------------------
    static String demoRoute(Context c) {
        return sp(c).getString(KEY_DEMO_ROUTE, ROUTE_REAL);
    }

    static void setDemoRoute(Context c, String route) {
        sp(c).edit().putString(KEY_DEMO_ROUTE, route == null ? ROUTE_REAL : route).apply();
    }

    static boolean demoDriving(Context c) {
        return sp(c).getBoolean(KEY_DEMO_DRIVING, false);
    }

    static void setDemoDriving(Context c, boolean driving) {
        sp(c).edit().putBoolean(KEY_DEMO_DRIVING, driving).apply();
    }

    /** SIMの通信量を、表示確認のために上限の何%で見せるか。-1 なら実測を出す */
    static int demoUsagePercent(Context c) {
        return sp(c).getInt(KEY_DEMO_USAGE, -1);
    }

    /** 負の値を渡すと、表示確認をやめて実測に戻す */
    static void setDemoUsagePercent(Context c, int percent) {
        if (percent < 0) {
            sp(c).edit().remove(KEY_DEMO_USAGE).apply();
        } else {
            sp(c).edit().putInt(KEY_DEMO_USAGE, Math.min(100, percent)).apply();
        }
    }

    /** 実際と違う状態を表示しているか(画面に「デモ表示中」を出す) */
    static boolean isDemoActive(Context c) {
        return !ROUTE_REAL.equals(demoRoute(c)) || demoDriving(c) || demoUsagePercent(c) >= 0;
    }

    static void clearDemo(Context c) {
        sp(c).edit().remove(KEY_DEMO_ROUTE).remove(KEY_DEMO_DRIVING).remove(KEY_DEMO_USAGE).apply();
    }

    /** 走行中か(要件U5)。GPSの速度で判定し、表示確認で「走行中」にしていればそれに従う */
    static boolean isDriving(Context c) {
        return demoDriving(c) || Driving.isDrivingByGps();
    }

    // ---------------------------------------------------------------
    // 地図(要件U13)
    // ---------------------------------------------------------------
    private static final String KEY_LAST_LAT = "last_lat";
    private static final String KEY_LAST_LON = "last_lon";
    private static final String KEY_MAP_OFFLINE = "map_offline";
    private static final String KEY_HOME_ADDRESS = "home_address";

    /** 起動直後に、測位できるまで出す前回の位置。無ければ null */
    static double[] lastPosition(Context c) {
        SharedPreferences p = sp(c);
        if (!p.contains(KEY_LAST_LAT)) return null;
        return new double[]{Double.longBitsToDouble(p.getLong(KEY_LAST_LAT, 0)),
                Double.longBitsToDouble(p.getLong(KEY_LAST_LON, 0))};
    }

    static void setLastPosition(Context c, double lat, double lon) {
        sp(c).edit()
                .putLong(KEY_LAST_LAT, Double.doubleToRawLongBits(lat))
                .putLong(KEY_LAST_LON, Double.doubleToRawLongBits(lon))
                .apply();
    }

    /** 地図を取りに行かない(CIの画面確認用。OSMのサーバーに負担をかけないため) */
    static boolean mapOffline(Context c) {
        return sp(c).getBoolean(KEY_MAP_OFFLINE, false);
    }

    static void setMapOffline(Context c, boolean offline) {
        sp(c).edit().putBoolean(KEY_MAP_OFFLINE, offline).apply();
    }

    /** 「自宅へ」の行き先。空なら Google マップに「自宅」で頼む */
    static String homeAddress(Context c) {
        return sp(c).getString(KEY_HOME_ADDRESS, "");
    }

    static void setHomeAddress(Context c, String address) {
        sp(c).edit().putString(KEY_HOME_ADDRESS, address == null ? "" : address.trim()).apply();
    }
}
