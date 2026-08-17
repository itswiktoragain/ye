package dev.wiktor.pixelbuilding.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Pure 16^3 micro-grid storage. Palette index 0 means empty. */
public final class GridData {
    public static final int SIZE = 16;
    public static final int CELL_COUNT = SIZE * SIZE * SIZE;

    private final int[] cells = new int[CELL_COUNT];
    private final List<String> palette = new ArrayList<>();
    private final List<Integer> counts = new ArrayList<>();
    private int revision;
    private int occupied;

    public GridData() {
        palette.add("");
        counts.add(0);
    }

    public static int index(int x, int y, int z) {
        requireLocal(x, y, z);
        return x | (z << 4) | (y << 8);
    }

    public static int xOf(int index) {
        requireIndex(index);
        return index & 15;
    }

    public static int zOf(int index) {
        requireIndex(index);
        return (index >>> 4) & 15;
    }

    public static int yOf(int index) {
        requireIndex(index);
        return (index >>> 8) & 15;
    }

    public int paletteIndex(int x, int y, int z) {
        return cells[index(x, y, z)];
    }

    public boolean occupied(int x, int y, int z) {
        return paletteIndex(x, y, z) != 0;
    }

    public String material(int x, int y, int z) {
        int p = paletteIndex(x, y, z);
        return p == 0 ? null : palette.get(p);
    }

    public boolean containsMaterial(String id) {
        int p = palette.indexOf(id);
        return p > 0 && counts.get(p) > 0;
    }

    public int materialCount(String id) {
        int p = palette.indexOf(id);
        return p <= 0 ? 0 : counts.get(p);
    }

    public boolean set(int x, int y, int z, String materialId) {
        Objects.requireNonNull(materialId, "materialId");
        if (materialId.isBlank()) throw new IllegalArgumentException("blank material id");

        int i = index(x, y, z);
        int old = cells[i];
        int next = paletteIndexFor(materialId);
        if (old == next) return false;

        if (old != 0) counts.set(old, counts.get(old) - 1);
        else occupied++;

        cells[i] = next;
        counts.set(next, counts.get(next) + 1);
        revision++;
        return true;
    }

    public String remove(int x, int y, int z) {
        int i = index(x, y, z);
        int old = cells[i];
        if (old == 0) return null;

        String result = palette.get(old);
        cells[i] = 0;
        counts.set(old, counts.get(old) - 1);
        occupied--;
        revision++;
        return result;
    }

    public int occupiedCount() { return occupied; }
    public boolean isEmpty() { return occupied == 0; }
    public int revision() { return revision; }
    public List<String> paletteView() { return Collections.unmodifiableList(palette); }
    public int[] copyCells() { return cells.clone(); }

    public void replaceFrom(List<String> loadedPalette, int[] loadedCells) {
        Objects.requireNonNull(loadedPalette, "loadedPalette");
        Objects.requireNonNull(loadedCells, "loadedCells");
        if (loadedCells.length != CELL_COUNT) throw new IllegalArgumentException("cell array must contain 4096 entries");
        if (loadedPalette.isEmpty() || !loadedPalette.get(0).isEmpty()) throw new IllegalArgumentException("palette[0] must be empty");

        palette.clear();
        counts.clear();
        palette.addAll(loadedPalette);
        for (int ignored = 0; ignored < palette.size(); ignored++) counts.add(0);

        occupied = 0;
        for (int i = 0; i < CELL_COUNT; i++) {
            int p = loadedCells[i];
            if (p < 0 || p >= palette.size()) throw new IllegalArgumentException("invalid palette index " + p);
            cells[i] = p;
            if (p != 0) {
                occupied++;
                counts.set(p, counts.get(p) + 1);
            }
        }
        revision++;
    }

    private int paletteIndexFor(String id) {
        int found = palette.indexOf(id);
        if (found >= 0) return found;
        palette.add(id);
        counts.add(0);
        return palette.size() - 1;
    }

    private static void requireLocal(int x, int y, int z) {
        if ((x | y | z) < 0 || x >= SIZE || y >= SIZE || z >= SIZE) {
            throw new IndexOutOfBoundsException("micro coordinate outside 0..15: " + x + "," + y + "," + z);
        }
    }

    private static void requireIndex(int index) {
        if (index < 0 || index >= CELL_COUNT) throw new IndexOutOfBoundsException(index);
    }
}
