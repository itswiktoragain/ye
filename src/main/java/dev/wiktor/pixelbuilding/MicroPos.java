package dev.wiktor.pixelbuilding;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** A normalized micro-cell location inside one normal block position. */
public record MicroPos(BlockPos container, int x, int y, int z) {
    private static final double EPSILON = 1.0e-7;

    public static MicroPos justOutside(Vec3 worldHit, int normalX, int normalY, int normalZ) {
        double px = worldHit.x + normalX * EPSILON;
        double py = worldHit.y + normalY * EPSILON;
        double pz = worldHit.z + normalZ * EPSILON;
        return fromGlobalMicro(floor(px * 16.0), floor(py * 16.0), floor(pz * 16.0));
    }

    public static MicroPos fromGlobalMicro(int gx, int gy, int gz) {
        int bx = Math.floorDiv(gx, 16);
        int by = Math.floorDiv(gy, 16);
        int bz = Math.floorDiv(gz, 16);
        return new MicroPos(new BlockPos(bx, by, bz), Math.floorMod(gx, 16), Math.floorMod(gy, 16), Math.floorMod(gz, 16));
    }

    public int globalX() { return container.getX() * 16 + x; }
    public int globalY() { return container.getY() * 16 + y; }
    public int globalZ() { return container.getZ() * 16 + z; }

    private static int floor(double value) {
        return (int)Math.floor(value);
    }
}
