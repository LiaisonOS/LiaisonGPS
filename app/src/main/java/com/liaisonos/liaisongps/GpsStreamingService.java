package com.liaisonos.liaisongps;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.location.LocationManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;

import java.io.OutputStream;

/**
 * Owns the GPS listener and Bluetooth SPP server as a foreground service so streaming
 * survives the screen locking — without a foreground service, Doze suspends the app's
 * threads the moment the display turns off.
 */
public class GpsStreamingService extends Service
        implements BluetoothGpsServer.Listener, NmeaForwarder.Listener {

    public interface UiListener {
        void onClientConnected();
        void onClientDisconnected();
        void onServerError(String message);
        void onNmeaReceived(String sentence);
        void onSatelliteUpdate(int count);
        void onAccuracyUpdate(String accuracy);
        void onUtcTimeUpdate(String utcTime);
        void onPositionUpdate(String latLon, String gridSquare);
        void onFixStateChanged(boolean hasFix);
    }

    public static final String ACTION_STOP = "com.liaisonos.liaisongps.action.STOP";
    private static final String CHANNEL_ID = "gps_streaming";
    private static final int NOTIFICATION_ID = 1;
    // If no NMEA sentence arrives for this long, the GPS listener has gone silent
    // (e.g. background location access was revoked after the task was swiped from
    // Recents) even though the notification still claims we're streaming.
    private static final long NMEA_TIMEOUT_MS = 8000;

    private final IBinder binder = new LocalBinder();
    private UiListener uiListener;

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothGpsServer bluetoothServer;
    private NmeaForwarder nmeaForwarder;
    private LocationManager locationManager;
    private Handler mainHandler;
    private PowerManager.WakeLock wakeLock;

    private volatile boolean clientConnected = false;
    private boolean notifiedStreaming = false;
    private final Runnable nmeaWatchdog = this::onNmeaTimeout;

    public class LocalBinder extends Binder {
        GpsStreamingService getService() {
            return GpsStreamingService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mainHandler = new Handler(Looper.getMainLooper());
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        BluetoothManager bm = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        bluetoothAdapter = bm != null ? bm.getAdapter() : null;
        createNotificationChannel();
    }

    @SuppressLint("MissingPermission")
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopStreaming();
            stopSelf();
            return START_NOT_STICKY;
        }

        startForegroundNotification(getString(R.string.status_waiting));
        acquireWakeLock();

        if (nmeaForwarder == null) {
            nmeaForwarder = new NmeaForwarder(locationManager, this, mainHandler);
            nmeaForwarder.startListening();
            armWatchdog();
        }
        if (bluetoothServer == null) {
            bluetoothServer = new BluetoothGpsServer(bluetoothAdapter, this, mainHandler);
            bluetoothServer.start();
        }
        // The foreground notification + wake lock are what keep this alive; no need
        // for the system to resurrect it on its own if the process is killed.
        return START_NOT_STICKY;
    }

    public void setUiListener(UiListener listener) {
        this.uiListener = listener;
    }

    public boolean isClientConnected() {
        return clientConnected;
    }

    public void pauseStreaming() {
        if (nmeaForwarder != null) nmeaForwarder.pause();
    }

    public void resumeStreaming() {
        if (nmeaForwarder != null) nmeaForwarder.resume();
    }

    /** Drop the current client but keep the server + notification up for the next connection. */
    public void disconnectClient() {
        if (nmeaForwarder != null) nmeaForwarder.stopStreaming();
        if (bluetoothServer != null) bluetoothServer.disconnectClient();
    }

    /** Full teardown — call when the operator explicitly closes the app. */
    private void stopStreaming() {
        mainHandler.removeCallbacks(nmeaWatchdog);
        if (nmeaForwarder != null) {
            nmeaForwarder.stop();
            nmeaForwarder = null;
        }
        if (bluetoothServer != null) {
            bluetoothServer.stop();
            bluetoothServer = null;
        }
        releaseWakeLock();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            //noinspection deprecation
            stopForeground(true);
        }
    }

    @Override
    public void onDestroy() {
        stopStreaming();
        super.onDestroy();
    }

    private void armWatchdog() {
        mainHandler.removeCallbacks(nmeaWatchdog);
        mainHandler.postDelayed(nmeaWatchdog, NMEA_TIMEOUT_MS);
    }

    /** Fires when no NMEA sentence has arrived for NMEA_TIMEOUT_MS — the GPS listener
     * has gone silent (most commonly: background location access was revoked after
     * the app was swiped from Recents) even though we'd otherwise keep showing stale
     * "streaming" status forever, since nothing else would ever flip it back. */
    private void onNmeaTimeout() {
        notifiedStreaming = false;
        if (clientConnected) updateNotification(getString(R.string.status_signal_lost));
        if (uiListener != null) uiListener.onFixStateChanged(false);
    }

    private void acquireWakeLock() {
        if (wakeLock != null) return;
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LiaisonGPS:streaming");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW);
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(channel);
    }

    private void startForegroundNotification(String statusText) {
        Notification notification = buildNotification(statusText);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                            | ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void updateNotification(String statusText) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(NOTIFICATION_ID, buildNotification(statusText));
    }

    private Notification buildNotification(String statusText) {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent openPending = PendingIntent.getActivity(this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stopIntent = new Intent(this, GpsStreamingService.class).setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(this, 0, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(statusText)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(openPending)
                .addAction(0, getString(R.string.btn_stop), stopPending)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    // --- BluetoothGpsServer.Listener ---

    @Override
    public void onClientConnected(OutputStream out) {
        clientConnected = true;
        notifiedStreaming = false;
        if (nmeaForwarder != null) nmeaForwarder.startStreaming(out);
        updateNotification(getString(R.string.status_connected));
        if (uiListener != null) uiListener.onClientConnected();
    }

    @Override
    public void onClientDisconnected() {
        clientConnected = false;
        notifiedStreaming = false;
        if (nmeaForwarder != null) nmeaForwarder.stopStreaming();
        updateNotification(getString(R.string.status_waiting));
        if (uiListener != null) uiListener.onClientDisconnected();
    }

    @Override
    public void onServerError(String message) {
        if (uiListener != null) uiListener.onServerError(message);
    }

    // --- NmeaForwarder.Listener ---

    @Override
    public void onNmeaReceived(String sentence) {
        armWatchdog();
        if (clientConnected && !notifiedStreaming) {
            notifiedStreaming = true;
            updateNotification(getString(R.string.status_streaming));
        }
        if (uiListener != null) uiListener.onNmeaReceived(sentence);
    }

    @Override
    public void onSatelliteUpdate(int count) {
        if (uiListener != null) uiListener.onSatelliteUpdate(count);
    }

    @Override
    public void onAccuracyUpdate(String accuracy) {
        if (uiListener != null) uiListener.onAccuracyUpdate(accuracy);
    }

    @Override
    public void onUtcTimeUpdate(String utcTime) {
        if (uiListener != null) uiListener.onUtcTimeUpdate(utcTime);
    }

    @Override
    public void onPositionUpdate(String latLon, String gridSquare) {
        if (uiListener != null) uiListener.onPositionUpdate(latLon, gridSquare);
    }

    @Override
    public void onFixStateChanged(boolean hasFix) {
        if (uiListener != null) uiListener.onFixStateChanged(hasFix);
    }
}
