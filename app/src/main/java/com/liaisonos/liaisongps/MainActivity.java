package com.liaisonos.liaisongps;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity
        implements BluetoothGpsServer.Listener, NmeaForwarder.Listener {

    private static final int REQUEST_PERMISSIONS = 1;
    private static final int MAX_LOG_LINES = 10;

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothGpsServer bluetoothServer;
    private NmeaForwarder nmeaForwarder;
    private LocationManager locationManager;
    private Handler mainHandler;

    private ActivityResultLauncher<Intent> enableBtLauncher;

    // UI
    private View statusDot;
    private TextView statusText;
    private TextView satelliteCount;
    private TextView accuracyText;
    private TextView utcTimeText;
    private TextView latLonText;
    private TextView gridSquareText;
    private TextView nmeaLog;
    private ScrollView nmeaScrollView;

    private final StringBuilder nmeaBuffer = new StringBuilder();
    private int logLineCount = 0;
    private boolean btConnected = false;

    private enum Status { WAITING, CONNECTED, STREAMING }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        if (getSupportActionBar() != null) getSupportActionBar().hide();

        mainHandler = new Handler(Looper.getMainLooper());

        statusDot = findViewById(R.id.statusDot);
        statusText = findViewById(R.id.statusText);
        satelliteCount = findViewById(R.id.satelliteCount);
        accuracyText = findViewById(R.id.accuracyText);
        utcTimeText = findViewById(R.id.utcTime);
        latLonText = findViewById(R.id.latLon);
        gridSquareText = findViewById(R.id.gridSquare);
        nmeaLog = findViewById(R.id.nmeaLog);
        nmeaScrollView = findViewById(R.id.nmeaScrollView);
        Button btnStop = findViewById(R.id.btnStop);

        btnStop.setOnClickListener(v -> {
            stopAll();
            finish();
        });

        enableBtLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK) {
                        startServer();
                    } else {
                        Toast.makeText(this, R.string.error_bt_required, Toast.LENGTH_LONG).show();
                        finish();
                    }
                });

        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);

        BluetoothManager bm = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        bluetoothAdapter = bm != null ? bm.getAdapter() : null;

        if (bluetoothAdapter == null) {
            Toast.makeText(this, R.string.error_no_bluetooth, Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        checkPermissionsAndStart();
    }

    private void checkPermissionsAndStart() {
        List<String> needed = new ArrayList<>();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                needed.add(Manifest.permission.BLUETOOTH_CONNECT);
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED)
                needed.add(Manifest.permission.BLUETOOTH_ADVERTISE);
        }

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION);

        if (needed.isEmpty()) {
            checkBluetoothEnabled();
        } else {
            ActivityCompat.requestPermissions(this, needed.toArray(new String[0]), REQUEST_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQUEST_PERMISSIONS) return;

        for (int result : results) {
            if (result != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, R.string.error_permissions_required, Toast.LENGTH_LONG).show();
                finish();
                return;
            }
        }
        checkBluetoothEnabled();
    }

    @SuppressWarnings("deprecation")
    private void checkBluetoothEnabled() {
        if (bluetoothAdapter.isEnabled()) {
            startServer();
        } else {
            enableBtLauncher.launch(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
        }
    }

    private void startServer() {
        setStatus(Status.WAITING);
        nmeaForwarder = new NmeaForwarder(locationManager, this, mainHandler);
        nmeaForwarder.startListening(); // show GPS stats immediately, before BT connects
        bluetoothServer = new BluetoothGpsServer(bluetoothAdapter, this, mainHandler);
        bluetoothServer.start();
    }

    private void stopAll() {
        if (nmeaForwarder != null) {
            nmeaForwarder.stop();
            nmeaForwarder = null;
        }
        if (bluetoothServer != null) {
            bluetoothServer.stop();
            bluetoothServer = null;
        }
    }

    private void setStatus(Status s) {
        int color;
        int textRes;
        switch (s) {
            case CONNECTED:
                color = 0xFFFFCC00;
                textRes = R.string.status_connected;
                break;
            case STREAMING:
                color = 0xFF33CC33;
                textRes = R.string.status_streaming;
                break;
            default: // WAITING
                color = 0xFFCC3333;
                textRes = R.string.status_waiting;
                break;
        }
        statusDot.setBackgroundTintList(ColorStateList.valueOf(color));
        statusText.setText(textRes);
    }

    // --- BluetoothGpsServer.Listener ---

    @Override
    public void onClientConnected(OutputStream out) {
        btConnected = true;
        setStatus(Status.CONNECTED);
        nmeaForwarder.startStreaming(out);
    }

    @Override
    public void onClientDisconnected() {
        btConnected = false;
        nmeaForwarder.stopStreaming(); // keep GPS listener alive for next connection
        setStatus(Status.WAITING);
        // BluetoothGpsServer loops automatically and waits for the next connection
    }

    @Override
    public void onServerError(String message) {
        statusDot.setBackgroundTintList(ColorStateList.valueOf(0xFF888888));
        statusText.setText(message);
    }

    // --- NmeaForwarder.Listener ---

    @Override
    public void onNmeaReceived(String sentence) {
        if (logLineCount >= MAX_LOG_LINES) {
            int idx = nmeaBuffer.indexOf("\n");
            if (idx >= 0) nmeaBuffer.delete(0, idx + 1);
            else nmeaBuffer.setLength(0);
            logLineCount--;
        }
        nmeaBuffer.append(sentence.trim()).append("\n");
        logLineCount++;
        nmeaLog.setText(nmeaBuffer.toString());
        nmeaScrollView.post(() -> nmeaScrollView.fullScroll(ScrollView.FOCUS_DOWN));
        if (btConnected) setStatus(Status.STREAMING);
    }

    @Override
    public void onSatelliteUpdate(int count) {
        satelliteCount.setText(String.valueOf(count));
    }

    @Override
    public void onAccuracyUpdate(String accuracy) {
        accuracyText.setText(accuracy);
    }

    @Override
    public void onUtcTimeUpdate(String utcTime) {
        utcTimeText.setText(utcTime);
    }

    @Override
    public void onPositionUpdate(String latLon, String gridSquare) {
        latLonText.setText(latLon);
        gridSquareText.setText(gridSquare);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopAll();
    }
}
