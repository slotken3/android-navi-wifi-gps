package com.livraison.wifiwatchdog;

import android.app.Application;
import android.os.SystemClock;

/**
 * アプリのプロセスが起動したことを記録する。
 * 「止められた → 何かのきっかけで起動し直した」を記録から追えるようにするため。
 */
public class WatchdogApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        EventLog.add(this, EventLog.BOOT, "アプリのプロセスが起動(端末の起動から"
                + EventLog.duration(SystemClock.elapsedRealtime()) + ")");
    }
}
