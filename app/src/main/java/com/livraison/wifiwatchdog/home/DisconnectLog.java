package com.livraison.wifiwatchdog.home;

import java.util.ArrayList;
import java.util.List;

/**
 * 切断の記録(要件N6・RT4)。
 *
 * 段階1で、監視サービスが端末に書き出すログ(容量の上限つき・O7)に置き換える。
 * 段階0では画面の確認用にサンプルを返す。
 */
final class DisconnectLog {

    enum Cause {
        ROUTER_STOPPED("ルーター側の停止"),
        NAVI_WIFI_OFF("ナビ側のWi-FiがOFF"),
        WEAK_SIGNAL("電波が弱い"),
        NO_INTERNET("つながっているが通信できない");

        final String text;

        Cause(String text) {
            this.text = text;
        }
    }

    static final class Entry {
        final long time;
        final Cause cause;
        /** 走行中に起きたか。停車中なら false */
        final boolean driving;
        /** 停車してからの分数。走行中・不明なら -1 */
        final int parkedMinutes;
        /** 切替先。切り替えなかったら null */
        final String switchedTo;
        /** 元に戻るまでの秒数。戻っていなければ -1 */
        final int recoveredSeconds;

        Entry(long time, Cause cause, boolean driving, int parkedMinutes,
              String switchedTo, int recoveredSeconds) {
            this.time = time;
            this.cause = cause;
            this.driving = driving;
            this.parkedMinutes = parkedMinutes;
            this.switchedTo = switchedTo;
            this.recoveredSeconds = recoveredSeconds;
        }
    }

    private DisconnectLog() {
    }

    static List<Entry> sample(long now) {
        long min = 60_000L;
        List<Entry> list = new ArrayList<>();
        list.add(new Entry(now - 35 * min, Cause.ROUTER_STOPPED, false, 124, "SIM", -1));
        list.add(new Entry(now - 3 * 60 * min, Cause.NAVI_WIFI_OFF, false, 0, "SIM", 45));
        list.add(new Entry(now - 22 * 60 * min, Cause.WEAK_SIGNAL, true, -1, "SIM", 20));
        list.add(new Entry(now - 26 * 60 * min, Cause.NO_INTERNET, true, -1, "SIM", 90));
        list.add(new Entry(now - 49 * 60 * min, Cause.ROUTER_STOPPED, false, 61, "SIM", 1800));
        list.add(new Entry(now - 51 * 60 * min, Cause.NAVI_WIFI_OFF, false, 0, null, 70));
        return list;
    }
}
