package com.livraison.wifiwatchdog.home;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.location.Location;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.livraison.wifiwatchdog.BuildConfig;
import com.livraison.wifiwatchdog.Prefs;
import com.livraison.wifiwatchdog.R;
import com.livraison.wifiwatchdog.WifiMonitorService;

import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider;
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * ホーム画面(要件U1〜U6・U13、フェーズ0指示書 6章「案A」)。
 *
 * 既定のホームにするかどうかは、設定 → ホームアプリ で切り替える。
 * 初期状態ではふつうのアプリとして開くだけ。
 */
public class HomeActivity extends BaseActivity {

    private static final long REFRESH_MS = 3000L;
    private static final String GOOGLE_MAPS = "com.google.android.apps.maps";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("M月d日(E)", Locale.JAPAN);

    private TileView tileVideo;
    private TileView tileMusic;
    private TileView tilePhone;
    private TileView tileMeeting;
    private TileView tileCarplay;
    private TileView tileDashcam;
    private TileView dockSettings;
    private TextView dateView;
    private View demoBadge;
    private View drivingBadge;
    private TextView gpsBadge;
    private TextView monitorBadge;
    private View connBadge;
    private View connDot;
    private TextView connText;
    private TextView npTitle;
    private TextView npArtist;
    private ImageButton npPlay;

    private MapView map;
    private MyLocationNewOverlay myLocation;

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

        tileVideo = findViewById(R.id.tile_video);
        tileMusic = findViewById(R.id.tile_music);
        tilePhone = findViewById(R.id.tile_phone);
        tileMeeting = findViewById(R.id.tile_meeting);
        tileCarplay = findViewById(R.id.tile_carplay);
        tileDashcam = findViewById(R.id.tile_dashcam);
        dockSettings = findViewById(R.id.dock_settings);
        dateView = findViewById(R.id.date);
        demoBadge = findViewById(R.id.demo_badge);
        drivingBadge = findViewById(R.id.driving_badge);
        gpsBadge = findViewById(R.id.gps_badge);
        monitorBadge = findViewById(R.id.monitor_badge);
        connBadge = findViewById(R.id.conn_badge);
        connDot = findViewById(R.id.conn_dot);
        connText = findViewById(R.id.conn_text);
        npTitle = findViewById(R.id.np_title);
        npArtist = findViewById(R.id.np_artist);
        npPlay = findViewById(R.id.np_play);
        pickerScrim = findViewById(R.id.picker_scrim);
        pickerTitle = findViewById(R.id.picker_title);
        pickerNote = findViewById(R.id.picker_note);
        pickerHint = findViewById(R.id.picker_hint);
        pickerItems = findViewById(R.id.picker_items);

        bindSlot(tileVideo, Slot.VIDEO);
        bindSlot(tileMusic, Slot.MUSIC);
        bindSlot(tilePhone, Slot.PHONE);
        bindSlot(tileMeeting, Slot.MEETING);
        bindSlot(tileCarplay, Slot.CARPLAY);
        bindSlot(tileDashcam, Slot.DASHCAM);
        bindSlot(findViewById(R.id.dock_radio), Slot.RADIO);

        View.OnClickListener openConnection =
                v -> startActivity(new Intent(this, ConnectionActivity.class));
        connBadge.setOnClickListener(openConnection);
        monitorBadge.setOnClickListener(openConnection);
        findViewById(R.id.dock_home).setOnClickListener(v -> {
            closePicker();
            recenterMap();
        });
        findViewById(R.id.dock_apps).setOnClickListener(
                v -> startActivity(new Intent(this, AppListActivity.class)));
        dockSettings.setOnClickListener(v -> {
            // 走行中は設定を開かない(要件U5)
            if (HomePrefs.isDriving(this)) {
                toast("設定は停車中に変更できます");
                return;
            }
            startActivity(new Intent(this, SettingsActivity.class));
        });

