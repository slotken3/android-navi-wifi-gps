package com.livraison.wifiwatchdog.home;

import android.content.Context;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;

import com.livraison.wifiwatchdog.BuildConfig;

import org.osmdroid.config.Configuration;
import org.osmdroid.config.IConfigurationProvider;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.CustomZoomButtonsController;
import org.osmdroid.views.MapView;

import java.io.File;

/**
 * ホームの地図(要件U13)。OpenStreetMap の公式サーバー(tile.openstreetmap.org)の利用ポリシーに合わせる。
 *
 * - アプリ名を含む独自の User-Agent を送る
 * - 一度見た地図は7日間は取り直さずに使い回す
 * - 地図の保存は200MBまで(超えたら180MBまで古いものから消す)
 * - 事前のまとめ取り(オフライン用の一括取得)はしない(osmdroid の CacheManager を使わない)
 * - 地図の色は暗く加工する(反転してから色合いを戻し、少し暗くする)
 */
final class MapSetup {

    /** 標準の範囲は横約1.5km(ズーム15) */
    static final double DEFAULT_ZOOM = 15.0;
    /** 前回の位置が無いときの中心(東京駅) */
    private static final GeoPoint FALLBACK = new GeoPoint(35.6812, 139.7671);

    private MapSetup() {
    }

    static MapView create(Context c, boolean offline) {
        IConfigurationProvider conf = Configuration.getInstance();
        conf.load(c, c.getSharedPreferences("osmdroid", Context.MODE_PRIVATE));
        conf.setUserAgentValue("NaviHome/" + BuildConfig.VERSION_NAME
                + " (personal car head unit app; " + c.getPackageName() + ")");
        // 地図の保存先はアプリの中(ストレージの許可を要らなくするため)
        File base = new File(c.getFilesDir(), "osmdroid");
        conf.setOsmdroidBasePath(base);
        conf.setOsmdroidTileCache(new File(base, "tiles"));
        conf.setTileFileSystemCacheMaxBytes(200L * 1024 * 1024);
        conf.setTileFileSystemCacheTrimBytes(180L * 1024 * 1024);
        conf.setExpirationOverrideDuration(7L * 24 * 60 * 60 * 1000);

        MapView map = new MapView(c);
        map.setTileSource(TileSourceFactory.MAPNIK);
        map.setUseDataConnection(!offline);
        map.setMultiTouchControls(false);
        map.getZoomController().setVisibility(CustomZoomButtonsController.Visibility.NEVER);
        map.setMinZoomLevel(5.0);
        map.setMaxZoomLevel(18.0);
        map.getController().setZoom(DEFAULT_ZOOM);
        map.getController().setCenter(FALLBACK);
        map.getOverlayManager().getTilesOverlay().setColorFilter(darkFilter());
        map.getOverlayManager().getTilesOverlay().setLoadingBackgroundColor(0xFF1F2A31);
        map.getOverlayManager().getTilesOverlay().setLoadingLineColor(0xFF2B3840);
        return map;
    }

    /** 地図を暗くする色の変換。白地を暗く、道路や水の色合いはなるべく残す */
    private static ColorMatrixColorFilter darkFilter() {
        ColorMatrix m = new ColorMatrix(new float[]{
                -1, 0, 0, 0, 255,
                0, -1, 0, 0, 255,
                0, 0, -1, 0, 255,
                0, 0, 0, 1, 0});
        m.postConcat(hueRotate(180));
        ColorMatrix dim = new ColorMatrix();
        dim.setScale(0.78f, 0.86f, 0.88f, 1f);
        m.postConcat(dim);
        return new ColorMatrixColorFilter(m);
    }

    /** 明るさを保ったまま色相を回す(反転で逆になった色合いを戻すため) */
    private static ColorMatrix hueRotate(float degrees) {
        double r = Math.toRadians(degrees);
        float cos = (float) Math.cos(r);
        float sin = (float) Math.sin(r);
        float lr = 0.213f;
        float lg = 0.715f;
        float lb = 0.072f;
        return new ColorMatrix(new float[]{
                lr + cos * (1 - lr) + sin * (-lr), lg + cos * (-lg) + sin * (-lg), lb + cos * (-lb) + sin * (1 - lb), 0, 0,
                lr + cos * (-lr) + sin * 0.143f, lg + cos * (1 - lg) + sin * 0.140f, lb + cos * (-lb) + sin * (-0.283f), 0, 0,
                lr + cos * (-lr) + sin * (-(1 - lr)), lg + cos * (-lg) + sin * lg, lb + cos * (1 - lb) + sin * lb, 0, 0,
                0, 0, 0, 1, 0});
    }
}
