package dev.framelink.camera;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Fully native UI. Hardware data is never simulated. */
public class MainActivity extends Activity implements
        GoProBleClient.Listener, CameraWifi.Listener {

    private static final int BG = Color.rgb(15, 20, 26);
    private static final int PANEL = Color.rgb(25, 32, 40);
    private static final int PANEL_LIGHT = Color.rgb(32, 42, 51);
    private static final int OUTLINE = Color.rgb(52, 64, 74);
    private static final int WHITE = Color.rgb(240, 246, 250);
    private static final int MUTED = Color.rgb(155, 174, 184);
    private static final int TEAL = Color.rgb(79, 214, 194);
    private static final int RED = Color.rgb(236, 103, 109);
    private static final int REQ_PERMS = 601;
    private static final int PICK_VIDEO = 602;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService jobs = Executors.newFixedThreadPool(3);
    private final LinkedHashMap<String, BluetoothDevice> found = new LinkedHashMap<>();
    private final LinkedHashMap<String, String> foundNames = new LinkedHashMap<>();
    private final LinkedHashMap<String, CameraDiscovery.Match> foundMatches = new LinkedHashMap<>();
    private final LinkedHashMap<String, Integer> foundRssi = new LinkedHashMap<>();
    private boolean showUnclassified = false;
    private int lastScanCount = -1;
    private final ArrayList<CameraHttpClient.MediaFile> media = new ArrayList<>();
    private final ArrayList<String> bluetoothDiagnostics = new ArrayList<>();

    private GoProBleClient ble;
    private CameraWifi wifi;
    private CameraHttpClient api;
    private Runnable deferredPermissionAction;
    private Runnable intervalPulse;
    private Runnable timedStop;
    private LinearLayout root, mainContent, foundList;
    private ScrollView scroller;
    private TextView statusView, connectionView;
    private int tab;
    private String status = "Find a camera to get started.";
    private String connectedName;
    private String wifiSsid = "", wifiPassword = "";
    private int intervalSeconds = 5, recordSeconds = 15;
    private boolean intervalActive, recordingTimerActive, loadingMedia;
    private int clipStart = 0, clipEnd = 10;

    @Override public void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(0);
        ble = new GoProBleClient(this, this);
        ble.setExpectedCameraName("Moneyman");
        wifi = new CameraWifi(this, this);
        api = new CameraHttpClient(this, wifi);
        createScaffold();
        showTab(0);
    }

    private int dp(float n) { return (int) (getResources().getDisplayMetrics().density * n + .5f); }
    private LinearLayout vertical() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }
    private LinearLayout horizontal() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }
    private GradientDrawable bg(int color, int stroke, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        if (stroke != 0) d.setStroke(dp(1), stroke);
        return d;
    }
    private TextView text(String value, int size, int color, boolean medium) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setFontFeatureSettings("kern");
        if (medium) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setLineSpacing(dp(2), 1.0f);
        return t;
    }
    private void gap(LinearLayout parent, int amount) {
        View spacer = new View(this);
        parent.addView(spacer, new LinearLayout.LayoutParams(1, dp(amount)));
    }
    private LinearLayout.LayoutParams widthWeight(float weight) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight);
    }
    private LinearLayout.LayoutParams rowParams(int margin) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.bottomMargin = dp(margin);
        return p;
    }
    private LinearLayout panel() {
        LinearLayout card = vertical();
        card.setBackground(bg(PANEL, OUTLINE, 22));
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        return card;
    }
    private void addPanel(LinearLayout card) { mainContent.addView(card, rowParams(14)); }

    private TextView button(String label, boolean primary, View.OnClickListener click) {
        TextView v = text(label, 14, primary ? BG : WHITE, true);
        v.setGravity(Gravity.CENTER);
        v.setMinimumHeight(dp(48));
        v.setPadding(dp(12), dp(12), dp(12), dp(12));
        v.setBackground(bg(primary ? TEAL : PANEL_LIGHT, primary ? 0 : OUTLINE, 14));
        v.setClickable(true);
        v.setFocusable(true);
        v.setOnClickListener(click);
        return v;
    }
    private void smallDescription(LinearLayout parent, String value) {
        parent.addView(text(value, 13, MUTED, false), rowParams(8));
    }
    private void eyebrow(LinearLayout parent, String value) {
        TextView t = text(value.toUpperCase(java.util.Locale.ROOT), 11, TEAL, true);
        t.setLetterSpacing(.13f);
        parent.addView(t, rowParams(10));
    }
    private void heading(LinearLayout parent, String title, String description) {
        parent.addView(text(title, 28, WHITE, true), rowParams(6));
        parent.addView(text(description, 14, MUTED, false), rowParams(22));
    }
    private EditText field(String value, int inputType, String hint) {
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setText(value);
        e.setHint(hint);
        e.setTextSize(15);
        e.setTextColor(WHITE);
        e.setHintTextColor(MUTED);
        e.setInputType(inputType);
        e.setPadding(dp(14), dp(10), dp(14), dp(10));
        e.setBackground(bg(PANEL_LIGHT, OUTLINE, 12));
        return e;
    }
    private void twoButtons(LinearLayout card, String a, boolean aPrimary, View.OnClickListener aClick,
                            String b, boolean bPrimary, View.OnClickListener bClick) {
        LinearLayout row = horizontal();
        TextView left = button(a, aPrimary, aClick);
        TextView right = button(b, bPrimary, bClick);
        LinearLayout.LayoutParams first = widthWeight(1);
        first.rightMargin = dp(10);
        row.addView(left, first);
        row.addView(right, widthWeight(1));
        card.addView(row, rowParams(5));
    }

    private void createScaffold() {
        root = vertical();
        root.setFitsSystemWindows(true);
        root.setBackgroundColor(BG);
        setContentView(root);

        LinearLayout header = horizontal();
        header.setPadding(dp(20), dp(20), dp(20), dp(14));
        View logo = new LensView(this, true);
        header.addView(logo, new LinearLayout.LayoutParams(dp(38), dp(38)));
        LinearLayout headerText = vertical();
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        titleParams.leftMargin = dp(11);
        headerText.addView(text("FrameLink", 22, WHITE, true));
        headerText.addView(text("Independent GoPro companion", 11, MUTED, false));
        header.addView(headerText, titleParams);
        connectionView = text("OFFLINE", 11, MUTED, true);
        connectionView.setPadding(dp(12), dp(7), dp(12), dp(7));
        connectionView.setBackground(bg(PANEL, OUTLINE, 100));
        header.addView(connectionView);
        root.addView(header);

        statusView = text(status, 12, MUTED, false);
        statusView.setPadding(dp(17), dp(11), dp(17), dp(11));
        statusView.setBackground(bg(PANEL, 0, 13));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.leftMargin = sp.rightMargin = dp(16);
        sp.bottomMargin = dp(6);
        root.addView(statusView, sp);

        scroller = new ScrollView(this);
        scroller.setFillViewport(true);
        scroller.setVerticalScrollBarEnabled(false);
        mainContent = vertical();
        mainContent.setPadding(dp(16), dp(21), dp(16), dp(32));
        scroller.addView(mainContent);
        root.addView(scroller, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout nav = horizontal();
        nav.setPadding(dp(8), dp(10), dp(8), dp(12));
        nav.setBackgroundColor(PANEL);
        String[] names = {"Remote", "Library", "Automate", "Tools"};
        for (int i = 0; i < names.length; i++) {
            final int index = i;
            TextView item = text(names[i], 12, MUTED, true);
            item.setGravity(Gravity.CENTER);
            item.setPadding(dp(3), dp(15), dp(3), dp(15));
            item.setTag(i);
            item.setOnClickListener(v -> showTab(index));
            nav.addView(item, widthWeight(1));
        }
        root.addView(nav);
        nav.setTag("navigation");
        updateNav();
    }

    private void updateNav() {
        if (root == null) return;
        LinearLayout nav = (LinearLayout) root.getChildAt(root.getChildCount() - 1);
        for (int i = 0; i < nav.getChildCount(); i++) {
            TextView t = (TextView) nav.getChildAt(i);
            boolean selected = tab == i;
            t.setTextColor(selected ? TEAL : MUTED);
            t.setBackground(selected ? bg(PANEL_LIGHT, 0, 12) : null);
        }
        connectionView.setText(ble.isReady() ? "● CONNECTED" : "○ OFFLINE");
        connectionView.setTextColor(ble.isReady() ? TEAL : MUTED);
    }

    private void showTab(int index) {
        tab = index;
        mainContent.removeAllViews();
        foundList = null;
        updateNav();
        scroller.scrollTo(0, 0);
        if (index == 0) renderRemote();
        else if (index == 1) renderMedia();
        else if (index == 2) renderAutomation();
        else renderTools();
    }

    private void renderRemote() {
        eyebrow(mainContent, "CAMERA / CONTROL");
        heading(mainContent, "Your camera, your rules.",
            "Fast capture controls and a reliable local connection.");

        LinearLayout hero = panel();
        hero.setGravity(Gravity.CENTER_HORIZONTAL);
        hero.setPadding(dp(22), dp(18), dp(22), dp(22));
        View lens = new LensView(this, false);
        hero.addView(lens, new LinearLayout.LayoutParams(dp(210), dp(170)));
        TextView cameraLabel = text(ble.isReady() ? connectedName : "No camera connected",
            21, WHITE, true);
        cameraLabel.setGravity(Gravity.CENTER);
        hero.addView(cameraLabel, rowParams(6));
        TextView subtitle = text(ble.isReady() ? "Bluetooth remote ready" :
            "Put the GoPro into pairing mode to begin", 13, MUTED, false);
        subtitle.setGravity(Gravity.CENTER);
        hero.addView(subtitle);
        addPanel(hero);

        LinearLayout capture = panel();
        eyebrow(capture, "QUICK CAPTURE");
        smallDescription(capture, "Uses the video/photo preset currently selected on your GoPro.");
        twoButtons(capture, "●  START SHUTTER", true, v -> shutter(true),
            "■  STOP SHUTTER", false, v -> shutter(false));
        addPanel(capture);

        LinearLayout devices = panel();
        eyebrow(devices, "CAMERA PAIRING");
        smallDescription(devices, "On your GoPro: Connections → Connect Device → GoPro Quik. The Bluetooth name may appear as GP plus four digits, even when the camera is named Moneyman.");
        smallDescription(devices, "Scan searches Bluetooth LE and classic Bluetooth for cameras from any manufacturer. Some cameras do not advertise a recognizable camera identity.");
        devices.addView(button("Scan for all cameras", true, v -> scan()), rowParams(10));
        devices.addView(button(showUnclassified ? "Hide unclassified devices" : "Show unclassified devices", false, v -> {
            showUnclassified = !showUnclassified;
            showTab(0);
        }), rowParams(12));
        foundList = vertical();
        devices.addView(foundList);
        rebuildFoundList();
        addPanel(devices);

        LinearLayout callout = panel();
        eyebrow(callout, "NOT A SIMULATED PREVIEW");
        smallDescription(callout, "Live moving preview is not implemented in this version. Capture and media controls connect to real GoPro hardware.");
        addPanel(callout);
    }

    private void rebuildFoundList() {
        if (foundList == null || tab != 0) return;
        foundList.removeAllViews();
        int visible = 0, cameraCount = 0;
        for (Map.Entry<String, BluetoothDevice> entry : found.entrySet()) {
            String address = entry.getKey();
            CameraDiscovery.Match match = foundMatches.get(address);
            if (match == null) continue;
            if (match.camera) cameraCount++;
            if (!match.camera && !showUnclassified) continue;
            visible++;
            String label = foundNames.get(address);
            String info = match.reason;
            Integer rssi = foundRssi.get(address);
            if (rssi != null && rssi > -127) info += " · " + rssi + " dBm";
            LinearLayout card = vertical();
            card.setPadding(dp(10),dp(10),dp(10),dp(10));
            card.setBackground(bg(PANEL_LIGHT, OUTLINE, 12));
            card.addView(text(label, 14, WHITE, true), rowParams(4));
            card.addView(text(info, 12, MUTED, false), rowParams(9));
            card.addView(button(match.goPro ? "Pair GoPro" : "Connect / inspect camera", false,
                v -> ble.connect(entry.getValue(), label)));
            foundList.addView(card, rowParams(9));
        }
        if (visible == 0) {
            foundList.addView(text(found.isEmpty()
                ? "No devices discovered yet. Turn on camera pairing mode and start a scan."
                : cameraCount == 0 ? "Bluetooth devices found, but none identify as cameras. Enable unclassified devices to inspect them."
                : "Camera results are loading...", 12, MUTED, false));
        }
    }

    private void renderMedia() {
        eyebrow(mainContent, "MEDIA / LIBRARY");
        heading(mainContent, "Camera library.",
            "Explore actual files on the GoPro SD card and save them to your phone.");
        LinearLayout intro = panel();
        smallDescription(intro, "Use Tools to connect to your camera's Wi-Fi. Bluetooth alone does not transfer video.");
        intro.addView(button(loadingMedia ? "Loading camera media..." : "Refresh camera media", true,
            v -> loadMedia()), rowParams(8));
        intro.addView(text(media.isEmpty() ? "No media loaded." : media.size() + " files returned by camera.",
            12, MUTED, false));
        addPanel(intro);

        if (media.isEmpty()) {
            LinearLayout empty = panel();
            eyebrow(empty, "NO PLACEHOLDER MEDIA");
            smallDescription(empty, "Connect camera Wi-Fi and tap Refresh. Only real camera files will appear here.");
            addPanel(empty);
        }
        int index = 0;
        for (CameraHttpClient.MediaFile file : media) {
            final int rowIndex = index++;
            LinearLayout card = panel();
            LinearLayout r = horizontal();
            FrameLayout thumbFrame = new FrameLayout(this);
            thumbFrame.setBackground(bg(PANEL_LIGHT, OUTLINE, 12));
            TextView type = text(file.video() ? "MP4" : "PHOTO", 10, TEAL, true);
            type.setGravity(Gravity.CENTER);
            thumbFrame.addView(type, new FrameLayout.LayoutParams(-1, -1));
            ImageView image = new ImageView(this);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setClipToOutline(true);
            image.setBackground(bg(PANEL_LIGHT, 0, 12));
            thumbFrame.addView(image, new FrameLayout.LayoutParams(-1, -1));
            image.setVisibility(View.GONE);
            r.addView(thumbFrame, new LinearLayout.LayoutParams(dp(74), dp(74)));

            LinearLayout description = vertical();
            LinearLayout.LayoutParams descriptionParams = new LinearLayout.LayoutParams(0, -2, 1);
            descriptionParams.leftMargin = dp(13);
            TextView fileName = text(file.name, 14, WHITE, true);
            fileName.setMaxLines(2);
            description.addView(fileName, rowParams(5));
            description.addView(text(file.directory + " · " + file.displaySize(), 11, MUTED, false));
            r.addView(description, descriptionParams);
            card.addView(r, rowParams(12));
            card.addView(button("Save to Downloads", false, v -> saveMedia(file)));
            addPanel(card);
            if (rowIndex < 30) {
                jobs.execute(() -> {
                    try {
                        Bitmap bmp = api.getThumbnail(file);
                        if (bmp != null) main.post(() -> {
                            if (image.getParent() != null) {
                                image.setImageBitmap(bmp);
                                image.setVisibility(View.VISIBLE);
                            }
                        });
                    } catch (Exception ignored) { /* thumbnails not guaranteed per file */ }
                });
            }
        }
    }

    private void renderAutomation() {
        eyebrow(mainContent, "AUTOMATION / EXTRAS");
        heading(mainContent, "Set it. Capture it.",
            "Simple automation that stays on your phone — no subscription or cloud needed.");

        LinearLayout interval = panel();
        eyebrow(interval, "INTERVAL PHOTO CAPTURE");
        smallDescription(interval, "Switch your GoPro to Photo mode first. A shutter press is sent at each interval while this app remains open.");
        EditText intervalInput = field(String.valueOf(intervalSeconds),
            InputType.TYPE_CLASS_NUMBER, "Seconds between shots");
        interval.addView(intervalInput, rowParams(10));
        twoButtons(interval, intervalActive ? "RUNNING" : "Start interval", true, v -> {
            intervalSeconds = readSeconds(intervalInput, 2, 3600);
            startInterval();
        }, "Stop interval", false, v -> stopInterval());
        addPanel(interval);

        LinearLayout timer = panel();
        eyebrow(timer, "AUTO-STOP RECORDING");
        smallDescription(timer, "Start the shutter now, then automatically stop it after the duration you choose.");
        EditText duration = field(String.valueOf(recordSeconds),
            InputType.TYPE_CLASS_NUMBER, "Record duration in seconds");
        timer.addView(duration, rowParams(10));
        twoButtons(timer, recordingTimerActive ? "TIMER ACTIVE" : "Record with timer", true,
            v -> {
                recordSeconds = readSeconds(duration, 1, 3600);
                startTimedRecording();
            }, "Stop now", false, v -> stopTimedRecording());
        addPanel(timer);

        LinearLayout tips = panel();
        eyebrow(tips, "BEFORE STARTING");
        smallDescription(tips,
            "The active preset is controlled on the camera itself. Keep FrameLink in the foreground: Android may pause timers if the app is closed or suspended.");
        addPanel(tips);
    }

    private void renderTools() {
        eyebrow(mainContent, "CONNECTION / TOOLS");
        heading(mainContent, "Everything connected.",
            "Manage camera Wi-Fi, diagnostics, and local media tools.");

        LinearLayout network = panel();
        eyebrow(network, "GOPRO CAMERA WI-FI");
        smallDescription(network, "First pair via Bluetooth, then wake the camera's access point. The SSID and password may be filled automatically if your firmware exposes them.");
        network.addView(button("Request camera Wi-Fi over Bluetooth", false, v -> {
            if (ble.enableWifi()) updateStatus("Requested camera Wi-Fi. Continue with the network fields below.");
        }), rowParams(12));
        network.addView(text("CAMERA WI-FI NAME", 11, MUTED, true), rowParams(7));
        EditText ssidField = field(wifiSsid, InputType.TYPE_CLASS_TEXT, "GoPro Wi-Fi SSID");
        network.addView(ssidField, rowParams(10));
        network.addView(text("CAMERA WI-FI PASSWORD", 11, MUTED, true), rowParams(7));
        EditText passField = field(wifiPassword,
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, "Camera Wi-Fi password");
        network.addView(passField, rowParams(12));
        network.addView(button("Connect using Android Wi-Fi", true, v -> {
            wifiSsid = ssidField.getText().toString().trim();
            wifiPassword = passField.getText().toString();
            withPermissions(true, () -> wifi.connect(wifiSsid, wifiPassword));
        }), rowParams(8));
        twoButtons(network, "Open Wi-Fi settings", false,
            v -> startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS)),
            "Disconnect Wi-Fi", false, v -> {
                wifi.disconnect();
                updateStatus("App camera Wi-Fi request released.");
            });
        addPanel(network);

        LinearLayout bluetoothLog = panel();
        eyebrow(bluetoothLog, "BLUETOOTH CONNECTION DIAGNOSTICS");
        smallDescription(bluetoothLog, "Live pairing and GoPro service messages. The most recent 18 steps are shown; Wi-Fi passwords are never logged.");
        bluetoothLog.addView(button("Refresh Bluetooth diagnostics", false, v -> showTab(3)), rowParams(10));
        if (bluetoothDiagnostics.isEmpty()) {
            smallDescription(bluetoothLog, "No diagnostic events yet. Scan for Moneyman and attempt to pair.");
        } else {
            int first = Math.max(0, bluetoothDiagnostics.size() - 18);
            for (int i = first; i < bluetoothDiagnostics.size(); i++) {
                bluetoothLog.addView(text(bluetoothDiagnostics.get(i), 12, MUTED, false), rowParams(6));
            }
        }
        addPanel(bluetoothLog);

        LinearLayout diagnostic = panel();
        eyebrow(diagnostic, "CONNECTION DIAGNOSTIC");
        smallDescription(diagnostic, "Test the camera's actual local media API at 10.5.5.9:8080.");
        diagnostic.addView(button("Test GoPro HTTP endpoint", false, v -> jobs.execute(() -> {
            try {
                int size = api.listMedia().size();
                updateStatus("Camera HTTP API responded. " + size + " media files.");
            } catch (Exception e) {
                updateStatus("Camera HTTP test failed: " + shortError(e));
            }
        })));
        addPanel(diagnostic);

        LinearLayout edit = panel();
        eyebrow(edit, "LOCAL VIDEO EDITOR");
        smallDescription(edit, "Trim a video already on your phone without recompression. Cuts align to the previous video keyframe.");
        edit.addView(text("START TIME (SECONDS)", 11, MUTED, true), rowParams(7));
        EditText start = field(String.valueOf(clipStart), InputType.TYPE_CLASS_NUMBER, "Start second");
        edit.addView(start, rowParams(10));
        edit.addView(text("END TIME (SECONDS)", 11, MUTED, true), rowParams(7));
        EditText end = field(String.valueOf(clipEnd), InputType.TYPE_CLASS_NUMBER, "End second");
        edit.addView(end, rowParams(12));
        edit.addView(button("Choose video and trim", false, v -> {
            clipStart = readSeconds(start, 0, 86400);
            clipEnd = readSeconds(end, 1, 86400);
            if (clipEnd <= clipStart) {
                updateStatus("Trim end must be later than start.");
                return;
            }
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.setType("video/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(intent, PICK_VIDEO);
        }));
        addPanel(edit);

        LinearLayout about = panel();
        eyebrow(about, "ABOUT FRAMELINK");
        smallDescription(about, "Independent GoPro camera companion · Open-source Android app · Local-first · No accounts · No analytics.");
        smallDescription(about, "Compatible behavior depends on GoPro model, firmware, available BLE services, and Wi-Fi permissions. Live preview and cloud editing are not yet supported.");
        addPanel(about);
    }

    private int readSeconds(EditText input, int min, int max) {
        try {
            return Math.max(min, Math.min(max, Integer.parseInt(input.getText().toString().trim())));
        } catch (Exception ignored) {
            return min;
        }
    }

    private void withPermissions(boolean forWifi, Runnable action) {
        ArrayList<String> required = new ArrayList<>();
        if (forWifi) {
            required.add(Manifest.permission.ACCESS_FINE_LOCATION);
            if (Build.VERSION.SDK_INT >= 33) required.add(Manifest.permission.NEARBY_WIFI_DEVICES);
        } else if (Build.VERSION.SDK_INT >= 31) {
            required.add(Manifest.permission.BLUETOOTH_SCAN);
            required.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            required.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        ArrayList<String> missing = new ArrayList<>();
        for (String permission : required)
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) missing.add(permission);
        if (missing.isEmpty()) action.run();
        else {
            deferredPermissionAction = action;
            requestPermissions(missing.toArray(new String[0]), REQ_PERMS);
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(requestCode, permissions, grants);
        if (requestCode != REQ_PERMS) return;
        boolean granted = grants.length > 0;
        for (int g : grants) if (g != PackageManager.PERMISSION_GRANTED) granted = false;
        Runnable action = deferredPermissionAction;
        deferredPermissionAction = null;
        if (granted && action != null) action.run();
        else updateStatus("Required Bluetooth/Wi-Fi permission was denied.");
    }

    private void scan() {
        found.clear();
        foundNames.clear();
        foundMatches.clear();
        foundRssi.clear();
        rebuildFoundList();
        withPermissions(false, ble::startScan);
    }

    private void shutter(boolean start) {
        if (ble.setShutter(start)) updateStatus(start ? "Sent shutter ON to GoPro." : "Sent shutter OFF to GoPro.");
    }

    private void startInterval() {
        if (!ble.isReady()) {
            updateStatus("Pair your GoPro before using interval capture.");
            return;
        }
        stopTimedRecording();
        stopInterval();
        intervalActive = true;
        intervalPulse = new Runnable() {
            @Override public void run() {
                if (!intervalActive || !ble.isReady()) {
                    stopInterval();
                    return;
                }
                ble.setShutter(true);
                main.postDelayed(() -> {
                    if (intervalActive) ble.setShutter(false);
                }, 500);
                main.postDelayed(this, intervalSeconds * 1000L);
            }
        };
        main.post(intervalPulse);
        updateStatus("Interval shooting enabled: every " + intervalSeconds + " seconds.");
        if (tab == 2) showTab(2);
    }
    private void stopInterval() {
        boolean active = intervalActive;
        intervalActive = false;
        if (intervalPulse != null) main.removeCallbacks(intervalPulse);
        intervalPulse = null;
        if (active) {
            ble.setShutter(false);
            updateStatus("Interval shooting stopped.");
            if (tab == 2) showTab(2);
        }
    }

    private void startTimedRecording() {
        if (!ble.isReady()) {
            updateStatus("Pair a GoPro before timed recording.");
            return;
        }
        stopInterval();
        stopTimedRecording();
        if (!ble.setShutter(true)) return;
        recordingTimerActive = true;
        timedStop = () -> {
            recordingTimerActive = false;
            ble.setShutter(false);
            updateStatus("Timed recording finished and shutter OFF sent.");
            if (tab == 2) showTab(2);
        };
        main.postDelayed(timedStop, recordSeconds * 1000L);
        updateStatus("Recording with " + recordSeconds + "-second stop timer.");
        if (tab == 2) showTab(2);
    }
    private void stopTimedRecording() {
        boolean active = recordingTimerActive;
        recordingTimerActive = false;
        if (timedStop != null) main.removeCallbacks(timedStop);
        timedStop = null;
        if (active) {
            ble.setShutter(false);
            updateStatus("Recording timer stopped; shutter OFF sent.");
            if (tab == 2) showTab(2);
        }
    }

    private void loadMedia() {
        if (loadingMedia) return;
        loadingMedia = true;
        updateStatus("Requesting actual media list from camera...");
        if (tab == 1) showTab(1);
        jobs.execute(() -> {
            try {
                List<CameraHttpClient.MediaFile> result = api.listMedia();
                main.post(() -> {
                    media.clear();
                    media.addAll(result);
                    loadingMedia = false;
                    updateStatus("Loaded " + result.size() + " files from GoPro SD card.");
                    if (tab == 1) showTab(1);
                });
            } catch (Exception e) {
                main.post(() -> {
                    loadingMedia = false;
                    updateStatus("Cannot load media: " + shortError(e) + ". Check camera Wi-Fi.");
                    if (tab == 1) showTab(1);
                });
            }
        });
    }

    private void saveMedia(CameraHttpClient.MediaFile file) {
        updateStatus("Downloading " + file.name + " from camera...");
        jobs.execute(() -> {
            try {
                final long[] last = {0L};
                Uri destination = api.download(file, (downloaded, total) -> {
                    long now = android.os.SystemClock.elapsedRealtime();
                    if (now - last[0] < 800) return;
                    last[0] = now;
                    if (total > 0) updateStatus("Downloading " + file.name + ": " +
                        (downloaded * 100 / total) + "%");
                });
                main.post(() -> {
                    updateStatus("Saved " + file.name + " in Downloads/FrameLink.");
                    Toast.makeText(this, "Saved to Downloads/FrameLink", Toast.LENGTH_LONG).show();
                    try {
                        Intent open = new Intent(Intent.ACTION_VIEW);
                        open.setDataAndType(destination, file.video() ? "video/mp4" : "image/jpeg");
                        open.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    } catch (Exception ignored) { }
                });
            } catch (Exception e) { updateStatus("Download failed: " + shortError(e)); }
        });
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_VIDEO || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri input = data.getData();
        updateStatus("Trimming local video (keyframe-aligned)...");
        jobs.execute(() -> {
            try {
                Uri output = VideoTrimmer.trim(this, input, clipStart * 1000L, clipEnd * 1000L);
                main.post(() -> {
                    updateStatus("Trimmed video saved to Movies/FrameLink.");
                    Toast.makeText(this, "Saved trimmed clip to Movies/FrameLink", Toast.LENGTH_LONG).show();
                    Intent view = new Intent(Intent.ACTION_VIEW);
                    view.setDataAndType(output, "video/mp4");
                    view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    try { startActivity(view); } catch (Exception ignored) { }
                });
            } catch (Exception e) { updateStatus("Video trim failed: " + shortError(e)); }
        });
    }

    private String shortError(Exception e) {
        String message = e.getMessage();
        if (message == null || message.trim().isEmpty()) return e.getClass().getSimpleName();
        return message.length() > 120 ? message.substring(0, 120) : message;
    }
    private void updateStatus(String message) {
        main.post(() -> {
            status = message;
            if (statusView != null) statusView.setText(message);
        });
    }

    @Override public void onStatus(String message) { updateStatus(message); }

    @Override public void onFound(BluetoothDevice device, String name,
            CameraDiscovery.Match match, int rssi, boolean bleTransport) {
        String address = device.getAddress();
        found.put(address, device);
        foundNames.put(address, name);
        foundMatches.put(address, match);
        foundRssi.put(address, rssi);
        rebuildFoundList();
    }

    @Override public void onScanFinished(int totalDevices, int cameras) {
        lastScanCount = totalDevices;
        if (cameras == 0) updateStatus("Found " + totalDevices + " Bluetooth devices, but none identified as cameras. Try showing unclassified devices.");
        else updateStatus("Found " + cameras + " camera candidates among " + totalDevices + " nearby Bluetooth devices.");
    }

    @Override public void onDiagnostic(String event) {
        main.post(() -> {
            String time = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT)
                .format(new java.util.Date());
            bluetoothDiagnostics.add(time + "  " + event);
            if (bluetoothDiagnostics.size() > 120) bluetoothDiagnostics.remove(0);
        });
    }

    @Override public void onReady(String name) {
        connectedName = name;
        updateStatus("Connected to " + name + " via BLE.");
        if (tab == 0) showTab(0);
        else updateNav();
    }

    @Override public void onWifiCredentials(String ssid, String password) {
        wifiSsid = ssid;
        wifiPassword = password;
        updateStatus("Camera supplied its Wi-Fi credentials via Bluetooth.");
        if (tab == 3) showTab(3);
    }

    @Override public void onDisconnected() {
        if (recordingTimerActive) stopTimedRecording();
        if (intervalActive) stopInterval();
        connectedName = null;
        if (root != null) {
            updateNav();
            if (tab == 0) showTab(0);
        }
    }

    @Override public void onConnected() {
        updateStatus("Camera Wi-Fi is active. Open Library to view media.");
        if (tab == 3) showTab(3);
    }

    @Override protected void onDestroy() {
        stopInterval();
        stopTimedRecording();
        ble.close();
        wifi.disconnect();
        jobs.shutdownNow();
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    /** Locally drawn optical lens symbol; never presents fake live camera pixels. */
    private static final class LensView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final boolean iconOnly;
        LensView(Activity context, boolean small) {
            super(context);
            iconOnly = small;
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        }
        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            float w = getWidth(), h = getHeight();
            float cx = w / 2, cy = h / 2;
            float r = Math.min(w, h) * (iconOnly ? .43f : .42f);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(iconOnly ? Color.rgb(28, 62, 65) : Color.rgb(25, 45, 51));
            c.drawCircle(cx, cy, r, paint);
            for (int i = 0; i < 4; i++) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Math.max(1.2f, w * (iconOnly ? .025f : .007f)));
                paint.setColor(i == 0 ? TEAL : i == 1 ? Color.rgb(90, 165, 159)
                    : Color.rgb(58, 112, 117));
                c.drawCircle(cx, cy, r * (1f - i * .21f), paint);
            }
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(TEAL);
            c.drawCircle(cx, cy, r * .125f, paint);
            if (!iconOnly) {
                paint.setColor(Color.rgb(45, 76, 79));
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Math.max(1f, w * .008f));
                c.drawArc(new RectF(cx-r*1.22f,cy-r*1.22f,cx+r*1.22f,cy+r*1.22f),
                    203, 65, false, paint);
                c.drawArc(new RectF(cx-r*1.22f,cy-r*1.22f,cx+r*1.22f,cy+r*1.22f),
                    23, 65, false, paint);
            }
        }
    }
}
