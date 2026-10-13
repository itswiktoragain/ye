package io.wallfall;

import com.mojang.serialization.MapCodec;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

public final class RotatedSlabBlock extends BlockWithEntity {
    public static final MapCodec<RotatedSlabBlock> CODEC = createCodec(RotatedSlabBlock::new);
    public static final DirectionProperty GRAVITY = Properties.FACING;
    private static final VoxelShape DOWN = Block.createCuboidShape(0, 0, 0, 16, 8, 16);
    private static final VoxelShape UP = Block.createCuboidShape(0, 8, 0, 16, 16, 16);
    private static final VoxelShape NORTH = Block.createCuboidShape(0, 0, 0, 16, 16, 8);
    private static final VoxelShape SOUTH = Block.createCuboidShape(0, 0, 8, 16, 16, 16);
    private static final VoxelShape WEST = Block.createCuboidShape(0, 0, 0, 8, 16, 16);
    private static final VoxelShape EAST = Block.createCuboidShape(8, 0, 0, 16, 16, 16);

    public RotatedSlabBlock(Settings settings) {
        super(settings);
        setDefaultState(getStateManager().getDefaultState().with(GRAVITY, Direction.DOWN));
    }

    @Override protected MapCodec<? extends BlockWithEntity> getCodec() { return CODEC; }

    @Override protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(GRAVITY);
    }

    @Nullable @Override public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new RotatedSlabBlockEntity(pos, state);
    }

    @Override protected VoxelShape getOutlineShape(BlockState state, BlockView world,
            BlockPos pos, ShapeContext context) {
        return switch (state.get(GRAVITY)) {
            case DOWN -> DOWN; case UP -> UP;
            case NORTH -> NORTH; case SOUTH -> SOUTH;
            case WEST -> WEST; case EAST -> EAST;
        };
    }
    @Override protected VoxelShape getCollisionShape(BlockState state, BlockView world,
            BlockPos pos, ShapeContext context) {
        return getOutlineShape(state, world, pos, context);
    }

    @Override public BlockState onBreak(World world, BlockPos pos, BlockState state, PlayerEntity player) {
        if (!world.isClient() && !player.isCreative() && world.getBlockEntity(pos) instanceof RotatedSlabBlockEntity be)
            Block.dropStack(world, pos, new ItemStack(be.getOriginal().getBlock()));
        return super.onBreak(world, pos, state, player);
    }
}
