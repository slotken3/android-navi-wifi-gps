package com.livraison.wifiwatchdog.home;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.text.TextUtils;
import android.util.DisplayMetrics;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 段階0の調査用。どれも読み取るだけで、何も書き換えない。
 *   - USBにつないだ機器、Androidが認識しているカメラ・マイク(要件U12)
 *   - FYTの「スリープで止めないアプリの一覧」protected_app.txt(要件O1)
 *   - 画面の実寸(タイルの大きさの確認用)
 */
final class DeviceProbe {

    static final String FYT_SERVICE_PKG = "com.syu.ms";
    static final String PROTECTED_LIST_PATH = "assets/app_category/protected_app.txt";

    private DeviceProbe() {
    }

    // ---------------------------------------------------------------
    // USB機器
    // ---------------------------------------------------------------
    static final class UsbInfo {
        final String name;
        final String kind;
        final boolean camera;
        final boolean audio;

        UsbInfo(String name, String kind, boolean camera, boolean audio) {
            this.name = name;
            this.kind = kind;
            this.camera = camera;
            this.audio = audio;
        }
    }

    static List<UsbInfo> usbDevices(Context c) {
        List<UsbInfo> out = new ArrayList<>();
        UsbManager um = (UsbManager) c.getSystemService(Context.USB_SERVICE);
        if (um == null) return out;
        for (UsbDevice d : um.getDeviceList().values()) {
            boolean video = d.getDeviceClass() == UsbConstants.USB_CLASS_VIDEO;
            boolean audio = d.getDeviceClass() == UsbConstants.USB_CLASS_AUDIO;
            boolean storage = false;
            boolean hid = false;
            boolean hub = d.getDeviceClass() == UsbConstants.USB_CLASS_HUB;
            for (int i = 0; i < d.getInterfaceCount(); i++) {
                int cls = d.getInterface(i).getInterfaceClass();
                if (cls == UsbConstants.USB_CLASS_VIDEO) video = true;
                else if (cls == UsbConstants.USB_CLASS_AUDIO) audio = true;
                else if (cls == UsbConstants.USB_CLASS_MASS_STORAGE) storage = true;
                else if (cls == UsbConstants.USB_CLASS_HID) hid = true;
                else if (cls == UsbConstants.USB_CLASS_HUB) hub = true;
            }
            List<String> kinds = new ArrayList<>();
            if (video) kinds.add("カメラ(映像)");
            if (audio) kinds.add("音声(マイク・スピーカー)");
            if (storage) kinds.add("ストレージ");
            if (hid) kinds.add("入力機器");
            if (hub) kinds.add("ハブ");
            if (kinds.isEmpty()) kinds.add("その他");

            String name = d.getProductName();
            if (TextUtils.isEmpty(name)) name = d.getManufacturerName();
            String ids = String.format(Locale.ROOT, "%04X:%04X", d.getVendorId(), d.getProductId());
            name = TextUtils.isEmpty(name) ? ids : name + "(" + ids + ")";
            out.add(new UsbInfo(name, TextUtils.join("・", kinds), video, audio));
        }
        return out;
    }

    // ---------------------------------------------------------------
    // カメラ・マイク
    // ---------------------------------------------------------------
    static List<String> cameras(Context c) {
        List<String> out = new ArrayList<>();
        try {
            CameraManager cm = (CameraManager) c.getSystemService(Context.CAMERA_SERVICE);
            if (cm != null) {
                for (String id : cm.getCameraIdList()) {
                    Integer f = cm.getCameraCharacteristics(id)
                            .get(CameraCharacteristics.LENS_FACING);
                    String facing;
                    if (f == null) facing = "向き不明";
                    else if (f == CameraCharacteristics.LENS_FACING_EXTERNAL) facing = "外付け(USB)";
                    else if (f == CameraCharacteristics.LENS_FACING_FRONT) facing = "前面";
                    else facing = "背面";
                    out.add("カメラ " + id + ":" + facing);
                }
            }
        } catch (Exception e) {
            out.add("取得できませんでした(" + e.getClass().getSimpleName() + ")");
        }
        // 会議アプリによっては旧来のカメラAPIを使うので、そちらの台数も出す
        try {
            @SuppressWarnings("deprecation")
            int legacy = android.hardware.Camera.getNumberOfCameras();
            out.add("旧来のカメラAPIから見える台数:" + legacy);
        } catch (Exception ignored) {
        }
        if (out.isEmpty()) out.add("カメラは見つかりません");
        return out;
    }