        findViewById(R.id.btn_go_home).setOnClickListener(v -> navigateHome());
        findViewById(R.id.btn_search).setOnClickListener(v -> searchDestination());
        findViewById(R.id.np_prev).setOnClickListener(v -> NowPlaying.previous(this));
        npPlay.setOnClickListener(v -> NowPlaying.playPause(this));
        findViewById(R.id.np_next).setOnClickListener(v -> NowPlaying.next(this));
        findViewById(R.id.np_info).setOnClickListener(v -> {
            if (!NowPlaying.permitted(this)) openNotificationAccess();
        });
        findViewById(R.id.zoom_in).setOnClickListener(v -> {
            if (map != null) map.getController().zoomIn();
        });
        findViewById(R.id.zoom_out).setOnClickListener(v -> {
            if (map != null) map.getController().zoomOut();
        });

        pickerScrim.setOnClickListener(v -> closePicker());
        findViewById(R.id.picker_close).setOnClickListener(v -> closePicker());

        setupMap();

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
        // 地図はホームが見えている間だけ描き、位置を更新する(全画面アプリの間は止める)
        if (map != null) {
            map.onResume();
            if (myLocation != null) myLocation.enableMyLocation();
        }
        handler.post(refresher);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(refresher);
        if (map != null) {
            saveLastPosition();
            if (myLocation != null) myLocation.disableMyLocation();
            map.onPause();
        }
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
    // 地図(要件U13)
    // ---------------------------------------------------------------
    private void setupMap() {
        FrameLayout frame = findViewById(R.id.map_frame);
        try {
            map = MapSetup.create(this, HomePrefs.mapOffline(this) && BuildConfig.DEBUG);
        } catch (RuntimeException e) {
            // 地図が使えなくても、ホームのほかの部分は使えるようにする
            ((TextView) findViewById(R.id.map_placeholder)).setText("地図を表示できません");
            return;
        }
        frame.addView(map, 0, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        findViewById(R.id.map_placeholder).setVisibility(View.GONE);

        // 起動直後は、測位できるまで前回の位置を出す
        double[] last = HomePrefs.lastPosition(this);
        if (last != null) map.getController().setCenter(new GeoPoint(last[0], last[1]));

        myLocation = new MyLocationNewOverlay(new GpsMyLocationProvider(this), map);
        myLocation.enableFollowLocation();
        map.getOverlays().add(myLocation);

        // 地図をタップするとナビアプリを開く。ドラッグで地図を動かすことはしない(走行中の誤操作を防ぐ)
        GestureDetector tap = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                openSlot(Slot.NAVI);
                return true;
            }
        });
        map.setOnTouchListener((v, e) -> {
            tap.onTouchEvent(e);
            return true;
        });
    }

    private void recenterMap() {
        if (myLocation == null) return;
        myLocation.enableFollowLocation();
        GeoPoint p = myLocation.getMyLocation();
        if (p != null) map.getController().animateTo(p);
    }

    private void saveLastPosition() {
        GeoPoint p = myLocation != null ? myLocation.getMyLocation() : null;
        if (p != null) {
            HomePrefs.setLastPosition(this, p.getLatitude(), p.getLongitude());
            return;
        }
        Location l = Driving.lastLocation();
        if (l != null) HomePrefs.setLastPosition(this, l.getLatitude(), l.getLongitude());
    }

    // ---------------------------------------------------------------
    // 自宅へ・目的地を検索(Google マップに頼む)
    // ---------------------------------------------------------------
    private void navigateHome() {
        String home = HomePrefs.homeAddress(this);
        // 住所が未設定なら「自宅」で頼む(Google マップに登録した自宅を使う見込み。実機で要確認)
        Uri uri = Uri.parse("google.navigation:q=" + Uri.encode(TextUtils.isEmpty(home) ? "自宅" : home));
        startMaps(uri);
    }

    private void searchDestination() {
        startMaps(Uri.parse("geo:0,0?q="));
    }

    private void startMaps(Uri uri) {
        Intent i = new Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (AppCatalog.isInstalled(this, GOOGLE_MAPS)) i.setPackage(GOOGLE_MAPS);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            toast("ナビアプリを開けませんでした");
        }
    }

    private void openNotificationAccess() {
        try {
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        } catch (ActivityNotFoundException e) {
            toast("通知へのアクセスの設定画面がありません");
        }
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
        // 走行中は動画を開かない(要件U5)
        if (slot == Slot.VIDEO && HomePrefs.isDriving(this)) {
            toast("動画は停車中に見られます");
            return;
        }
        if (slot.group) {
            List<String> installed = AppCatalog.installedOnly(this, HomePrefs.apps(this, slot));
            // 入っているのが1つだけなら、選ばせずにそのまま開く
            if (installed.size() == 1) {
                AppCatalog.launch(this, installed.get(0));
            } else {
                openPicker(slot);
            }
            return;
        }
        openSlot(slot);
    }

    private void openSlot(Slot slot) {
        String pkg = AppCatalog.firstInstalled(this, HomePrefs.apps(this, slot));
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
            toast("走行中は変更できません。停車してから操作してください");
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
        boolean driving = HomePrefs.isDriving(this);
        drivingBadge.setVisibility(driving ? View.VISIBLE : View.GONE);
        boolean fix = Driving.hasFix();
        gpsBadge.setTextColor(color(fix ? R.color.ok : R.color.text_disabled));

        boolean running = ConnectionStatus.isMonitorRunning(this);
        monitorBadge.setText(running ? "監視" : "監視停止");
        monitorBadge.setTextColor(color(running ? R.color.text_secondary : R.color.bg));
        monitorBadge.setBackgroundTintList(ColorStateList.valueOf(
                color(running ? R.color.surface_high : R.color.ng)));

        updateConnectionBadge();

        updateGroupTile(tileVideo, Slot.VIDEO);
        tileVideo.setLocked(driving);
        if (driving) tileVideo.setSubtitle("停車中に見られます", color(R.color.warn));
        updateGroupTile(tileMusic, Slot.MUSIC);
        updateSingleTile(tilePhone, Slot.PHONE);
        updateGroupTile(tileMeeting, Slot.MEETING);
        if (driving) {
            // 走行中はカメラを使わず音声のみ(要件U12)
            tileMeeting.setSubtitle("走行中は音声のみ", color(R.color.warn));
        }
        updateSingleTile(tileCarplay, Slot.CARPLAY);
        updateSingleTile(tileDashcam, Slot.DASHCAM);
        dockSettings.setLocked(driving);

        updateNowPlaying();
    }

    /** 接続のバッジ(要件N7): Wi-Fi は緑、SIM は橙、つながっているが通信できないときは灰、なしは赤 */
    private void updateConnectionBadge() {
        ConnectionStatus s = ConnectionStatus.read(this);
        int bg;
        String text;
        if (s.route == ConnectionStatus.Route.NONE) {
            bg = R.color.ng;
            text = "接続なし";
        } else if (!s.online) {
            bg = R.color.text_disabled;
            text = "接続中";
        } else if (s.route == ConnectionStatus.Route.SIM) {
            bg = R.color.warn;
            text = "SIM";
        } else {
            bg = R.color.ok;
            text = s.routeName();
        }
        connBadge.setBackgroundTintList(ColorStateList.valueOf(color(bg)));
        connDot.setBackgroundTintList(ColorStateList.valueOf(color(R.color.bg)));
        connText.setText(text);
    }

    private void updateNowPlaying() {
        NowPlaying n = NowPlaying.read(this);
        if (!NowPlaying.permitted(this)) {
            npTitle.setText("再生中の曲");
            npArtist.setText("曲名は「通知へのアクセス」を許可すると出ます");
        } else if (n == null || TextUtils.isEmpty(n.title)) {
            npTitle.setText("再生していません");
            npArtist.setText("");
        } else {
            npTitle.setText(n.title);
            npArtist.setText(n.artist == null ? "" : n.artist);
        }
        npPlay.setImageResource(n != null && n.playing ? R.drawable.ic_pause : R.drawable.ic_play);
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

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
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
