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

    public static boolean canFit(Entity player, Direction direction) {
        if (direction == Direction.DOWN) return true;
        Box box = rotatedBox(player, direction).contract(.035);
        // On the initial floor-to-wall transition, allow the floor-lift performed
        // by set() without rejecting the rotation because the old feet touch ground.
        if (get(player) == Direction.DOWN && direction.getAxis().isHorizontal() &&
                player.isOnGround()) box = box.offset(0, player.getWidth() * .55 + .05, 0);
        return player.getWorld().isSpaceEmpty(player, box);
    }
}
