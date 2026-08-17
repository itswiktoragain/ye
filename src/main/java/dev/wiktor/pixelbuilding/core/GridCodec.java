package dev.wiktor.pixelbuilding.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** Compact, self-versioned Base64 payload used inside the block entity's ValueOutput string. */
public final class GridCodec {
    private static final int MAGIC = 0x50425832;
    private static final int FORMAT_VERSION = 1;

    private GridCodec() {}

    public static String encode(GridData grid) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(1024);
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeInt(MAGIC);
                out.writeByte(FORMAT_VERSION);
                List<String> palette = grid.paletteView();
                out.writeShort(palette.size());
                for (String id : palette) out.writeUTF(id);
                int[] cells = grid.copyCells();
                int runCount = 0;
                for (int i = 0; i < cells.length;) {
                    int value = cells[i];
                    int len = 1;
                    while (i + len < cells.length && cells[i + len] == value && len < 0xffff) len++;
                    runCount++;
                    i += len;
                }
                out.writeShort(runCount);
                for (int i = 0; i < cells.length;) {
                    int value = cells[i];
                    int len = 1;
                    while (i + len < cells.length && cells[i + len] == value && len < 0xffff) len++;
                    out.writeShort(value);
                    out.writeShort(len);
                    i += len;
                }
            }
            return Base64.getEncoder().encodeToString(bytes.toByteArray());
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public static GridData decode(String encoded) {
        GridData grid = new GridData();
        if (encoded == null || encoded.isBlank()) return grid;
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException badBase64) {
            throw new IllegalArgumentException("invalid Pixel Building grid Base64", badBase64);
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw))) {
            if (in.readInt() != MAGIC) throw new IllegalArgumentException("invalid Pixel Building grid magic");
            int version = in.readUnsignedByte();
            if (version != FORMAT_VERSION) throw new IllegalArgumentException("unsupported Pixel Building grid version " + version);
            int paletteSize = in.readUnsignedShort();
            if (paletteSize < 1 || paletteSize > GridData.CELL_COUNT + 1) throw new IllegalArgumentException("invalid palette size " + paletteSize);
            List<String> palette = new ArrayList<>(paletteSize);
            for (int i = 0; i < paletteSize; i++) palette.add(in.readUTF());
            if (!palette.get(0).isEmpty()) throw new IllegalArgumentException("palette[0] must be empty");
            int[] cells = new int[GridData.CELL_COUNT];
            int runs = in.readUnsignedShort();
            int cursor = 0;
            for (int r = 0; r < runs; r++) {
                int value = in.readUnsignedShort();
                int len = in.readUnsignedShort();
                if (value >= paletteSize || len <= 0 || cursor + len > cells.length) throw new IllegalArgumentException("malformed Pixel Building RLE stream");
                for (int n = 0; n < len; n++) cells[cursor++] = value;
            }
            if (cursor != cells.length) throw new IllegalArgumentException("RLE stream expands to " + cursor + " cells, expected 4096");
            if (in.available() != 0) throw new IllegalArgumentException("trailing bytes in Pixel Building grid");
            grid.replaceFrom(palette, cells);
            return grid;
        } catch (IOException ex) {
            throw new IllegalArgumentException("truncated Pixel Building grid", ex);
        }
    }
}
