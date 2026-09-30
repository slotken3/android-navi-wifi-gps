package com.livraison.wifiwatchdog.home;

import android.content.Context;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.livraison.wifiwatchdog.R;

/** 画面をコードで組み立てるときの共通部品。 */
final class Ui {

    interface OnPick {
        void pick(int index);
    }

    private Ui() {
    }

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    /** 1辺64dp以上の大きなボタン(要件U2) */
    static TextView button(Context c, String text) {
        TextView b = new TextView(c, null, 0, R.style.BigButton);
        b.setText(text);
        return b;
    }

    /**
     * 横並びの選択ボタン。選んだものに枠と色が付く。
     * container の中身は作り直す。
     */
    static void segments(Context c, LinearLayout container, String[] labels, int selected,
                         OnPick onPick) {
        container.removeAllViews();
        container.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < labels.length; i++) {
            TextView b = button(c, labels[i]);
            b.setGravity(Gravity.CENTER);
            b.setPaddingRelative(dp(c, 8), 0, dp(c, 8), 0);
            b.setMaxLines(1);
            if (i == selected) {
                b.setBackgroundResource(R.drawable.bg_segment_on);
                b.setTextColor(c.getColor(R.color.accent_navi));
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, dp(c, 64), 1f);
            if (i > 0) lp.setMarginStart(dp(c, 8));
            final int index = i;
            b.setOnClickListener(v -> onPick.pick(index));
            container.addView(b, lp);
        }
    }
}
