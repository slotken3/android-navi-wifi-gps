package com.livraison.wifiwatchdog.home;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.provider.Settings;

/**
 * 他のアプリを、ステータスバーと下のメニューを出さない全画面で開く(要件U10・フェーズ0指示書 3-5)。
 *
 * Android 10 の「policy_control」(Android 11 で廃止)に immersive.full=* を書く。全アプリに効き、
 * 画面の端をスワイプするとバーが一時的に出る。書き込みには WRITE_SECURE_SETTINGS が要り、
 * 通常のアプリには与えられないので、ADB で1回だけ許可する:
 *   adb shell pm grant com.tiantian.ttclock android.permission.WRITE_SECURE_SETTINGS
 * FYT の独自のステータスバーに効くかは未確認(実機で確かめる)。元に戻すときは null を書く。
 */
final class ImmersiveMode {

    static final String GRANT_COMMAND =
            "adb shell pm grant com.tiantian.ttclock android.permission.WRITE_SECURE_SETTINGS";
    private static final String KEY = "policy_control";

    private ImmersiveMode() {
    }

    static boolean canWrite(Context c) {
        return c.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
    }

    static boolean isOn(Context c) {
        String v = Settings.Global.getString(c.getContentResolver(), KEY);
        return v != null && v.contains("immersive.full");
    }

    /** 書き込めたら true */
    static boolean set(Context c, boolean on) {
        if (!canWrite(c)) return false;
        try {
            return Settings.Global.putString(c.getContentResolver(), KEY, on ? "immersive.full=*" : null);
        } catch (SecurityException e) {
            return false;
        }
    }
}
