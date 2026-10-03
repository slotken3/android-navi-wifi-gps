package com.livraison.wifiwatchdog.home;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;

/**
 * 走行中かの判定(要件U5・フェーズ0指示書 3-10)。GPSの速度だけで判断する。
 *
 * - 時速10km以上になったら走行中
 * - 時速3km以下が20秒続いたら停車中
 * - トンネルなどで測位が途切れても、直前の状態を保つ
 *
 * FYTのシステムが走行中・パーキングを知らせる仕組み(com.syu.ipc)を使えるかは、
 * com.syu.ms の解析待ち(指示書 1-7)。使えるなら、そちらに置き換える。
 *
 * 画面が見えている間だけ GPS を使う(BaseActivity の onResume/onPause から start/stop)。
 * 最後に分かった位置は、地図の初期表示に使う。
 */
final class Driving {

    private static final float DRIVE_MPS = 10f / 3.6f;
    private static final float STOP_MPS = 3f / 3.6f;
    private static final long PARK_AFTER_MS = 20_000L;
    private static final long FIX_FRESH_MS = 5_000L;

    private static boolean driving;
    private static long slowSince = -1;
    private static long lastFixAt;
    private static Location lastLocation;
    private static int users;
    private static LocationListener listener;

    private Driving() {
    }

    static synchronized void start(Context c) {
        users++;
        if (listener != null) return;
        if (c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        LocationManager lm = c.getApplicationContext().getSystemService(LocationManager.class);
        if (lm == null) return;
        listener = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                onLocation(location);
            }

            @Override
            public void onStatusChanged(String provider, int status, Bundle extras) {
            }

            @Override
            public void onProviderEnabled(String provider) {
            }

            @Override
            public void onProviderDisabled(String provider) {
            }
        };
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener,
                    Looper.getMainLooper());
        } catch (SecurityException | IllegalArgumentException e) {
            listener = null;
        }
    }

    static synchronized void stop(Context c) {
        users = Math.max(0, users - 1);
        if (users > 0 || listener == null) return;
        LocationManager lm = c.getApplicationContext().getSystemService(LocationManager.class);
        if (lm != null) lm.removeUpdates(listener);
        listener = null;
    }

    private static synchronized void onLocation(Location l) {
        long now = SystemClock.elapsedRealtime();
        lastFixAt = now;
        lastLocation = l;
        float v = l.hasSpeed() ? l.getSpeed() : 0f;
        if (v >= DRIVE_MPS) {
            driving = true;
            slowSince = -1;
        } else if (v <= STOP_MPS) {
            if (slowSince < 0) slowSince = now;
            if (now - slowSince >= PARK_AFTER_MS) driving = false;
        }
    }

    /** GPSの速度から見て走行中か(表示確認の「走行中」は HomePrefs.isDriving で足す) */
    static synchronized boolean isDrivingByGps() {
        return driving;
    }

    /** 直近5秒以内に測位できているか */
    static synchronized boolean hasFix() {
        return lastFixAt > 0 && SystemClock.elapsedRealtime() - lastFixAt < FIX_FRESH_MS;
    }

    /** 最後に分かった位置。まだ無ければ null */
    static synchronized Location lastLocation() {
        return lastLocation;
    }
}
