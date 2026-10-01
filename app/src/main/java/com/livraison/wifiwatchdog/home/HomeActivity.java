package com.livraison.wifiwatchdog.home;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.livraison.wifiwatchdog.BuildConfig;
import com.livraison.wifiwatchdog.Prefs;
import com.livraison.wifiwatchdog.R;
import com.livraison.wifiwatchdog.WifiMonitorService;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * ホーム画面(要件U1〜U6)。
 *
 * 既定のホームにするかどうかは、設定 → ホームアプリ で切り替える。
 * 初期状態ではふつうのアプリとして一覧から開くだけ。
 */
public class HomeActivity extends BaseActivity {

    private static final long REFRESH_MS = 3000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("M月d日(E)", Locale.JAPAN);

    private TileView tileNavi;
    private TileView tileVideo;
    private TileView tileMusic;
    private TileView tileMeeting;
    private TileView tileCarplay;
    private TileView tileDashcam;
    private TileView tileStatus;
    private TextView dateView;
    private View demoBadge;
    private View monitorDot;
    private TextView monitorText;

    private View pickerScrim;
    private TextView pickerTitle;
    private TextView pickerNote;
    private TextView pickerHint;
    private LinearLayout pickerItems;

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
        setContentView(R.layout.activity_home);

        tileNavi = findViewById(R.id.tile_navi);
        tileVideo = findViewById(R.id.tile_video);
        tileMusic = findViewById(R.id.tile_music);
        tileMeeting = findViewById(R.id.tile_meeting);
        tileCarplay = findViewById(R.id.tile_carplay);
        tileDashcam = findViewById(R.id.tile_dashcam);
        tileStatus = findViewById(R.id.tile_status);
        dateView = findViewById(R.id.date);
        demoBadge = findViewById(R.id.demo_badge);
        monitorDot = findViewById(R.id.monitor_dot);
        monitorText = findViewById(R.id.monitor_text);
        pickerScrim = findViewById(R.id.picker_scrim);
        pickerTitle = findViewById(R.id.picker_title);
        pickerNote = findViewById(R.id.picker_note);
        pickerHint = findViewById(R.id.picker_hint);
        pickerItems = findViewById(R.id.picker_items);

        bindSlot(tileNavi, Slot.NAVI);
        bindSlot(tileVideo, Slot.VIDEO);
        bindSlot(tileMusic, Slot.MUSIC);
        bindSlot(tileMeeting, Slot.MEETING);
        bindSlot(tileCarplay, Slot.CARPLAY);
        bindSlot(tileDashcam, Slot.DASHCAM);
        bindSlot(findViewById(R.id.dock_radio), Slot.RADIO);
        bindSlot(findViewById(R.id.dock_phone), Slot.PHONE);

        View.OnClickListener openConnection =
                v -> startActivity(new Intent(this, ConnectionActivity.class));
        tileStatus.setOnClickListener(openConnection);
        findViewById(R.id.monitor_chip).setOnClickListener(openConnection);
        findViewById(R.id.dock_apps).setOnClickListener(
                v -> startActivity(new Intent(this, AppListActivity.class)));
        findViewById(R.id.dock_settings).setOnClickListener(
                v -> startActivity(new Intent(this, SettingsActivity.class)));

        pickerScrim.setOnClickListener(v -> closePicker());
        findViewById(R.id.picker_close).setOnClickListener(v -> closePicker());

        // CIの画面確認用: 選択パネルを開いた状態で表示する
        String panel = getIntent().getStringExtra("open_panel");
        if (BuildConfig.DEBUG && panel != null) {
            Slot s = Slot.fromKey(panel);
            if (s != null) openPicker(s);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // ホームを開くたびに、監視が止まっていれば始める(要件U6)。
        // 起動完了の通知が届かず監視が始まらなかったことがあるため(2026-10-01)
        boolean autoStart = getSharedPreferences(Prefs.NAME, MODE_PRIVATE)
                .getBoolean(Prefs.KEY_AUTO_START, true);
        if (autoStart && !ConnectionStatus.isMonitorRunning(this)) {
            WifiMonitorService.start(this, "ナビホームを開いた");
        }
        handler.post(refresher);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(refresher);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // ホームボタンで戻ってきたらパネルを閉じる
        closePicker();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (pickerScrim.getVisibility() == View.VISIBLE) {
            closePicker();
            return;
        }
        // 既定のホームとして動いているときは、戻るで閉じない
        Intent i = getIntent();
        if (i != null && i.hasCategory(Intent.CATEGORY_HOME)) return;
        super.onBackPressed();
    }

    // ---------------------------------------------------------------
    // タイル
    // ---------------------------------------------------------------
    private void bindSlot(View tile, Slot slot) {
        tile.setOnClickListener(v -> onSlotTapped(slot));
        tile.setOnLongClickListener(v -> {
            editSlot(slot);
            return true;
        });
    }

    private void onSlotTapped(Slot slot) {
        List<String> pkgs = HomePrefs.apps(this, slot);
        if (slot.group) {
            List<String> installed = AppCatalog.installedOnly(this, pkgs);
            // 入っているのが1つだけなら、選ばせずにそのまま開く
            if (installed.size() == 1) {
                AppCatalog.launch(this, installed.get(0));
            } else {
                openPicker(slot);
            }
            return;
        }
        String pkg = AppCatalog.firstInstalled(this, pkgs);
        if (pkg != null) {
            AppCatalog.launch(this, pkg);
        } else {
            Toast.makeText(this, "「" + slot.title + "」に使うアプリが見つかりません。"
                    + "停車中に長押しすると選べます", Toast.LENGTH_LONG).show();
        }
    }

