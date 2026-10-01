package com.livraison.wifiwatchdog;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;

/**
 * 端末の起動時と、アプリを更新したときに監視サービスを起動する。
 * 一部の中華カーナビはBOOT_COMPLETEDの発火タイミングが不安定なため、複数の通知を受ける。
 * 受けた通知と、起動から何秒後に届いたかは EventLog に残す(起動しても監視が始まらない
 * ことがあったため。2026-10-01、再起動後に監視が停止中だった)。
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String what = describe(intent.getAction());
        EventLog.add(context, EventLog.BOOT, what + "を受信(端末の起動から"
                + EventLog.duration(SystemClock.elapsedRealtime()) + ")");

        SharedPreferences prefs = context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE);
        if (!prefs.getBoolean(Prefs.KEY_AUTO_START, true)) {
            EventLog.add(context, EventLog.BOOT, "自動で開始する設定がOFFのため、監視は始めない");
            return;
        }
        WifiMonitorService.start(context, what);
    }

    private static String describe(String action) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) return "起動完了の通知";
        if (Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)) return "起動の通知(ロック中)";
        if ("android.intent.action.QUICKBOOT_POWERON".equals(action)) return "起動の通知(QUICKBOOT)";
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return "アプリ更新の通知";
        return "通知 " + action;
    }
}
