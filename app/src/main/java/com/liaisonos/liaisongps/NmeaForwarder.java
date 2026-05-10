package com.liaisonos.liaisongps;

import android.annotation.SuppressLint;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.OnNmeaMessageListener;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class NmeaForwarder {

    public interface Listener {
        void onNmeaReceived(String sentence);
        void onSatelliteUpdate(int count);
        void onAccuracyUpdate(String accuracy);
        void onUtcTimeUpdate(String utcTime);
        void onPositionUpdate(String latLon, String gridSquare);
        void onFixStateChanged(boolean hasFix);
    }

    private final LocationManager locationManager;
    private final Listener listener;
    private final Handler mainHandler;

    private HandlerThread nmeaThread;
    private OnNmeaMessageListener nmeaListener;
    private LocationListener locationListener;

    private volatile OutputStream outputStream;
    private OutputStream pausedOutputStream;
    private boolean hasFix = false; // holds the stream while paused; main-thread only
    private static final byte[] CRLF = "\r\n".getBytes(StandardCharsets.US_ASCII);
    // Pre-allocated buffer reused on every write — no GC on the hot path
    private final byte[] writeBuffer = new byte[256];

    public NmeaForwarder(LocationManager locationManager, Listener listener, Handler mainHandler) {
        this.locationManager = locationManager;
        this.listener = listener;
        this.mainHandler = mainHandler;
    }

    /**
     * Start GPS listening for UI display. Call this on launch, before BT connects.
     */
    @SuppressLint("MissingPermission")
    public void startListening() {
        if (nmeaThread != null) return; // already listening

        // THREAD_PRIORITY_URGENT_AUDIO = highest priority without root
        nmeaThread = new HandlerThread("NMEA-Worker", Process.THREAD_PRIORITY_URGENT_AUDIO);
        nmeaThread.start();
        Handler nmeaHandler = new Handler(nmeaThread.getLooper());

        nmeaListener = (message, timestamp) -> {
            // Forward to BT — pre-allocated buffer, no GC on this path
            OutputStream out = outputStream;
            if (out != null) {
                try {
                    byte[] src = message.getBytes(StandardCharsets.US_ASCII);
                    boolean hasCrlf = message.endsWith("\n");
                    out.write(src);
                    if (!hasCrlf) out.write(CRLF);
                    out.flush();
                } catch (IOException ignored) {}
            }

            // Parse satellite count and fix status from GGA
            if (message.startsWith("$GPGGA") || message.startsWith("$GNGGA")) {
                String[] parts = message.split(",");
                if (parts.length > 7) {
                    // Field 6: fix quality — 0 = no fix, 1+ = fix
                    if (!parts[6].isEmpty()) {
                        try {
                            boolean nowFix = Integer.parseInt(parts[6].trim()) > 0;
                            if (nowFix != hasFix) {
                                hasFix = nowFix;
                                mainHandler.post(() -> listener.onFixStateChanged(hasFix));
                            }
                        } catch (NumberFormatException ignored) {}
                    }
                    // Field 7: satellite count
                    if (!parts[7].isEmpty()) {
                        try {
                            int sats = Integer.parseInt(parts[7].trim());
                            mainHandler.post(() -> listener.onSatelliteUpdate(sats));
                        } catch (NumberFormatException ignored) {}
                    }
                }
            }

            // Parse UTC time + date from RMC
            if (message.startsWith("$GPRMC") || message.startsWith("$GNRMC")) {
                String[] parts = message.split(",");
                if (parts.length > 9 && parts[1].length() >= 6 && parts[9].length() == 6) {
                    String t = parts[1]; // HHMMSS.ss
                    String d = parts[9]; // DDMMYY
                    String formatted = t.substring(0, 2) + ":" + t.substring(2, 4) + ":" + t.substring(4, 6)
                            + " UTC   20" + d.substring(4, 6) + "-" + d.substring(2, 4) + "-" + d.substring(0, 2);
                    mainHandler.post(() -> listener.onUtcTimeUpdate(formatted));
                }
            }

            mainHandler.post(() -> listener.onNmeaReceived(message));
        };

        locationListener = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                double lat = location.getLatitude();
                double lon = location.getLongitude();
                String acc = String.format("%.0f m", location.getAccuracy());
                String latLon = String.format("%.5f°%s   %.5f°%s",
                        Math.abs(lat), lat >= 0 ? "N" : "S",
                        Math.abs(lon), lon >= 0 ? "E" : "W");
                String grid = toMaidenhead(lat, lon);
                mainHandler.post(() -> {
                    listener.onAccuracyUpdate(acc);
                    listener.onPositionUpdate(latLon, grid);
                });
            }
        };

        locationManager.addNmeaListener(nmeaListener, nmeaHandler);
        // minTime=0, minDistance=0 → fastest possible GPS delivery
        locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 0, 0, locationListener, nmeaThread.getLooper());
    }

    /** Call when BT client connects — NMEA sentences will now stream over this socket. */
    public void startStreaming(OutputStream out) {
        pausedOutputStream = null;
        outputStream = out;
    }

    /** Call when BT client disconnects — stops streaming but keeps GPS listener alive. */
    public void stopStreaming() {
        pausedOutputStream = null;
        outputStream = null;
    }

    public void pause() {
        pausedOutputStream = outputStream; // save
        outputStream = null;               // single volatile write — nmeaThread sees null immediately
    }

    public void resume() {
        outputStream = pausedOutputStream; // single volatile write — nmeaThread resumes
        pausedOutputStream = null;
    }

    private static String toMaidenhead(double lat, double lon) {
        lon += 180.0;
        lat += 90.0;
        char f1 = (char) ('A' + (int) (lon / 20));
        char f2 = (char) ('A' + (int) (lat / 10));
        char s1 = (char) ('0' + (int) ((lon % 20) / 2));
        char s2 = (char) ('0' + (int) (lat % 10));
        char t1 = (char) ('a' + (int) ((lon % 2) * 12));
        char t2 = (char) ('a' + (int) ((lat % 1) * 24));
        return "" + f1 + f2 + s1 + s2 + t1 + t2;
    }

    /** Full stop — unregisters GPS listeners and shuts down worker thread. */
    public void stop() {
        hasFix = false;
        outputStream = null;
        if (nmeaListener != null) {
            locationManager.removeNmeaListener(nmeaListener);
            nmeaListener = null;
        }
        if (locationListener != null) {
            locationManager.removeUpdates(locationListener);
            locationListener = null;
        }
        if (nmeaThread != null) {
            nmeaThread.quitSafely();
            nmeaThread = null;
        }
    }
}
