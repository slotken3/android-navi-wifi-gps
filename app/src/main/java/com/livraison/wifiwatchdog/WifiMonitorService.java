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
import android.net.NetworkInfo;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
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
 */
public class WifiMonitorService extends Service {

    private static final String TAG = "WifiWatchdog";
    private static final String CHANNEL_ID = "wifi_watchdog_channel";
    private static final int NOTIF_ID = 1;

    // 何秒おきに「本当に通信できているか」をチェックするか
    private static final long HEALTH_CHECK_INTERVAL_MS = 15_000L;
    // 復旧試行の最大バックオフ
    private static final long MAX_BACKOFF_MS = 60_000L;
    // この回数連続で失敗したらWi-Fiトグル(OFF/ON)を行う
    private static final int TOGGLE_THRESHOLD = 3;

    private WifiManager wifiManager;
    private Handler handler;
    private PowerManager.WakeLock wakeLock;

    private String targetSsid;
    private String targetPassword; // 空なら「既存の保存済み設定を使う」

    private int consecutiveFailures = 0;
    private long currentBackoff = 5_000L;
    private boolean recoveryInProgress = false;

    private final BroadcastReceiver wifiStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (WifiManager.NETWORK_STATE_CHANGED_ACTION.equals(action)) {
                NetworkInfo info = intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);
                if (info != null && !info.isConnected()) {
                    Log.w(TAG, "Wi-Fi切断を検知。復旧処理を開始します。");
                    scheduleRecovery(0);
                }
            } else if (WifiManager.WIFI_STATE_CHANGED_ACTION.equals(action)) {
                int state = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE,
                        WifiManager.WIFI_STATE_UNKNOWN);
                if (state == WifiManager.WIFI_STATE_DISABLED) {
                    Log.w(TAG, "Wi-Fi自体がOFFになっています。ONにします。");
                    wifiManager.setWifiEnabled(true);
                }
            }
        }
    };

    private final Runnable healthCheckRunnable = new Runnable() {
        @Override
        public void run() {
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
        startForeground(NOTIF_ID, buildNotification("監視中: " + safeSsid()));

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WifiWatchdog:lock");
            wakeLock.acquire(10 * 60 * 60 * 1000L); // 最大10時間保持し、定期的に更新する
        }

        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        registerReceiver(wifiStateReceiver, filter);

        handler.post(healthCheckRunnable);

        Log.i(TAG, "WifiMonitorService 起動。対象SSID=" + targetSsid);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
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
        // サービスが終了させられた場合、5秒後に自分自身を再起動する
        Intent restart = new Intent(getApplicationContext(), WifiMonitorService.class);
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
    // ヘルスチェック: 「接続中」表示でも実際に通信できているかを確認
    // ---------------------------------------------------------------
    private void checkHealthAndMaybeRecover() {
        if (recoveryInProgress) return;

        WifiInfo info = wifiManager.getConnectionInfo();
        boolean linked = info != null && info.getNetworkId() != -1;
        boolean ssidMatches = linked && matchesTargetSsid(info.getSSID());

        if (!ssidMatches) {
            Log.w(TAG, "対象SSIDに接続していません。復旧処理を開始します。");
            scheduleRecovery(0);
            return;
        }

        new Thread(() -> {
            boolean online = isActuallyOnline();
            if (!online) {
                Log.w(TAG, "接続表示はあるが疎通確認に失敗。復旧処理を開始します。");
                handler.post(() -> scheduleRecovery(0));
            } else {
                if (consecutiveFailures > 0) {
                    Log.i(TAG, "通信正常に復帰。失敗カウントをリセット。");
                }
                consecutiveFailures = 0;
                currentBackoff = 5_000L;
                handler.post(() -> updateNotification("正常: " + safeSsid()));
            }
        }).start();
    }

    private boolean isActuallyOnline() {
        try {
            URL url = new URL("http://connectivitycheck.gstatic.com/generate_204");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(4000);
            conn.setInstanceFollowRedirects(false);
            int code = conn.getResponseCode();
            conn.disconnect();
            return code == 204 || code == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean matchesTargetSsid(String currentSsidRaw) {
        if (TextUtils.isEmpty(targetSsid)) return true; // 未設定なら常に許容
        if (currentSsidRaw == null) return false;
        String current = currentSsidRaw.replace("\"", "");
        return current.equals(targetSsid);
    }

    // ---------------------------------------------------------------
    // 復旧処理本体(段階的リトライ)
    // ---------------------------------------------------------------
    private void scheduleRecovery(long delayMs) {
        if (recoveryInProgress) return;
        recoveryInProgress = true;
        handler.postDelayed(this::runRecoveryStep, delayMs);
    }

    private void runRecoveryStep() {
        consecutiveFailures++;
        updateNotification("復旧試行 " + consecutiveFailures + "回目: " + safeSsid());
        Log.i(TAG, "復旧ステップ実行。失敗回数=" + consecutiveFailures);

        if (!wifiManager.isWifiEnabled()) {
            wifiManager.setWifiEnabled(true);
        }

        if (consecutiveFailures >= TOGGLE_THRESHOLD) {
            // 段階4: Wi-Fi自体をOFF/ONしてリセット
            Log.w(TAG, "連続失敗が閾値到達。Wi-FiをOFF/ONしてリセットします。");
            wifiManager.setWifiEnabled(false);
            handler.postDelayed(() -> {
                wifiManager.setWifiEnabled(true);
                handler.postDelayed(this::attemptReconnect, 3000);
            }, 2000);
            consecutiveFailures = 0; // トグル後はカウントをリセットして様子を見る
        } else {
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
        NotificationManager nm =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification(text));
    }
}
