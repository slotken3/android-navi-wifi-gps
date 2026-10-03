package com.livraison.wifiwatchdog.home;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.ImageView;

import com.livraison.wifiwatchdog.R;

/**
 * 他のアプリの上に重ねる、小さな丸いホームボタン(要件U10・フェーズ0指示書 3-6)。
 * 直径72、左下。押すとナビホームに戻る。
 *
 * 自分の画面(ナビホームや設定)が見えている間は隠し、他のアプリに切り替わったら出す
 * (BaseActivity の onResume/onPause から hide/show)。
 * 「他のアプリの上に重ねて表示」の許可と、設定 → ホームアプリ での有効化が要る。
 */
public class OverlayButtonService extends Service {

    private static final String ACTION_SHOW = "show";
    private static final String ACTION_HIDE = "hide";
    private static final int SIZE_DP = 72;
    private static final int MARGIN_DP = 16;

    private ImageView button;

    /** 有効で、許可もあるか */
    static boolean active(Context c) {
        return HomePrefs.overlayEnabled(c) && Settings.canDrawOverlays(c);
    }

    static void show(Context c) {
        if (!active(c)) return;
        send(c, ACTION_SHOW);
    }

    static void hide(Context c) {
        if (!HomePrefs.overlayEnabled(c)) return;
        send(c, ACTION_HIDE);
    }

    private static void send(Context c, String action) {
        try {
            c.startService(new Intent(c, OverlayButtonService.class).setAction(action));
        } catch (IllegalStateException ignored) {
            // 裏にいてサービスを起こせないとき。次に画面が切り替わったときにやり直す
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_SHOW.equals(action) && active(this)) {
            addButton();
        } else {
            removeButton();
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void addButton() {
        if (button != null) return;
        WindowManager wm = getSystemService(WindowManager.class);
        if (wm == null) return;
        button = new ImageView(this);
        button.setImageResource(R.drawable.ic_home);
        button.setImageTintList(ColorStateList.valueOf(getColor(R.color.on_accent)));
        button.setBackgroundResource(R.drawable.bg_circle);
        button.setBackgroundTintList(ColorStateList.valueOf(getColor(R.color.accent)));
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setAlpha(0.9f);
        button.setContentDescription("ナビホームに戻る");
        button.setOnClickListener(v -> startActivity(new Intent(this, HomeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));

        int size = Ui.dp(this, SIZE_DP);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                size, size,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM | Gravity.START;
        lp.x = Ui.dp(this, MARGIN_DP);
        lp.y = Ui.dp(this, MARGIN_DP);
        try {
            wm.addView(button, lp);
        } catch (RuntimeException e) {
            button = null;
        }
    }

    private void removeButton() {
        if (button == null) return;
        WindowManager wm = getSystemService(WindowManager.class);
        try {
            if (wm != null) wm.removeView(button);
        } catch (RuntimeException ignored) {
        }
        button = null;
    }

    @Override
    public void onDestroy() {
        removeButton();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
