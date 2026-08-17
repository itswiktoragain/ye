package dev.wiktor.pixelbuilding.client;

import java.util.Optional;
import java.util.OptionalDouble;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;

import dev.wiktor.pixelbuilding.MicroContainerBlockEntity;
import dev.wiktor.pixelbuilding.MicroPos;
import dev.wiktor.pixelbuilding.ModContent;
import dev.wiktor.pixelbuilding.PixelBuilding;
import dev.wiktor.pixelbuilding.PixelModes;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;

/** Renders a depth-tested wire box exactly around the 1/16 cell that right-click would place. */
public final class PlacementPreview {
    private static final RenderPipeline PIPELINE = RenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
                    .withLocation(PixelBuilding.id("pipeline/pixel_cell_outline"))
                    .build()
    );

    private static final Vector4f COLOR_MODULATOR = new Vector4f(1, 1, 1, 1);
    private static final Vector3f MODEL_OFFSET = new Vector3f();
    private static final Matrix4f TEXTURE_MATRIX = new Matrix4f();
    private static final StagedVertexBuffer BUFFER = new StagedVertexBuffer(
            () -> "Pixel Building placement outline",
            RenderType.SMALL_BUFFER_SIZE
    );

    private static PreviewState preview;

    private PlacementPreview() {}

    public static void initialize() {
        LevelExtractionEvents.END_EXTRACTION.register(PlacementPreview::extract);
        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(PlacementPreview::drawPreview);
    }

    private static void extract(LevelExtractionContext context) {
        preview = null;
        if (!PixelModes.clientEnabled()) return;

        Minecraft client = Minecraft.getInstance();
        if (client.player == null || !(client.player.getMainHandItem().getItem() instanceof BlockItem)) return;
        if (!(client.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return;

        var face = hit.getDirection();
        MicroPos target = MicroPos.justOutside(hit.getLocation(), face.getStepX(), face.getStepY(), face.getStepZ());
        var blockState = context.level().getBlockState(target.container());
        if (!blockState.isAir() && !blockState.is(ModContent.MICRO_CONTAINER)) return;
        if (context.level().getBlockEntity(target.container()) instanceof MicroContainerBlockEntity be
                && be.grid().occupied(target.x(), target.y(), target.z())) return;

        double minX = target.container().getX() + target.x() / 16.0;
        double minY = target.container().getY() + target.y() / 16.0;
        double minZ = target.container().getZ() + target.z() / 16.0;
        preview = new PreviewState(minX, minY, minZ, minX + 1.0 / 16.0, minY + 1.0 / 16.0, minZ + 1.0 / 16.0);
    }

    private static void drawPreview(LevelRenderContext context) {
        PreviewState state = preview;
        if (state == null) return;

        VertexFormat formatBinding = PIPELINE.getVertexFormatBinding(0);
        if (formatBinding == null) return;
        PrimitiveTopology primitive = PIPELINE.getPrimitiveTopology();
        StagedVertexBuffer.Draw draw = BUFFER.appendDraw(
                formatBinding,
                primitive,
                primitive == PrimitiveTopology.QUADS ? RenderSystem.getProjectionType().vertexSorting() : null
        );

        PoseStack matrices = context.poseStack();
        Vec3 camera = context.levelState().cameraRenderState.pos;
        matrices.pushPose();
        matrices.translate(-camera.x, -camera.y, -camera.z);
        VertexConsumer builder = BUFFER.getVertexBuilder(draw);
        renderWireBox(matrices.last().pose(), builder, state);
        matrices.popPose();

        BUFFER.upload();
        StagedVertexBuffer.ExecuteInfo info = BUFFER.getExecuteInfo(draw);
        if (info != null) execute(Minecraft.getInstance(), info);
        BUFFER.endFrame();
    }

    private static void renderWireBox(Matrix4fc matrix, VertexConsumer out, PreviewState s) {
        float x0=(float)s.minX,y0=(float)s.minY,z0=(float)s.minZ,x1=(float)s.maxX,y1=(float)s.maxY,z1=(float)s.maxZ;
        float t=0.0018f,r=1f,g=1f,b=1f,a=0.88f;
        box(matrix,out,x0,y0-t,z0-t,x1,y0+t,z0+t,r,g,b,a); box(matrix,out,x0,y1-t,z0-t,x1,y1+t,z0+t,r,g,b,a);
        box(matrix,out,x0,y0-t,z1-t,x1,y0+t,z1+t,r,g,b,a); box(matrix,out,x0,y1-t,z1-t,x1,y1+t,z1+t,r,g,b,a);
        box(matrix,out,x0-t,y0,z0-t,x0+t,y1,z0+t,r,g,b,a); box(matrix,out,x1-t,y0,z0-t,x1+t,y1,z0+t,r,g,b,a);
        box(matrix,out,x0-t,y0,z1-t,x0+t,y1,z1+t,r,g,b,a); box(matrix,out,x1-t,y0,z1-t,x1+t,y1,z1+t,r,g,b,a);
        box(matrix,out,x0-t,y0-t,z0,x0+t,y0+t,z1,r,g,b,a); box(matrix,out,x1-t,y0-t,z0,x1+t,y0+t,z1,r,g,b,a);
        box(matrix,out,x0-t,y1-t,z0,x0+t,y1+t,z1,r,g,b,a); box(matrix,out,x1-t,y1-t,z0,x1+t,y1+t,z1,r,g,b,a);
    }

    private static void box(Matrix4fc m, VertexConsumer v, float x0,float y0,float z0,float x1,float y1,float z1,float r,float g,float b,float a) {
        v.addVertex(m,x0,y0,z1).setColor(r,g,b,a); v.addVertex(m,x1,y0,z1).setColor(r,g,b,a); v.addVertex(m,x1,y1,z1).setColor(r,g,b,a); v.addVertex(m,x0,y1,z1).setColor(r,g,b,a);
        v.addVertex(m,x1,y0,z0).setColor(r,g,b,a); v.addVertex(m,x0,y0,z0).setColor(r,g,b,a); v.addVertex(m,x0,y1,z0).setColor(r,g,b,a); v.addVertex(m,x1,y1,z0).setColor(r,g,b,a);
        v.addVertex(m,x0,y0,z0).setColor(r,g,b,a); v.addVertex(m,x0,y0,z1).setColor(r,g,b,a); v.addVertex(m,x0,y1,z1).setColor(r,g,b,a); v.addVertex(m,x0,y1,z0).setColor(r,g,b,a);
        v.addVertex(m,x1,y0,z1).setColor(r,g,b,a); v.addVertex(m,x1,y0,z0).setColor(r,g,b,a); v.addVertex(m,x1,y1,z0).setColor(r,g,b,a); v.addVertex(m,x1,y1,z1).setColor(r,g,b,a);
        v.addVertex(m,x0,y1,z1).setColor(r,g,b,a); v.addVertex(m,x1,y1,z1).setColor(r,g,b,a); v.addVertex(m,x1,y1,z0).setColor(r,g,b,a); v.addVertex(m,x0,y1,z0).setColor(r,g,b,a);
        v.addVertex(m,x0,y0,z0).setColor(r,g,b,a); v.addVertex(m,x1,y0,z0).setColor(r,g,b,a); v.addVertex(m,x1,y0,z1).setColor(r,g,b,a); v.addVertex(m,x0,y0,z1).setColor(r,g,b,a);
    }

    private static void execute(Minecraft client, StagedVertexBuffer.ExecuteInfo info) {
        GpuBufferSlice dynamicTransforms = RenderSystem.getDynamicUniforms().writeTransform(RenderSystem.getModelViewMatrixCopy(), COLOR_MODULATOR, MODEL_OFFSET, TEXTURE_MATRIX);
        RenderTarget mainTarget = client.gameRenderer.mainRenderTarget();
        GpuTextureView colorTexture = mainTarget.getColorTextureView();
        if (colorTexture == null) return;
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "Pixel Building placement outline", colorTexture, Optional.empty(), mainTarget.getDepthTextureView(), OptionalDouble.empty())) {
            pass.setPipeline(PIPELINE);
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", dynamicTransforms);
            pass.setVertexBuffer(0, info.vertexBuffer().slice());
            pass.setIndexBuffer(info.indexBuffer(), info.indexType());
            pass.drawIndexed(info.indexCount(), 1, info.firstIndex(), info.baseVertex(), 0);
        }
    }

    public static void close() { BUFFER.close(); }
    private record PreviewState(double minX,double minY,double minZ,double maxX,double maxY,double maxZ) {}
}
