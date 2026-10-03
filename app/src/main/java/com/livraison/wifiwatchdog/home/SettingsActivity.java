package com.livraison.wifiwatchdog.home;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.livraison.wifiwatchdog.MainActivity;
import com.livraison.wifiwatchdog.R;
import com.livraison.wifiwatchdog.SatelliteInfoActivity;
import com.livraison.wifiwatchdog.WifiMonitorService;

import java.util.List;
import java.util.Locale;

/**
 * 設定。左に分類、右に中身。中身はコードで組み立てる。
 * 走行中は「表示確認」と「このアプリ」以外を開けない(要件U5)。
 */
public class SettingsActivity extends BaseActivity {

    static final String EXTRA_CATEGORY = "category";

    private enum Category {
        APPS("apps", "ホーム画面のアプリ"),
        NETWORK("network", "通信"),
        DEVICES("devices", "会議の機材"),
        HOME_APP("home", "ホームアプリ"),
        SURVEY("survey", "調査(段階0)"),
        DEMO("demo", "表示確認"),
        ABOUT("about", "このアプリ");

        final String key;
        final String title;

        Category(String key, String title) {
            this.key = key;
            this.title = title;
        }
    }

    private static final String[] ROUTE_KEYS = {
            HomePrefs.ROUTE_REAL, HomePrefs.ROUTE_WIFI, HomePrefs.ROUTE_WIFI_NO_NET,
            HomePrefs.ROUTE_SIM, HomePrefs.ROUTE_NONE};
    private static final String[] ROUTE_LABELS = {"実際", "Wi-Fi", "Wi-Fi不通", "SIM", "通信なし"};
    private static final int[] USAGE_VALUES = {40, 85, 100};

    private LinearLayout nav;
    private LinearLayout content;
    private ScrollView scroll;
    private Category current = Category.APPS;
    /** 調査で読んだ一覧。画面を作り直しても残す */
    private DeviceProbe.ProtectedList protectedList;
    /** 解析用に保存した結果。画面を作り直しても残す */
    private DeviceProbe.Export lastExport;
    private static final int REQ_STORAGE = 300;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        setupAppBar("設定");
        nav = findViewById(R.id.settings_nav);
        content = findViewById(R.id.settings_content);
        scroll = findViewById(R.id.settings_scroll);

