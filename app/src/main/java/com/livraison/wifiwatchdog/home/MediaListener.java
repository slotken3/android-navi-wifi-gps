package com.livraison.wifiwatchdog.home;

import android.service.notification.NotificationListenerService;

/**
 * 再生中の曲(MediaSession)を読むための入口。中身は何もしない。
 * Android は「通知へのアクセス」を許可したアプリにだけ、他のアプリの再生状態を渡すため。
 * 通知の中身は読まない。
 */
public class MediaListener extends NotificationListenerService {
}
