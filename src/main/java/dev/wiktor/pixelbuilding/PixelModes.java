package dev.wiktor.pixelbuilding;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.server.level.ServerPlayer;

public final class PixelModes {
    private static final Set<UUID> ENABLED = ConcurrentHashMap.newKeySet();
    private static volatile boolean clientEnabled;

    private PixelModes() {}

    public static boolean enabled(ServerPlayer player) {
        return ENABLED.contains(player.getUUID());
    }

    public static boolean toggle(ServerPlayer player) {
        UUID id = player.getUUID();
        if (ENABLED.remove(id)) return false;
        ENABLED.add(id);
        return true;
    }

    public static void clear(ServerPlayer player) {
        ENABLED.remove(player.getUUID());
    }

    public static boolean clientEnabled() {
        return clientEnabled;
    }

    public static boolean toggleClient() {
        clientEnabled = !clientEnabled;
        return clientEnabled;
    }

    public static void clearClient() {
        clientEnabled = false;
    }
}
