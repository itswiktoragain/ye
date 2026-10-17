package dev.framelink.camera;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.ScanRecord;
import android.os.ParcelUuid;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Finds devices that advertise themselves as cameras. There is no universal
 * BLE "camera" service or flag, so detection is best-effort, not exhaustive.
 * All unclassified devices remain selectable in the advanced discovery list.
 */
public final class CameraDiscovery {
    private CameraDiscovery() {}

    public static final UUID GOPRO_SERVICE =
        UUID.fromString("b5f90001-aa8d-11e3-9046-0002a5d5c51b");

    public static final class Match {
        public final boolean camera;
        public final boolean goPro;
        public final String reason;

        public Match(boolean camera, boolean goPro, String reason) {
            this.camera = camera;
            this.goPro = goPro;
            this.reason = reason;
        }
    }

    private static final String[] CAMERA_TERMS = {
        "camera", "camcorder", "webcam", "dashcam", "dash cam",
        "actioncam", "action cam", "bodycam", "body cam", "trail cam",
        "trailcam", "security cam", "ip cam", "ipcam", "birdcam",
        "sjcam", "akaso", "insta360", "ricoh theta", "theta sc",
        "gopro", "osmo action", "dji action", "dji pocket", "dji osmo",
        "sony ilce", "sony dsc", "sony zv", "sony fx", "sony alpha",
        "canon eos", "canon powershot", "nikon z", "nikon d",
        "coolpix", "fujifilm", "lumix", "panasonic gh", "panasonic s",
        "olympus om", "om system", "leica", "kodak pixpro",
        "yi 4k", "yi action", "garmin virb", "blackmagic pocket",
        "runcam", "caddx", "reolink", "arlo", "wyze cam", "eufycam",
        "tapo c", "hikvision", "ezviz", "instax", "drift ghost"
    };

    private static boolean cameraName(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.ROOT).trim();
        if (n.isEmpty()) return false;
        if (n.startsWith("hero") && (n.length() == 4 || Character.isDigit(n.charAt(4))
                || n.charAt(4) == ' ')) return true;
        if (n.startsWith("gopro-") || n.startsWith("gp-") || n.startsWith("gp_")) return true;
        if (n.startsWith("dsc-") || n.startsWith("ilce-") || n.startsWith("eos ")) return true;
        for (String keyword : CAMERA_TERMS) {
            if (n.contains(keyword)) return true;
        }
        return n.endsWith("-cam") || n.endsWith("_cam")
                || n.startsWith("cam-") || n.startsWith("cam_");
    }

    private static boolean goProName(String name) {
        if (name == null) return false;
        String n = name.trim().toLowerCase(Locale.ROOT);
        return n.startsWith("gopro") || n.startsWith("hero")
                || n.startsWith("gp-") || n.startsWith("gp_");
    }

    private static boolean advertisesGoProService(ScanRecord record) {
        if (record == null) return false;
        List<ParcelUuid> services = record.getServiceUuids();
        if (services != null) {
            for (ParcelUuid id : services) {
                if (id != null && GOPRO_SERVICE.equals(id.getUuid())) return true;
            }
        }
        return record.getServiceData(new ParcelUuid(GOPRO_SERVICE)) != null;
    }

    @SuppressLint("MissingPermission")
    public static Match classify(BluetoothDevice device, String name,
                                  ScanRecord record, String savedAlias) {
        if (advertisesGoProService(record))
            return new Match(true, true, "GoPro Bluetooth service");
        if (goProName(name))
            return new Match(true, true, "GoPro/HERO advertised name");
        // A user-given name for a renamed GoPro is not necessarily a manufacturer ID.
        if (savedAlias != null && !savedAlias.trim().isEmpty()
                && name != null && name.trim().equalsIgnoreCase(savedAlias.trim()))
            return new Match(true, true, "Your saved GoPro camera name");
        try {
            BluetoothClass kind = device == null ? null : device.getBluetoothClass();
            if (kind != null) {
                int deviceClass = kind.getDeviceClass();
                if (deviceClass == BluetoothClass.Device.AUDIO_VIDEO_CAMCORDER
                    || deviceClass == BluetoothClass.Device.AUDIO_VIDEO_VIDEO_CAMERA) {
                    return new Match(true, false, "Bluetooth camera/camcorder class");
                }
            }
        } catch (Exception ignored) {}
        if (cameraName(name))
            return new Match(true, false, "Camera-identifying advertised name");
        return new Match(false, false, "Unclassified Bluetooth device");
    }
}
