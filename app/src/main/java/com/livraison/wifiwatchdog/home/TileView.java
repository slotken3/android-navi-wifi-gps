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
 * タイルの高さは機種のステータスバーや画面密度で変わる(実機はエミュレーターより低かった)。
 * そこで、実際に測って中身が収まらなければ、次の順に詰めていく。
 *   普通 → やや詰める(アイコンを小さく) → 詰める(丸いアイコンを外し、名前の左に小さく出す)
 * 決めた高さのしきい値で切り替える方式は、実機で補足の2行目が切れたのでやめた。
 */
public class TileView extends LinearLayout {

    private static final int MODE_NORMAL = 0;
    private static final int MODE_MEDIUM = 1;
    private static final int MODE_DENSE = 2;

    private final ImageView icon;
    private final View iconBg;
    private final View spacer;
    private final TextView title;
    private final TextView subtitle;
    private final ProgressBar progress;
    private final boolean large;
    private int mode = -1;
    private int iconRes;
    private int accent;
    private CharSequence subtitleText;
    private boolean locked;

    // 前回どの条件で詰め方を決めたか。同じなら決め直さない(毎回決め直すと再描画が止まらない)
    private int fitWidth = -1;
    private int fitHeight = -1;
    private CharSequence fitSubtitle;
    private boolean fitProgress;

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
        spacer = findViewById(R.id.tile_spacer);
        title = findViewById(R.id.tile_title);
        subtitle = findViewById(R.id.tile_subtitle);
        progress = findViewById(R.id.tile_progress);

        if (large && subtitle != null) subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        if (iconRes != 0) icon.setImageResource(iconRes);
        title.setText(titleText);
        setContentDescription(titleText);
        setAccent(accent);
        if (iconBg != null) applyMode(MODE_NORMAL);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (iconBg == null || MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.EXACTLY) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            return;
        }
        int w = MeasureSpec.getSize(widthMeasureSpec);
        int h = MeasureSpec.getSize(heightMeasureSpec);
        boolean prog = progress != null && progress.getVisibility() == VISIBLE;
        if (w == fitWidth && h == fitHeight && prog == fitProgress
                && TextUtils.equals(subtitleText, fitSubtitle)) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            return;
        }
        fitWidth = w;
        fitHeight = h;
        fitProgress = prog;
        fitSubtitle = subtitleText;
        for (int m = MODE_NORMAL; m <= MODE_DENSE; m++) {
            applyMode(m);
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            if (contentHeight() <= h) return;
        }
    }

    /** 余白を含めた中身の高さ(伸び縮みする空白は数えない) */
    private int contentHeight() {
        int total = getPaddingTop() + getPaddingBottom();
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i);
            if (c == spacer || c.getVisibility() == GONE) continue;
            LayoutParams lp = (LayoutParams) c.getLayoutParams();
            total += c.getMeasuredHeight() + lp.topMargin + lp.bottomMargin;
        }
        return total;
    }

    private void applyMode(int m) {
        if (m == mode) return;
        mode = m;
        int circle;
        int glyph;
        int titleSp;
        if (large) {
            circle = m == MODE_NORMAL ? 88 : 64;
            glyph = m == MODE_NORMAL ? 52 : 38;
            titleSp = m == MODE_NORMAL ? 36 : (m == MODE_MEDIUM ? 32 : 30);
        } else {
            circle = m == MODE_NORMAL ? 56 : 40;
            glyph = m == MODE_NORMAL ? 32 : 24;
            titleSp = m == MODE_NORMAL ? 24 : (m == MODE_MEDIUM ? 22 : 21);
        }
        int pad = Ui.dp(getContext(), m == MODE_NORMAL ? 18 : (m == MODE_MEDIUM ? 14 : 12));

        iconBg.setVisibility(m == MODE_DENSE ? GONE : VISIBLE);
        ViewGroup.LayoutParams bg = iconBg.getLayoutParams();
        bg.width = bg.height = Ui.dp(getContext(), circle);
        iconBg.setLayoutParams(bg);
        ViewGroup.LayoutParams ic = icon.getLayoutParams();
        ic.width = ic.height = Ui.dp(getContext(), glyph);
        icon.setLayoutParams(ic);
        setPadding(pad, pad, pad, pad);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, titleSp);
        if (subtitle != null) {
            subtitle.setMaxLines(m == MODE_DENSE && !large ? 1 : 2);
            applySubtitle();
        }
        updateTitleIcon();
    }

    private boolean dense() {
        return mode == MODE_DENSE;
    }

    /** 詰めた表示のときは名前の左にアイコンを、鍵が掛かっているときは右に鍵を出す */
    private void updateTitleIcon() {
        Drawable d = null;
        if (dense() && iconRes != 0) {
            d = getContext().getDrawable(iconRes);
        }
        if (d != null) {
            d = d.mutate();
            int size = Ui.dp(getContext(), large ? 36 : 24);
            d.setBounds(0, 0, size, size);
            d.setTint(accent);
        }
        Drawable lock = null;
        if (locked) {
            lock = getContext().getDrawable(R.drawable.ic_lock);
            if (lock != null) {
                lock = lock.mutate();
                int size = Ui.dp(getContext(), 22);
                lock.setBounds(0, 0, size, size);
                lock.setTint(getContext().getColor(R.color.warn));
            }
        }
        title.setCompoundDrawablePadding(Ui.dp(getContext(), 8));
        title.setCompoundDrawablesRelative(d, null, lock, null);
    }

    /** 走行中に使えないタイルに鍵の印を付ける(要件U5) */
    public void setLocked(boolean l) {
        if (locked == l) return;
        locked = l;
        updateTitleIcon();
    }

    public void setAccent(int color) {
        accent = color;
        icon.setImageTintList(ColorStateList.valueOf(color));
        if (iconBg != null) {
            iconBg.setBackgroundTintList(
                    ColorStateList.valueOf(ColorUtils.setAlphaComponent(color, 0x33)));
        }
        if (dense()) updateTitleIcon();
    }

    public void setIcon(int res) {
        iconRes = res;
        icon.setImageResource(res);
        if (dense()) updateTitleIcon();
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
        if (dense() && !large && text != null) {
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