    static List<String> audioInputs(Context c) {
        List<String> out = new ArrayList<>();
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
                CharSequence product = d.getProductName();
                out.add(audioTypeName(d.getType())
                        + (TextUtils.isEmpty(product) ? "" : ":" + product));
            }
        }
        if (out.isEmpty()) out.add("マイクは見つかりません");
        return out;
    }

    private static String audioTypeName(int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_BUILTIN_MIC: return "内蔵マイク";
            case AudioDeviceInfo.TYPE_USB_DEVICE: return "USBマイク";
            case AudioDeviceInfo.TYPE_USB_HEADSET: return "USBヘッドセット";
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO: return "Bluetooth(通話)";
            case AudioDeviceInfo.TYPE_WIRED_HEADSET: return "有線ヘッドセット";
            case AudioDeviceInfo.TYPE_FM_TUNER: return "FMチューナー";
            case AudioDeviceInfo.TYPE_TELEPHONY: return "電話回線";
            default: return "種類" + type;
        }
    }

    /** 会議の選択パネルに出す一行 */
    static String meetingSummary(Context c) {
        boolean cam = false;
        boolean mic = false;
        for (UsbInfo u : usbDevices(c)) {
            cam |= u.camera;
            mic |= u.audio;
        }
        // 改行を入れておく。パネルの幅は中身に合わせて決まるため、長い1行は折り返されずに切れる
        return "USBカメラ:" + (cam ? "あり" : "なし")
                + " ・ USBマイク:" + (mic ? "あり" : "なし")
                + "\n詳しくは 設定 → 会議の機材";
    }

    // ---------------------------------------------------------------
    // protected_app.txt(要件O1)
    // ---------------------------------------------------------------
    static final class ProtectedList {
        /** 読んだ場所。読めなかったら null */
        String source;
        final List<String> entries = new ArrayList<>();
        /** 読めなかった理由 */
        String error;
        /** 名前に protect を含む、ほかのファイル(一覧の場所が違う場合の手がかり) */
        final List<String> otherCandidates = new ArrayList<>();
    }

    /**
     * com.syu.ms のapkの中から一覧を読む。apkは誰でも読めるので root は不要な見込み。
     * 時間がかかるので画面のスレッドでは呼ばない。
     */
    static ProtectedList readProtectedList(Context c) {
        ProtectedList r = new ProtectedList();
        ApplicationInfo ai;
        try {
            ai = c.getPackageManager().getApplicationInfo(FYT_SERVICE_PKG, 0);
        } catch (PackageManager.NameNotFoundException e) {
            r.error = FYT_SERVICE_PKG + " が見つかりません(FYT系の機種ではないか、エミュレーター)";
            return r;
        }
        List<String> apks = new ArrayList<>();
        apks.add(ai.sourceDir);
        if (ai.splitSourceDirs != null) Collections.addAll(apks, ai.splitSourceDirs);

        for (String apk : apks) {
            try (ZipFile zip = new ZipFile(apk)) {
                Enumeration<? extends ZipEntry> all = zip.entries();
                while (all.hasMoreElements()) {
                    String n = all.nextElement().getName();
                    if (n.toLowerCase(Locale.ROOT).contains("protect")
                            && !n.equals(PROTECTED_LIST_PATH)) {
                        r.otherCandidates.add(n);
                    }
                }
                ZipEntry e = zip.getEntry(PROTECTED_LIST_PATH);
                if (e == null) continue;
                r.source = apk + " の中の " + PROTECTED_LIST_PATH;
                try (BufferedReader br = new BufferedReader(new InputStreamReader(
                        zip.getInputStream(e), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        line = line.trim();
                        if (!line.isEmpty() && !line.startsWith("#")) r.entries.add(line);
                    }
                }
            } catch (IOException | SecurityException ex) {
                r.error = apk + " を読めませんでした(" + ex.getMessage() + ")";
            }
        }
        if (r.source == null && r.error == null) {
            r.error = PROTECTED_LIST_PATH + " が見つかりません";
        }
        return r;
    }

    /**
     * pkg が一覧のどれかで始まっていれば、その名前を返す。
     * FYTが前方一致で判定しているかは未確認(要件O1・O3で確かめる)。
     */
    static String prefixMatch(List<String> entries, String pkg) {
        for (String e : entries) {
            if (pkg.equals(e) || pkg.startsWith(e + ".")) return e;
        }
        return null;
    }

    // ---------------------------------------------------------------
    // 画面
    // ---------------------------------------------------------------
    @SuppressWarnings("deprecation")
    static String screenSummary(Activity a) {
        DisplayMetrics m = new DisplayMetrics();
        a.getWindowManager().getDefaultDisplay().getRealMetrics(m);
        return String.format(Locale.JAPAN, "%d×%d px ・ %d dpi ・ %d×%d dp",
                m.widthPixels, m.heightPixels, m.densityDpi,
                Math.round(m.widthPixels / m.density), Math.round(m.heightPixels / m.density));
    }
}
