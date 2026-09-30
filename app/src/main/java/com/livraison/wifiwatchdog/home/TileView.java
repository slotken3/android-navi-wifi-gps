package com.livraison.wifiwatchdog.home;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
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
 */
public class TileView extends LinearLayout {

    private final ImageView icon;
    private final View iconBg;
    private final TextView title;
    private final TextView subtitle;
    private final ProgressBar progress;

    public TileView(Context context, AttributeSet attrs) {
        super(context, attrs);

        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.TileView);
        boolean compact = a.getBoolean(R.styleable.TileView_tileCompact, false);
        boolean large = a.getBoolean(R.styleable.TileView_tileLarge, false);
        int iconRes = a.getResourceId(R.styleable.TileView_tileIcon, 0);
        CharSequence titleText = a.getText(R.styleable.TileView_tileTitle);
        int accent = a.getColor(R.styleable.TileView_tileAccent,
                context.getColor(R.color.accent_neutral));
        a.recycle();

        setOrientation(compact ? HORIZONTAL : VERTICAL);
        setGravity(compact ? Gravity.CENTER : Gravity.START);
        setBackgroundResource(R.drawable.bg_tile);
        int pad = Ui.dp(context, compact ? 12 : 18);
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

    public void setAccent(int color) {
        icon.setImageTintList(ColorStateList.valueOf(color));
        if (iconBg != null) {
            iconBg.setBackgroundTintList(
                    ColorStateList.valueOf(ColorUtils.setAlphaComponent(color, 0x33)));
        }
    }

    public void setIcon(int res) {
        icon.setImageResource(res);
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
        subtitle.setText(text);
        subtitle.setTextColor(color);
        subtitle.setVisibility(TextUtils.isEmpty(text) ? GONE : VISIBLE);
    }

    public void setProgress(int percent, int color) {
        if (progress == null) return;
        progress.setVisibility(VISIBLE);
        progress.setProgress(percent);
        progress.setProgressTintList(ColorStateList.valueOf(color));
    }
}
