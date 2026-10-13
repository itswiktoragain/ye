package io.wallfall.mixin;

import io.wallfall.WallfallClient;
import io.wallfall.WallLogic;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockView;
import net.minecraft.world.RaycastContext;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The vanilla update has already placed the camera using WORLD-down and the
 * original unrotated perspective. We must rebuild its basis AND its position
 * after rolling the quaternion: otherwise F5 snaps into the head/wall.
 */
@Mixin(Camera.class)
public abstract class CameraMixin {
    private static final double THIRD_PERSON_DISTANCE = 4.0;
    private static final double NEAR_CLIP_MARGIN = 0.17;

    @Shadow protected abstract void setPos(Vec3d pos);

    @Inject(method = "update", at = @At("TAIL"))
    private void wallfall$updateWallCamera(BlockView area, Entity focusedEntity,
                                            boolean thirdPerson, boolean inverseView,
                                            float tickDelta, CallbackInfo ci) {
        if (focusedEntity != MinecraftClient.getInstance().player) return;
        Direction gravity = WallfallClient.getDirection();
        if (gravity == Direction.DOWN) return;

        Camera camera = (Camera) (Object) this;

        // Preserve vanilla's F5 front-facing flip; rotate its COMPLETE basis
        // into local gravity rather than only changing the quaternion.
        Quaternionf tilt = new Quaternionf().rotationTo(
                new Vector3f(0, -1, 0),
                new Vector3f(gravity.getOffsetX(), gravity.getOffsetY(), gravity.getOffsetZ()));
        if (!valid(tilt)) return;
        Quaternionf rotation = camera.getRotation();
        rotation.premul(tilt.normalize()).normalize();
        if (!valid(rotation)) return;

        Vector3f forward = new Vector3f(0, 0, 1).rotate(rotation);
        Vector3f up = new Vector3f(0, 1, 0).rotate(rotation);
        Vector3f left = new Vector3f(1, 0, 0).rotate(rotation);
        // The camera exposes these vectors to renderers and uses them for its
        // own offset calculations. Stale values caused incorrect F5 movement.
        camera.getHorizontalPlane().set(forward);
        camera.getVerticalPlane().set(up);
        camera.getDiagonalPlane().set(left);

        // Keep the eye INSIDE the rotated collision box rather than letting
        // interpolation put it inside a solid block at a wall/ceiling corner.
        Vec3d foot = focusedEntity.getLerpedPos(tickDelta);
        Vec3d fromSurface = WallLogic.axis(gravity).negate();
        double eyeDistance = Math.min(1.54, Math.max(0.55, focusedEntity.getHeight() - 0.22));
        Vec3d eye = findAirEye(focusedEntity, foot, fromSurface, eyeDistance);
        setPos(eye);

        if (thirdPerson) {
            Vec3d backward = new Vec3d(-forward.x, -forward.y, -forward.z).normalize();
            Vec3d right = new Vec3d(left.x, left.y, left.z);
            Vec3d localUp = new Vec3d(up.x, up.y, up.z);
            double distance = clippedCameraDistance(focusedEntity, eye, backward, right, localUp);
            Vec3d destination = eye.add(backward.multiply(distance));
            if (valid(destination)) setPos(destination);
        }
    }

    private static Vec3d findAirEye(Entity player, Vec3d foot,
                                     Vec3d awayFromSurface, double desiredDistance) {
        Vec3d result = foot.add(awayFromSurface.multiply(desiredDistance));
        if (clear(player, result)) return result;
        // Move TOWARD the centre of the player's hitbox, never through the wall.
        for (int i = 1; i <= 10; i++) {
            double offset = Math.max(0.4, desiredDistance - i * 0.13);
            Vec3d candidate = foot.add(awayFromSurface.multiply(offset));
            if (clear(player, candidate)) return candidate;
        }
        // Collision box centre is the last stable fallback for odd transitions.
        Vec3d centre = player.getBoundingBox().getCenter();
        if (clear(player, centre)) return centre;
        return player.getWorld().isSpaceEmpty(player,
                Box.of(foot, 0.04, 0.04, 0.04)) ? foot : result;
    }

    private static boolean clear(Entity player, Vec3d p) {
        return valid(p) && player.getWorld().isSpaceEmpty(
                player, Box.of(p, 0.22, 0.22, 0.22));
    }

    private static double clippedCameraDistance(Entity player, Vec3d eye,
                   Vec3d backward, Vec3d right, Vec3d up) {
        double distance = THIRD_PERSON_DISTANCE;
        // Camera near plane can clip even when its centre ray is clear.
        // Eight offset rays (as in the vanilla third-person collision scheme)
        // stop the whole screen from passing through blocks.
        for (int rx = -1; rx <= 1; rx += 2) {
            for (int ry = -1; ry <= 1; ry += 2) {
                for (int rz = -1; rz <= 1; rz += 2) {
                    Vec3d jitter = right.multiply(rx * 0.105)
                            .add(up.multiply(ry * 0.105))
                            .add(backward.multiply(rz * 0.055));
                    Vec3d start = eye.add(jitter);
                    Vec3d end = start.add(backward.multiply(THIRD_PERSON_DISTANCE));
                    HitResult hit = player.getWorld().raycast(new RaycastContext(
                            start, end, RaycastContext.ShapeType.COLLIDER,
                            RaycastContext.FluidHandling.NONE, player));
                    if (hit.getType() != HitResult.Type.MISS) {
                        double hitDistance = hit.getPos().distanceTo(start);
                        distance = Math.min(distance,
                                Math.max(0.06, hitDistance - NEAR_CLIP_MARGIN));
                    }
                }
            }
        }
        return Math.max(0.06, distance);
    }

    private static boolean valid(Quaternionf q) {
        return Float.isFinite(q.x) && Float.isFinite(q.y)
                && Float.isFinite(q.z) && Float.isFinite(q.w);
    }

    private static boolean valid(Vec3d v) {
        return Double.isFinite(v.x) && Double.isFinite(v.y)
                && Double.isFinite(v.z);
    }
}
