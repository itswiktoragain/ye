package dev.wiktor.pixelbuilding;

import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** Exact Amanatides-Woo traversal over the global 1/16-block lattice. */
public final class MicroRaycaster {
    private MicroRaycaster() {}

    public static MicroPos firstOccupied(Level level, Vec3 start, Vec3 look, double maxDistance) {
        Vec3 dir = look.normalize();
        double sx = start.x * 16.0;
        double sy = start.y * 16.0;
        double sz = start.z * 16.0;
        double dx = dir.x * 16.0;
        double dy = dir.y * 16.0;
        double dz = dir.z * 16.0;

        int gx = (int)Math.floor(sx);
        int gy = (int)Math.floor(sy);
        int gz = (int)Math.floor(sz);

        int stepX = Integer.compare((int)Math.signum(dx), 0);
        int stepY = Integer.compare((int)Math.signum(dy), 0);
        int stepZ = Integer.compare((int)Math.signum(dz), 0);

        double tDeltaX = dx == 0.0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dx);
        double tDeltaY = dy == 0.0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dy);
        double tDeltaZ = dz == 0.0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dz);

        double tMaxX = firstBoundaryT(sx, dx, gx, stepX);
        double tMaxY = firstBoundaryT(sy, dy, gy, stepY);
        double tMaxZ = firstBoundaryT(sz, dz, gz, stepZ);
        double t = 0.0;

        for (int guard = 0; guard < 512 && t <= maxDistance; guard++) {
            MicroPos pos = MicroPos.fromGlobalMicro(gx, gy, gz);
            if (level.getBlockEntity(pos.container()) instanceof MicroContainerBlockEntity be
                    && be.grid().occupied(pos.x(), pos.y(), pos.z())) {
                return pos;
            }

            if (tMaxX <= tMaxY && tMaxX <= tMaxZ) {
                gx += stepX;
                t = tMaxX;
                tMaxX += tDeltaX;
            } else if (tMaxY <= tMaxZ) {
                gy += stepY;
                t = tMaxY;
                tMaxY += tDeltaY;
            } else {
                gz += stepZ;
                t = tMaxZ;
                tMaxZ += tDeltaZ;
            }
        }
        return null;
    }

    private static double firstBoundaryT(double s, double d, int cell, int step) {
        if (d == 0.0) return Double.POSITIVE_INFINITY;
        double boundary = step > 0 ? cell + 1.0 : cell;
        return (boundary - s) / d;
    }
}
