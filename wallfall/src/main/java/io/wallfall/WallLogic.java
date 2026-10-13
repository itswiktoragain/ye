package io.wallfall;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

public final class WallLogic {
    private WallLogic() {}

    // Check a narrow strip between a player's hitbox and the prospective surface.
    // This detects walls, floors and ceilings without treating a jump as an edge.
    public static boolean hasSurface(PlayerEntity player, Direction gravity) {
        if (gravity == Direction.DOWN) return true;
        Box box = player.getBoundingBox();
        Box probe = switch (gravity) {
            case NORTH -> new Box(box.minX + .05, box.minY + .14, box.minZ - .45,
                    box.maxX - .05, box.maxY - .14, box.minZ + .06);
            case SOUTH -> new Box(box.minX + .05, box.minY + .14, box.maxZ - .06,
                    box.maxX - .05, box.maxY - .14, box.maxZ + .45);
            case WEST -> new Box(box.minX - .45, box.minY + .14, box.minZ + .05,
                    box.minX + .06, box.maxY - .14, box.maxZ - .05);
            case EAST -> new Box(box.maxX - .06, box.minY + .14, box.minZ + .05,
                    box.maxX + .45, box.maxY - .14, box.maxZ - .05);
            case UP -> new Box(box.minX + .05, box.maxY - .06, box.minZ + .05,
                    box.maxX - .05, box.maxY + .45, box.maxZ - .05);
            default -> box;
        };
        return !player.getWorld().isSpaceEmpty(player, probe);
    }

    public static Vec3d axis(Direction dir) {
        return new Vec3d(dir.getOffsetX(), dir.getOffsetY(), dir.getOffsetZ());
    }

    public static Vec3d rightAlongWall(Direction wall) {
        return switch (wall) {
            case NORTH -> new Vec3d(1, 0, 0);
            case SOUTH -> new Vec3d(-1, 0, 0);
            case EAST -> new Vec3d(0, 0, 1);
            case WEST -> new Vec3d(0, 0, -1);
            default -> Vec3d.ZERO;
        };
    }
}
