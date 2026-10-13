package io.wallfall;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Direction;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class Wallfall implements ModInitializer {
    private static final Map<UUID, Direction> STATES = new HashMap<>();

    @Override
    public void onInitialize() {
        PayloadTypeRegistry.playC2S().register(GravityPayload.ID, GravityPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(GravityPayload.ID, GravityPayload.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(GravityPayload.ID, (payload, context) -> {
            context.server().execute(() -> {
                ServerPlayerEntity player = context.player();
                Direction desired = payload.direction();

                if (payload.code() < 0 || payload.code() > 4 ||
                        (desired != Direction.DOWN &&
                         (!player.isAlive() || player.hasVehicle() || !WallLogic.hasWall(player, desired)))) {
                    ServerPlayNetworking.send(player, GravityPayload.of(current(player)));
                    return;
                }

                apply(player, desired);
                ServerPlayNetworking.send(player, GravityPayload.of(desired));
            });
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                Direction direction = current(player);
                if (direction == Direction.DOWN) continue;

                if (!player.isAlive() || player.hasVehicle() || !WallLogic.hasWall(player, direction)) {
                    apply(player, Direction.DOWN);
                    ServerPlayNetworking.send(player, GravityPayload.of(Direction.DOWN));
                } else {
                    player.setNoGravity(true);
                    player.fallDistance = 0;
                }
            }
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            STATES.remove(handler.getPlayer().getUuid());
        });
    }

    private static Direction current(ServerPlayerEntity player) {
        return STATES.getOrDefault(player.getUuid(), Direction.DOWN);
    }

    private static void apply(ServerPlayerEntity player, Direction direction) {
        Direction old = current(player);
        if (direction == Direction.DOWN) STATES.remove(player.getUuid());
        else STATES.put(player.getUuid(), direction);
        if (old != Direction.DOWN || direction != Direction.DOWN) {
            player.setNoGravity(direction != Direction.DOWN);
            player.fallDistance = 0;
        }
    }
}
