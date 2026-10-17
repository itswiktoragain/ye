package dev.framelink.camera;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.Handler;
import android.os.Looper;

/** Android-approved, local-only camera Wi-Fi connection. */
@SuppressLint("MissingPermission")
public final class CameraWifi {
    public interface Listener {
        void onStatus(String status);
        void onConnected();
        void onDisconnected();
    }

    private final ConnectivityManager connectivity;
    private final Listener listener;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private ConnectivityManager.NetworkCallback callback;
    private volatile Network cameraNetwork;

    public CameraWifi(Context context, Listener listener) {
        connectivity = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        this.listener = listener;
    }

    public Network getNetwork() {
        if (cameraNetwork != null) return cameraNetwork;
        Network active = connectivity.getActiveNetwork();
        NetworkCapabilities caps = active == null ? null : connectivity.getNetworkCapabilities(active);
        return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? active : null;
    }

    public void connect(String ssid, String password) {
        disconnect();
        if (ssid.isEmpty() || password.length() < 8) {
            listener.onStatus("Enter a valid camera Wi-Fi name and password (at least 8 characters).");
            return;
        }
        try {
            WifiNetworkSpecifier spec = new WifiNetworkSpecifier.Builder()
                .setSsid(ssid)
                .setWpa2Passphrase(password)
                .build();
            NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(spec)
                .build();
            callback = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) {
                    cameraNetwork = network;
                    ui.post(() -> {
                        listener.onStatus("Camera Wi-Fi connected. You can browse media.");
                        listener.onConnected();
                    });
                }
                @Override public void onLost(Network network) {
                    if (network.equals(cameraNetwork)) cameraNetwork = null;
                    ui.post(() -> {
                        listener.onStatus("Camera Wi-Fi disconnected.");
                        listener.onDisconnected();
                    });
                }
                @Override public void onUnavailable() {
                    ui.post(() -> listener.onStatus("Camera Wi-Fi request timed out or was declined."));
                }
            };
            listener.onStatus("Approve the camera Wi-Fi network in Android's connection dialog.");
            connectivity.requestNetwork(request, callback, 30000);
        } catch (Exception e) {
            listener.onStatus("Unable to join camera Wi-Fi: " + e.getMessage());
        }
    }

    public void disconnect() {
        if (callback != null) {
            try { connectivity.unregisterNetworkCallback(callback); } catch (Exception ignored) {}
            callback = null;
        }
        cameraNetwork = null;
    }
}
