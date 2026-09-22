package com.livraison.wifiwatchdog;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

/**
 * 端末起動時、およびWi-Fi/接続状態が変化した際に監視サービスが
 * 動いていなければ起動し直す。一部の中華カーナビはBOOT_COMPLETEDの
 * 発火タイミングが不安定なため、複数のイベントをトリガーにしている。
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "WifiWatchdog";

    @Override
    public void onReceive(Context context, Intent intent) {
        SharedPreferences prefs = context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE);
        boolean autoStart = prefs.getBoolean(Prefs.KEY_AUTO_START, true);
        if (!autoStart) return;

        Log.i(TAG, "起動イベント受信 (" + intent.getAction() + ")。監視サービスを開始します。");
        Intent serviceIntent = new Intent(context, WifiMonitorService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent);
        } else {
            context.startService(serviceIntent);
        }
    }
}
