package dev.wiktor.pixelbuilding.core;

/** Integer micro-cell bounds; max values are exclusive. */
public record GridPrism(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    public GridPrism {
        if (minX < 0 || minY < 0 || minZ < 0 || maxX > 16 || maxY > 16 || maxZ > 16
                || minX >= maxX || minY >= maxY || minZ >= maxZ) {
            throw new IllegalArgumentException("invalid micro prism");
        }
    }
}
