package dev.wiktor.pixelbuilding.client;

import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;

import dev.wiktor.pixelbuilding.ModContent;
import dev.wiktor.pixelbuilding.PixelBuilding;
import dev.wiktor.pixelbuilding.PixelModes;
import dev.wiktor.pixelbuilding.TogglePixelModePayload;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraft.network.chat.Component;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public final class PixelBuildingClient implements ClientModInitializer {
    private static final KeyMapping TOGGLE = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.pixelbuilding.toggle",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_J,
            KeyMapping.Category.MISC
    ));

    @Override
    public void onInitializeClient() {
        BlockEntityRenderers.register(ModContent.MICRO_CONTAINER_ENTITY, MicroContainerRenderer::new);
        PlacementPreview.initialize();

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (TOGGLE.consumeClick()) {
                boolean enabled = PixelModes.toggleClient();
                if (client.getConnection() != null) ClientPlayNetworking.send(TogglePixelModePayload.INSTANCE);
                if (client.player != null) client.player.sendOverlayMessage(Component.literal("Pixel Building: " + (enabled ? "ON" : "OFF")));
            }
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> PixelModes.clearClient());
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> PlacementPreview.close());
        PixelBuilding.LOGGER.info("Pixel Building client initializer completed");
    }
}
