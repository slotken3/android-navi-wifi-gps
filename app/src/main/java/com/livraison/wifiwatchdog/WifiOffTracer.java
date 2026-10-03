package com.livraison.wifiwatchdog;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Wi-Fi を OFF にしたのはどのアプリかを調べる(利用者の依頼。アプリを入れる前からスリープ後に
 * Wi-Fi が OFF になることがあり、ナビ側にも原因があるため)。
 *
 * Android 10 の WifiService は、アプリが Wi-Fi を ON/OFF すると logcat に
 *   setWifiEnabled package=<パッケージ名> uid=<uid> enable=<true/false>
 * を残す。OFF を検知した直後にこれを読む。他のアプリの記録を読むには READ_LOGS が要り、
 * 通常のアプリには与えられないので、ADB で1回だけ許可する:
 *   adb shell pm grant com.tiantian.ttclock android.permission.READ_LOGS
 * この行が見つからなければ、アプリの操作ではない(ナビ側の電源管理など)と見られる。
 */
public final class WifiOffTracer {

    public static final String GRANT_COMMAND =
            "adb shell pm grant com.tiantian.ttclock android.permission.READ_LOGS";
    private static final Pattern LINE = Pattern.compile(
            "setWifiEnabled\\s+package=(\\S+)\\s+uid=(\\d+)\\s+enable=(\\S+)");

    private WifiOffTracer() {
    }

    public static boolean canRead(Context c) {
        return c.checkSelfPermission(Manifest.permission.READ_LOGS)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * 直近の Wi-Fi の ON/OFF の操作を、新しい順に最大3件。読めなければ理由を1行で返す。
     * logcat を呼ぶので、画面のスレッドでは呼ばない。
     */
    public static List<String> recentToggles(Context c) {
        List<String> out = new ArrayList<>();
        if (!canRead(c)) {
            out.add("調べるには ADB で READ_LOGS の許可が要る");
            return out;
        }
        List<String> hits = new ArrayList<>();
        Process p = null;
        try {
            p = new ProcessBuilder("logcat", "-d", "-v", "time", "-t", "2000")
                    .redirectErrorStream(true).start();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    Matcher m = LINE.matcher(line);
                    if (m.find()) {
                        String time = line.length() >= 18 ? line.substring(6, 18) : "";
                        hits.add(time + " " + m.group(1) + "(uid " + m.group(2) + ")が"
                                + ("true".equals(m.group(3)) ? "ON" : "OFF"));
                    }
                }
            }
        } catch (Exception e) {
            out.add("logcat を読めなかった(" + e.getClass().getSimpleName() + ")");
            return out;
        } finally {
            if (p != null) p.destroy();
        }
        if (hits.isEmpty()) {
            out.add("直近に Wi-Fi を ON/OFF したアプリの記録は無い(アプリの操作ではない可能性。ナビ側の電源管理など)");
            return out;
        }
        for (int i = hits.size() - 1; i >= 0 && out.size() < 3; i--) out.add(hits.get(i));
        return out;
    }
}
