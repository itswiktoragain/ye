package io.wallfall;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.UUID;

public final class WallfallClient implements ClientModInitializer {
    private static Direction gravity = Direction.DOWN;
    private static UUID attachedPlayer;

    public static Direction getDirection() {
        return gravity;
    }

    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(GravityPayload.ID, (payload, context) ->
                context.client().execute(() -> {
                    ClientPlayerEntity player = context.client().player;
                    if (player != null && payload.code() >= 0 && payload.code() <= 4) {
                        setDirection(player, payload.direction(), false);
                    }
                })
        );
        ClientTickEvents.END_CLIENT_TICK.register(WallfallClient::onClientTick);
    }

    private static void onClientTick(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) {
            gravity = Direction.DOWN;
            attachedPlayer = null;
            return;
        }
        if (!player.getUuid().equals(attachedPlayer)) {
            gravity = Direction.DOWN;
            attachedPlayer = player.getUuid();
        }

        if (gravity == Direction.DOWN) {
            if (!player.isAlive() || player.isSpectator() || player.hasVehicle() ||
                    player.getAbilities().flying || !client.options.forwardKey.isPressed()) return;
            Direction facing = player.getHorizontalFacing();
            if (WallLogic.hasWall(player, facing)) setDirection(player, facing, true);
            return;
        }

        if (!player.isAlive() || player.isSpectator() || player.hasVehicle() ||
                player.isTouchingWater() || player.isInLava() || player.getAbilities().flying ||
                client.options.jumpKey.isPressed() || !WallLogic.hasWall(player, gravity)) {
            setDirection(player, Direction.DOWN, true);
            return;
        }

        player.setNoGravity(true);
        player.fallDistance = 0;

        double up = (client.options.forwardKey.isPressed() ? 1 : 0) -
                    (client.options.backKey.isPressed() ? 1 : 0);
        double across = (client.options.rightKey.isPressed() ? 1 : 0) -
                        (client.options.leftKey.isPressed() ? 1 : 0);
        double length = Math.max(1, Math.sqrt(up * up + across * across));
        double speed = player.isSprinting() ? 0.255 : 0.19;
        Vec3d tangent = WallLogic.rightAlongWall(gravity);

        player.setVelocity(
                tangent.x * across / length * speed + gravity.getOffsetX() * 0.11,
                up / length * speed,
                tangent.z * across / length * speed + gravity.getOffsetZ() * 0.11
        );
    }

    private static void setDirection(ClientPlayerEntity player, Direction next, boolean send) {
        if (gravity == next) return;
        gravity = next;
        attachedPlayer = player.getUuid();
        player.setNoGravity(next != Direction.DOWN);
        player.fallDistance = 0;

        if (next == Direction.DOWN) {
            Vec3d old = player.getVelocity();
            player.setVelocity(old.x * 0.65, Math.min(old.y, 0.02), old.z * 0.65);
        } else {
            player.setVelocity(gravity.getOffsetX() * 0.09, 0, gravity.getOffsetZ() * 0.09);
        }

        if (send && ClientPlayNetworking.canSend(GravityPayload.ID)) {
            ClientPlayNetworking.send(GravityPayload.of(next));
        }
    }
}
