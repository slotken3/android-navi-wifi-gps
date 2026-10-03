package com.livraison.wifiwatchdog.home;

import android.content.ComponentName;
import android.content.Context;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.KeyEvent;

import java.util.List;

/**
 * 再生中の曲と、前・再生/一時停止・次の操作(フェーズ0指示書 6章 左カードの下段)。
 *
 * 曲名は「通知へのアクセス」を許可したときだけ読める(MediaSession)。
 * 操作ボタンは許可が無くても動くよう、そのときはメディアキーを送る。
 */
final class NowPlaying {

    String title;
    String artist;
    boolean playing;

    private NowPlaying() {
    }

    /** 「通知へのアクセス」が許可されているか */
    static boolean permitted(Context c) {
        String s = Settings.Secure.getString(c.getContentResolver(), "enabled_notification_listeners");
        return s != null && s.contains(c.getPackageName());
    }

    private static MediaController controller(Context c) {
        if (!permitted(c)) return null;
        MediaSessionManager m = c.getSystemService(MediaSessionManager.class);
        if (m == null) return null;
        try {
            List<MediaController> list =
                    m.getActiveSessions(new ComponentName(c, MediaListener.class));
            return list == null || list.isEmpty() ? null : list.get(0);
        } catch (SecurityException e) {
            return null;
        }
    }

    /** 今の曲。許可が無い、または何も再生していなければ null */
    static NowPlaying read(Context c) {
        MediaController mc = controller(c);
        if (mc == null) return null;
        NowPlaying n = new NowPlaying();
        MediaMetadata md = mc.getMetadata();
        if (md != null) {
            n.title = md.getString(MediaMetadata.METADATA_KEY_TITLE);
            if (TextUtils.isEmpty(n.title)) n.title = md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
            n.artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
        }
        PlaybackState ps = mc.getPlaybackState();
        n.playing = ps != null && ps.getState() == PlaybackState.STATE_PLAYING;
        return n;
    }

    static void previous(Context c) {
        MediaController mc = controller(c);
        if (mc != null) mc.getTransportControls().skipToPrevious();
        else sendKey(c, KeyEvent.KEYCODE_MEDIA_PREVIOUS);
    }

    static void next(Context c) {
        MediaController mc = controller(c);
        if (mc != null) mc.getTransportControls().skipToNext();
        else sendKey(c, KeyEvent.KEYCODE_MEDIA_NEXT);
    }

    static void playPause(Context c) {
        MediaController mc = controller(c);
        if (mc != null) {
            PlaybackState ps = mc.getPlaybackState();
            if (ps != null && ps.getState() == PlaybackState.STATE_PLAYING) {
                mc.getTransportControls().pause();
            } else {
                mc.getTransportControls().play();
            }
        } else {
            sendKey(c, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
        }
    }

    private static void sendKey(Context c, int code) {
        AudioManager am = c.getSystemService(AudioManager.class);
        if (am == null) return;
        long t = SystemClock.uptimeMillis();
        am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0));
        am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0));
    }
}
