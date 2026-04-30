package com.liaisonos.liaisongps;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.os.Handler;

import java.io.IOException;
import java.io.OutputStream;
import java.util.UUID;

public class BluetoothGpsServer {

    private static final UUID SPP_UUID =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final String SERVICE_NAME = "LiaisonGPS";

    public interface Listener {
        void onClientConnected(OutputStream out);
        void onClientDisconnected();
        void onServerError(String message);
    }

    private final BluetoothAdapter adapter;
    private final Listener listener;
    private final Handler mainHandler;

    private volatile BluetoothServerSocket serverSocket;
    private volatile BluetoothSocket clientSocket;
    private Thread acceptThread;
    private volatile boolean running;

    public BluetoothGpsServer(BluetoothAdapter adapter, Listener listener, Handler mainHandler) {
        this.adapter = adapter;
        this.listener = listener;
        this.mainHandler = mainHandler;
    }

    @SuppressLint("MissingPermission")
    public void start() {
        running = true;
        acceptThread = new Thread(() -> {
            while (running) {
                try {
                    serverSocket = adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SPP_UUID);
                    BluetoothSocket socket = serverSocket.accept(); // blocks until connected

                    if (!running) {
                        try { socket.close(); } catch (IOException ignored) {}
                        return;
                    }

                    clientSocket = socket;
                    try { serverSocket.close(); } catch (IOException ignored) {}

                    OutputStream out = socket.getOutputStream();
                    mainHandler.post(() -> listener.onClientConnected(out));

                    monitorConnection(socket);

                    if (running) {
                        mainHandler.post(() -> listener.onClientDisconnected());
                    }

                } catch (IOException e) {
                    if (running) {
                        mainHandler.post(() -> listener.onServerError("BT error: " + e.getMessage()));
                    }
                    return;
                }
            }
        }, "BT-Accept");
        acceptThread.start();
    }

    private void monitorConnection(BluetoothSocket socket) {
        // Read loop — blocks until client disconnects or socket is closed
        try {
            byte[] buf = new byte[64];
            //noinspection InfiniteLoopStatement
            while (true) {
                if (socket.getInputStream().read(buf) < 0) break;
            }
        } catch (IOException ignored) {}
    }

    public void stop() {
        running = false;
        // Close output stream first so the remote side gets a clean EOF
        try {
            if (clientSocket != null) {
                try { clientSocket.getOutputStream().flush(); } catch (IOException ignored) {}
                try { clientSocket.getOutputStream().close(); } catch (IOException ignored) {}
                clientSocket.close();
                clientSocket = null;
            }
        } catch (IOException ignored) {}
        try {
            if (serverSocket != null) {
                serverSocket.close();
                serverSocket = null;
            }
        } catch (IOException ignored) {}
        if (acceptThread != null) {
            acceptThread.interrupt();
            acceptThread = null;
        }
    }
}
