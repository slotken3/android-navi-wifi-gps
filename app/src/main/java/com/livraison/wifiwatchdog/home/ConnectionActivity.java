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

import com.livraison.wifiwatchdog.EventLog;
import com.livraison.wifiwatchdog.MainActivity;
import com.livraison.wifiwatchdog.R;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 接続状態の詳細(要件N5・N6・N7、RT4)。
 * 左に今の経路と通信量、右に自動で取った記録(起動・スリープ・切断・復旧)。
 */
public class ConnectionActivity extends BaseActivity {

    private static final long REFRESH_MS = 3000L;
    private static final String[] FILTERS = {"すべて", "切断・復旧", "起動・スリープ"};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("H:mm:ss", Locale.JAPAN);
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
    private TextView logCount;
    private final LogAdapter logAdapter = new LogAdapter();
    private int filter = 0;
    private long loadedLogSize = -1;

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
        logCount = findViewById(R.id.log_count);

        findViewById(R.id.monitor_open).setOnClickListener(
                v -> startActivity(new Intent(this, MainActivity.class)));

        ListView logList = findViewById(R.id.log_list);
        logList.setAdapter(logAdapter);
        logList.setEmptyView(findViewById(R.id.log_empty));
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
                "残り %.1f GB ・ 80%%で黄色、100%%で赤 ・ %s", left / 1024f,
                s.usageSimulated ? "表示確認のデモ値" : "起動してからのモバイル通信量を足し合わせた値"));
        findViewById(R.id.usage_badge).setVisibility(s.usageSimulated ? View.VISIBLE : View.GONE);

        boolean running = ConnectionStatus.isMonitorRunning(this);
        monitorLine.setText(running
                ? "Wi-Fi監視:稼働中"
                : "Wi-Fi監視:停止中(スリープ復帰後に止められている可能性)");
        monitorLine.setTextColor(color(running ? R.color.text_primary : R.color.ng));

        // 記録は増えたときだけ読み直す
        if (EventLog.sizeBytes(this) != loadedLogSize) loadLog();
    }

    private void applyFilter(int index) {
        filter = index;
        Ui.segments(this, logFilter, FILTERS, index, this::applyFilter);
        loadLog();
    }

    private void loadLog() {
        loadedLogSize = EventLog.sizeBytes(this);
        List<EventLog.Entry> all = EventLog.readAll(this);
        List<Row> rows = new ArrayList<>();
        // 新しい順に並べる
        for (int i = all.size() - 1; i >= 0; i--) {
            EventLog.Entry e = all.get(i);
            boolean cutOrRecover = EventLog.CUT.equals(e.type) || EventLog.RECOVER.equals(e.type);
            if (filter == 1 && !cutOrRecover) continue;
            if (filter == 2 && cutOrRecover) continue;
            rows.add(new Row(e, EventLog.displayTime(all, i)));
        }
        logCount.setText(all.size() + "件");
        logAdapter.setItems(rows);
    }

    private static final class Row {
        final EventLog.Entry entry;
        /** 補正後の時刻。分からなければ -1 */
        final long time;

        Row(EventLog.Entry entry, long time) {
            this.entry = entry;
            this.time = time;
        }
    }

    // ---------------------------------------------------------------
    // 記録の表示
    // ---------------------------------------------------------------
    private final class LogAdapter extends BaseAdapter {
        private List<Row> items = new ArrayList<>();

        void setItems(List<Row> list) {
            items = list;
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Row getItem(int position) {
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
            Row r = getItem(position);
            EventLog.Entry e = r.entry;
            TextView time = v.findViewById(R.id.log_time);
            TextView date = v.findViewById(R.id.log_date);
            if (r.time >= 0) {
                time.setText(timeFormat.format(new Date(r.time)));
                date.setText(dayFormat.format(new Date(r.time)));
            } else {
                // 電源が落ちた後、ネットで時計が合う前の記録
                time.setText("時刻不明");
                date.setText("起動" + EventLog.duration(e.elapsed) + "後");
            }
            ((TextView) v.findViewById(R.id.log_cause)).setText(e.text);
            TextView detail = v.findViewById(R.id.log_detail);
            if (!e.clockValid() && r.time >= 0) {
                detail.setText("時計が合う前の記録。時刻は後から補正");
                detail.setVisibility(View.VISIBLE);
            } else {
                detail.setVisibility(View.GONE);
            }

            TextView tag = v.findViewById(R.id.log_vehicle);
            tag.setText(typeLabel(e.type));
            tag.setBackgroundTintList(ColorStateList.valueOf(color(typeColor(e.type))));
            return v;
        }
    }

    private static String typeLabel(String type) {
        switch (type) {
            case EventLog.BOOT: return "起動";
            case EventLog.SLEEP: return "スリープ";
            case EventLog.MONITOR: return "監視";
            case EventLog.CUT: return "切断";
            case EventLog.RECOVER: return "復旧";
            default: return type;
        }
    }

    private static int typeColor(String type) {
        switch (type) {
            case EventLog.BOOT: return R.color.accent_navi;
            case EventLog.MONITOR: return R.color.accent_meeting;
            case EventLog.CUT: return R.color.ng;
            case EventLog.RECOVER: return R.color.ok;
            default: return R.color.text_secondary;
        }
    }
}
