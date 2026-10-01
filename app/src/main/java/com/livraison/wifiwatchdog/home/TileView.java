package com.livraison.wifiwatchdog.home;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;

import com.livraison.wifiwatchdog.R;

/**
 * ホーム画面のタイル。アイコン・名前・補足・進捗バーを持つ。
 * tileCompact=true のときは、下段用にアイコンと名前を横に並べる。
 *
 * 画面の密度が高くタイルが低いとき(240dpiで 1024×600 → 683×400dp など)は、
 * 丸いアイコンを外して名前の左に小さく出す「詰めた表示」に切り替える。
 * そうしないと、アイコンだけで高さを使い切って名前が見えなくなる。
 */
public class TileView extends LinearLayout {

    /** この高さ(dp)より低ければ詰めた表示にする */
    private static final int DENSE_BELOW_DP = 170;
    private static final int DENSE_BELOW_DP_LARGE = 230;

    private final ImageView icon;
    private final View iconBg;
    private final TextView title;
    private final TextView subtitle;
    private final ProgressBar progress;
    private final boolean large;
    private boolean dense;
    private int iconRes;
    private int accent;
    private CharSequence subtitleText;

    public TileView(Context context, AttributeSet attrs) {
        super(context, attrs);

        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.TileView);
        boolean compact = a.getBoolean(R.styleable.TileView_tileCompact, false);
        large = a.getBoolean(R.styleable.TileView_tileLarge, false);
        iconRes = a.getResourceId(R.styleable.TileView_tileIcon, 0);
        CharSequence titleText = a.getText(R.styleable.TileView_tileTitle);
        accent = a.getColor(R.styleable.TileView_tileAccent,
                context.getColor(R.color.accent_neutral));
        a.recycle();

        setOrientation(compact ? HORIZONTAL : VERTICAL);
        setGravity(compact ? Gravity.CENTER : Gravity.START);
        setBackgroundResource(R.drawable.bg_tile);
        int pad = Ui.dp(context, compact ? 8 : 18);
        setPadding(pad, pad, pad, pad);
        setMinimumHeight(Ui.dp(context, 64));
        setClickable(true);
        setFocusable(true);

        LayoutInflater.from(context).inflate(
                compact ? R.layout.view_tile_compact : R.layout.view_tile, this, true);
        icon = findViewById(R.id.tile_icon);
        iconBg = findViewById(R.id.tile_icon_bg);
        title = findViewById(R.id.tile_title);
        subtitle = findViewById(R.id.tile_subtitle);
        progress = findViewById(R.id.tile_progress);

        if (large && iconBg != null) {
            // ナビ用の大きいタイル
            ViewGroup.LayoutParams bg = iconBg.getLayoutParams();
            bg.width = bg.height = Ui.dp(context, 88);
            ViewGroup.LayoutParams ic = icon.getLayoutParams();
            ic.width = ic.height = Ui.dp(context, 52);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 36);
            subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        }

        if (iconRes != 0) icon.setImageResource(iconRes);
        title.setText(titleText);
        setContentDescription(titleText);
        setAccent(accent);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (iconBg != null && MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) {
            float heightDp = MeasureSpec.getSize(heightMeasureSpec)
                    / getResources().getDisplayMetrics().density;
            setDense(heightDp < (large ? DENSE_BELOW_DP_LARGE : DENSE_BELOW_DP));
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    private void setDense(boolean d) {
        if (d == dense) return;
        dense = d;
        iconBg.setVisibility(d ? GONE : VISIBLE);
        int pad = Ui.dp(getContext(), d ? 12 : 18);
        setPadding(pad, pad, pad, pad);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, large ? (d ? 30 : 36) : (d ? 21 : 24));
        if (subtitle != null) {
            subtitle.setMaxLines(d && !large ? 1 : 2);
            applySubtitle();
        }
        updateTitleIcon();
    }

    /** 詰めた表示のときだけ、名前の左にアイコンを出す */
    private void updateTitleIcon() {
        Drawable d = null;
        if (dense && iconRes != 0) {
            d = getContext().getDrawable(iconRes);
        }
        if (d != null) {
            d = d.mutate();
            int size = Ui.dp(getContext(), large ? 36 : 24);
            d.setBounds(0, 0, size, size);
            d.setTint(accent);
            title.setCompoundDrawablePadding(Ui.dp(getContext(), 8));
        }
        title.setCompoundDrawablesRelative(d, null, null, null);
    }

    public void setAccent(int color) {
        accent = color;
        icon.setImageTintList(ColorStateList.valueOf(color));
        if (iconBg != null) {
            iconBg.setBackgroundTintList(
                    ColorStateList.valueOf(ColorUtils.setAlphaComponent(color, 0x33)));
        }
        if (dense) updateTitleIcon();
    }

    public void setIcon(int res) {
        iconRes = res;
        icon.setImageResource(res);
        if (dense) updateTitleIcon();
    }

    public void setTitle(CharSequence text) {
        title.setText(text);
        setContentDescription(text);
    }

    public void setSubtitle(CharSequence text) {
        setSubtitle(text, getContext().getColor(R.color.text_secondary));
    }

    /** 補足の文字色を変える(アプリが見つからないときは注意の色にする) */
    public void setSubtitle(CharSequence text, int color) {
        if (subtitle == null) return;
        subtitleText = text;
        subtitle.setTextColor(color);
        applySubtitle();
    }

    /** 詰めた表示では1行しか出せないので、改行より前だけを出す */
    private void applySubtitle() {
        CharSequence text = subtitleText;
        if (dense && !large && text != null) {
            int nl = TextUtils.indexOf(text, '\n');
            if (nl >= 0) text = text.subSequence(0, nl);
        }
        subtitle.setText(text);
        subtitle.setVisibility(TextUtils.isEmpty(text) ? GONE : VISIBLE);
    }

    public void setProgress(int percent, int color) {
        if (progress == null) return;
        progress.setVisibility(VISIBLE);
        progress.setProgress(percent);
        progress.setProgressTintList(ColorStateList.valueOf(color));
    }
}
