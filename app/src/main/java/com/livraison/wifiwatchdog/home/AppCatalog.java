package com.livraison.wifiwatchdog.home;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.widget.Toast;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 入っているアプリを調べ、名前・アイコンを取り、起動する。 */
final class AppCatalog {

    /** タイルに出す短い名前。入っていないアプリも名前で表示できるようにする */
    private static final Map<String, String> KNOWN = new HashMap<>();

    static {
        KNOWN.put("com.google.android.apps.maps", "Google マップ");
        KNOWN.put("jp.co.yahoo.android.apps.navi", "Yahoo!カーナビ");
        KNOWN.put("tv.abema", "ABEMA");
        KNOWN.put("com.amazon.avod.thirdpartyclient", "Prime Video");
        KNOWN.put("com.google.android.youtube", "YouTube");
        KNOWN.put("com.syu.music", "ミュージック");
        KNOWN.put("com.google.android.apps.youtube.music", "YouTube Music");
        KNOWN.put("com.amazon.mp3", "Amazon Music");
        KNOWN.put("com.spotify.music", "Spotify");
        KNOWN.put("com.microsoft.teams", "Teams");
        KNOWN.put("us.zoom.videomeetings", "Zoom");
        KNOWN.put("com.google.android.apps.tachyon", "Meet");
        KNOWN.put("com.zjinnova.zlink", "ZLINK");
        KNOWN.put("com.syu.radio", "ラジオ");
        KNOWN.put("com.syu.bt", "Bluetooth");
    }

    static final class AppEntry {
        final String pkg;
        final String label;
        final ComponentName component;
        final Drawable icon;

        AppEntry(String pkg, String label, ComponentName component, Drawable icon) {
            this.pkg = pkg;
            this.label = label;
            this.component = component;
            this.icon = icon;
        }
    }

    private AppCatalog() {
    }

    static boolean isInstalled(Context c, String pkg) {
        return c.getPackageManager().getLaunchIntentForPackage(pkg) != null;
    }

    static String label(Context c, String pkg) {
        String known = KNOWN.get(pkg);
        if (known != null) return known;
        PackageManager pm = c.getPackageManager();
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            return pm.getApplicationLabel(ai).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    static Drawable icon(Context c, String pkg) {
        try {
            return c.getPackageManager().getApplicationIcon(pkg);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    /** 候補のうち最初に入っているもの。1つも無ければ null */
    static String firstInstalled(Context c, List<String> pkgs) {
        for (String p : pkgs) {
            if (isInstalled(c, p)) return p;
        }
        return null;
    }

    static List<String> installedOnly(Context c, List<String> pkgs) {
        List<String> out = new ArrayList<>();
        for (String p : pkgs) {
            if (isInstalled(c, p)) out.add(p);
        }
        return out;
    }

    /** 起動できたら true。入っていなければ理由を表示して false */
    static boolean launch(Context c, String pkg) {
        Intent i = c.getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) {
            Toast.makeText(c, "「" + label(c, pkg) + "」はこのナビに入っていません",
                    Toast.LENGTH_SHORT).show();
            return false;
        }
        return start(c, i);
    }

    static boolean launch(Context c, AppEntry e) {
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(e.component);
        return start(c, i);
    }

    private static boolean start(Context c, Intent i) {
        // 全画面で開く(要件U10)は段階1。ここではふつうに起動する
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            c.startActivity(i);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(c, "起動できませんでした", Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    /** アプリ一覧用。時間がかかるので画面のスレッドでは呼ばない */
    static List<AppEntry> launchableApps(Context c) {
        PackageManager pm = c.getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> infos = pm.queryIntentActivities(main, 0);
        List<AppEntry> out = new ArrayList<>();
        for (ResolveInfo ri : infos) {
            String cls = ri.activityInfo.name;
            // ホーム画面そのものは一覧に出さない
            if (HomeActivity.class.getName().equals(cls)) continue;
            out.add(new AppEntry(
                    ri.activityInfo.packageName,
                    ri.loadLabel(pm).toString(),
                    new ComponentName(ri.activityInfo.packageName, cls),
                    ri.loadIcon(pm)));
        }
        final Collator collator = Collator.getInstance(Locale.JAPAN);
        Collections.sort(out, (a, b) -> collator.compare(a.label, b.label));
        return out;
    }
}