    /** 長押しで割り当てを変える。走行中は受け付けない(要件U5) */
    private void editSlot(Slot slot) {
        if (HomePrefs.isDriving(this)) {
            Toast.makeText(this, "走行中は変更できません。停車してから操作してください",
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (slot.group) {
            startActivity(new Intent(this, SettingsActivity.class)
                    .putExtra(SettingsActivity.EXTRA_CATEGORY, "apps"));
        } else {
            startActivity(new Intent(this, AppListActivity.class)
                    .putExtra(AppListActivity.EXTRA_PICK_SLOT, slot.key));
        }
    }

    private void refresh() {
        dateView.setText(dateFormat.format(new Date()));
        demoBadge.setVisibility(HomePrefs.isDemoActive(this) ? View.VISIBLE : View.GONE);

        boolean running = ConnectionStatus.isMonitorRunning(this);
        monitorDot.setBackgroundTintList(ColorStateList.valueOf(
                color(running ? R.color.ok : R.color.ng)));
        monitorText.setText(running ? "Wi-Fi監視 稼働中" : "Wi-Fi監視 停止中");

        updateSingleTile(tileNavi, Slot.NAVI);
        updateGroupTile(tileVideo, Slot.VIDEO);
        updateGroupTile(tileMusic, Slot.MUSIC);
        updateGroupTile(tileMeeting, Slot.MEETING);
        if (HomePrefs.isDriving(this)) {
            // 走行中はカメラを使わず音声のみ(要件U12)
            tileMeeting.setSubtitle("走行中は音声のみ", color(R.color.warn));
        }
        updateSingleTile(tileCarplay, Slot.CARPLAY);
        updateSingleTile(tileDashcam, Slot.DASHCAM);
        updateStatusTile();
    }

    private void updateSingleTile(TileView tile, Slot slot) {
        List<String> pkgs = HomePrefs.apps(this, slot);
        String pkg = AppCatalog.firstInstalled(this, pkgs);
        if (pkg != null) {
            tile.setSubtitle(AppCatalog.label(this, pkg));
        } else {
            tile.setSubtitle(pkgs.isEmpty() ? "未設定(長押しで選ぶ)" : "アプリが見つかりません",
                    color(R.color.warn));
        }
    }

    private void updateGroupTile(TileView tile, Slot slot) {
        List<String> installed = AppCatalog.installedOnly(this, HomePrefs.apps(this, slot));
        if (installed.isEmpty()) {
            tile.setSubtitle("アプリが見つかりません", color(R.color.warn));
            return;
        }
        List<String> labels = new ArrayList<>();
        for (String p : installed) labels.add(AppCatalog.label(this, p));
        tile.setSubtitle(TextUtils.join("・", labels));
    }

    /** 接続状態のタイル(要件N7) */
    private void updateStatusTile() {
        ConnectionStatus s = ConnectionStatus.read(this);
        int statusColor = color(s.statusColorRes());
        tileStatus.setIcon(s.iconRes());
        tileStatus.setAccent(statusColor);
        tileStatus.setTitle(s.routeName());
        // SSIDは長くてあふれるので、接続状態の画面だけに出す
        String line2 = "SIM " + s.usageTextShort() + "(デモ)";
        tileStatus.setSubtitle(s.onlineTextShort() + "\n" + line2,
                s.online ? color(R.color.text_secondary) : statusColor);
        tileStatus.setProgress(s.usagePercent(), color(s.usageColorRes()));
    }

    // ---------------------------------------------------------------
    // アプリを選ぶパネル(動画・音楽・会議)
    // ---------------------------------------------------------------
    private void openPicker(Slot slot) {
        pickerTitle.setText(slot.title + "を選ぶ");
        pickerItems.removeAllViews();
        LayoutInflater inflater = getLayoutInflater();

        List<String> pkgs = HomePrefs.apps(this, slot);
        for (String pkg : pkgs) {
            View item = inflater.inflate(R.layout.item_picker_app, pickerItems, false);
            ImageView icon = item.findViewById(R.id.picker_app_icon);
            TextView label = item.findViewById(R.id.picker_app_label);
            View state = item.findViewById(R.id.picker_app_state);

            boolean installed = AppCatalog.isInstalled(this, pkg);
            Drawable d = installed ? AppCatalog.icon(this, pkg) : null;
            if (d != null) {
                icon.setImageDrawable(d);
            } else {
                icon.setImageResource(slot.icon);
                icon.setImageTintList(ColorStateList.valueOf(color(R.color.text_disabled)));
            }
            label.setText(AppCatalog.label(this, pkg));
            state.setVisibility(installed ? View.GONE : View.VISIBLE);
            item.setAlpha(installed ? 1f : 0.6f);
            item.setOnClickListener(v -> {
                if (AppCatalog.launch(this, pkg)) closePicker();
            });
            pickerItems.addView(item);
        }
        if (pkgs.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("並べるアプリがありません。設定 → ホーム画面のアプリ で追加してください");
            empty.setTextColor(color(R.color.text_secondary));
            empty.setTextSize(17);
            pickerItems.addView(empty);
        }

        boolean driving = HomePrefs.isDriving(this);
        if (slot == Slot.MEETING && driving) {
            pickerNote.setText("走行中:カメラは使わず、音声だけで参加してください");
            pickerNote.setVisibility(View.VISIBLE);
        } else {
            pickerNote.setVisibility(View.GONE);
        }
        pickerHint.setText(slot == Slot.MEETING
                ? DeviceProbe.meetingSummary(this)
                : "並べるアプリは 設定 → ホーム画面のアプリ で入れ替えられます");

        pickerScrim.setVisibility(View.VISIBLE);
    }

    private void closePicker() {
        if (pickerScrim != null) pickerScrim.setVisibility(View.GONE);
    }
}