        for (Category c : Category.values()) {
            TextView item = new TextView(this);
            item.setText(c.title);
            item.setTextSize(17);
            item.setTextColor(color(R.color.text_primary));
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPaddingRelative(Ui.dp(this, 16), 0, Ui.dp(this, 12), 0);
            item.setBackgroundResource(R.drawable.bg_nav_item);
            item.setTag(c);
            item.setOnClickListener(v -> show(c));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 64));
            lp.bottomMargin = Ui.dp(this, 8);
            nav.addView(item, lp);
        }

        String key = getIntent().getStringExtra(EXTRA_CATEGORY);
        for (Category c : Category.values()) {
            if (c.key.equals(key)) current = c;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // アプリ一覧で選んで戻ってきたときなどに作り直す
        show(current);
    }

    private void show(Category c) {
        current = c;
        updateDemoBadge();
        for (int i = 0; i < nav.getChildCount(); i++) {
            View v = nav.getChildAt(i);
            v.setActivated(v.getTag() == c);
        }
        content.removeAllViews();
        scroll.scrollTo(0, 0);

        if (c != Category.DEMO && c != Category.ABOUT && HomePrefs.isDriving(this)) {
            addDrivingLock();
            return;
        }
        switch (c) {
            case APPS: buildApps(); break;
            case NETWORK: buildNetwork(); break;
            case DEVICES: buildDevices(); break;
            case HOME_APP: buildHomeApp(); break;
            case SURVEY: buildSurvey(); break;
            case DEMO: buildDemo(); break;
            case ABOUT: buildAbout(); break;
        }
    }

    private void rebuild() {
        int y = scroll.getScrollY();
        show(current);
        scroll.post(() -> scroll.scrollTo(0, y));
    }

    // ---------------------------------------------------------------
    // ホーム画面のアプリ(要件U1・U11)
    // ---------------------------------------------------------------
    private void buildApps() {
        addHeader("1タップで開くタイル");
        for (Slot s : new Slot[]{Slot.NAVI, Slot.CARPLAY, Slot.DASHCAM, Slot.RADIO, Slot.PHONE}) {
            List<String> pkgs = HomePrefs.apps(this, s);
            String pkg = AppCatalog.firstInstalled(this, pkgs);
            String value;
            if (pkg != null) value = AppCatalog.label(this, pkg);
            else if (pkgs.isEmpty()) value = "未設定";
            else value = "見つかりません";
            addRow(s.title, pkgs.isEmpty() ? null : TextUtils.join("、", pkgs),
                    value, v -> pickApp(s, false));
        }
        addNote("ホーム画面のタイルを長押ししても変更できます(停車中のみ)。"
                + "純正アプリ(com.syu.*)と CarPlay(ZLINK)の名前は推測です。実機で入っているものを選び直してください。");

        for (Slot s : new Slot[]{Slot.VIDEO, Slot.MUSIC, Slot.MEETING}) {
            addHeader(s.title + "(タイルを押すと選択パネルに並ぶ)");
            List<String> pkgs = HomePrefs.apps(this, s);
            for (String pkg : pkgs) {
                boolean installed = AppCatalog.isInstalled(this, pkg);
                addRow(AppCatalog.label(this, pkg),
                        installed ? pkg : pkg + " ・ 未インストール",
                        "外す", v -> confirmRemove(s, pkg));
            }
            addRow("+ アプリを追加", null, null, v -> pickApp(s, true));
        }

        addHeader("元に戻す");
        addButton("初期の割り当てに戻す", v -> new AlertDialog.Builder(this)
                .setMessage("タイルに割り当てたアプリを、すべて初期の状態に戻しますか?")
                .setPositiveButton("戻す", (d, w) -> {
                    HomePrefs.resetAllApps(this);
                    rebuild();
                })
                .setNegativeButton("やめる", null)
                .show());
    }

    private void pickApp(Slot slot, boolean add) {
        startActivity(new Intent(this, AppListActivity.class)
                .putExtra(AppListActivity.EXTRA_PICK_SLOT, slot.key)
                .putExtra(AppListActivity.EXTRA_ADD, add));
    }

    private void confirmRemove(Slot slot, String pkg) {
        new AlertDialog.Builder(this)
                .setMessage("「" + AppCatalog.label(this, pkg) + "」を" + slot.title + "から外しますか?")
                .setPositiveButton("外す", (d, w) -> {
                    List<String> list = HomePrefs.apps(this, slot);
                    list.remove(pkg);
                    HomePrefs.setApps(this, slot, list);
                    rebuild();
                })
                .setNegativeButton("やめる", null)
                .show();
    }

    // ---------------------------------------------------------------
    // 通信(要件N5)
    // ---------------------------------------------------------------
    private void buildNetwork() {
        addHeader("SIMの通信量");
        LinearLayout stepper = new LinearLayout(this);
        stepper.setGravity(Gravity.CENTER_VERTICAL);
        TextView minus = Ui.button(this, "−");
        TextView value = new TextView(this);
        TextView plus = Ui.button(this, "+");
        value.setText("月の上限 " + HomePrefs.simLimitGb(this) + " GB");
        value.setTextSize(22);
        value.setTextColor(color(R.color.text_primary));
        value.setGravity(Gravity.CENTER);
        minus.setOnClickListener(v -> {
            HomePrefs.setSimLimitGb(this, HomePrefs.simLimitGb(this) - 1);
            rebuild();
        });
        plus.setOnClickListener(v -> {
            HomePrefs.setSimLimitGb(this, HomePrefs.simLimitGb(this) + 1);
            rebuild();
        });
        int size = Ui.dp(this, 72);
        stepper.addView(minus, new LinearLayout.LayoutParams(size, size));
        stepper.addView(value, new LinearLayout.LayoutParams(0, size, 1f));
        stepper.addView(plus, new LinearLayout.LayoutParams(size, size));
        content.addView(stepper);
        addNote("上限の80%でホーム画面の接続タイルを黄色、100%で赤にします。"
                + "通信量の実測は段階1です(今はデモ値)。SIMのプランと予算は未確定。");

        addHeader("Wi-Fi監視");
        boolean running = ConnectionStatus.isMonitorRunning(this);
        addRow("監視の状態", "スリープ復帰後に止められていないかをここで確かめる(要件O3)",
                running ? "稼働中" : "停止中", null);
        // 「使用中のみ」だと、裏で動いている間はWi-Fiの名前を読めない(Android 10)
        addRow("位置情報の許可", "Wi-Fiの名前を読むのに必要。「常に許可」にする(タップでアプリ情報を開く)",
                WifiMonitorService.locationPermission(this), v -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                android.net.Uri.parse("package:" + getPackageName())));
                    } catch (ActivityNotFoundException e) {
                        Toast.makeText(this, "アプリ情報の画面を開けませんでした", Toast.LENGTH_SHORT).show();
                    }
                });
        addRow("Wi-Fi監視の設定を開く", "対象SSID・自動起動・バッテリー最適化の除外", null,
                v -> startActivity(new Intent(this, MainActivity.class)));
        addRow("接続状態と記録", "起動・スリープ・切断・復旧を自動で記録(N6)", null,
                v -> startActivity(new Intent(this, ConnectionActivity.class)));

        addHeader("これから追加するもの");
        addNote("・Wi-Fiを優先し、使えないときはSIMへ切り替わるかの確認(N2・N3)\n"
                + "・SIMの通信量の実測(N5)\n"
                + "・走行中の判定(GPSの速度。U5)");
    }

    // ---------------------------------------------------------------
    // 会議の機材(要件U12)
    // ---------------------------------------------------------------
    private void buildDevices() {
        addButton("もう一度調べる", v -> rebuild());

        addHeader("USBにつないだ機器");
        List<DeviceProbe.UsbInfo> usb = DeviceProbe.usbDevices(this);
        if (usb.isEmpty()) {
            addNote("USB機器が見つかりません。ナビ側の仕組みによっては、つないでいても一覧に出ないことがあります。");
        }
        for (DeviceProbe.UsbInfo u : usb) {
            addRow(u.name, u.kind, u.camera ? "カメラ" : (u.audio ? "音声" : null), null);
        }

        addHeader("Androidが認識しているカメラ");
        addMono(TextUtils.join("\n", DeviceProbe.cameras(this)));
        addNote("「外付け(USB)」が出れば、会議アプリから選べる見込みです。"
                + "ドラレコもUSBカメラなので、ここに出ることがあります。同時につないだときの動作とUSB端子の数も確かめてください。");

        addHeader("Androidが認識しているマイク");
        addMono(TextUtils.join("\n", DeviceProbe.audioInputs(this)));

        addHeader("走行中の扱い");
        addNote("走行中はカメラを使わず、音声だけにします(会議のタイルと選択パネルに表示)。"
                + "カメラを自動で止める仕組みは段階1で検討します。");
    }

    // ---------------------------------------------------------------
    // ホームアプリ(要件U6・U10、可逆性)
    // ---------------------------------------------------------------
    private ComponentName homeAlias() {
        // manifest の activity-alias。初期状態は無効
        return new ComponentName(this, HomeActivity.class.getPackage().getName() + ".HomeAlias");
    }

    private boolean isHomeAliasEnabled() {
        return getPackageManager().getComponentEnabledSetting(homeAlias())
                == PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
    }

    private void buildHomeApp() {
        boolean enabled = isHomeAliasEnabled();
        addHeader("既定のホーム");
        addRow("ナビホームをホームの候補にする",
                "有効にしたあと、ホームボタンで「ナビホーム」を選び「常時」を押します",
                enabled ? "有効" : "無効", v -> {
                    getPackageManager().setComponentEnabledSetting(homeAlias(),
                            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                            PackageManager.DONT_KILL_APP);
                    openHomeChooser();
                    rebuild();
                });
        addRow("純正のホームに戻す",
                "ナビホームをホームの候補から外します。すぐに純正の画面に戻ります",
                null, v -> {
                    getPackageManager().setComponentEnabledSetting(homeAlias(),
                            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                            PackageManager.DONT_KILL_APP);
                    Toast.makeText(this, "ホームの候補から外しました", Toast.LENGTH_SHORT).show();
                    rebuild();
                });
        addNote("インストールした直後からホームの候補にすると、ホームボタンを押したときに選択画面が出て、"
                + "走行中に操作を求めることになります。そのため、ここで明示的に有効にする形にしています。"
                + "FYT機でホームの切替がそもそも効くかは、実機で確かめる必要があります。");

        addHeader("全画面とホームボタン(段階1)");
        addRow("アプリを全画面で開く", "ステータスバーと下のメニューを出さない(U10)", "段階1", null);
        addRow("画面の隅の小さなホームボタン",
                "他のアプリの上に重ねる許可が要る。実機で要確認(U10)", "段階1", null);
    }

    private void openHomeChooser() {
        try {
            startActivity(new Intent(Settings.ACTION_HOME_SETTINGS));
        } catch (ActivityNotFoundException e) {
            // 設定画面が無い機種では、ホームを開いて選択画面を出す
            startActivity(new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }

    // ---------------------------------------------------------------
    // 調査(段階0)
    // ---------------------------------------------------------------
    private void buildSurvey() {
        addHeader("画面(タイルの大きさの確認用)");
        addRow("画面の実寸", null, DeviceProbe.screenSummary(this), null);
        TextView area = addRow("アプリが使える領域",
                "ステータスバーなどを除いた広さ。タイルの大きさはこれで決まる", "計測中", null);
        content.post(() -> {
            View root = findViewById(android.R.id.content);
            float d = getResources().getDisplayMetrics().density;
            area.setText(String.format(Locale.JAPAN, "%d×%d dp",
                    Math.round(root.getWidth() / d), Math.round(root.getHeight() / d)));
        });

        addHeader("スリープで止めないアプリの一覧(protected_app.txt・要件O1)");
        addNote(DeviceProbe.FYT_SERVICE_PKG + " のapkの中から一覧を読みます。読むだけで、何も書き換えません。");
        addButton(protectedList == null ? "一覧を読み取る" : "もう一度読み取る", v -> {
            Toast.makeText(this, "読み取っています…", Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                DeviceProbe.ProtectedList r = DeviceProbe.readProtectedList(this);
                runOnUiThread(() -> {
                    protectedList = r;
                    if (current == Category.SURVEY) rebuild();
                });
            }).start();
        });
        if (protectedList != null) showProtectedList(protectedList);

        addHeader("解析用に保存(フェーズ0 作業1)");
        addNote("ナビに入っている " + DeviceProbe.FYT_SERVICE_PKG + " の apk を、内部ストレージの "
                + "Download/navi-analysis に写します。USBメモリでPCへ移すと、一覧の判定方法"
                + "(前方一致か)と車両情報の仕組みを調べられます。元のアプリは変えません。"
                + "ファームの同じアプリはパスワード付きで取り出せないため、この方法を使います。");
        addButton("com.syu.ms を保存する", v -> exportForAnalysis());
        if (lastExport != null) showExport(lastExport);

        addHeader("衛星");
        addRow("衛星情報を見る(みちびき確認)", null, null,
                v -> startActivity(new Intent(this, SatelliteInfoActivity.class)));
    }

    private void exportForAnalysis() {
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    REQ_STORAGE);
            return;
        }
        Toast.makeText(this, "保存しています…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            DeviceProbe.Export r = DeviceProbe.exportApk(this, DeviceProbe.FYT_SERVICE_PKG);
            runOnUiThread(() -> {
                lastExport = r;
                if (current == Category.SURVEY) rebuild();
            });
        }).start();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_STORAGE) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            exportForAnalysis();
        } else {
            Toast.makeText(this, "ストレージの許可がないため保存できません", Toast.LENGTH_LONG).show();
        }
    }

    private void showExport(DeviceProbe.Export r) {
        if (r.error != null) {
            addNote(r.error, R.color.warn);
            return;
        }
        addRow("保存先", null, r.path, null);
        addRow("大きさ", "ファームの一覧では 7,128,768 バイト(同じなら同じ版の見込み)",
                String.format(Locale.JAPAN, "%,d バイト", r.size), null);
        addMono("SHA-256\n" + r.sha256);
    }

    private void showProtectedList(DeviceProbe.ProtectedList r) {
        if (r.error != null) addNote(r.error, R.color.warn);
        if (!r.otherCandidates.isEmpty()) {
            addNote("名前に protect を含むほかのファイル:\n" + TextUtils.join("\n", r.otherCandidates));
        }
        if (r.source == null) return;

        addRow("読み取った件数(重複を除く)", r.source, r.entries.size() + "件", null);
        addRow("com.tiantian.ttclock", "みんカラの記事で使われた名前",
                r.entries.contains("com.tiantian.ttclock") ? "あり" : "なし", null);
        String match = DeviceProbe.prefixMatch(r.entries, getPackageName());
        addRow("このアプリ(" + getPackageName() + ")",
                "一覧の名前で始まっていれば、スリープで止められない見込み",
                match != null ? "該当:" + match : "該当なし", null);
        addNote("前方一致で判定しているかは未確認です(O1)。該当なしなら、"
                + "段階1でパッケージ名を一覧の名前で始まるものに変えた版を作ります(O2)。");

        // 純正機能のアプリ(ドラレコなど)がスリープで止められる側かを見る(要件定義書11章)
        addHeader("タイルに割り当てたアプリ");
        for (Slot s : Slot.values()) {
            String pkg = AppCatalog.firstInstalled(this, HomePrefs.apps(this, s));
            if (pkg == null) continue;
            boolean listed = DeviceProbe.prefixMatch(r.entries, pkg) != null;
            addRow(s.title + ":" + AppCatalog.label(this, pkg), pkg,
                    listed ? "一覧にある" : "一覧に無い", null);
        }
        addNote("「一覧に無い」アプリは、スリープで止められる見込みです。");

        addHeader("一覧の全件(# 以降のコメントは除いて表示)");
        addMono(TextUtils.join("\n", r.entries));
    }

    // ---------------------------------------------------------------
    // 表示確認(デモ)
    // ---------------------------------------------------------------
    private void buildDemo() {
        addNote("段階0で未実装の部分や、実車でしか起きない状態の見た目を確かめるためのものです。"
                + "ここで変えたものは、ホーム画面に「デモ表示中」と出ます。");

        addHeader("接続の表示");
        LinearLayout route = addContainer();
        int routeIndex = 0;
        for (int i = 0; i < ROUTE_KEYS.length; i++) {
            if (ROUTE_KEYS[i].equals(HomePrefs.demoRoute(this))) routeIndex = i;
        }
        Ui.segments(this, route, ROUTE_LABELS, routeIndex, i -> {
            HomePrefs.setDemoRoute(this, ROUTE_KEYS[i]);
            rebuild();
        });

        addHeader("走行中の表示(要件U5の確認)");
        LinearLayout driving = addContainer();
        Ui.segments(this, driving, new String[]{"停車中", "走行中"},
                HomePrefs.isDriving(this) ? 1 : 0, i -> {
                    HomePrefs.setDemoDriving(this, i == 1);
                    rebuild();
                });
        addNote("走行中は、設定の変更とタイルの長押しを止め、会議は音声のみの表示になります。"
                + "実際の走行判定(GPSの速度)は段階1です。");

        addHeader("SIMの通信量(デモ値)");
        LinearLayout usage = addContainer();
        int usageIndex = -1;
        String[] usageLabels = new String[USAGE_VALUES.length];
        for (int i = 0; i < USAGE_VALUES.length; i++) {
            usageLabels[i] = USAGE_VALUES[i] + "%";
            if (USAGE_VALUES[i] == HomePrefs.demoUsagePercent(this)) usageIndex = i;
        }
        Ui.segments(this, usage, usageLabels, usageIndex, i -> {
            HomePrefs.setDemoUsagePercent(this, USAGE_VALUES[i]);
            rebuild();
        });

        addHeader("元に戻す");
        addButton("表示確認をすべて解除", v -> {
            HomePrefs.clearDemo(this);
            rebuild();
        });
    }

    // ---------------------------------------------------------------
    // このアプリ
    // ---------------------------------------------------------------
    @SuppressWarnings("deprecation")
    private void buildAbout() {
        String version = "?";
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            version = pi.versionName + "(" + pi.versionCode + ")";
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        addRow("バージョン", null, version, null);
        addRow("パッケージ名", null, getPackageName(), null);
        addNote("実際に動くもの:アプリの起動、接続経路と通信できるかの表示、Wi-Fi監視の稼働表示、"
                + "起動・スリープ・切断・復旧の記録、USB機器・カメラ・マイクの一覧、"
                + "protected_app.txt の読み取り。\n"
                + "デモ値のもの:SIMの通信量、走行中の判定。");
    }

    // ---------------------------------------------------------------
    // 部品
    // ---------------------------------------------------------------
    private void addDrivingLock() {
        LinearLayout box = new LinearLayout(this, null, 0, R.style.Card);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = Ui.dp(this, 32);
        box.setPadding(pad, pad, pad, pad);

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_car);
        icon.setImageTintList(android.content.res.ColorStateList.valueOf(color(R.color.warn)));
        box.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 64), Ui.dp(this, 64)));

        TextView title = new TextView(this);
        title.setText("走行中は設定を変更できません");
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(color(R.color.text_primary));
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 16);
        box.addView(title, lp);

        TextView body = new TextView(this);
        body.setText("停車してから操作してください。\n(段階0では「表示確認」で停車中に戻せます)");
        body.setTextSize(17);
        body.setTextColor(color(R.color.text_secondary));
        body.setGravity(Gravity.CENTER);
        box.addView(body);

        content.addView(box);
    }

    private void addHeader(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(15);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(color(R.color.text_secondary));
        t.setPadding(Ui.dp(this, 4), Ui.dp(this, content.getChildCount() == 0 ? 0 : 18),
                0, Ui.dp(this, 8));
        content.addView(t);
    }

    private void addNote(String text) {
        addNote(text, R.color.text_secondary);
    }

    private void addNote(String text, int colorRes) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(15);
        t.setTextColor(color(colorRes));
        t.setLineSpacing(0, 1.15f);
        t.setPadding(Ui.dp(this, 4), Ui.dp(this, 2), Ui.dp(this, 4), Ui.dp(this, 8));
        content.addView(t);
    }

    private void addMono(String text) {
        TextView t = new TextView(this, null, 0, R.style.Card);
        t.setText(text);
        t.setTextSize(14);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextColor(color(R.color.text_primary));
        t.setTextIsSelectable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(this, 8);
        content.addView(t, lp);
    }

    /** 戻り値は右側の値の表示(あとから書き換えるとき用) */
    private TextView addRow(String title, String summary, String value,
                            View.OnClickListener click) {
        View row = getLayoutInflater().inflate(R.layout.item_setting_row, content, false);
        ((TextView) row.findViewById(R.id.row_title)).setText(title);
        TextView sum = row.findViewById(R.id.row_summary);
        if (!TextUtils.isEmpty(summary)) {
            sum.setText(summary);
            sum.setVisibility(View.VISIBLE);
        }
        TextView valueView = row.findViewById(R.id.row_value);
        valueView.setText(value);
        if (click != null) {
            row.setOnClickListener(click);
            row.findViewById(R.id.row_chevron).setVisibility(View.VISIBLE);
        } else {
            row.setClickable(false);
            row.setBackgroundResource(R.drawable.bg_card);
        }
        content.addView(row);
        return valueView;
    }

    private void addButton(String text, View.OnClickListener click) {
        TextView b = Ui.button(this, text);
        b.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 64));
        lp.bottomMargin = Ui.dp(this, 8);
        content.addView(b, lp);
    }

    private LinearLayout addContainer() {
        LinearLayout l = new LinearLayout(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(this, 8);
        content.addView(l, lp);
        return l;
    }
}
