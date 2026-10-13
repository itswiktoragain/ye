package io.wallfall;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactories;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.MovementType;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;
import java.util.UUID;

public final class WallfallClient implements ClientModInitializer {
    private static Direction gravity = Direction.DOWN;
    private static UUID attachedPlayer;
    private static boolean jumpWasDown;
    private static int jumpGrace, switchCooldown;
    private static KeyBinding normalGravityKey;
    private static KeyBinding ceilingGravityKey;

    public static Direction getDirection() { return gravity; }

    @Override public void onInitializeClient() {
        BlockEntityRendererFactories.register(Wallfall.ROTATED_SLAB_BE, RotatedSlabRenderer::new);
        normalGravityKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.wallfall.normal", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_G,
                KeyBinding.MOVEMENT_CATEGORY));
        ceilingGravityKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.wallfall.ceiling", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_C,
                KeyBinding.MOVEMENT_CATEGORY));

        ClientPlayNetworking.registerGlobalReceiver(GravityPayload.ID, (payload, context) ->
                context.client().execute(() -> {
                    ClientPlayerEntity player = context.client().player;
                    if (player != null && payload.code() <= 5)
                        setDirection(player, payload.direction(), false);
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

        // Gravity persists across ledges. Only G / a life-state change restores
        // normal gravity, so a jump or the end of a wall does not drop the player.
        if (client.currentScreen == null) {
            while (normalGravityKey.wasPressed()) {
                if (gravity != Direction.DOWN) setDirection(player, Direction.DOWN, true);
                return;
            }
            while (ceilingGravityKey.wasPressed()) {
                if (gravity != Direction.UP && WallfallState.canFit(player, Direction.UP))
                    setDirection(player, Direction.UP, true);
                return;
            }
        }

        if (gravity == Direction.DOWN) {
            if (switchCooldown == 0 && client.options.forwardKey.isPressed()) {
                Direction facing = player.getHorizontalFacing();
                if (WallLogic.hasSurface(player, facing) && WallfallState.canFit(player, facing))
                    setDirection(player, facing, true);
            }
            return;
        }

        player.setNoGravity(true);
        player.fallDistance = 0;
        if (justJumped && WallLogic.hasSurface(player, gravity)) {
            Vec3d g = WallLogic.axis(gravity);
            player.setVelocity(player.getVelocity().subtract(g.multiply(0.44)));
            jumpGrace = 22;
            if (ClientPlayNetworking.canSend(GravityPayload.ID))
                ClientPlayNetworking.send(new GravityPayload(6));
        }
        if (jumpGrace > 0) jumpGrace--;

        // Once a ceiling is close enough, walking UP a wall rolls onto it.
        // Look direction matters: walking sideways along a high wall no longer
        // triggers an unwanted ceiling switch.
        if (gravity.getAxis().isHorizontal()
                && switchCooldown == 0 && jumpGrace == 0
                && currentMovement(player).y > 0.12
                && WallLogic.hasSurface(player, Direction.UP)
                && WallfallState.canFit(player, Direction.UP)) {
            setDirection(player, Direction.UP, true);
        }
    }

    private static Vec3d projectToPlane(Vec3d direction, Vec3d gravityAxis) {
        return direction.subtract(gravityAxis.multiply(direction.dotProduct(gravityAxis)));
    }

    // Recompute from CURRENT mouse yaw/pitch, in the same gravity-rotated frame
    // as CameraMixin. This works in F5 and on any side of a wall.
    private static Vec3d[] getLocalBasis(ClientPlayerEntity player) {
        Vec3d g = WallLogic.axis(gravity);
        Quaternionf tilt = new Quaternionf().rotationTo(new Vector3f(0, -1, 0),
                new Vector3f(gravity.getOffsetX(), gravity.getOffsetY(), gravity.getOffsetZ()));
        float radians = (float) (Math.PI / 180.0);
        Quaternionf view = tilt.mul(new Quaternionf().rotationYXZ(
                (float) Math.PI - player.getYaw() * radians,
                -player.getPitch() * radians, 0));
        Vector3f fwd = new Vector3f(0, 0, -1).rotate(view);
        Vector3f right = new Vector3f(1, 0, 0).rotate(view);

        Vec3d forward = projectToPlane(new Vec3d(fwd.x, fwd.y, fwd.z), g);
        Vec3d sideways = projectToPlane(new Vec3d(right.x, right.y, right.z), g);

        // Looking exactly into or away from gravity produces zero projection.
        // Use yaw without pitch so W keeps a defined tangential direction.
        if (forward.lengthSquared() < 0.005) {
            Quaternionf flat = new Quaternionf().rotationTo(
                    new Vector3f(0, -1, 0),
                    new Vector3f(gravity.getOffsetX(), gravity.getOffsetY(), gravity.getOffsetZ()));
            flat.mul(new Quaternionf().rotationYXZ(
                    (float) Math.PI - player.getYaw() * radians, 0, 0));
            Vector3f v = new Vector3f(0, 0, -1).rotate(flat);
            forward = projectToPlane(new Vec3d(v.x, v.y, v.z), g);
        }
        if (forward.lengthSquared() > 0.00001) forward = forward.normalize();
        if (sideways.lengthSquared() > 0.00001) sideways = sideways.normalize();
        else {
            Vector3f v = new Vector3f(1, 0, 0).rotate(view);
            sideways = projectToPlane(new Vec3d(v.x, v.y, v.z), g);
            if (sideways.lengthSquared() > 0.00001) sideways = sideways.normalize();
        }
        return new Vec3d[] { forward, sideways };
    }

    private static Vec3d currentMovement(ClientPlayerEntity player) {
        MinecraftClient client = MinecraftClient.getInstance();
        double forward = (client.options.forwardKey.isPressed() ? 1 : 0) -
                (client.options.backKey.isPressed() ? 1 : 0);
        double strafe = (client.options.rightKey.isPressed() ? 1 : 0) -
                (client.options.leftKey.isPressed() ? 1 : 0);
        double len = Math.max(1, Math.hypot(forward, strafe));
        Vec3d[] basis = getLocalBasis(player);
        Vec3d wish = basis[0].multiply(forward / len)
                .add(basis[1].multiply(strafe / len));
        return wish.lengthSquared() > 1 ? wish.normalize() : wish;
    }

    public static void simulateTravel(ClientPlayerEntity player) {
        if (gravity == Direction.DOWN) return;
        Vec3d wish = currentMovement(player);
        double acceleration = player.isSprinting() ? 0.058 : 0.043;
        Vec3d current = player.getVelocity();
        Vec3d next = current.multiply(0.78)
                .add(WallLogic.axis(gravity).multiply(0.073))
                .add(wish.multiply(acceleration));

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
        player.setVelocity(
                Math.abs(moved.x - next.x) > .005 ? 0 : next.x,
                Math.abs(moved.y - next.y) > .005 ? 0 : next.y,
                Math.abs(moved.z - next.z) > .005 ? 0 : next.z);
        player.fallDistance = 0;
    }

    private static void setDirection(ClientPlayerEntity player, Direction next, boolean sync) {
        if (gravity == next) return;
        gravity = next;
        WallfallState.set(player, next);
        switchCooldown = 12;
        attachedPlayer = player.getUuid();
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
