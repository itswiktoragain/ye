package dev.framelink.camera;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Basic Open GoPro BLE transport. Requires runtime Bluetooth permissions before use.
 * Camera controls use the official GoPro command characteristic; credentials
 * are read only if exposed by the camera firmware.
 */
@SuppressLint("MissingPermission")
public final class GoProBleClient {
    public interface Listener {
        void onStatus(String status);
        void onFound(BluetoothDevice device, String name);
        void onReady(String name);
        void onWifiCredentials(String ssid, String password);
        void onDisconnected();
    }

    private static final String BASE = "b5f9%04x-aa8d-11e3-9046-0002a5d5c51b";
    private static UUID uuid(int value) {
        return UUID.fromString(String.format(java.util.Locale.US, BASE, value));
    }

    private static final UUID CMD_SERVICE = uuid(0x0001);
    private static final UUID CMD_WRITE = uuid(0x0072);
    private static final UUID CMD_RESPONSE = uuid(0x0073);
    private static final UUID SSID_CHARACTERISTIC = uuid(0x0002);
    private static final UUID PASSWORD_CHARACTERISTIC = uuid(0x0003);
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayDeque<byte[]> pending = new ArrayDeque<>();
    private final Set<String> discovered = new HashSet<>();
    private BluetoothLeScanner scanner;
    private ScanCallback scanCallback;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic command;
    private BluetoothGattCharacteristic ssidChar;
    private BluetoothGattCharacteristic passwordChar;
    private boolean writing;
    private boolean ready;
    private String deviceName = "GoPro";
    private String ssid;
    private String password;

