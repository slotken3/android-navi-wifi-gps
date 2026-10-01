package com.livraison.wifiwatchdog;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

/**
 * 車内Wi-Fiの接続状態を常時監視し、切断や疑似切断(接続はしているが
 * 実際には通信できない状態)を検知したら段階的に復旧処理を行うサービス。
 *
 * 復旧の段階:
 *   1. wifiManager.reconnect() / reassociate()
 *   2. 対象SSIDの設定を enableNetwork() で明示的に有効化
 *   3. 設定自体が失われている場合は addNetwork() で再登録
 *   4. それでも直らない場合、Wi-Fi自体をOFF→ONしてリセット
 *
 * 各段階の間隔は指数バックオフ(最大60秒)で、一定回数失敗したら
 * 通知に状態を表示しつつリトライを継続する。
 *
 * 起動・スリープ・切断・復旧は EventLog に自動で記録する(要件N6)。
 * スリープ中(画面OFF)は何もしない。CPUを起こし続けるとスリープできず、
 * バッテリー上がりの恐れがあるため(要件「待機電力」)。
 */
public class WifiMonitorService extends Service {

    private static final String TAG = "WifiWatchdog";
    private static final String CHANNEL_ID = "wifi_watchdog_channel";
    private static final int NOTIF_ID = 1;
    public static final String EXTRA_REASON = "reason";

    // 何秒おきに「本当に通信できているか」をチェックするか
    private static final long HEALTH_CHECK_INTERVAL_MS = 15_000L;
    // 復旧試行の最大バックオフ
    private static final long MAX_BACKOFF_MS = 60_000L;
    // この回数連続で失敗したらWi-Fiトグル(OFF/ON)を行う
    private static final int TOGGLE_THRESHOLD = 3;
    // 監視の間隔よりこれ以上長く止まっていたら、スリープしていたとみなす
    private static final long SLEEP_GAP_MS = 60_000L;
    // 起動からこの時間以内に監視が始まったら、「起動から通信できるまで」を測る(要件N1)
    private static final long BOOT_WINDOW_MS = 10 * 60_000L;

    /** 「監視を停止」で止めたときは、自動で再開しない */
    private static boolean stopRequested = false;

    private WifiManager wifiManager;
    private Handler handler;
    private PowerManager.WakeLock wakeLock;

    private String targetSsid;
    private String targetPassword; // 空なら「既存の保存済み設定を使う」

    private int consecutiveFailures = 0;
    private long currentBackoff = 5_000L;
    private boolean recoveryInProgress = false;
    private boolean started = false;
    private String notificationText = "";

    // 切断から復旧までの追跡(要件N6)
    private boolean outage = false;
    private long outageStart;
    private boolean appActed;
    private String lastSsid;

    // 起動・スリープ復帰から通信できるまでの時間(要件N1)
    private long waitingOnlineSince = -1;
    private String waitingOnlineLabel;
    private long monitorStartElapsed;

    // スリープの検知
    private boolean screenOff = false;
    /** SSIDを読めないことを記録したか(同じ記録を繰り返さない) */
    private boolean ssidHiddenLogged = false;
    private long lastTickElapsed;
    private long lastTickUptime;

