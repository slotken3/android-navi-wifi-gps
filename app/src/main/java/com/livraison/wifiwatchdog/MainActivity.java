package com.livraison.wifiwatchdog;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    private static final int REQ_LOCATION = 100;

    private EditText ssidInput;
    private EditText passwordInput;
    private CheckBox autoStartCheck;
    private TextView statusText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        ssidInput = findViewById(R.id.ssid_input);
        passwordInput = findViewById(R.id.password_input);
        autoStartCheck = findViewById(R.id.auto_start_check);
        statusText = findViewById(R.id.status_text);
        Button saveButton = findViewById(R.id.save_button);
        Button startButton = findViewById(R.id.start_button);
        Button stopButton = findViewById(R.id.stop_button);
        Button useCurrentButton = findViewById(R.id.use_current_button);
        Button batteryButton = findViewById(R.id.battery_button);
        Button satelliteButton = findViewById(R.id.satellite_button);

        SharedPreferences prefs = getSharedPreferences(Prefs.NAME, MODE_PRIVATE);
        ssidInput.setText(prefs.getString(Prefs.KEY_SSID, ""));
        passwordInput.setText(prefs.getString(Prefs.KEY_PASSWORD, ""));
        autoStartCheck.setChecked(prefs.getBoolean(Prefs.KEY_AUTO_START, true));

        requestLocationPermissionIfNeeded();

        saveButton.setOnClickListener(v -> saveSettings());

        useCurrentButton.setOnClickListener(v -> {
            String current = getCurrentSsid();
            if (current != null) {
                ssidInput.setText(current);
                Toast.makeText(this, "現在接続中のSSIDを入力しました", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "現在Wi-Fiに接続していません", Toast.LENGTH_SHORT).show();
            }
        });

        startButton.setOnClickListener(v -> {
            saveSettings();
            startMonitorService();
            statusText.setText("状態: 監視サービスを開始しました");
        });

        stopButton.setOnClickListener(v ->
                stopService(new Intent(this, WifiMonitorService.class)));

        batteryButton.setOnClickListener(v -> requestIgnoreBatteryOptimizations());

        satelliteButton.setOnClickListener(v ->
                startActivity(new Intent(this, SatelliteInfoActivity.class)));
    }

    private void saveSettings() {
        SharedPreferences prefs = getSharedPreferences(Prefs.NAME, MODE_PRIVATE);
        prefs.edit()
                .putString(Prefs.KEY_SSID, ssidInput.getText().toString().trim())
                .putString(Prefs.KEY_PASSWORD, passwordInput.getText().toString())
                .putBoolean(Prefs.KEY_AUTO_START, autoStartCheck.isChecked())
                .apply();
        Toast.makeText(this, "設定を保存しました", Toast.LENGTH_SHORT).show();
    }

    private void startMonitorService() {
        Intent intent = new Intent(this, WifiMonitorService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private String getCurrentSsid() {
        WifiManager wm = (WifiManager) getApplicationContext()
                .getSystemService(WIFI_SERVICE);
        if (wm == null) return null;
        WifiInfo info = wm.getConnectionInfo();
        if (info == null || info.getNetworkId() == -1) return null;
        String ssid = info.getSSID();
        if (TextUtils.isEmpty(ssid) || ssid.equals("<unknown ssid>")) return null;
        return ssid.replace("\"", "");
    }

    private void requestLocationPermissionIfNeeded() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_LOCATION);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                            @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_LOCATION) {
            if (grantResults.length == 0 || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this,
                        "位置情報の許可がないとSSID名を正しく取得できない場合があります",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    /** 中華製OEM端末はバッテリー最適化でサービスをkillしがちなため、除外を促す */
    private void requestIgnoreBatteryOptimizations() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            String pkg = getPackageName();
            if (pm != null && !pm.isIgnoringBatteryOptimizations(pkg)) {
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + pkg));
                startActivity(intent);
            } else {
                Toast.makeText(this, "すでに最適化対象外です", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "この端末では設定画面を開けませんでした。" +
                    "システム設定内の「自動起動管理」「電源管理」等から" +
                    "本アプリを手動で許可してください。", Toast.LENGTH_LONG).show();
        }
    }
}
