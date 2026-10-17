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
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.ParcelUuid;
import android.os.Looper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Arrays;
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
        void onDiagnostic(String event);
        void onReady(String name);
        void onWifiCredentials(String ssid, String password);
        void onDisconnected();
    }

    private static final String BASE = "b5f9%04x-aa8d-11e3-9046-0002a5d5c51b";
    private static UUID uuid(int value) {
        return UUID.fromString(String.format(java.util.Locale.US, BASE, value));
    }

    // Official Open GoPro: 0xFEA6 is Control and Query, 0x0001 is Wi-Fi AP.
    private static final UUID CMD_SERVICE =
        UUID.fromString("0000fea6-0000-1000-8000-00805f9b34fb");
    private static final UUID WIFI_AP_SERVICE = uuid(0x0001);
    private static final UUID MGMT_SERVICE = uuid(0x0090);
    private static final UUID MGMT_WRITE = uuid(0x0091);
    private static final UUID MGMT_RESPONSE = uuid(0x0092);
    private static final UUID CMD_WRITE = uuid(0x0072);
    private static final UUID CMD_RESPONSE = uuid(0x0073);
    private static final UUID SSID_CHARACTERISTIC = uuid(0x0002);
    private static final UUID PASSWORD_CHARACTERISTIC = uuid(0x0003);
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private static final class PacketToSend {
        final BluetoothGattCharacteristic characteristic;
        final byte[] payload;
        PacketToSend(BluetoothGattCharacteristic characteristic, byte[] payload) {
            this.characteristic = characteristic;
            this.payload = payload;
        }
    }
    private final ArrayDeque<PacketToSend> pending = new ArrayDeque<>();
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
    private ScanCallback priorityScanCallback;
    private Runnable connectionTimeout;
    private Runnable pairingFallbackTimeout;
    private boolean awaitingBond;
    private boolean notifyingManagement;
    private BluetoothGattCharacteristic managementCommand;
    private BluetoothGattCharacteristic managementResponse;
    private BluetoothGattCharacteristic commandResponse;
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
    private void diagnostic(String event) { main.post(() -> listener.onDiagnostic(event)); }

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
            if (match.camera) diagnostic("Camera detected: " + displayName + " (" + match.reason + ", "
                + (rssi > -127 ? rssi + " dBm" : "bonded") + ")");
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
            ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setReportDelay(0)
                .build();
            scanner.startScan(null, settings, scanCallback);
            // Second filtered scan follows the actual GoPro-advertised 0xFEA6 service.
            // It improves discovery while preserving the all-camera scan above.
            priorityScanCallback = new ScanCallback() {
                @Override public void onScanResult(int type, ScanResult result) {
                    if (result == null || result.getDevice() == null) return;
                    ScanRecord record = result.getScanRecord();
                    offerDevice(result.getDevice(), record == null ? null : record.getDeviceName(),
                        record, result.getRssi(), true);
                }
                @Override public void onBatchScanResults(List<ScanResult> results) {
                    for (ScanResult result : results) onScanResult(0, result);
                }
                @Override public void onScanFailed(int code) {
                    diagnostic("GoPro-specific filtered BLE scan unavailable (Android " + code
                        + "); broad camera scan continues.");
                }
            };
            try {
                scanner.startScan(Arrays.asList(new ScanFilter.Builder()
                    .setServiceUuid(new ParcelUuid(CMD_SERVICE)).build()),
                    settings, priorityScanCallback);
                diagnostic("BLE scan started, GoPro service filter FEA6 active.");
            } catch (Exception e) {
                diagnostic("GoPro service filter unavailable: " + e.getMessage());
                priorityScanCallback = null;
            }
            status("Scanning for GoPro GP#### advertisements and other cameras...");
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
        if (scanner != null && priorityScanCallback != null) {
            try { scanner.stopScan(priorityScanCallback); } catch (Exception ignored) {}
        }
        priorityScanCallback = null;
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
        diagnostic("Scan completed: " + cameraCount + " camera candidates, " + total
            + " total Bluetooth devices.");
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
        bondingDevice = null;
        if (bondReceiver != null) {
            try { context.unregisterReceiver(bondReceiver); } catch (Exception ignored) {}
            bondReceiver = null;
        }
    }

    private void installBondListener(BluetoothDevice device) {
        bondingDevice = device;
        bondReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (!BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(intent.getAction())) return;
                BluetoothDevice changed;
                if (Build.VERSION.SDK_INT >= 33)
                    changed = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
                else changed = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (changed == null || bondingDevice == null
                        || !changed.getAddress().equals(bondingDevice.getAddress())) return;
                int bond = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR);
                diagnostic("Android bond state: " + (bond == BluetoothDevice.BOND_BONDED
                    ? "BONDED" : bond == BluetoothDevice.BOND_BONDING ? "BONDING" : "NOT BONDED"));
                if (bond == BluetoothDevice.BOND_BONDED && awaitingBond) {
                    awaitingBond = false;
                    if (pairingFallbackTimeout != null) main.removeCallbacks(pairingFallbackTimeout);
                    status("Bluetooth paired. Reading encrypted camera credentials...");
                    main.postDelayed(() -> {
                        BluetoothGatt current = gatt;
                        if (current != null) readWifiPassword(current);
                    }, 450);
                } else if (bond == BluetoothDevice.BOND_NONE && awaitingBond) {
                    awaitingBond = false;
                    status("Android pairing was rejected. Confirm the GoPro pairing request and retry.");
                    main.postDelayed(() -> {
                        if (gatt != null && !ready) enableNotifications(gatt);
                    }, 600);
                }
            }
        };
        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33)
            context.registerReceiver(bondReceiver, filter, Context.RECEIVER_EXPORTED);
        else
            context.registerReceiver(bondReceiver, filter);
    }

    public synchronized void connect(BluetoothDevice device, String name) {
        stopScan();
        disconnect();
        if (device == null) {
            status("No Bluetooth device selected.");
            return;
        }
        deviceName = name;
        diagnostic("Opening BLE GATT connection to " + name + " (not bonding prematurely).");
        try {
            installBondListener(device);
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE);
            if (gatt == null) {
                status("Android could not start a BLE GATT connection.");
                clearBondListener();
                return;
            }
            status("Connecting to " + name + " over BLE...");
            connectionTimeout = () -> {
                if (!ready && gatt != null) {
                    diagnostic("Connection timeout: no ready state after 35 seconds.");
                    status("Connection timeout. Try reopening GoPro pairing mode and rescanning.");
                    disconnect();
                }
            };
            main.postDelayed(connectionTimeout, 35000);
        } catch (Exception e) {
            status("BLE connection start failed: " + e.getMessage());
            diagnostic("GATT connection error: " + e.getClass().getSimpleName());
            clearBondListener();
        }
    }

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int code, int state) {
            if (code != BluetoothGatt.GATT_SUCCESS) {
                diagnostic("GATT connection failed with status " + code + ".");
                status("Camera Bluetooth GATT error " + code + ".");
                main.post(() -> disconnect());
                return;
            }
            if (state == android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
                diagnostic("GATT connected. Discovering services...");
                status("Bluetooth linked. Discovering GoPro services...");
                if (!g.discoverServices()) {
                    diagnostic("Android failed to start GATT service discovery.");
                    status("Could not start Bluetooth service discovery.");
                }
            } else if (state == android.bluetooth.BluetoothProfile.STATE_DISCONNECTED) {
                diagnostic("Camera disconnected Bluetooth.");
                main.post(() -> disconnect());
            }
        }

        @Override public void onServicesDiscovered(BluetoothGatt g, int code) {
            if (code != BluetoothGatt.GATT_SUCCESS) {
                diagnostic("GATT service discovery error " + code);
                status("Camera service discovery failed (GATT " + code + ").");
                return;
            }
            BluetoothGattService controlService = g.getService(CMD_SERVICE);
            BluetoothGattService wifiService = g.getService(WIFI_AP_SERVICE);
            BluetoothGattService manageService = g.getService(MGMT_SERVICE);
            StringBuilder list = new StringBuilder();
            for (BluetoothGattService service : g.getServices()) {
                if (list.length() > 0) list.append(", ");
                list.append(service.getUuid().toString(), 0, 8);
            }
            diagnostic("Discovered " + g.getServices().size() + " GATT services: " + list);
            if (controlService == null) {
                status("This Bluetooth device does not expose GoPro Control/Query service FEA6.");
                diagnostic("Missing FEA6: possible non-GoPro or unsupported firmware.");
                return;
            }
            command = controlService.getCharacteristic(CMD_WRITE);
            commandResponse = controlService.getCharacteristic(CMD_RESPONSE);
            ssidChar = wifiService == null ? null : wifiService.getCharacteristic(SSID_CHARACTERISTIC);
            passwordChar = wifiService == null ? null : wifiService.getCharacteristic(PASSWORD_CHARACTERISTIC);
            managementCommand = manageService == null ? null : manageService.getCharacteristic(MGMT_WRITE);
            managementResponse = manageService == null ? null : manageService.getCharacteristic(MGMT_RESPONSE);
            if (command == null || commandResponse == null) {
                diagnostic("FEA6 is present but the expected 0072/0073 characteristics are missing.");
                status("Camera has GoPro service but command characteristics are unavailable.");
                return;
            }
            diagnostic("GoPro FEA6/0072/0073 controls found. Wi-Fi credential service "
                + (wifiService == null ? "missing" : "present") + ".");
            if (passwordChar != null
                    && (passwordChar.getProperties() & BluetoothGattCharacteristic.PROPERTY_READ) != 0) {
                status("Requesting encrypted Wi-Fi characteristic to initiate GoPro pairing...");
                readWifiPassword(g);
            } else {
                diagnostic("No readable protected Wi-Fi password characteristic; enabling command responses.");
                enableNotifications(g);
            }
        }

        @Override public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, int code) {
            handleRead(g, c.getUuid(), c.getValue(), code);
        }
        @Override public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c,
                                                    byte[] value, int code) {
            handleRead(g, c.getUuid(), value, code);
        }

        @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor descriptor, int code) {
            UUID id = descriptor.getCharacteristic().getUuid();
            diagnostic("CCCD " + id.toString().substring(0, 8) + ": GATT " + code);
            if (CMD_RESPONSE.equals(id) && !notifyingManagement && managementResponse != null) {
                notifyingManagement = true;
                if (subscribe(g, managementResponse)) return;
            }
            markReady();
        }

        @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int code) {
            synchronized (GoProBleClient.this) {
                writing = false;
                if (code != BluetoothGatt.GATT_SUCCESS) {
                    diagnostic("Characteristic " + c.getUuid().toString().substring(0, 8)
                        + " write failed with GATT " + code);
                    status("GoPro command write failed (GATT " + code + ").");
                } else {
                    diagnostic("BLE packet sent to " + c.getUuid().toString().substring(0, 8));
                }
                pump();
            }
        }
        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            receivedResponse(c.getUuid(), c.getValue());
        }
        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c,
                                                       byte[] data) {
            receivedResponse(c.getUuid(), data);
        }
    };

    private void readWifiPassword(BluetoothGatt g) {
        try {
            if (passwordChar != null && g.readCharacteristic(passwordChar)) {
                diagnostic("Protected GoPro characteristic read initiated (0003).");
                return;
            }
        } catch (Exception e) {
            diagnostic("Protected characteristic read threw " + e.getClass().getSimpleName());
        }
        // Not all cameras expose password. The controls can still operate.
        enableNotifications(g);
    }

    private void handleRead(BluetoothGatt g, UUID characteristic, byte[] value, int code) {
        if (PASSWORD_CHARACTERISTIC.equals(characteristic)) {
            if (code == BluetoothGatt.GATT_SUCCESS && value != null) {
                password = new String(value, StandardCharsets.UTF_8).replace("\u0000", "").trim();
                diagnostic("Encrypted GoPro credential read succeeded (password not logged).");
                if (ssidChar != null && (ssidChar.getProperties()
                        & BluetoothGattCharacteristic.PROPERTY_READ) != 0) {
                    if (g.readCharacteristic(ssidChar)) return;
                }
                enableNotifications(g);
                return;
            }
            diagnostic("Protected GoPro read returned GATT " + code + " (5/15 = authentication needed).");
            if (code == 5 || code == 15 || code == 8) {
                try {
                    if (bondingDevice != null
                            && bondingDevice.getBondState() != BluetoothDevice.BOND_BONDED) {
                        awaitingBond = true;
                        status("Android is pairing the GoPro. Confirm any Bluetooth prompt.");
                        if (bondingDevice.getBondState() != BluetoothDevice.BOND_BONDING) {
                            if (!bondingDevice.createBond())
                                diagnostic("Android createBond declined; waiting for system authentication.");
                        }
                        pairingFallbackTimeout = () -> {
                            if (awaitingBond) {
                                awaitingBond = false;
                                status("Pairing timed out. Trying GoPro commands in limited mode.");
                                enableNotifications(g);
                            }
                        };
                        main.postDelayed(pairingFallbackTimeout, 25000);
                        return;
                    }
                } catch (Exception e) {
                    diagnostic("Android bond request exception: " + e.getClass().getSimpleName());
                }
            }
            status("Camera credentials unavailable; attempting remote control without Wi-Fi credentials.");
            enableNotifications(g);
        } else if (SSID_CHARACTERISTIC.equals(characteristic)) {
            if (code == BluetoothGatt.GATT_SUCCESS && value != null) {
                ssid = new String(value, StandardCharsets.UTF_8).replace("\u0000", "").trim();
                diagnostic("Camera SSID read succeeded (SSID not logged).");
            } else {
                diagnostic("Camera SSID read failed with GATT " + code);
            }
            if (ssid != null && password != null && !ssid.isEmpty() && !password.isEmpty()) {
                final String ssidValue = ssid, passwordValue = password;
                main.post(() -> listener.onWifiCredentials(ssidValue, passwordValue));
            }
            enableNotifications(g);
        }
    }

    private boolean subscribe(BluetoothGatt g, BluetoothGattCharacteristic characteristic) {
        try {
            if (!g.setCharacteristicNotification(characteristic, true)) {
                diagnostic("Failed to register notification " + characteristic.getUuid());
                return false;
            }
            BluetoothGattDescriptor descriptor = characteristic.getDescriptor(CCCD);
            if (descriptor == null) {
                diagnostic("CCCD missing for " + characteristic.getUuid());
                return false;
            }
            byte[] val = (characteristic.getProperties()
                    & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                ? BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                : BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
            descriptor.setValue(val);
            return g.writeDescriptor(descriptor);
        } catch (Exception e) {
            diagnostic("Notification setup error: " + e.getMessage());
            return false;
        }
    }

    private void enableNotifications(BluetoothGatt g) {
        if (g == null || commandResponse == null) {
            diagnostic("Cannot enable notifications: command response missing.");
            return;
        }
        diagnostic("Enabling GoPro command response notifications (0073).");
        if (!subscribe(g, commandResponse)) markReady();
    }

    private synchronized void markReady() {
        if (ready) return;
        ready = true;
        awaitingBond = false;
        if (connectionTimeout != null) main.removeCallbacks(connectionTimeout);
        if (pairingFallbackTimeout != null) main.removeCallbacks(pairingFallbackTimeout);
        connectionTimeout = null;
        pairingFallbackTimeout = null;
        diagnostic("GoPro remote ready using FEA6 service, shutter command 0072.");
        status("GoPro BLE ready. Try Start Shutter to test communication.");
        main.post(() -> listener.onReady(deviceName));
        if (managementCommand != null) {
            // On newer GoPros, end pairing UI using documented Wireless Management protobuf:
            // feature 0x03, action 0x01, field1(result)=0, field2(phoneName)="FrameLink".
            byte[] proto = new byte[] {
                0x03,0x01,0x08,0x00,0x12,0x09,
                'F','r','a','m','e','L','i','n','k'
            };
            byte[] framed = new byte[proto.length + 1];
            framed[0] = (byte)proto.length;
            System.arraycopy(proto, 0, framed, 1, proto.length);
            pending.offer(new PacketToSend(managementCommand, framed));
            diagnostic("Sending GoPro pairing-complete protobuf (00091).");
            pump();
        }
    }

    private void receivedResponse(UUID characteristic, byte[] data) {
        if (data == null || data.length < 3) return;
        // Common short response: header(length), command ID, status byte.
        if (CMD_RESPONSE.equals(characteristic)) {
            int commandId = data[1] & 0xff, statusByte = data[2] & 0xff;
            diagnostic("GoPro command response: id=0x" + Integer.toHexString(commandId)
                + ", status=0x" + Integer.toHexString(statusByte));
            if (statusByte != 0) {
                status("GoPro reported command error 0x" + Integer.toHexString(statusByte) + ".");
            }
        } else if (MGMT_RESPONSE.equals(characteristic)) {
            diagnostic("GoPro pairing management replied (" + data.length + " bytes).");
        }
    }

    public synchronized boolean setShutter(boolean on) {
        return enqueue(command, new byte[] { 0x03, 0x01, 0x01, (byte) (on ? 1 : 0) });
    }

    public synchronized boolean enableWifi() {
        return enqueue(command, new byte[] { 0x03, 0x17, 0x01, 0x01 });
    }

    private synchronized boolean enqueue(BluetoothGattCharacteristic target, byte[] bytes) {
        if (!ready || gatt == null || target == null) {
            status("GoPro BLE not ready. Pair the camera first.");
            return false;
        }
        pending.offer(new PacketToSend(target, bytes));
        pump();
        return true;
    }

    private synchronized void pump() {
        if (writing || pending.isEmpty() || gatt == null) return;
        PacketToSend p = pending.poll();
        writing = true;
        try {
            p.characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            p.characteristic.setValue(p.payload);
            if (!gatt.writeCharacteristic(p.characteristic)) {
                writing = false;
                diagnostic("Android refused a GoPro BLE packet write.");
                status("Bluetooth write failed to start. Retry the command.");
                if (!pending.isEmpty()) main.postDelayed(this::pump, 200);
            }
        } catch (Exception e) {
            writing = false;
            diagnostic("Write exception: " + e.getMessage());
            status("Bluetooth write exception: " + e.getMessage());
        }
    }

    public synchronized void disconnect() {
        ready = false;
        awaitingBond = false;
        if (connectionTimeout != null) main.removeCallbacks(connectionTimeout);
        if (pairingFallbackTimeout != null) main.removeCallbacks(pairingFallbackTimeout);
        connectionTimeout = null;
        pairingFallbackTimeout = null;
        clearBondListener();
        writing = false;
        pending.clear();
        command = null;
        commandResponse = null;
        managementCommand = null;
        managementResponse = null;
        notifyingManagement = false;
        ssidChar = null;
        passwordChar = null;
        ssid = null;
        password = null;
        BluetoothGatt old = gatt;
        gatt = null;
        if (old != null) {
            try { old.disconnect(); old.close(); } catch (Exception ignored) {}
        }
        main.post(listener::onDisconnected);
    }

    public void close() {
        stopScan();
        disconnect();
    }
}
