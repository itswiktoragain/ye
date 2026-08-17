package dev.wiktor.pixelbuilding;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public final class TogglePixelModePayload implements CustomPacketPayload {
    public static final TogglePixelModePayload INSTANCE = new TogglePixelModePayload();
    public static final Type<TogglePixelModePayload> TYPE = new Type<>(PixelBuilding.id("toggle_mode"));
    public static final StreamCodec<RegistryFriendlyByteBuf, TogglePixelModePayload> CODEC = StreamCodec.unit(INSTANCE);

    private TogglePixelModePayload() {}

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