    public GoProBleClient(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    private void status(String s) { main.post(() -> listener.onStatus(s)); }

    public synchronized boolean isReady() { return ready; }
    public synchronized String getDeviceName() { return deviceName; }

    public void startScan() {
        stopScan();
        BluetoothManager bm = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = bm == null ? null : bm.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            status("Enable Bluetooth to discover GoPro cameras.");
            return;
        }
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            status("Bluetooth LE scanner unavailable.");
            return;
        }
        discovered.clear();
        scanCallback = new ScanCallback() {
            @Override public void onScanResult(int callbackType, ScanResult result) {
                BluetoothDevice d = result.getDevice();
                if (d == null) return;
                String name = result.getScanRecord() == null ? null : result.getScanRecord().getDeviceName();
                if (name == null) {
                    try { name = d.getName(); } catch (SecurityException ignored) {}
                }
                if (name == null) return;
                String lower = name.toLowerCase(java.util.Locale.ROOT);
                if (!lower.contains("gopro") && !lower.startsWith("hero")) return;
                if (discovered.add(d.getAddress())) {
                    final String label = name;
                    main.post(() -> listener.onFound(d, label));
                }
            }
            @Override public void onScanFailed(int error) { status("Camera scan failed (code " + error + ")."); }
        };
        try {
            scanner.startScan(null,
                new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                scanCallback);
            status("Searching for cameras in pairing mode...");
            main.postDelayed(this::stopScan, 14000);
        } catch (Exception e) {
            status("Bluetooth scan failed: " + e.getMessage());
        }
    }

    public void stopScan() {
        if (scanner != null && scanCallback != null) {
            try { scanner.stopScan(scanCallback); } catch (Exception ignored) {}
        }
        scanCallback = null;
        scanner = null;
    }

    public synchronized void connect(BluetoothDevice device, String name) {
        stopScan();
        disconnect();
        deviceName = name;
        status("Connecting to " + name + "...");
        try {
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE);
        } catch (Exception e) {
            status("BLE connection failed: " + e.getMessage());
        }
    }

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int statusCode, int newState) {
            if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                status("Camera connection failed (GATT " + statusCode + ").");
                disconnect();
                return;
            }
            if (newState == android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
                status("Connected over Bluetooth. Discovering controls...");
                if (!g.discoverServices()) status("Failed to discover camera services.");
            } else if (newState == android.bluetooth.BluetoothProfile.STATE_DISCONNECTED) {
                disconnect();
            }
        }

        @Override public void onServicesDiscovered(BluetoothGatt g, int statusCode) {
            if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                status("Could not discover BLE camera services.");
                return;
            }
            BluetoothGattService service = g.getService(CMD_SERVICE);
            if (service == null) {
                status("This camera does not expose the expected Open GoPro BLE service.");
                return;
            }
            command = service.getCharacteristic(CMD_WRITE);
            BluetoothGattCharacteristic response = service.getCharacteristic(CMD_RESPONSE);
            ssidChar = service.getCharacteristic(SSID_CHARACTERISTIC);
            passwordChar = service.getCharacteristic(PASSWORD_CHARACTERISTIC);
            if (command == null) {
                status("Camera control characteristic unavailable.");
                return;
            }
            if (response != null) {
                g.setCharacteristicNotification(response, true);
                BluetoothGattDescriptor descriptor = response.getDescriptor(CCCD);
                if (descriptor != null) {
                    descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    if (g.writeDescriptor(descriptor)) return;
                }
            }
            markReady();
        }

        @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int s) {
            if (s != BluetoothGatt.GATT_SUCCESS) status("BLE notifications not available; controls may still work.");
            markReady();
        }

        @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int statusCode) {
            synchronized (GoProBleClient.this) {
                writing = false;
                if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                    status("Camera command failed (GATT " + statusCode + ").");
                }
                pump();
            }
        }

        @Override public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, int code) {
            receivedRead(g, c.getUuid(), c.getValue(), code);
        }

        @Override public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value, int code) {
            receivedRead(g, c.getUuid(), value, code);
        }

        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            receivedResponse(c.getValue());
        }
        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] data) {
            receivedResponse(data);
        }
    };

    private synchronized void markReady() {
        ready = true;
        status("BLE remote ready. Select your camera preset before recording.");
        main.post(() -> listener.onReady(deviceName));
        // Optional Wi-Fi credentials: not every HERO firmware exposes these.
        if (ssidChar != null && (ssidChar.getProperties() & BluetoothGattCharacteristic.PROPERTY_READ) != 0) {
            try { gatt.readCharacteristic(ssidChar); } catch (Exception ignored) {}
        }
    }

    private void receivedRead(BluetoothGatt g, UUID id, byte[] value, int code) {
        if (code == BluetoothGatt.GATT_SUCCESS && value != null) {
            String str = new String(value, StandardCharsets.UTF_8).replace("\u0000", "").trim();
            if (SSID_CHARACTERISTIC.equals(id)) ssid = str;
            if (PASSWORD_CHARACTERISTIC.equals(id)) password = str;
        }
        if (SSID_CHARACTERISTIC.equals(id) && passwordChar != null &&
            (passwordChar.getProperties() & BluetoothGattCharacteristic.PROPERTY_READ) != 0) {
            try { g.readCharacteristic(passwordChar); } catch (Exception ignored) {}
        } else if (PASSWORD_CHARACTERISTIC.equals(id) && ssid != null && password != null &&
                   !ssid.isEmpty() && !password.isEmpty()) {
            String s = ssid, p = password;
            main.post(() -> listener.onWifiCredentials(s, p));
        }
    }

    private void receivedResponse(byte[] data) {
        if (data == null || data.length < 3) return;
        // Open GoPro response packet includes length, command id and result.
        int error = data[2] & 0xff;
        if (error != 0 && error < 0x10) status("Camera returned status 0x" + Integer.toHexString(error) + ".");
    }

    public synchronized boolean setShutter(boolean on) {
        return enqueue(new byte[] { 0x03, 0x01, 0x01, (byte) (on ? 1 : 0) });
    }

    public synchronized boolean enableWifi() {
        // Open GoPro BLE AP control command.
        return enqueue(new byte[] { 0x03, 0x17, 0x01, 0x01 });
    }

    private boolean enqueue(byte[] data) {
        if (!ready || gatt == null || command == null) {
            status("Pair a GoPro over Bluetooth first.");
            return false;
        }
        pending.offer(data);
        pump();
        return true;
    }

    private void pump() {
        if (writing || pending.isEmpty() || gatt == null || command == null) return;
        byte[] bytes = pending.poll();
        writing = true;
        try {
            command.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            command.setValue(bytes);
            if (!gatt.writeCharacteristic(command)) {
                writing = false;
                status("Could not send camera command.");
                if (!pending.isEmpty()) main.postDelayed(this::retryPump, 120);
            }
        } catch (Exception e) {
            writing = false;
            status("BLE write error: " + e.getMessage());
        }
    }

    private synchronized void retryPump() { pump(); }

    public synchronized void disconnect() {
        ready = false;
        writing = false;
        pending.clear();
        command = null;
        ssidChar = null;
        passwordChar = null;
        ssid = null;
        password = null;
        if (gatt != null) {
            try { gatt.disconnect(); gatt.close(); } catch (Exception ignored) {}
            gatt = null;
        }
        main.post(listener::onDisconnected);
    }

    public void close() { stopScan(); disconnect(); }
}
