package dev.schembot.schematic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Format-independent schematic. Blocks are stored as palette indices; index 0 is always air.
 * Layout: index = (y * sizeZ + z) * sizeX + x.
 */
public final class Schematic {
    public static final String AIR = "minecraft:air";
    public static final String STRUCTURE_VOID = "minecraft:structure_void";

    public record Stats(long placeable, Map<String, Long> byBlock) {}

    private final String name;
    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    private final String[] palette;
    private final int[] blocks;

    private Schematic(String name, int sizeX, int sizeY, int sizeZ, String[] palette, int[] blocks) {
        this.name = name;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.palette = palette;
        this.blocks = blocks;
    }

    public static Builder builder(String name, int sizeX, int sizeY, int sizeZ) {
        return new Builder(name, sizeX, sizeY, sizeZ);
    }

    // ---- state string helpers ---------------------------------------------

    /** Adds the "minecraft:" namespace when missing. */
    public static String normalize(String state) {
        String s = state.trim();
        int bracket = s.indexOf('[');
        int colon = s.indexOf(':');
        if (colon < 0 || (bracket >= 0 && colon > bracket)) {
            return "minecraft:" + s;
        }
        return s;
    }

    public static String baseName(String state) {
        int i = state.indexOf('[');
        return i < 0 ? state : state.substring(0, i);
    }

    public static boolean isAir(String state) {
        String base = baseName(state);
        return base.equals(AIR) || base.equals("minecraft:cave_air") || base.equals("minecraft:void_air");
    }

    /** Structure void means "leave whatever is there"; the planner skips it. */
    public static boolean isIgnored(String state) {
        return baseName(state).equals(STRUCTURE_VOID);
    }

    // ---- accessors ----------------------------------------------------------

    public String name() { return name; }
    public int sizeX() { return sizeX; }
    public int sizeY() { return sizeY; }
    public int sizeZ() { return sizeZ; }
    public long volume() { return (long) sizeX * sizeY * sizeZ; }
    public int paletteSize() { return palette.length; }

    public String paletteState(int paletteIndex) {
        return palette[paletteIndex];
    }

    public boolean inBounds(int x, int y, int z) {
        return x >= 0 && y >= 0 && z >= 0 && x < sizeX && y < sizeY && z < sizeZ;
    }

    public int paletteIndexAt(int x, int y, int z) {
        if (!inBounds(x, y, z)) {
            throw new IndexOutOfBoundsException(
                    "(" + x + "," + y + "," + z + ") outside " + sizeX + "x" + sizeY + "x" + sizeZ);
        }
        return blocks[(y * sizeZ + z) * sizeX + x];
    }

    public String stateAt(int x, int y, int z) {
        return palette[paletteIndexAt(x, y, z)];
    }

    /** One pass over the volume: placeable block count and per-block-type totals (descending). */
    public Stats stats() {
        long[] counts = new long[palette.length];
        for (int b : blocks) {
            counts[b]++;
        }
        long placeable = 0;
        Map<String, Long> merged = new HashMap<>();
        for (int i = 1; i < palette.length; i++) {
            if (counts[i] == 0 || isIgnored(palette[i])) {
                continue;
            }
            placeable += counts[i];
            merged.merge(baseName(palette[i]), counts[i], Long::sum);
        }
        List<Map.Entry<String, Long>> entries = new ArrayList<>(merged.entrySet());
        entries.sort((a, b) -> {
            int c = Long.compare(b.getValue(), a.getValue());
            return c != 0 ? c : a.getKey().compareTo(b.getKey());
        });
        Map<String, Long> sorted = new LinkedHashMap<>();
        for (Map.Entry<String, Long> e : entries) {
            sorted.put(e.getKey(), e.getValue());
        }
        return new Stats(placeable, Collections.unmodifiableMap(sorted));
    }

    // ---- builder ------------------------------------------------------------

    public static final class Builder {
        private final String name;
        private final int sizeX;
        private final int sizeY;
        private final int sizeZ;
        private final List<String> palette = new ArrayList<>();
        private final Map<String, Integer> lookup = new HashMap<>();
        private final int[] blocks;

        private Builder(String name, int sizeX, int sizeY, int sizeZ) {
            long volume = (long) sizeX * sizeY * sizeZ;
            if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0 || volume > Integer.MAX_VALUE - 8) {
                throw new IllegalArgumentException("Invalid schematic size " + sizeX + "x" + sizeY + "x" + sizeZ);
            }
            this.name = name;
            this.sizeX = sizeX;
            this.sizeY = sizeY;
            this.sizeZ = sizeZ;
            this.blocks = new int[(int) volume];
            palette.add(AIR);
            lookup.put(AIR, 0);
        }

        /** Registers a block state string and returns its palette index (air variants map to 0). */
        public int intern(String state) {
            String normalized = normalize(state);
            if (isAir(normalized)) {
                return 0;
            }
            Integer existing = lookup.get(normalized);
            if (existing != null) {
                return existing;
            }
            int index = palette.size();
            palette.add(normalized);
            lookup.put(normalized, index);
            return index;
        }

        public void set(int x, int y, int z, int paletteIndex) {
            blocks[(y * sizeZ + z) * sizeX + x] = paletteIndex;
        }

        public void setLinear(int linearIndex, int paletteIndex) {
            blocks[linearIndex] = paletteIndex;
        }

        public Schematic build() {
            return new Schematic(name, sizeX, sizeY, sizeZ, palette.toArray(new String[0]), blocks);
        }
    }
}
