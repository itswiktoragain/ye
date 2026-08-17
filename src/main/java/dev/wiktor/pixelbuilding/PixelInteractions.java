package dev.wiktor.pixelbuilding;

import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;

import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;

public final class PixelInteractions {
    private PixelInteractions() {}

    public static void initialize() {
        UseBlockCallback.EVENT.register(PixelInteractions::useBlock);
        AttackBlockCallback.EVENT.register(PixelInteractions::attackBlock);
    }

    private static InteractionResult useBlock(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
        ItemStack held = player.getItemInHand(hand);
        if (!(held.getItem() instanceof BlockItem blockItem)) return InteractionResult.PASS;

        if (level.isClientSide()) {
            return PixelModes.clientEnabled() ? InteractionResult.SUCCESS : InteractionResult.PASS;
        }

        if (!(player instanceof ServerPlayer serverPlayer) || !PixelModes.enabled(serverPlayer)) return InteractionResult.PASS;
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.SUCCESS;

        Block material = blockItem.getBlock();
        if (material == Blocks.AIR || material == ModContent.MICRO_CONTAINER) return InteractionResult.SUCCESS;

        Direction face = hit.getDirection();
        MicroPos target = MicroPos.justOutside(hit.getLocation(), face.getStepX(), face.getStepY(), face.getStepZ());
        if (!place(serverPlayer, level, target, material, held)) return InteractionResult.SUCCESS;

        player.swing(hand, true);
        return InteractionResult.SUCCESS;
    }

    private static boolean place(ServerPlayer player, Level level, MicroPos target, Block material, ItemStack held) {
        BlockStateCheck existing = BlockStateCheck.at(level, target);
        if (!existing.canContainMicro()) return false;

        String materialId = BuiltInRegistries.BLOCK.getKey(material).toString();
        boolean newlyCreatedContainer = existing.isAir();
        boolean firstMaterial = newlyCreatedContainer;

        MicroContainerBlockEntity be = existing.blockEntity();
        if (be != null) {
            if (be.grid().occupied(target.x(), target.y(), target.z())) return false;
            firstMaterial = !be.grid().containsMaterial(materialId);
        }

        if (firstMaterial && !player.getAbilities().instabuild) {
            if (held.isEmpty()) return false;
            held.shrink(1);
        }

        if (newlyCreatedContainer) {
            if (!level.setBlock(target.container(), ModContent.MICRO_CONTAINER.defaultBlockState(), Block.UPDATE_ALL)) {
                if (firstMaterial && !player.getAbilities().instabuild) held.grow(1);
                return false;
            }
            if (!(level.getBlockEntity(target.container()) instanceof MicroContainerBlockEntity created)) return false;
            be = created;
        }

        return be.setCell(target.x(), target.y(), target.z(), materialId);
    }

    private static InteractionResult attackBlock(Player player, Level level, InteractionHand hand, net.minecraft.core.BlockPos pos, Direction direction) {
        if (level.isClientSide()) {
            if (!PixelModes.clientEnabled()) return InteractionResult.PASS;
            return level.getBlockState(pos).is(ModContent.MICRO_CONTAINER) ? InteractionResult.SUCCESS : InteractionResult.PASS;
        }

        if (!(player instanceof ServerPlayer serverPlayer) || !PixelModes.enabled(serverPlayer)) return InteractionResult.PASS;
        if (!level.getBlockState(pos).is(ModContent.MICRO_CONTAINER)) return InteractionResult.PASS;

        MicroPos hit = MicroRaycaster.firstOccupied(level, player.getEyePosition(), player.getViewVector(1.0F), 6.0);
        if (hit == null || !(level.getBlockEntity(hit.container()) instanceof MicroContainerBlockEntity be)) return InteractionResult.SUCCESS;

        String removed = be.removeCell(hit.x(), hit.y(), hit.z());
        if (removed == null) return InteractionResult.SUCCESS;

        if (!player.getAbilities().instabuild && be.grid().materialCount(removed) == 0) {
            Block refunded = BuiltInRegistries.BLOCK.getValue(PixelBuilding.parseId(removed));
            if (refunded != Blocks.AIR) {
                ItemStack refund = new ItemStack(refunded);
                if (!player.getInventory().add(refund)) player.drop(refund, false);
            }
        }

        if (be.grid().isEmpty()) level.removeBlock(hit.container(), false);
        player.swing(hand, true);
        return InteractionResult.SUCCESS;
    }

    private record BlockStateCheck(boolean air, MicroContainerBlockEntity blockEntity) {
        static BlockStateCheck at(Level level, MicroPos target) {
            var state = level.getBlockState(target.container());
            if (state.isAir()) return new BlockStateCheck(true, null);
            if (state.is(ModContent.MICRO_CONTAINER) && level.getBlockEntity(target.container()) instanceof MicroContainerBlockEntity be) {
                return new BlockStateCheck(false, be);
            }
            return new BlockStateCheck(false, null);
        }

        boolean isAir() { return air; }
        boolean canContainMicro() { return air || blockEntity != null; }
    }
}
