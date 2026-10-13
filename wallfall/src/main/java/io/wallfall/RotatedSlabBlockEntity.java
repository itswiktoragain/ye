package io.wallfall;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.util.math.BlockPos;

public final class RotatedSlabBlockEntity extends BlockEntity {
    private BlockState original = Blocks.OAK_SLAB.getDefaultState();

    public RotatedSlabBlockEntity(BlockPos pos, BlockState state) {
        super(Wallfall.ROTATED_SLAB_BE, pos, state);
    }

    public BlockState getOriginal() { return original; }

    public void setOriginal(BlockState newOriginal) {
        original = newOriginal;
        markDirty();
        if (world != null && !world.isClient())
            world.updateListeners(pos, getCachedState(), getCachedState(), 3);
    }

    @Override protected void writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        super.writeNbt(nbt, lookup);
        nbt.put("OriginalSlab", NbtHelper.fromBlockState(original));
    }

    @Override protected void readNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        super.readNbt(nbt, lookup);
        if (nbt.contains("OriginalSlab"))
            original = NbtHelper.toBlockState(Registries.BLOCK, nbt.getCompound("OriginalSlab"));
    }

    @Override public NbtCompound toInitialChunkDataNbt(RegistryWrapper.WrapperLookup lookup) {
        return createNbt(lookup);
    }
    @Override public BlockEntityUpdateS2CPacket toUpdatePacket() {
        return BlockEntityUpdateS2CPacket.create(this);
    }
}
