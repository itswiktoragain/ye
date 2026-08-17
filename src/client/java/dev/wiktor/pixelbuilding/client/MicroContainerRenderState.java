package dev.wiktor.pixelbuilding.client;

import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;

public final class MicroContainerRenderState extends BlockEntityRenderState {
    final BlockModelRenderState geometry = new BlockModelRenderState();
    int revision = Integer.MIN_VALUE;
    long neighborSignature = Long.MIN_VALUE;
    boolean ready;
}
