package io.wallfall;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import java.util.Map;
import java.util.Collections;
import java.util.WeakHashMap;

/** Server and client track separate Entity objects and their authoritative orientation. */
public final class WallfallState {
    private static final Map<Entity, Direction> STATES = Collections.synchronizedMap(new WeakHashMap<>());
    private WallfallState() {}

    public static Direction get(Entity entity) {
        return STATES.getOrDefault(entity, Direction.DOWN);
    }

    public static void set(Entity entity, Direction direction) {
        if (get(entity) == direction) return;
        Direction old = get(entity);
        if (direction == Direction.DOWN) STATES.remove(entity);
        else STATES.put(entity, direction);
        if (direction == Direction.UP && old != Direction.UP) {
            Vec3d candidate = findCeilingAnchor(entity);
            if (candidate != null) entity.setPosition(candidate);
        }
        if (old == Direction.UP && direction == Direction.DOWN) {
            entity.setPosition(entity.getPos().add(0, -entity.getHeight(), 0));
        }
        if (old == Direction.DOWN && direction.getAxis().isHorizontal() &&
                entity.isOnGround()) {
            // Lift the newly sideways player clear of the floor.
            entity.setPosition(entity.getPos().add(0, entity.getWidth() * 0.55 + .05, 0));
        }
        entity.calculateDimensions();
    }

    public static Box rotatedBox(Entity entity, Direction dir) {
        Vec3d p = entity.getPos();
        double half = entity.getWidth() / 2.0;
        double h = entity.getHeight();
        return switch (dir) {
            case NORTH -> new Box(p.x-half, p.y-half, p.z, p.x+half, p.y+half, p.z+h);
            case SOUTH -> new Box(p.x-half, p.y-half, p.z-h, p.x+half, p.y+half, p.z);
            case WEST -> new Box(p.x, p.y-half, p.z-half, p.x+h, p.y+half, p.z+half);
            case EAST -> new Box(p.x-h, p.y-half, p.z-half, p.x, p.y+half, p.z+half);
            // Invert vertical anchoring: feet attach to ceiling, body extends downward.
            case UP -> new Box(p.x-half, p.y-h, p.z-half, p.x+half, p.y, p.z+half);
            case DOWN -> new Box(p.x-half, p.y, p.z-half, p.x+half, p.y+h, p.z+half);
        };
    }

    private static Box at(Entity entity, Direction direction, Vec3d pos) {
        // rotatedBox() uses the entity's current origin. Translate that box to
        // each trial origin without moving the player or mutating world state.
        return rotatedBox(entity, direction).offset(pos.subtract(entity.getPos()));
    }

    /**
     * Transition away from the wall before switching to an upside-down 1.8
     * block box. Without a transition, the old wall's 3rd block can intersect
     * the ceiling-gravity hitbox and reject every rotation.
     */
    private static Vec3d findCeilingAnchor(Entity entity) {
        Vec3d origin = entity.getPos();
        Direction old = get(entity);
        Vec3d away = WallLogic.axis(old).negate();
        if (old == Direction.UP) return origin;

        double[] retreat = { 0, 0.22, 0.45, 0.68, 0.9, 1.1 };
        double[] rise = { 0, 0.15, 0.3, 0.5, 0.7, 0.9, 1.15 };
        for (double r : retreat) {
            for (double lift : rise) {
                Vec3d test = origin.add(away.multiply(r)).add(0, lift, 0);
                if (entity.getWorld().isSpaceEmpty(entity,
                        at(entity, Direction.UP, test).contract(0.025))) {
                    return test;
                }
            }
        }
        return null;
    }

    public static boolean canFit(Entity player, Direction direction) {
        if (direction == Direction.UP) return findCeilingAnchor(player) != null;
        if (direction == Direction.DOWN) return true;
        Box box = rotatedBox(player, direction).contract(.035);
        // On the initial floor-to-wall transition, allow the floor-lift performed
        // by set() without rejecting the rotation because the old feet touch ground.
        if (get(player) == Direction.DOWN && direction.getAxis().isHorizontal() &&
                player.isOnGround()) box = box.offset(0, player.getWidth() * .55 + .05, 0);
        return player.getWorld().isSpaceEmpty(player, box);
    }
}
