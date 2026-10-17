package dev.framelink.camera;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
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
        void onFound(BluetoothDevice device, String name, CameraDiscovery.Match match,
                     int rssi, boolean bleTransport);
        void onScanFinished(int totalDevices, int cameras);
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
    private final Map<String, String> discovered = new HashMap<>();
    private final Map<String, Boolean> cameraFlags = new HashMap<>();
    private BluetoothAdapter adapter;
    private BroadcastReceiver classicReceiver;
    private BroadcastReceiver bondReceiver;
    private Runnable scanPhaseTimeout;
    private Runnable bondTimeout;
    private BluetoothDevice bondingDevice;
    private boolean scanning;
    private String expectedAlias = "Moneyman";
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

    public synchronized void setExpectedCameraName(String name) {
        expectedAlias = name == null ? "" : name.trim();
    }

    private String nameOf(BluetoothDevice device, String provided) {
        String name = provided;
        if (name == null || name.trim().isEmpty()) {
            try { name = device.getName(); } catch (Exception ignored) {}
        }
        return name == null ? "" : name.trim();
    }

    private void offerDevice(BluetoothDevice device, String name, ScanRecord record,
                             int rssi, boolean bleTransport) {
        if (!scanning || device == null) return;
        String address;
        try { address = device.getAddress(); } catch (Exception e) { return; }
        if (address == null || address.isEmpty()) return;
        String advertisedName = nameOf(device, name);
        CameraDiscovery.Match match =
            CameraDiscovery.classify(device, advertisedName, record, expectedAlias);
        String displayName = advertisedName.isEmpty()
            ? "Unnamed Bluetooth device (…" + address.substring(Math.max(0, address.length() - 5)) + ")"
            : advertisedName;
        String fingerprint = displayName + "|" + match.camera + "|" + match.goPro
            + "|" + bleTransport + "|" + match.reason;
        boolean changed;
        synchronized (discovered) {
            String prior = discovered.put(address, fingerprint);
            Boolean previouslyCamera = cameraFlags.put(address, match.camera);
            // Never demote an already positively identified camera on a later weaker advertisement.
            if (Boolean.TRUE.equals(previouslyCamera) && !match.camera) {
                cameraFlags.put(address, true);
                return;
            }
            changed = prior == null || !prior.equals(fingerprint);
        }
        if (changed) {
            main.post(() -> {
                if (scanning) listener.onFound(device, displayName, match, rssi, bleTransport);
            });
        }
    }

    public void startScan() {
        stopScan();
        BluetoothManager manager =
            (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            status("Enable Bluetooth, then scan again.");
            return;
        }
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            status("Cannot access BLE scanner. Check Nearby devices permission and Bluetooth.");
            return;
        }
        synchronized (discovered) {
            discovered.clear();
            cameraFlags.clear();
        }
        scanning = true;
        // Previously paired cameras may not advertise while switched on.
        try {
            for (BluetoothDevice device : adapter.getBondedDevices()) {
                String n = nameOf(device, null);
                CameraDiscovery.Match match =
                    CameraDiscovery.classify(device, n, null, expectedAlias);
                if (match.camera) offerDevice(device, n, null, -127, true);
            }
        } catch (Exception ignored) {}

        scanCallback = new ScanCallback() {
            @Override public void onScanResult(int type, ScanResult result) {
                if (result == null || result.getDevice() == null) return;
                ScanRecord rec = result.getScanRecord();
                String name = rec == null ? null : rec.getDeviceName();
                offerDevice(result.getDevice(), name, rec, result.getRssi(), true);
            }
            @Override public void onBatchScanResults(List<ScanResult> results) {
                for (ScanResult result : results) onScanResult(0, result);
            }
            @Override public void onScanFailed(int error) {
                status("BLE scan returned error " + error + ". Checking classic Bluetooth cameras...");
                main.post(() -> {
                    if (scanning) {
                        stopBleOnly();
                        beginClassicDiscovery();
                    }
                });
            }
        };
        try {
            scanner.startScan(null,
                new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .setReportDelay(0)
                    .build(),
                scanCallback);
            status("Searching for camera-identifying Bluetooth devices, including renamed GoPros...");
            scanPhaseTimeout = () -> {
                if (scanning) {
                    stopBleOnly();
                    beginClassicDiscovery();
                }
            };
            main.postDelayed(scanPhaseTimeout, 20000);
        } catch (Exception e) {
            status("BLE scan failed: " + e.getMessage() + ". Trying classic Bluetooth...");
            stopBleOnly();
            beginClassicDiscovery();
        }
    }

    private void stopBleOnly() {
        if (scanPhaseTimeout != null) main.removeCallbacks(scanPhaseTimeout);
        scanPhaseTimeout = null;
        if (scanner != null && scanCallback != null) {
            try { scanner.stopScan(scanCallback); } catch (Exception ignored) {}
        }
        scanCallback = null;
        scanner = null;
    }

    private void beginClassicDiscovery() {
        if (!scanning || adapter == null) return;
        if (classicReceiver != null) return;
        classicReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent intent) {
                String action = intent.getAction();
                if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                    BluetoothDevice device;
                    if (Build.VERSION.SDK_INT >= 33) {
                        device = intent.getParcelableExtra(
                            BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
                    } else {
                        device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    }
                    if (device == null) return;
                    String name = intent.getStringExtra(BluetoothDevice.EXTRA_NAME);
                    int rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, (short) -127);
                    offerDevice(device, name, null, rssi, false);
                } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                    finishScan();
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        try {
            if (Build.VERSION.SDK_INT >= 33)
                context.registerReceiver(classicReceiver, filter, Context.RECEIVER_EXPORTED);
            else context.registerReceiver(classicReceiver, filter);
            status("Checking cameras identifiable through classic Bluetooth...");
            boolean started = adapter.startDiscovery();
            if (!started) {
                status("Classic discovery unavailable; BLE results retained.");
                finishScan();
            } else {
                scanPhaseTimeout = this::finishScan;
                main.postDelayed(scanPhaseTimeout, 14000);
            }
        } catch (Exception e) {
            status("Classic Bluetooth scan could not start: " + e.getMessage());
            finishScan();
        }
    }

    private void finishScan() {
        if (!scanning) return;
        int total, cams = 0;
        synchronized (discovered) {
            total = discovered.size();
            for (boolean isCamera : cameraFlags.values()) if (isCamera) cams++;
        }
        stopScan();
        final int cameraCount = cams;
        status(cameraCount == 0
            ? "No identifiable camera found. Try 'Show unclassified devices' or verify GoPro pairing mode."
            : "Found " + cameraCount + " camera candidate(s) among " + total + " Bluetooth devices.");
        main.post(() -> listener.onScanFinished(total, cameraCount));
    }

    public void stopScan() {
        scanning = false;
        stopBleOnly();
        if (scanPhaseTimeout != null) main.removeCallbacks(scanPhaseTimeout);
        scanPhaseTimeout = null;
        if (classicReceiver != null) {
            try { context.unregisterReceiver(classicReceiver); } catch (Exception ignored) {}
            classicReceiver = null;
        }
        if (adapter != null) {
            try { if (adapter.isDiscovering()) adapter.cancelDiscovery(); } catch (Exception ignored) {}
        }
    }

    private synchronized void clearBondListener() {
        if (bondTimeout != null) main.removeCallbacks(bondTimeout);
        bondTimeout = null;
        if (bondReceiver != null) {
            try { context.unregisterReceiver(bondReceiver); } catch (Exception ignored) {}
            bondReceiver = null;
        }
        bondingDevice = null;
    }

    public synchronized void connect(BluetoothDevice device, String name) {
        stopScan();
        disconnect();
        deviceName = name;
        if (device == null) {
            status("Camera is not available to connect.");
            return;
        }
        try {
            if (device.getBondState() == BluetoothDevice.BOND_BONDED) {
                status("Connected camera is already paired; opening controls...");
                connectGatt(device);
                return;
            }
            bondingDevice = device;
            bondReceiver = new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent intent) {
                    if (!BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(intent.getAction())) return;
                    BluetoothDevice d;
                    if (Build.VERSION.SDK_INT >= 33)
                        d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
                    else d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    if (d == null || bondingDevice == null
                            || !d.getAddress().equals(bondingDevice.getAddress())) return;
                    int state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR);
                    if (state == BluetoothDevice.BOND_BONDED) {
                        clearBondListener();
                        status("Android Bluetooth pairing succeeded. Connecting camera controls...");
                        connectGatt(device);
                    } else if (state == BluetoothDevice.BOND_NONE) {
                        clearBondListener();
                        status("Android pairing was refused; attempting direct BLE connection.");
                        connectGatt(device);
                    }
                }
            };
            IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
            if (Build.VERSION.SDK_INT >= 33)
                context.registerReceiver(bondReceiver, filter, Context.RECEIVER_EXPORTED);
            else context.registerReceiver(bondReceiver, filter);
            status("Pairing " + name + ". Accept the Android Bluetooth pairing prompt if it appears.");
            if (device.getBondState() != BluetoothDevice.BOND_BONDING
                    && !device.createBond()) {
                clearBondListener();
                status("Android system pairing was unavailable; trying direct GoPro BLE.");
                connectGatt(device);
                return;
            }
            bondTimeout = () -> {
                if (bondingDevice != null) {
                    clearBondListener();
                    status("Pairing timed out; trying direct BLE control connection.");
                    connectGatt(device);
                }
            };
            main.postDelayed(bondTimeout, 25000);
        } catch (Exception e) {
            clearBondListener();
            status("Pairing error: " + e.getMessage() + ". Trying direct BLE...");
            connectGatt(device);
        }
    }

    private synchronized void connectGatt(BluetoothDevice device) {
        try {
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE);
            if (gatt == null) status("Android could not open a BLE GATT connection.");
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
        clearBondListener();
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
