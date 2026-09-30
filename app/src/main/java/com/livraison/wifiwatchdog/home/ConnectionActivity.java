package com.livraison.wifiwatchdog.home;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;

import com.livraison.wifiwatchdog.MainActivity;
import com.livraison.wifiwatchdog.R;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 接続状態の詳細(要件N5・N6・N7、RT4)。
 * 左に今の経路と通信量、右に切断の記録(停車中・走行中で絞り込める)。
 */
public class ConnectionActivity extends BaseActivity {

    private static final long REFRESH_MS = 3000L;
    private static final String[] FILTERS = {"すべて", "停車中", "走行中"};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("H:mm", Locale.JAPAN);
    private final SimpleDateFormat dayFormat = new SimpleDateFormat("M/d(E)", Locale.JAPAN);

    private View routeIconBg;
    private ImageView routeIcon;
    private TextView routeTitle;
    private TextView routeDetail;
    private TextView wifiLine;
    private TextView simLine;
    private TextView usageValue;
    private ProgressBar usageBar;
    private TextView usageNote;
    private TextView monitorLine;
    private LinearLayout logFilter;
    private final LogAdapter logAdapter = new LogAdapter();
    private int filter = 0;

    private final Runnable refresher = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, REFRESH_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_connection);
        setupAppBar("接続状態");

        routeIconBg = findViewById(R.id.route_icon_bg);
        routeIcon = findViewById(R.id.route_icon);
        routeTitle = findViewById(R.id.route_title);
        routeDetail = findViewById(R.id.route_detail);
        wifiLine = findViewById(R.id.wifi_line);
        simLine = findViewById(R.id.sim_line);
        usageValue = findViewById(R.id.usage_value);
        usageBar = findViewById(R.id.usage_bar);
        usageNote = findViewById(R.id.usage_note);
        monitorLine = findViewById(R.id.monitor_line);
        logFilter = findViewById(R.id.log_filter);

        findViewById(R.id.monitor_open).setOnClickListener(
                v -> startActivity(new Intent(this, MainActivity.class)));

        ListView logList = findViewById(R.id.log_list);
        logList.setAdapter(logAdapter);
        applyFilter(0);
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(refresher);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(refresher);
    }

    private void refresh() {
        updateDemoBadge();
        ConnectionStatus s = ConnectionStatus.read(this);
        int statusColor = color(s.statusColorRes());

        routeIcon.setImageResource(s.iconRes());
        routeIcon.setImageTintList(ColorStateList.valueOf(statusColor));
        routeIconBg.setBackgroundTintList(
                ColorStateList.valueOf(ColorUtils.setAlphaComponent(statusColor, 0x33)));
        switch (s.route) {
            case WIFI:
                routeTitle.setText(s.online ? "Wi-Fiで通信中" : "Wi-Fiに接続中(通信できない)");
                break;
            case SIM:
                routeTitle.setText(s.online ? "SIMで通信中" : "SIMに接続中(通信できない)");
                break;
            case OTHER:
                routeTitle.setText("その他の回線で通信中");
                break;
            default:
                routeTitle.setText("通信できません");
                break;
        }
        String detail = s.online ? "インターネットまで届いています(Androidの判定)"
                : "インターネットに届いていません";
        if (s.simulated) detail += " ・ 表示確認のデモ";
        routeDetail.setText(detail);

        String wifi = "Wi-Fi:" + (s.wifiEnabled ? "ON" : "OFF(ナビ側のスイッチ)");
        if (s.wifiEnabled) wifi += s.ssid != null ? " ・ " + s.ssid : " ・ 未接続";
        wifiLine.setText(wifi);
        simLine.setText("SIM:" + (s.simReady
                ? "使用可能" + (s.carrier != null ? " ・ " + s.carrier : "")
                : "未挿入、または使えません"));

        usageValue.setText(s.usageText());
        usageBar.setProgress(s.usagePercent());
        usageBar.setProgressTintList(ColorStateList.valueOf(color(s.usageColorRes())));
        long left = Math.max(0, s.simLimitMb - s.simUsedMb);
        usageNote.setText(String.format(Locale.JAPAN,
                "残り %.1f GB ・ 80%%で黄色、100%%で赤 ・ 実測は段階1", left / 1024f));

        boolean running = ConnectionStatus.isMonitorRunning(this);
        monitorLine.setText(running
                ? "Wi-Fi監視:稼働中"
                : "Wi-Fi監視:停止中(スリープ復帰後に止められている可能性)");
        monitorLine.setTextColor(color(running ? R.color.text_primary : R.color.ng));
    }

    private void applyFilter(int index) {
        filter = index;
        Ui.segments(this, logFilter, FILTERS, index, this::applyFilter);
        List<DisconnectLog.Entry> all = DisconnectLog.sample(System.currentTimeMillis());
        List<DisconnectLog.Entry> shown = new ArrayList<>();
        for (DisconnectLog.Entry e : all) {
            if (filter == 0 || (filter == 1 && !e.driving) || (filter == 2 && e.driving)) {
                shown.add(e);
            }
        }
        logAdapter.setItems(shown);
    }

    // ---------------------------------------------------------------
    // 切断の記録の表示
    // ---------------------------------------------------------------
    private final class LogAdapter extends BaseAdapter {
        private List<DisconnectLog.Entry> items = new ArrayList<>();

        void setItems(List<DisconnectLog.Entry> list) {
            items = list;
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public DisconnectLog.Entry getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public boolean isEnabled(int position) {
            return false;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView != null ? convertView
                    : getLayoutInflater().inflate(R.layout.item_log, parent, false);
            DisconnectLog.Entry e = getItem(position);
            Date d = new Date(e.time);
            ((TextView) v.findViewById(R.id.log_time)).setText(timeFormat.format(d));
            ((TextView) v.findViewById(R.id.log_date)).setText(dayFormat.format(d));
            ((TextView) v.findViewById(R.id.log_cause)).setText(e.cause.text);

            List<String> parts = new ArrayList<>();
            if (!e.driving) {
                parts.add(e.parkedMinutes == 0 ? "ACC ON直後" : "停車 " + e.parkedMinutes + "分");
            }
            parts.add(e.switchedTo != null ? e.switchedTo + "へ切替" : "切替なし");
            parts.add(e.recoveredSeconds < 0 ? "Wi-Fi未復旧" : recoverText(e.recoveredSeconds));
            ((TextView) v.findViewById(R.id.log_detail)).setText(
                    android.text.TextUtils.join(" ・ ", parts));

            TextView vehicle = v.findViewById(R.id.log_vehicle);
            vehicle.setText(e.driving ? "走行中" : "停車中");
            vehicle.setBackgroundTintList(ColorStateList.valueOf(
                    color(e.driving ? R.color.accent_navi : R.color.text_secondary)));
            return v;
        }

        private String recoverText(int sec) {
            return sec < 60 ? sec + "秒で復旧" : (sec / 60) + "分で復旧";
        }
    }
}
