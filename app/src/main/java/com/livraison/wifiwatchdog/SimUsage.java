package com.livraison.wifiwatchdog;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.TrafficStats;
import android.os.SystemClock;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * SIM(モバイル回線)の今月の通信量(要件N5)。
 *
 * 特別な許可が要らないよう、Android の「起動してからのモバイル通信量」(TrafficStats)を
 * 定期的に読み、増えた分を月ごとに足していく。監視サービスが15秒ごとに呼ぶ。
 * 起動してから最初に読むまでの分と、最後に読んでから電源が落ちるまでの分は数えられない
 * (どちらも短い)。
 *
 * 本機は電源が完全に落ちると時計が 2023-08-31 に戻るため、時計が合っていないときは
 * 月の切り替えをせず、今の月に足す。
 */
public final class SimUsage {

    private static final String NAME = "sim_usage";
    private static final String KEY_MONTH = "month";
    private static final String KEY_BYTES = "bytes";
    private static final String KEY_BOOT = "boot";
    private static final String KEY_ELAPSED = "elapsed";
    private static final String KEY_COUNTER = "counter";
    /** これより前の時刻は、時計が合っていないとみなす(2025-01-01) */
    private static final long CLOCK_VALID_FROM = 1735689600000L;

    private SimUsage() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    /** 最新の値を読み込んで、今月の合計(バイト)を返す */
    public static synchronized long update(Context c) {
        SharedPreferences p = sp(c);
        long rx = TrafficStats.getMobileRxBytes();
        long tx = TrafficStats.getMobileTxBytes();
        long bytes = p.getLong(KEY_BYTES, 0);
        if (rx < 0 || tx < 0) return bytes; // この端末では取れない

        long counter = rx + tx;
        long now = System.currentTimeMillis();
        long elapsed = SystemClock.elapsedRealtime();
        int boot = EventLog.bootCount(c);

        String month = p.getString(KEY_MONTH, null);
        if (now >= CLOCK_VALID_FROM) {
            String thisMonth = new SimpleDateFormat("yyyyMM", Locale.JAPAN).format(new Date(now));
            if (!thisMonth.equals(month)) {
                month = thisMonth;
                bytes = 0;
            }
        }

        boolean sameBoot = p.contains(KEY_COUNTER)
                && p.getInt(KEY_BOOT, -2) == boot
                && elapsed >= p.getLong(KEY_ELAPSED, Long.MAX_VALUE);
        long last = p.getLong(KEY_COUNTER, 0);
        // 同じ起動中なら増えた分だけ。起動し直していたら、起動してからの全部を足す
        long delta = sameBoot ? Math.max(0, counter - last) : counter;
        bytes += delta;

        p.edit()
                .putString(KEY_MONTH, month)
                .putLong(KEY_BYTES, bytes)
                .putInt(KEY_BOOT, boot)
                .putLong(KEY_ELAPSED, elapsed)
                .putLong(KEY_COUNTER, counter)
                .apply();
        return bytes;
    }

    /** 今月の合計(バイト)。読み込みはしない */
    public static long monthBytes(Context c) {
        return sp(c).getLong(KEY_BYTES, 0);
    }

    /** 一度でも数えたか */
    public static boolean measured(Context c) {
        return sp(c).contains(KEY_COUNTER);
    }
}
