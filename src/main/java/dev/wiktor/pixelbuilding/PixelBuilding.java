package dev.wiktor.pixelbuilding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.resources.Identifier;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

public final class PixelBuilding implements ModInitializer {
    public static final String MOD_ID = "pixelbuilding";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    public static Identifier parseId(String value) {
        int colon = value.indexOf(':');
        if (colon < 1 || colon == value.length() - 1) return Identifier.fromNamespaceAndPath("minecraft", "air");
        return Identifier.fromNamespaceAndPath(value.substring(0, colon), value.substring(colon + 1));
    }

    @Override
    public void onInitialize() {
        ModContent.initialize();
        PixelInteractions.initialize();

        PayloadTypeRegistry.serverboundPlay().register(TogglePixelModePayload.TYPE, TogglePixelModePayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(TogglePixelModePayload.TYPE, (payload, context) -> PixelModes.toggle(context.player()));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> PixelModes.clear(handler.player));

        LOGGER.info("Pixel Building 2.0.0 initialized for Minecraft 26.2");
    }
}
