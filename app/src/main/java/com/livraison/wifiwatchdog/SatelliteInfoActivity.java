package com.livraison.wifiwatchdog;

import android.Manifest;
import android.content.pm.PackageManager;
import android.location.GnssStatus;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 現在補足しているGNSS衛星を一覧表示し、
 *   - 衛星系ごとの捕捉数(GPS/QZSS(みちびき)/GLONASS/Galileo/BeiDou 等)
 *   - みちびきが実際に測位計算(usedInFix)に使われているか
 *   - 各衛星の信号強度(CN0)
 * をリアルタイムに確認できる診断画面。
 *
 * 「アプリがみちびき対応と表示しているが本当に使っているかわからない」
 * という問題に対して、チップが実際に受信・使用している衛星を
 * そのまま出すことで客観的に判定できるようにする。
 */
public class SatelliteInfoActivity extends AppCompatActivity {

    private static final int REQ_LOCATION = 200;

    private TextView summaryView;
    private TextView listView;
    private LocationManager locationManager;

    private GnssStatus.Callback gnssCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_satellite);

        summaryView = findViewById(R.id.satellite_summary);
        listView = findViewById(R.id.satellite_list);
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_LOCATION);
        } else {
            startListening();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                            @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_LOCATION && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startListening();
        } else {
            summaryView.setText("位置情報の許可がないため衛星情報を取得できません。");
        }
    }

    private void startListening() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            summaryView.setText("この端末のAndroidバージョンではGnssStatus APIが使えません。");
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        // GNSSチップを起動状態にするため、位置情報リクエストも張っておく
        try {
            locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, 1000, 0, location -> { });
        } catch (SecurityException ignored) {
        }

        gnssCallback = new GnssStatus.Callback() {
            @Override
            public void onSatelliteStatusChanged(@NonNull GnssStatus status) {
                renderStatus(status);
            }
        };
        locationManager.registerGnssStatusCallback(gnssCallback);
        summaryView.setText("測位待機中... (屋外・窓際の方が衛星を捕捉しやすいです)");
    }

    private void renderStatus(GnssStatus status) {
        int count = status.getSatelliteCount();

        Map<Integer, Integer> seenByConstellation = new HashMap<>();
        Map<Integer, Integer> usedByConstellation = new HashMap<>();

        StringBuilder list = new StringBuilder();
        list.append(String.format(Locale.JAPAN, "%-6s %-5s %-6s %-6s %s%n",
                "衛星系", "SVID", "CN0", "測位", "仰角/方位"));

        for (int i = 0; i < count; i++) {
            int constellation = status.getConstellationType(i);
            int svid = status.getSvid(i);
            float cn0 = status.getCn0DbHz(i);
            boolean used = status.usedInFix(i);
            float elevation = status.getElevationDegrees(i);
            float azimuth = status.getAzimuthDegrees(i);

            seenByConstellation.merge(constellation, 1, Integer::sum);
            if (used) usedByConstellation.merge(constellation, 1, Integer::sum);

            list.append(String.format(Locale.JAPAN, "%-6s %-5d %-6.1f %-6s %.0f°/%.0f°%n",
                    constellationName(constellation), svid, cn0,
                    used ? "○使用" : "-", elevation, azimuth));
        }

        StringBuilder summary = new StringBuilder();
        summary.append("捕捉中の衛星: 合計 ").append(count).append("機\n");
        for (int c : new int[]{
                GnssStatus.CONSTELLATION_GPS,
                GnssStatus.CONSTELLATION_QZSS,
                GnssStatus.CONSTELLATION_GLONASS,
                GnssStatus.CONSTELLATION_GALILEO,
                GnssStatus.CONSTELLATION_BEIDOU}) {
            int seen = seenByConstellation.getOrDefault(c, 0);
            int used = usedByConstellation.getOrDefault(c, 0);
            summary.append(String.format(Locale.JAPAN, "  %-8s 捕捉:%2d機  測位使用:%2d機%s%n",
                    constellationName(c), seen, used,
                    c == GnssStatus.CONSTELLATION_QZSS
                            ? (used > 0 ? "  ← みちびきが実際に測位に使われています"
                                        : (seen > 0 ? "  ← 捕捉はしているが測位には未使用"
                                                    : "  ← みちびきが1機も捕捉できていません"))
                            : ""));
        }

        runOnUiThread(() -> {
            summaryView.setText(summary.toString());
            listView.setText(list.toString());
        });
    }

    private String constellationName(int c) {
        switch (c) {
            case GnssStatus.CONSTELLATION_GPS: return "GPS";
            case GnssStatus.CONSTELLATION_QZSS: return "みちびき";
            case GnssStatus.CONSTELLATION_GLONASS: return "GLONASS";
            case GnssStatus.CONSTELLATION_GALILEO: return "Galileo";
            case GnssStatus.CONSTELLATION_BEIDOU: return "BeiDou";
            case GnssStatus.CONSTELLATION_SBAS: return "SBAS";
            default: return "不明(" + c + ")";
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (gnssCallback != null) {
            locationManager.unregisterGnssStatusCallback(gnssCallback);
        }
        try {
            locationManager.removeUpdates(location -> { });
        } catch (Exception ignored) {
        }
    }
}
