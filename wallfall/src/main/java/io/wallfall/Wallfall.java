package io.wallfall;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.BlockState;
import net.minecraft.block.SlabBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class Wallfall implements ModInitializer {
    public static Block ROTATED_SLAB;
    public static BlockEntityType<RotatedSlabBlockEntity> ROTATED_SLAB_BE;

    private static final class GravityState {
        Direction direction;
        int jumpGrace = 0;
        GravityState(Direction direction) { this.direction = direction; }
    }
    private static final Map<UUID, GravityState> STATES = new HashMap<>();

    public static Direction gravity(ServerPlayerEntity player) {
        GravityState state = STATES.get(player.getUuid());
        return state == null ? Direction.DOWN : state.direction;
    }

    private static void set(ServerPlayerEntity player, Direction direction) {
        Direction old = gravity(player);
        if (old == direction) return;
        if (direction == Direction.DOWN) STATES.remove(player.getUuid());
        else STATES.put(player.getUuid(), new GravityState(direction));
        WallfallState.set(player, direction);
        player.setNoGravity(direction != Direction.DOWN);
        player.fallDistance = 0;
        ServerPlayNetworking.send(player, GravityPayload.of(direction));
    }

    @Override
    public void onInitialize() {
        Identifier blockId = Identifier.of("wallfall", "rotated_slab");
        RegistryKey<Block> blockKey = RegistryKey.of(RegistryKeys.BLOCK, blockId);
        ROTATED_SLAB = Registry.register(Registries.BLOCK, blockKey,
                new RotatedSlabBlock(Block.Settings.create().registryKey(blockKey).strength(2.0f).nonOpaque()));
        ROTATED_SLAB_BE = Registry.register(Registries.BLOCK_ENTITY_TYPE,
                Identifier.of("wallfall", "rotated_slab"),
                FabricBlockEntityTypeBuilder.create(RotatedSlabBlockEntity::new, ROTATED_SLAB).build());

        PayloadTypeRegistry.playC2S().register(GravityPayload.ID, GravityPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(GravityPayload.ID, GravityPayload.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(GravityPayload.ID, (payload, context) -> {
            context.server().execute(() -> {
                ServerPlayerEntity player = context.player();
                if (payload.code() == 6) {
                    GravityState state = STATES.get(player.getUuid());
                    if (state != null) state.jumpGrace = 22;
                    return;
                }
                if (payload.code() > 5) return;
                Direction next = payload.direction();
                if (next != Direction.DOWN &&
                        (!player.isAlive() || player.hasVehicle() ||
                        (next != Direction.UP && !WallLogic.hasSurface(player, next)) ||
                        !WallfallState.canFit(player, next))) {
                    ServerPlayNetworking.send(player, GravityPayload.of(gravity(player)));
                    return;
                }
                set(player, next);
            });
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                GravityState state = STATES.get(player.getUuid());
                if (state == null) continue;
                if (!player.isAlive() || player.hasVehicle()) {
                    set(player, Direction.DOWN);
                    continue;
                }
                player.setNoGravity(true);
                player.fallDistance = 0;
                if (state.jumpGrace > 0) state.jumpGrace--;
                // Gravity direction intentionally persists beyond ledges.
            }
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            handler.getPlayer().setNoGravity(false);
            WallfallState.set(handler.getPlayer(), Direction.DOWN);
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                {
            WallfallState.set(handler.getPlayer(), Direction.DOWN);
            handler.getPlayer().setNoGravity(false);
            STATES.remove(handler.getPlayer().getUuid());
        });

        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (!(player instanceof ServerPlayerEntity serverPlayer) && !world.isClient()) return ActionResult.PASS;
            Direction direction = world.isClient() ? WallfallClientDirectionProxy.fromClient() :
                    gravity((ServerPlayerEntity) player);
            if (direction == Direction.DOWN) return ActionResult.PASS;
            ItemStack stack = player.getStackInHand(hand);
            if (!(stack.getItem() instanceof BlockItem blockItem) ||
                    !(blockItem.getBlock() instanceof SlabBlock)) return ActionResult.PASS;
            BlockPos clicked = hit.getBlockPos();
            BlockPos place = world.getBlockState(clicked).isReplaceable() ? clicked : clicked.offset(hit.getSide());
            if (!world.getBlockState(place).isReplaceable() ||
                    !world.getWorldBorder().contains(place)) return ActionResult.PASS;
            if (world.isClient()) return ActionResult.SUCCESS;
            BlockState state = ROTATED_SLAB.getDefaultState().with(RotatedSlabBlock.GRAVITY, direction);
            if (!world.setBlockState(place, state, Block.NOTIFY_ALL)) return ActionResult.FAIL;
            BlockEntity be = world.getBlockEntity(place);
            if (be instanceof RotatedSlabBlockEntity rotated)
                rotated.setOriginal(blockItem.getBlock().getDefaultState());
            if (!player.isCreative()) stack.decrement(1);
            return ActionResult.SUCCESS;
        });
    }

    // Isolated proxy avoids loading MinecraftClient-only classes on the server.
    public static final class WallfallClientDirectionProxy {
        public static Direction fromClient() {
            return net.fabricmc.loader.api.FabricLoader.getInstance()
                    .getEnvironmentType() == net.fabricmc.api.EnvType.CLIENT ?
                    WallfallClient.getDirection() : Direction.DOWN;
        }
    }
}
