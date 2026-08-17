package dev.wiktor.pixelbuilding.core;

import java.util.ArrayList;
import java.util.List;

/** Greedily merges occupied micro-cells into non-overlapping collision prisms. */
public final class CollisionMesher {
    private CollisionMesher() {}

    public static List<GridPrism> mesh(GridData grid) {
        boolean[] used = new boolean[GridData.CELL_COUNT];
        List<GridPrism> out = new ArrayList<>();

        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int start = GridData.index(x, y, z);
                    if (used[start] || !grid.occupied(x, y, z)) continue;

                    int maxX = x + 1;
                    while (maxX < 16 && canUse(grid, used, maxX, y, z)) maxX++;

                    int maxZ = z + 1;
                    outerZ:
                    while (maxZ < 16) {
                        for (int xx = x; xx < maxX; xx++) {
                            if (!canUse(grid, used, xx, y, maxZ)) break outerZ;
                        }
                        maxZ++;
                    }

                    int maxY = y + 1;
                    outerY:
                    while (maxY < 16) {
                        for (int zz = z; zz < maxZ; zz++) {
                            for (int xx = x; xx < maxX; xx++) {
                                if (!canUse(grid, used, xx, maxY, zz)) break outerY;
                            }
                        }
                        maxY++;
                    }

                    for (int yy = y; yy < maxY; yy++) {
                        for (int zz = z; zz < maxZ; zz++) {
                            for (int xx = x; xx < maxX; xx++) used[GridData.index(xx, yy, zz)] = true;
                        }
                    }
                    out.add(new GridPrism(x, y, z, maxX, maxY, maxZ));
                }
            }
        }
        return out;
    }

    private static boolean canUse(GridData grid, boolean[] used, int x, int y, int z) {
        int i = GridData.index(x, y, z);
        return !used[i] && grid.occupied(x, y, z);
    }
}
