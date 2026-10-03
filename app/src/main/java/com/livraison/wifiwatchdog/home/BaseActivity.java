package com.livraison.wifiwatchdog.home;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.livraison.wifiwatchdog.BuildConfig;
import com.livraison.wifiwatchdog.R;

import java.util.ArrayList;
import java.util.Arrays;

/** ホーム画面から開く画面の共通部分。 */
abstract class BaseActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        applyDebugExtras(getIntent());
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 走行中の判定(GPSの速度)。画面が見えている間だけ使う
        Driving.start(this);
        // 自分の画面が見えている間は、重ねるホームボタンを隠す
        OverlayButtonService.hide(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        Driving.stop(this);
        // 他のアプリに切り替わったら、重ねるホームボタンを出す(要件U10)
        OverlayButtonService.show(this);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        applyDebugExtras(intent);
    }

    /**
     * CIの画面確認(scripts/ui-screenshots.sh)から表示状態を切り替える入口。
     * デバッグ版だけで受け付ける。
     */
    private void applyDebugExtras(Intent i) {
        if (!BuildConfig.DEBUG || i == null) return;
        if (i.hasExtra("demo_route")) HomePrefs.setDemoRoute(this, i.getStringExtra("demo_route"));
        if (i.hasExtra("demo_driving")) {
            HomePrefs.setDemoDriving(this, i.getBooleanExtra("demo_driving", false));
        }
        if (i.hasExtra("demo_usage")) {
            HomePrefs.setDemoUsagePercent(this,
                    i.getIntExtra("demo_usage", -1));
        }
        if (i.hasExtra("overlay_enabled")) {
            HomePrefs.setOverlayEnabled(this, i.getBooleanExtra("overlay_enabled", false));
        }
        if (i.hasExtra("map_offline")) {
            HomePrefs.setMapOffline(this, i.getBooleanExtra("map_offline", false));
        }
        // タイルの割り当て(例: --es slot_video com.android.gallery3d,com.android.deskclock)。
        // エミュレーターに入っているアプリで、補足が2行になる状態を作るため
        for (Slot s : Slot.values()) {
            String v = i.getStringExtra("slot_" + s.key);
            if (v != null) HomePrefs.setApps(this, s, new ArrayList<>(Arrays.asList(v.split(","))));
        }
    }

    /** 上部のバー(戻る + 見出し)。layout に view_app_bar を include しておく */
    protected void setupAppBar(String title) {
        ((TextView) findViewById(R.id.app_bar_title)).setText(title);
        findViewById(R.id.app_bar_back).setOnClickListener(v -> finish());
    }

    /** 表示確認(デモ)中なら上部のバーに印を出す */
    protected void updateDemoBadge() {
        View badge = findViewById(R.id.app_bar_badge);
        if (badge != null) {
            badge.setVisibility(HomePrefs.isDemoActive(this) ? View.VISIBLE : View.GONE);
        }
    }

    protected int color(int res) {
        return getColor(res);
    }
}
