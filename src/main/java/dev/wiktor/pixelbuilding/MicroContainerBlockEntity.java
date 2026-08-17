package dev.wiktor.pixelbuilding;

import java.util.List;

import dev.wiktor.pixelbuilding.core.CollisionMesher;
import dev.wiktor.pixelbuilding.core.GridCodec;
import dev.wiktor.pixelbuilding.core.GridData;
import dev.wiktor.pixelbuilding.core.GridPrism;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class MicroContainerBlockEntity extends BlockEntity {
    private GridData grid = new GridData();
    private VoxelShape cachedShape = Shapes.empty();
    private int cachedShapeRevision = Integer.MIN_VALUE;

    public MicroContainerBlockEntity(BlockPos pos, BlockState state) {
        super(ModContent.MICRO_CONTAINER_ENTITY, pos, state);
    }

    public GridData grid() {
        return grid;
    }

    public int revision() {
        return grid.revision();
    }

    public boolean setCell(int x, int y, int z, String materialId) {
        if (!grid.set(x, y, z, materialId)) return false;
        setChanged();
        return true;
    }

    public String removeCell(int x, int y, int z) {
        String removed = grid.remove(x, y, z);
        if (removed != null) setChanged();
        return removed;
    }

    public VoxelShape voxelShape() {
        if (cachedShapeRevision == grid.revision()) return cachedShape;

        VoxelShape shape = Shapes.empty();
        List<GridPrism> prisms = CollisionMesher.mesh(grid);
        for (GridPrism p : prisms) {
            shape = Shapes.or(shape, Shapes.box(
                    p.minX() / 16.0, p.minY() / 16.0, p.minZ() / 16.0,
                    p.maxX() / 16.0, p.maxY() / 16.0, p.maxZ() / 16.0
            ));
        }
        cachedShape = shape.optimize();
        cachedShapeRevision = grid.revision();
        return cachedShape;
    }

    @Override
    public void setChanged() {
        super.setChanged();
        cachedShapeRevision = Integer.MIN_VALUE;

        if (level != null) {
            BlockState state = getBlockState();
            level.sendBlockUpdated(worldPosition, state, state, Block.UPDATE_ALL);
        }
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        output.putString("grid", GridCodec.encode(grid));
        super.saveAdditional(output);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        String encoded = input.getStringOr("grid", "");
        try {
            this.grid = GridCodec.decode(encoded);
        } catch (IllegalArgumentException malformed) {
            PixelBuilding.LOGGER.error("Ignoring malformed Pixel Building data at {}", worldPosition, malformed);
            this.grid = new GridData();
        }
        cachedShapeRevision = Integer.MIN_VALUE;
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registryLookup) {
        return saveWithoutMetadata(registryLookup);
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
