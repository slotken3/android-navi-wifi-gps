package com.livraison.wifiwatchdog;

import android.content.Context;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 起動・スリープ・切断・復旧の記録(要件N6・O7)。操作なしで、端末の中に自動で保存する。
 * 1ファイルが256KBを超えたら1世代だけ残し、それより古いものは消す(合計で最大約512KB)。
 *
 * 本機は電源が完全に落ちると、時計が 2023-08-31 に戻る(2026-10-01 確認)。
 * そのため起動からの経過時間と起動回数も一緒に残し、表示のときに時刻を補正する。
 */
public final class EventLog {

    public static final String BOOT = "boot";
    public static final String SLEEP = "sleep";
    public static final String MONITOR = "monitor";
    public static final String CUT = "cut";
    public static final String RECOVER = "recover";

    private static final String TAG = "WifiWatchdog";
    private static final String FILE = "events.tsv";
    private static final String OLD_FILE = "events.1.tsv";
    private static final long MAX_BYTES = 256 * 1024;
    /** これより前の時刻は、時計が合っていないとみなす(2025-01-01) */
    private static final long CLOCK_VALID_FROM = 1735689600000L;

    public static final class Entry {
        public final long wallTime;
        /** 起動からの経過時間(スリープ中も進む) */
        public final long elapsed;
        public final int bootCount;
        public final String type;
        public final String text;

        Entry(long wallTime, long elapsed, int bootCount, String type, String text) {
            this.wallTime = wallTime;
            this.elapsed = elapsed;
            this.bootCount = bootCount;
            this.type = type;
            this.text = text;
        }

        public boolean clockValid() {
            return wallTime >= CLOCK_VALID_FROM;
        }
    }

    private EventLog() {
    }

    public static synchronized void add(Context c, String type, String text) {
        Log.i(TAG, type + ": " + text);
        File dir = c.getFilesDir();
        File f = new File(dir, FILE);
        if (f.length() > MAX_BYTES) {
            File old = new File(dir, OLD_FILE);
            //noinspection ResultOfMethodCallIgnored
            old.delete();
            //noinspection ResultOfMethodCallIgnored
            f.renameTo(old);
        }
        String line = System.currentTimeMillis() + "\t" + SystemClock.elapsedRealtime() + "\t"
                + bootCount(c) + "\t" + type + "\t"
                + text.replace('\t', ' ').replace('\n', ' ') + "\n";
        try (FileOutputStream out = new FileOutputStream(f, true)) {
            out.write(line.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.w(TAG, "記録を書けませんでした", e);
        }
    }

    /** 古い順に返す */
    public static synchronized List<Entry> readAll(Context c) {
        List<Entry> out = new ArrayList<>();
        readFile(new File(c.getFilesDir(), OLD_FILE), out);
        readFile(new File(c.getFilesDir(), FILE), out);
        return out;
    }

    /** 記録が増えたかを手軽に見るための値 */
    public static long sizeBytes(Context c) {
        return new File(c.getFilesDir(), FILE).length()
                + new File(c.getFilesDir(), OLD_FILE).length();
    }

    private static void readFile(File f, List<Entry> out) {
        if (!f.exists()) return;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                String[] p = line.split("\t", 5);
                if (p.length < 5) continue;
                try {
                    out.add(new Entry(Long.parseLong(p[0]), Long.parseLong(p[1]),
                            Integer.parseInt(p[2]), p[3], p[4]));
                } catch (NumberFormatException ignored) {
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "記録を読めませんでした", e);
        }
    }

    /**
     * 表示する時刻。時計が合う前の記録は、同じ起動中で時計が合った後の記録から逆算する。
     * 逆算できなければ -1。
     */
    public static long displayTime(List<Entry> all, int index) {
        Entry e = all.get(index);
        if (e.clockValid()) return e.wallTime;
        for (int j = index + 1; j < all.size(); j++) {
            Entry later = all.get(j);
            if (later.bootCount != e.bootCount || later.elapsed < e.elapsed) break;
            if (later.clockValid()) return later.wallTime - (later.elapsed - e.elapsed);
        }
        return -1;
    }

    public static int bootCount(Context c) {
        return Settings.Global.getInt(c.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
    }

    /** 「45秒」「12分」「3時間5分」の形にする */
    public static String duration(long ms) {
        long sec = Math.max(0, ms / 1000);
        if (sec < 60) return sec + "秒";
        long min = sec / 60;
        if (min < 60) return min + "分";
        return (min / 60) + "時間" + (min % 60) + "分";
    }
}
