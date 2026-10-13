package io.wallfall;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

public final class WallLogic {
    private WallLogic() { }

    public static boolean hasWall(PlayerEntity player, Direction side) {
        if (!side.getAxis().isHorizontal()) return false;
        Box b = player.getBoundingBox();
        Box sample = new Box(
                b.minX + 0.065, b.minY + 0.20, b.minZ + 0.065,
                b.maxX - 0.065, b.minY + 0.82, b.maxZ - 0.065
        );
        Box towardWall = sample.offset(
                side.getOffsetX() * 0.34, 0, side.getOffsetZ() * 0.34
        );
        return !player.getWorld().isSpaceEmpty(player, towardWall);
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
