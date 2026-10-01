package com.livraison.wifiwatchdog.home;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;

import com.livraison.wifiwatchdog.R;
import com.livraison.wifiwatchdog.WifiMonitorService;

import java.util.Locale;

/**
 * 今の接続経路と、通信できるか(要件N7)。
 *
 * 経路と疎通は Android の判定(NET_CAPABILITY_VALIDATED)を読むだけで、
 * 自分では通信しない。SIMの通信量は段階1で実測する(今はデモ値)。
 */
final class ConnectionStatus {

    enum Route { WIFI, SIM, OTHER, NONE }

    Route route = Route.NONE;
    /** インターネットまで届いているか(Androidの判定) */
    boolean online;
    boolean wifiEnabled;
    /** 接続中のSSID。不明・未接続なら null */
    String ssid;
    boolean simReady;
    String carrier;
    long simUsedMb;
    long simLimitMb;
    /** 表示確認(デモ)の値か */
    boolean simulated;

    private ConnectionStatus() {
    }

    static ConnectionStatus read(Context c) {
        ConnectionStatus s = new ConnectionStatus();
        String demo = HomePrefs.demoRoute(c);
        if (HomePrefs.ROUTE_REAL.equals(demo)) {
            s.readReal(c);
        } else {
            s.applyDemo(demo);
        }
        s.simLimitMb = HomePrefs.simLimitGb(c) * 1024L;
        s.simUsedMb = s.simLimitMb * HomePrefs.demoUsagePercent(c) / 100;
        return s;
    }

    private void readReal(Context c) {
        ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
        Network n = cm != null ? cm.getActiveNetwork() : null;
        NetworkCapabilities nc = n != null ? cm.getNetworkCapabilities(n) : null;
        if (nc == null) {
            route = Route.NONE;
        } else if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            route = Route.WIFI;
        } else if (nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            route = Route.SIM;
        } else {
            route = Route.OTHER;
        }
        online = nc != null && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);

        WifiManager wm = (WifiManager) c.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            wifiEnabled = wm.isWifiEnabled();
            WifiInfo info = wm.getConnectionInfo();
            if (info != null && info.getNetworkId() != -1) {
                String raw = info.getSSID();
                if (raw != null && !"<unknown ssid>".equals(raw)) ssid = raw.replace("\"", "");
            }
        }

        TelephonyManager tm = c.getSystemService(TelephonyManager.class);
        if (tm != null) {
            simReady = tm.getSimState() == TelephonyManager.SIM_STATE_READY;
            String op = tm.getNetworkOperatorName();
            carrier = TextUtils.isEmpty(op) ? null : op;
        }
    }

    private void applyDemo(String demo) {
        simulated = true;
        simReady = true;
        carrier = "SIM(デモ)";
        switch (demo) {
            case HomePrefs.ROUTE_WIFI:
                route = Route.WIFI;
                online = true;
                wifiEnabled = true;
                ssid = "DCT-WR100D(デモ)";
                break;
            case HomePrefs.ROUTE_WIFI_NO_NET:
                // ルーターの停車時の制限などで、つながっているのに通信できない状態
                route = Route.WIFI;
                online = false;
                wifiEnabled = true;
                ssid = "DCT-WR100D(デモ)";
                break;
            case HomePrefs.ROUTE_SIM:
                // ナビ側のWi-FiがOFFになり、SIMで通信を続けている状態
                route = Route.SIM;
                online = true;
                wifiEnabled = false;
                break;
            default:
                route = Route.NONE;
                online = false;
                wifiEnabled = true;
                simReady = false;
                carrier = null;
                break;
        }
    }

    // ---------------------------------------------------------------
    // 表示用
    // ---------------------------------------------------------------
    String routeName() {
        switch (route) {
            case WIFI: return "Wi-Fi";
            case SIM: return "SIM";
            case OTHER: return "その他の回線";
            default: return "オフライン";
        }
    }

    /** ホームのタイル用(1行に収まる長さ) */
    String onlineTextShort() {
        if (route == Route.NONE) return "通信できません";
        return online ? "通信OK" : "通信できない";
    }

    int statusColorRes() {
        if (route == Route.NONE) return R.color.ng;
        return online ? R.color.ok : R.color.warn;
    }

    int iconRes() {
        switch (route) {
            case WIFI: return R.drawable.ic_wifi;
            case SIM: return R.drawable.ic_cellular;
            case OTHER: return R.drawable.ic_wifi;
            default: return R.drawable.ic_offline;
        }
    }

    int usagePercent() {
        if (simLimitMb <= 0) return 0;
        return (int) Math.min(100, simUsedMb * 100 / simLimitMb);
    }

    /** 80%で注意、100%で警告(要件N5) */
    int usageColorRes() {
        int p = usagePercent();
        if (p >= 100) return R.color.ng;
        if (p >= 80) return R.color.warn;
        return R.color.ok;
    }

    String usageText() {
        return String.format(Locale.JAPAN, "%.1f / %d GB",
                simUsedMb / 1024f, simLimitMb / 1024);
    }

    String usageTextShort() {
        return String.format(Locale.JAPAN, "%.1f/%dGB",
                simUsedMb / 1024f, simLimitMb / 1024);
    }

    /** Wi-Fi監視サービスが動いているか */
    static boolean isMonitorRunning(Context c) {
        return WifiMonitorService.isRunning(c);
    }
}
