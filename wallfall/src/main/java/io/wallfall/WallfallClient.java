package io.wallfall;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactories;
import net.minecraft.entity.MovementType;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.UUID;

public final class WallfallClient implements ClientModInitializer {
    private static Direction gravity = Direction.DOWN;
    private static UUID attachedPlayer;
    private static boolean jumpWasDown;
    private static int airTicks, jumpGrace, switchCooldown;
    public static Direction getDirection() { return gravity; }

    @Override public void onInitializeClient() {
        BlockEntityRendererFactories.register(Wallfall.ROTATED_SLAB_BE, RotatedSlabRenderer::new);
        ClientPlayNetworking.registerGlobalReceiver(GravityPayload.ID, (payload, context) ->
                context.client().execute(() -> {
                    ClientPlayerEntity player = context.client().player;
                    if (player != null && payload.code() <= 5) {
                        // Server is authoritative about the gravity direction.
                        setDirection(player, payload.direction(), false);
                    }
                }));
        ClientTickEvents.START_CLIENT_TICK.register(WallfallClient::tick);
        System.out.println("WALLFALL_CLIENT_READY");
    }

    private static void tick(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) {
            gravity = Direction.DOWN;
            attachedPlayer = null;
            jumpWasDown = false;
            return;
        }
        if (!player.getUuid().equals(attachedPlayer)) {
            gravity = Direction.DOWN;
            WallfallState.set(player, Direction.DOWN);
            attachedPlayer = player.getUuid();
            airTicks = 0;
            jumpGrace = 0;
            switchCooldown = 0;
        }
        if (switchCooldown > 0) switchCooldown--;
        boolean jump = client.options.jumpKey.isPressed();
        boolean justJumped = jump && !jumpWasDown;
        jumpWasDown = jump;
        if (!player.isAlive() || player.isSpectator() || player.hasVehicle() ||
                player.getAbilities().flying || player.isTouchingWater() || player.isInLava()) {
            if (gravity != Direction.DOWN) setDirection(player, Direction.DOWN, true);
            return;
        }
        if (gravity == Direction.DOWN) {
            if (switchCooldown == 0 && client.options.forwardKey.isPressed()) {
                Direction facing = player.getHorizontalFacing();
                if (WallLogic.hasSurface(player, facing) && WallfallState.canFit(player, facing)) setDirection(player, facing, true);
            }
            return;
        }
        player.setNoGravity(true);
        player.fallDistance = 0;

        // A jump pushes AWAY from the attached surface, then gravity draws us back.
        // Do not reset gravity just because jumping creates an air gap.
        if (justJumped && WallLogic.hasSurface(player, gravity)) {
            Vec3d g = WallLogic.axis(gravity);
            player.setVelocity(player.getVelocity().subtract(g.multiply(0.44)));
            jumpGrace = 22;
            ClientPlayNetworking.send(new GravityPayload(6));
        }
        if (jumpGrace > 0) jumpGrace--;

        // At the top of a wall, roll around the edge onto the ceiling.
        if (gravity.getAxis().isHorizontal() &&
                client.options.forwardKey.isPressed() && jumpGrace == 0 && switchCooldown == 0 &&
                WallLogic.hasSurface(player, Direction.UP) && WallfallState.canFit(player, Direction.UP)) {
            setDirection(player, Direction.UP, true);
            return;
        }
        if (WallLogic.hasSurface(player, gravity)) airTicks = 0;
        else airTicks++;
        if (airTicks >= 5 && jumpGrace == 0) {
            setDirection(player, Direction.DOWN, true);
        }
    }

    public static void simulateTravel(ClientPlayerEntity player) {
        if (gravity == Direction.DOWN) return;
        MinecraftClient client = MinecraftClient.getInstance();
        double forward = (client.options.forwardKey.isPressed() ? 1 : 0) -
                (client.options.backKey.isPressed() ? 1 : 0);
        double strafe = (client.options.rightKey.isPressed() ? 1 : 0) -
                (client.options.leftKey.isPressed() ? 1 : 0);
        double len = Math.max(1, Math.hypot(forward, strafe));
        Vec3d horizontal;
        if (gravity == Direction.UP) {
            Vec3d look = WallLogic.axis(player.getHorizontalFacing());
            Vec3d right = new Vec3d(-look.z, 0, look.x);
            horizontal = look.multiply(forward / len).add(right.multiply(strafe / len));
        } else {
            horizontal = new Vec3d(0, forward / len, 0)
                    .add(WallLogic.rightAlongWall(gravity).multiply(strafe / len));
        }
        double acceleration = player.isSprinting() ? 0.058 : 0.043;
        Vec3d gravityAcceleration = WallLogic.axis(gravity).multiply(0.073);
        Vec3d current = player.getVelocity();
        // Preserve the outward jump impulse and add actual force toward the wall/ceiling.
        Vec3d next = current.multiply(0.78)
                .add(gravityAcceleration)
                .add(horizontal.multiply(acceleration));
        next = new Vec3d(Math.max(-0.32, Math.min(0.32, next.x)),
                Math.max(-0.32, Math.min(0.32, next.y)),
                Math.max(-0.32, Math.min(0.32, next.z)));

        if (!Double.isFinite(next.x) || !Double.isFinite(next.y) || !Double.isFinite(next.z)) {
            player.setVelocity(Vec3d.ZERO);
            return;
        }
        Vec3d oldPos = player.getPos();
        player.move(MovementType.SELF, next);
        Vec3d moved = player.getPos().subtract(oldPos);
        // World collision resolves the gravity axis and wall tangential velocities.
        player.setVelocity(
                Math.abs(moved.x - next.x) > .005 ? 0 : next.x,
                Math.abs(moved.y - next.y) > .005 ? 0 : next.y,
                Math.abs(moved.z - next.z) > .005 ? 0 : next.z
        );
        player.fallDistance = 0;
    }

    private static void setDirection(ClientPlayerEntity player, Direction next, boolean sync) {
        if (gravity == next) return;
        gravity = next;
        WallfallState.set(player, next);
        switchCooldown = 12;
        attachedPlayer = player.getUuid();
        airTicks = 0;
        jumpGrace = 0;
        player.setNoGravity(next != Direction.DOWN);
        player.fallDistance = 0;
        if (next == Direction.DOWN) {
            Vec3d old = player.getVelocity();
            player.setVelocity(old.x * .7, Math.min(old.y, 0), old.z * .7);
        } else player.setVelocity(Vec3d.ZERO);
        if (sync && ClientPlayNetworking.canSend(GravityPayload.ID))
            ClientPlayNetworking.send(GravityPayload.of(next));
    }
}
