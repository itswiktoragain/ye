package dev.wiktor.pixelbuilding.client;

import com.mojang.blaze3d.vertex.PoseStack;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

import dev.wiktor.pixelbuilding.MicroContainerBlockEntity;
import dev.wiktor.pixelbuilding.PixelBuilding;
import dev.wiktor.pixelbuilding.core.GridData;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.client.renderer.v1.mesh.MutableQuadView;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;

public final class MicroContainerRenderer implements BlockEntityRenderer<MicroContainerBlockEntity, MicroContainerRenderState> {
    private static final Matrix4f IDENTITY = new Matrix4f();

    public MicroContainerRenderer(BlockEntityRendererProvider.Context context) {}

    @Override
    public MicroContainerRenderState createRenderState() {
        return new MicroContainerRenderState();
    }

    @Override
    public void extractRenderState(MicroContainerBlockEntity be, MicroContainerRenderState state, float tickProgress,
                                   Vec3 cameraPos, @Nullable ModelFeatureRenderer.CrumblingOverlay crumblingOverlay) {
        BlockEntityRenderer.super.extractRenderState(be, state, tickProgress, cameraPos, crumblingOverlay);
        long neighborSignature = neighborSignature(be);
        if (state.revision == be.revision() && state.neighborSignature == neighborSignature && state.ready) return;
        rebuild(be, state.geometry);
        state.revision = be.revision();
        state.neighborSignature = neighborSignature;
        state.ready = true;
    }

    @Override
    public void submit(MicroContainerRenderState state, PoseStack matrices, SubmitNodeCollector queue, CameraRenderState cameraState) {
        if (!state.ready) return;
        state.geometry.submit(matrices, queue, state.lightCoords, OverlayTexture.NO_OVERLAY, 0);
    }

    private static void rebuild(MicroContainerBlockEntity be, BlockModelRenderState renderState) {
        QuadEmitter emitter = renderState.setupMesh(IDENTITY, true);
        GridData grid = be.grid();
        Level level = be.getLevel();
        if (level == null) return;
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            String materialId = grid.material(x, y, z);
            if (materialId == null) continue;
            Block block = BuiltInRegistries.BLOCK.getValue(PixelBuilding.parseId(materialId));
            if (block == Blocks.AIR) continue;
            BlockStateModel model = Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(block.defaultBlockState());
            Material.Baked material = model.particleMaterial();
            if (!occupied(level, be.getBlockPos(), x, y - 1, z)) emitFace(emitter, Direction.DOWN, x, y, z, material);
            if (!occupied(level, be.getBlockPos(), x, y + 1, z)) emitFace(emitter, Direction.UP, x, y, z, material);
            if (!occupied(level, be.getBlockPos(), x, y, z - 1)) emitFace(emitter, Direction.NORTH, x, y, z, material);
            if (!occupied(level, be.getBlockPos(), x, y, z + 1)) emitFace(emitter, Direction.SOUTH, x, y, z, material);
            if (!occupied(level, be.getBlockPos(), x - 1, y, z)) emitFace(emitter, Direction.WEST, x, y, z, material);
            if (!occupied(level, be.getBlockPos(), x + 1, y, z)) emitFace(emitter, Direction.EAST, x, y, z, material);
        }
    }

    private static void emitFace(QuadEmitter e, Direction face, int x, int y, int z, Material.Baked material) {
        float x0=x/16f,x1=(x+1)/16f,y0=y/16f,y1=(y+1)/16f,z0=z/16f,z1=(z+1)/16f;
        switch (face) {
            case DOWN -> e.square(face, x0, z0, x1, z1, y0);
            case UP -> e.square(face, x0, 1-z0, x1, 1-z1, 1-y1);
            case NORTH -> e.square(face, 1-x1, y0, 1-x0, y1, z0);
            case SOUTH -> e.square(face, x0, y0, x1, y1, 1-z1);
            case WEST -> e.square(face, z0, y0, z1, y1, x0);
            case EAST -> e.square(face, 1-z0, y0, 1-z1, y1, 1-x1);
        }
        e.cullFace(null);
        e.materialBake(material, MutableQuadView.BAKE_LOCK_UV);
        e.emit();
    }

    private static boolean occupied(Level level, BlockPos origin, int x, int y, int z) {
        int ox=Math.floorDiv(x,16), oy=Math.floorDiv(y,16), oz=Math.floorDiv(z,16);
        int lx=Math.floorMod(x,16), ly=Math.floorMod(y,16), lz=Math.floorMod(z,16);
        BlockPos pos=origin.offset(ox,oy,oz);
        return level.getBlockEntity(pos) instanceof MicroContainerBlockEntity other && other.grid().occupied(lx,ly,lz);
    }

    private static long neighborSignature(MicroContainerBlockEntity be) {
        Level level=be.getLevel(); if (level==null) return 0;
        long hash=0xcbf29ce484222325L;
        for (Direction d:Direction.values()) {
            int rev=level.getBlockEntity(be.getBlockPos().relative(d)) instanceof MicroContainerBlockEntity n ? n.revision() : -1;
            hash^=rev; hash*=0x100000001b3L;
        }
        return hash;
    }
}
