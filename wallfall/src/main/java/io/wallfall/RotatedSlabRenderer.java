package io.wallfall;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Direction;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public final class RotatedSlabRenderer implements BlockEntityRenderer<RotatedSlabBlockEntity> {
    public RotatedSlabRenderer(BlockEntityRendererFactory.Context context) { }

    @Override
    public void render(RotatedSlabBlockEntity entity, float tickDelta, MatrixStack matrices,
                       VertexConsumerProvider vertices, int light, int overlay) {
        Direction gravity = entity.getCachedState().get(RotatedSlabBlock.GRAVITY);
        matrices.push();
        matrices.translate(.5, .5, .5);
        matrices.multiply(new Quaternionf().rotationTo(
                new Vector3f(0, -1, 0),
                new Vector3f(gravity.getOffsetX(), gravity.getOffsetY(), gravity.getOffsetZ())
        ));
        matrices.translate(-.5, -.5, -.5);
        MinecraftClient.getInstance().getBlockRenderManager().renderBlockAsEntity(
                entity.getOriginal(), matrices, vertices, light, overlay);
        matrices.pop();
    }
}