    /** 監視を始める。理由は記録に残す */
    public static void start(Context c, String reason) {
        stopRequested = false;
        Intent i = new Intent(c, WifiMonitorService.class).putExtra(EXTRA_REASON, reason);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            c.startForegroundService(i);
        } else {
            c.startService(i);
        }
    }

    /**
     * 監視サービスが動いているか。スリープ復帰後に止められていないかを画面で見るためのもの。
     * getRunningServices は非推奨だが、自分のアプリのサービスは今も返る。
     */
    @SuppressWarnings("deprecation")
    public static boolean isRunning(Context c) {
        android.app.ActivityManager am = c.getSystemService(android.app.ActivityManager.class);
        if (am == null) return false;
        List<android.app.ActivityManager.RunningServiceInfo> list = am.getRunningServices(100);
        if (list == null) return false;
        for (android.app.ActivityManager.RunningServiceInfo info : list) {
            if (c.getPackageName().equals(info.service.getPackageName())
                    && WifiMonitorService.class.getName().equals(info.service.getClassName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 位置情報の許可。Android 10 では「常に許可」でないと、裏で動いている間はWi-Fiの名前を読めない。
     */
    public static String locationPermission(Context c) {
        boolean fine = c.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
        if (!fine) return "許可なし";
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return "常に許可";
        boolean background = c.checkSelfPermission(
                android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
        return background ? "常に許可" : "使用中のみ";
    }

    /** 利用者の操作で止める。自動の再開はしない */
    public static void stop(Context c) {
        stopRequested = true;
        c.stopService(new Intent(c, WifiMonitorService.class));
    }

    private final BroadcastReceiver wifiStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                onScreenOff();
            } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                screenOff = false;
                onWake("画面ON");
            } else if (WifiManager.NETWORK_STATE_CHANGED_ACTION.equals(action)) {
                if (screenOff) return;
                NetworkInfo info = intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);
                if (info != null && !info.isConnected()) {
                    if (info.getState() == NetworkInfo.State.DISCONNECTED) {
                        startOutage(classifyDisconnect());
                    }
                    Log.w(TAG, "Wi-Fi切断を検知。復旧処理を開始します。");
                    scheduleRecovery(0);
                }
            } else if (WifiManager.WIFI_STATE_CHANGED_ACTION.equals(action)) {
                int state = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE,
                        WifiManager.WIFI_STATE_UNKNOWN);
                if (state == WifiManager.WIFI_STATE_DISABLED) {
                    if (screenOff) {
                        log(EventLog.CUT, "スリープ中にWi-FiがOFFになった(復帰後に戻す)");
                        return;
                    }
                    startOutage("ナビ側のWi-FiがOFF");
                    turnWifiOn();
                }
            }
        }
    };

    private final Runnable healthCheckRunnable = new Runnable() {
        @Override
        public void run() {
            detectSleepGap();
            checkHealthAndMaybeRecover();
            handler.postDelayed(this, HEALTH_CHECK_INTERVAL_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        wifiManager = (WifiManager) getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        handler = new Handler(Looper.getMainLooper());

        SharedPreferences prefs = getSharedPreferences(Prefs.NAME, MODE_PRIVATE);
        targetSsid = prefs.getString(Prefs.KEY_SSID, "");
        targetPassword = prefs.getString(Prefs.KEY_PASSWORD, "");

        createNotificationChannel();
        notificationText = "監視中: " + safeSsid();
        startForeground(NOTIF_ID, buildNotification(notificationText));

        // 復旧の操作中だけ短くCPUを起こす。常時保持はしない(スリープを妨げるため)
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WifiWatchdog:recovery");
            wakeLock.setReferenceCounted(false);
        }

        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        registerReceiver(wifiStateReceiver, filter);

        long sinceBoot = SystemClock.elapsedRealtime();
        monitorStartElapsed = sinceBoot;
        if (sinceBoot < BOOT_WINDOW_MS) {
            waitingOnlineSince = 0;
            waitingOnlineLabel = "起動";
        }
        lastTickElapsed = sinceBoot;
        lastTickUptime = SystemClock.uptimeMillis();
        handler.post(healthCheckRunnable);

        Log.i(TAG, "WifiMonitorService 起動。対象SSID=" + targetSsid);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // startForegroundService で呼ばれるたびに startForeground が要る(動作中に再度呼ばれても)
        startForeground(NOTIF_ID, buildNotification(notificationText));
        String reason = intent != null ? intent.getStringExtra(EXTRA_REASON) : null;
        if (!started) {
            started = true;
            log(EventLog.MONITOR, "Wi-Fi監視を開始(きっかけ:"
                    + (reason != null ? reason : "停止後の自動再開") + " ・ 対象:" + safeSsid()
                    + " ・ 位置情報:" + locationPermission(this)
                    + " ・ 端末の起動から" + EventLog.duration(SystemClock.elapsedRealtime()) + ")");
        } else if (reason != null) {
            Log.i(TAG, "開始の指示(すでに動作中): " + reason);
        }
        // サービスがkillされても再作成される(START_STICKY)
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        try {
            unregisterReceiver(wifiStateReceiver);
        } catch (Exception ignored) {
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        if (stopRequested) {
            log(EventLog.MONITOR, "Wi-Fi監視を停止(利用者の操作)");
            return;
        }
        log(EventLog.MONITOR, "Wi-Fi監視が終了した(5秒後に自動で再開を予約)");
        // サービスが終了させられた場合、5秒後に自分自身を再起動する
        Intent restart = new Intent(getApplicationContext(), WifiMonitorService.class)
                .putExtra(EXTRA_REASON, "終了後の自動再開");
        PendingIntent pi = PendingIntent.getService(getApplicationContext(), 1, restart,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        ? PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE
                        : PendingIntent.FLAG_ONE_SHOT);
        android.app.AlarmManager am =
                (android.app.AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (am != null) {
            am.set(android.app.AlarmManager.RTC, System.currentTimeMillis() + 5000, pi);
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---------------------------------------------------------------
    // スリープと復帰
    // ---------------------------------------------------------------
    private void onScreenOff() {
        screenOff = true;
        log(EventLog.SLEEP, "画面OFF(スリープへ)");
        if (outage) {
            outage = false;
            log(EventLog.CUT, "スリープに入ったため、切断の記録をここで区切る");
        }
        waitingOnlineSince = -1;
    }

    /** 監視の間隔が大きく空いていたら、スリープしていたとみなす(画面OFFが来ない機種の保険) */
    private void detectSleepGap() {
        long elapsed = SystemClock.elapsedRealtime();
        long uptime = SystemClock.uptimeMillis();
        long slept = (elapsed - lastTickElapsed) - (uptime - lastTickUptime);
        lastTickElapsed = elapsed;
        lastTickUptime = uptime;
        if (slept > SLEEP_GAP_MS) {
            log(EventLog.SLEEP, "スリープしていた時間:約" + EventLog.duration(slept));
            if (waitingOnlineSince < 0) {
                waitingOnlineSince = elapsed;
                waitingOnlineLabel = "スリープ復帰";
            }
        }
    }

    private void onWake(String how) {
        // 接続中かは回線情報で見る(Wi-Fiの名前が隠されているときも正しく出すため)
        boolean linked = findWifiNetwork() != null;
        log(EventLog.SLEEP, how + "(復帰)。Wi-Fi " + (wifiManager.isWifiEnabled() ? "ON" : "OFF")
                + " ・ " + (linked ? "接続中" : "未接続"));
        waitingOnlineSince = SystemClock.elapsedRealtime();
        waitingOnlineLabel = "スリープ復帰";
        handler.removeCallbacks(healthCheckRunnable);
        handler.post(healthCheckRunnable);
    }

    // ---------------------------------------------------------------
    // ヘルスチェック: 「接続中」表示でも実際に通信できているかを確認
    // ---------------------------------------------------------------
    /**
     * つながっているかは Android の回線情報(Wi-Fiの回線があるか)で判断し、
     * Wi-Fiの名前(SSID)は読めるときだけ確かめる。
     *
     * Android 10 では位置情報の許可が「アプリの使用中のみ」だと、裏で動いている間は
     * SSID が隠され(<unknown ssid>・networkId=-1)、以前はそれを「未接続」と誤判定して
     * 通信できているのに Wi-Fi を OFF→ON し続けていた(2026-10-01、実機の記録で発覚)。
     */
    private void checkHealthAndMaybeRecover() {
        if (recoveryInProgress || screenOff) return;

        Network wifiNet = findWifiNetwork();
        String ssid = readSsid();
        if (ssid != null) {
            lastSsid = ssid;
        } else if (wifiNet != null && !ssidHiddenLogged) {
            ssidHiddenLogged = true;
            log(EventLog.MONITOR, "接続中のWi-Fiの名前を読めない(位置情報の許可が「常に許可」でない可能性)。"
                    + "名前は確かめず、つながっているかと通信できるかだけで判断する");
        }

        if (wifiNet == null) {
            Log.w(TAG, "Wi-Fiにつながっていません。復旧処理を開始します。");
            startOutage(wifiManager.isWifiEnabled() ? classifyDisconnect() : "ナビ側のWi-FiがOFF");
            scheduleRecovery(0);
            return;
        }
        if (!TextUtils.isEmpty(targetSsid) && ssid != null && !ssid.equals(targetSsid)) {
            // 使わないネットワーク(以前のスマホのテザリングなど)につながっている(要件N8)
            startOutage("別のWi-Fi(" + ssid + ")につながっている");
            scheduleRecovery(0);
            return;
        }

        new Thread(() -> {
            String problem = probeInternet(wifiNet);
            handler.post(() -> {
                if (problem != null) {
                    Log.w(TAG, "接続表示はあるが疎通確認に失敗(" + problem + ")。復旧処理を開始します。");
                    startOutage("つながっているが通信できない(確認の結果:" + problem + ")");
                    scheduleRecovery(0);
                    return;
                }
                if (consecutiveFailures > 0) {
                    Log.i(TAG, "通信正常に復帰。失敗カウントをリセット。");
                }
                consecutiveFailures = 0;
                currentBackoff = 5_000L;
                onOnline();
                updateNotification("正常: " + safeSsid());
            });
        }).start();
    }

    /**
     * Wi-Fi経由で本当にインターネットまで届くかを確かめる。届けば null、だめなら理由を返す。
     *
     * 以前は http(暗号化なし)で確かめていたが、targetSdk 28 では平文の通信が禁止されているため
     * 常に失敗し、通信できているのに数分おきにWi-FiをOFF→ONしていた(2026-10-01、CIの記録で発覚)。
     * https で、Wi-Fiの回線を指定して確かめる(SIMを入れたあとSIM経由で成功してしまわないように)。
     */
    private String probeInternet(Network wifi) {
        try {
            URL url = new URL("https://connectivitycheck.gstatic.com/generate_204");
            HttpURLConnection conn = (HttpURLConnection) wifi.openConnection(url);
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(4000);
            conn.setInstanceFollowRedirects(false);
            conn.setUseCaches(false);
            int code = conn.getResponseCode();
            conn.disconnect();
            // 204以外(ログイン画面への転送など)は、インターネットに届いていない
            return code == 204 ? null : "応答" + code;
        } catch (Exception e) {
            return e.getClass().getSimpleName();
        }
    }

    /** つながっているWi-Fiの回線。無ければ null */
    @SuppressWarnings("deprecation")
    private Network findWifiNetwork() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm == null) return null;
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities nc = cm.getNetworkCapabilities(n);
            if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return n;
        }
        return null;
    }

    /** 接続中のSSID。未接続、または隠されて読めなければ null */
    private String readSsid() {
        WifiInfo info = wifiManager.getConnectionInfo();
        if (info == null || info.getSSID() == null) return null;
        String raw = info.getSSID();
        if ("<unknown ssid>".equals(raw)) return null;
        return raw.replace("\"", "");
    }

    // ---------------------------------------------------------------
    // 切断と復旧の記録(要件N6)
    // ---------------------------------------------------------------
    private void startOutage(String cause) {
        if (outage) return;
        outage = true;
        outageStart = SystemClock.elapsedRealtime();
        appActed = false;
        log(EventLog.CUT, cause + " ・ 今の経路:" + routeName());
    }

    private void onOnline() {
        long now = SystemClock.elapsedRealtime();
        if (outage) {
            outage = false;
            log(EventLog.RECOVER, "通信が戻った(" + EventLog.duration(now - outageStart) + ") ・ "
                    + (appActed ? "アプリの復旧操作のあと" : "Androidが自動で再接続(アプリは操作していない)"));
        }
        if (waitingOnlineSince >= 0) {
            boolean boot = "起動".equals(waitingOnlineLabel);
            // 起動のときは、監視を始めた時点で既につながっていた可能性があるので、開始時刻も残す
            log(boot ? EventLog.BOOT : EventLog.SLEEP,
                    waitingOnlineLabel + "から" + EventLog.duration(now - waitingOnlineSince)
                            + "で通信OK(" + routeName() + ")"
                            + (boot ? " ・ 監視の開始は起動から" + EventLog.duration(monitorStartElapsed) : ""));
            waitingOnlineSince = -1;
        }
    }

    /** 切断の原因の見当(電波が見えるか・強さ)。直前のスキャン結果から判断する */
    private String classifyDisconnect() {
        // Wi-Fiを切るときは「OFFになった」より先に「切断」が届くことがあるので、スイッチの状態も見る
        int ws = wifiManager.getWifiState();
        if (ws == WifiManager.WIFI_STATE_DISABLING || ws == WifiManager.WIFI_STATE_DISABLED) {
            return "ナビ側のWi-FiがOFF";
        }
        String target = !TextUtils.isEmpty(targetSsid) ? targetSsid : lastSsid;
        if (target == null) return "Wi-Fiの切断";
        try {
            List<ScanResult> scans = wifiManager.getScanResults();
            // 位置情報の許可が足りないと、周りのWi-Fiの一覧は空で返る。そのときは見当を付けない
            if (scans == null || scans.isEmpty()) return "Wi-Fiの切断(周りのWi-Fiの一覧を読めない)";
            ScanResult best = null;
            if (scans != null) {
                for (ScanResult r : scans) {
                    if (target.equals(r.SSID) && (best == null || r.level > best.level)) best = r;
                }
            }
            if (best == null) return "Wi-Fiの切断(" + target + " の電波が見えない。ルーター側の停止か圏外)";
            if (best.level < -80) return "Wi-Fiの切断(電波が弱い " + best.level + "dBm)";
            return "Wi-Fiの切断(電波は見えている " + best.level + "dBm)";
        } catch (SecurityException e) {
            return "Wi-Fiの切断";
        }
    }

    private String routeName() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        Network n = cm != null ? cm.getActiveNetwork() : null;
        NetworkCapabilities nc = n != null ? cm.getNetworkCapabilities(n) : null;
        if (nc == null) return "なし";
        if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "Wi-Fi";
        if (nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return "SIM";
        return "その他";
    }

    private void log(String type, String text) {
        EventLog.add(this, type, text);
    }

    // ---------------------------------------------------------------
    // 復旧処理本体(段階的リトライ)
    // ---------------------------------------------------------------
    private void scheduleRecovery(long delayMs) {
        if (recoveryInProgress) return;
        recoveryInProgress = true;
        handler.postDelayed(this::runRecoveryStep, delayMs);
    }

    private void turnWifiOn() {
        boolean ok = wifiManager.setWifiEnabled(true);
        appActed = true;
        log(EventLog.RECOVER, "Wi-FiをONに戻す操作(" + (ok ? "受け付けられた" : "拒否された") + ")");
    }

    private void runRecoveryStep() {
        if (screenOff) {
            recoveryInProgress = false;
            return;
        }
        if (wakeLock != null) wakeLock.acquire(30_000L);
        consecutiveFailures++;
        appActed = true;
        updateNotification("復旧試行 " + consecutiveFailures + "回目: " + safeSsid());
        Log.i(TAG, "復旧ステップ実行。失敗回数=" + consecutiveFailures);

        if (!wifiManager.isWifiEnabled()) {
            turnWifiOn();
        }

        if (consecutiveFailures >= TOGGLE_THRESHOLD) {
            // 段階4: Wi-Fi自体をOFF/ONしてリセット
            log(EventLog.RECOVER, "復旧操作 " + consecutiveFailures + "回目: Wi-FiをOFF→ONしてリセット");
            wifiManager.setWifiEnabled(false);
            handler.postDelayed(() -> {
                wifiManager.setWifiEnabled(true);
                handler.postDelayed(this::attemptReconnect, 3000);
            }, 2000);
            consecutiveFailures = 0; // トグル後はカウントをリセットして様子を見る
        } else {
            log(EventLog.RECOVER, "復旧操作 " + consecutiveFailures + "回目: 再接続");
            attemptReconnect();
        }

        // 次のヘルスチェックまでバックオフして待つ(recoveryInProgressを解除)
        currentBackoff = Math.min(currentBackoff * 2, MAX_BACKOFF_MS);
        handler.postDelayed(() -> recoveryInProgress = false, currentBackoff);
    }

    private void attemptReconnect() {
        // 段階1: シンプルな再接続
        boolean ok = wifiManager.reconnect();
        Log.i(TAG, "reconnect() 実行結果=" + ok);

        // 段階2: 対象SSIDの設定を探して明示的に有効化
        int networkId = findConfiguredNetworkId(targetSsid);
        if (networkId != -1) {
            wifiManager.enableNetwork(networkId, true);
            Log.i(TAG, "既存設定(id=" + networkId + ")を enableNetwork しました。");
            return;
        }

        // 段階3: 設定が見つからず、パスワードが保存されていれば新規登録
        if (!TextUtils.isEmpty(targetSsid) && !TextUtils.isEmpty(targetPassword)) {
            Log.w(TAG, "対象SSIDの設定が見つからないため新規追加します。");
            WifiConfiguration config = new WifiConfiguration();
            config.SSID = "\"" + targetSsid + "\"";
            config.preSharedKey = "\"" + targetPassword + "\"";
            int newId = wifiManager.addNetwork(config);
            if (newId != -1) {
                wifiManager.enableNetwork(newId, true);
                wifiManager.saveConfiguration();
            }
        }
    }

    private int findConfiguredNetworkId(String ssid) {
        if (TextUtils.isEmpty(ssid)) return -1;
        List<WifiConfiguration> configs = wifiManager.getConfiguredNetworks();
        if (configs == null) return -1;
        for (WifiConfiguration c : configs) {
            if (c.SSID != null && c.SSID.replace("\"", "").equals(ssid)) {
                return c.networkId;
            }
        }
        return -1;
    }

    private String safeSsid() {
        return TextUtils.isEmpty(targetSsid) ? "(未設定/現在の接続を維持)" : targetSsid;
    }

    // ---------------------------------------------------------------
    // 通知(フォアグラウンドサービス用)
    // ---------------------------------------------------------------
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Wi-Fi監視", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                        : PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Wi-Fi自動復旧")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
    }

    private void updateNotification(String text) {
        notificationText = text;
        NotificationManager nm =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification(text));
    }
}
