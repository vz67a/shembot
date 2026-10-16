package dev.schembot.schematic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Loads .litematic, Sponge .schem (v1-v3) and vanilla structure .nbt into {@link Schematic}. */
public final class SchematicLoader {
    private record Region(String name, Map<String, Object> tag, long x, long y, long z, int sx, int sy, int sz) {}

    private SchematicLoader() {}

    public static boolean isSupportedName(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".litematic") || lower.endsWith(".schem") || lower.endsWith(".nbt");
    }

    public static Schematic load(Path file, int maxVolume) throws IOException, SchematicException {
        String fileName = file.getFileName().toString();
        String lower = fileName.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        String ext = dot < 0 ? "" : lower.substring(dot);
        String baseName = dot < 0 ? fileName : fileName.substring(0, dot);

        if (ext.equals(".schematic")) {
            throw new SchematicException(
                    "Legacy MCEdit .schematic uses numeric block IDs and is not supported. Convert to .schem or .litematic.");
        }
        if (!isSupportedName(fileName)) {
            throw new SchematicException("Unsupported extension '" + ext + "' (use .litematic, .schem or .nbt)");
        }
        if (!Files.isRegularFile(file)) {
            throw new SchematicException("Not a file: " + file);
        }

        Map<String, Object> root = Nbt.readFile(file);
        return switch (ext) {
            case ".litematic" -> readLitematic(root, baseName, maxVolume);
            case ".schem" -> readSponge(root, baseName, maxVolume);
            default -> readStructure(root, baseName, maxVolume);
        };
    }

    // ---- Litematica ---------------------------------------------------------

    private static Schematic readLitematic(Map<String, Object> root, String fallbackName, int maxVolume)
            throws SchematicException {
        Map<String, Object> regionsTag = Nbt.compound(root, "Regions");
        if (regionsTag.isEmpty()) {
            throw new SchematicException("Litematic has no regions");
        }

        List<Region> regions = new ArrayList<>();
        long minX = Long.MAX_VALUE, minY = Long.MAX_VALUE, minZ = Long.MAX_VALUE;
        long maxX = Long.MIN_VALUE, maxY = Long.MIN_VALUE, maxZ = Long.MIN_VALUE;

        for (Map.Entry<String, Object> entry : regionsTag.entrySet()) {
            String regionName = entry.getKey();
            Map<String, Object> tag = Nbt.asCompound(entry.getValue(), "region '" + regionName + "'");
            Map<String, Object> pos = Nbt.compound(tag, "Position");
            Map<String, Object> size = Nbt.compound(tag, "Size");
            long px = Nbt.integer(pos, "x"), py = Nbt.integer(pos, "y"), pz = Nbt.integer(pos, "z");
            long rx = Nbt.integer(size, "x"), ry = Nbt.integer(size, "y"), rz = Nbt.integer(size, "z");
            if (rx == 0 || ry == 0 || rz == 0) {
                throw new SchematicException("Region '" + regionName + "' has zero size");
            }
            long ax = Math.abs(rx), ay = Math.abs(ry), az = Math.abs(rz);
            requireVolume(ax, ay, az, maxVolume);

            // Negative sizes extend backwards from Position; local coordinates start at the lower corner.
            long x0 = rx > 0 ? px : px + rx + 1;
            long y0 = ry > 0 ? py : py + ry + 1;
            long z0 = rz > 0 ? pz : pz + rz + 1;
            regions.add(new Region(regionName, tag, x0, y0, z0, (int) ax, (int) ay, (int) az));

            minX = Math.min(minX, x0);
            minY = Math.min(minY, y0);
            minZ = Math.min(minZ, z0);
            maxX = Math.max(maxX, x0 + ax);
            maxY = Math.max(maxY, y0 + ay);
            maxZ = Math.max(maxZ, z0 + az);
        }

        long totalX = maxX - minX, totalY = maxY - minY, totalZ = maxZ - minZ;
        requireVolume(totalX, totalY, totalZ, maxVolume);

        String name = fallbackName;
        if (root.get("Metadata") instanceof Map<?, ?> meta && meta.get("Name") instanceof String s && !s.isBlank()) {
            name = s;
        }
        Schematic.Builder builder = Schematic.builder(name, (int) totalX, (int) totalY, (int) totalZ);

        for (Region r : regions) {
            int[] remap = remapPalette(builder, Nbt.list(r.tag(), "BlockStatePalette"), "region '" + r.name() + "'");
            long[] packed = Nbt.longs(r.tag(), "BlockStates");
            int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(remap.length - 1));
            long count = (long) r.sx() * r.sy() * r.sz();
            if ((count * bits + 63) / 64 > packed.length) {
                throw new SchematicException("Region '" + r.name() + "' block data is truncated");
            }
            long mask = (1L << bits) - 1;
            int ox = (int) (r.x() - minX), oy = (int) (r.y() - minY), oz = (int) (r.z() - minZ);

            long n = 0;
            for (int y = 0; y < r.sy(); y++) {
                for (int z = 0; z < r.sz(); z++) {
                    for (int x = 0; x < r.sx(); x++) {
                        int v = unpack(packed, n++, bits, mask);
                        if (v >= remap.length) {
                            throw new SchematicException("Region '" + r.name() + "' references palette index "
                                    + v + " but palette has " + remap.length + " entries");
                        }
                        int p = remap[v];
                        if (p != 0) {
                            builder.set(ox + x, oy + y, oz + z, p);
                        }
                    }
                }
            }
        }
        return builder.build();
    }

    /** Reads one value from Litematica's tightly packed (values may span longs) bit array. */
    private static int unpack(long[] data, long index, int bits, long mask) {
        long bit = index * bits;
        int start = (int) (bit >>> 6);
        int offset = (int) (bit & 63);
        int end = (int) (((index + 1) * bits - 1) >>> 6);
        if (start == end) {
            return (int) ((data[start] >>> offset) & mask);
        }
        int firstPart = 64 - offset;
        return (int) (((data[start] >>> offset) | (data[end] << firstPart)) & mask);
    }

    // ---- Sponge .schem ------------------------------------------------------

    private static Schematic readSponge(Map<String, Object> root, String name, int maxVolume)
            throws SchematicException {
        Map<String, Object> schem = root.get("Schematic") instanceof Map<?, ?> ? Nbt.compound(root, "Schematic") : root;
        int version = Nbt.integer(schem, "Version");
        if (version < 1 || version > 3) {
            throw new SchematicException("Unsupported Sponge schematic version " + version);
        }
        int width = Nbt.unsignedShort(schem, "Width");
        int height = Nbt.unsignedShort(schem, "Height");
        int length = Nbt.unsignedShort(schem, "Length");
        requireVolume(width, height, length, maxVolume);

        Map<String, Object> paletteTag;
        byte[] data;
        if (version >= 3) {
            Map<String, Object> blocks = Nbt.compound(schem, "Blocks");
            paletteTag = Nbt.compound(blocks, "Palette");
            data = Nbt.bytes(blocks, "Data");
        } else {
            paletteTag = Nbt.compound(schem, "Palette");
            data = Nbt.bytes(schem, "BlockData");
        }
        if (paletteTag.isEmpty()) {
            throw new SchematicException("Schematic palette is empty");
        }

        int maxIndex = -1;
        for (Map.Entry<String, Object> e : paletteTag.entrySet()) {
            int idx = Nbt.asInt(e.getValue(), "palette index of " + e.getKey());
            if (idx < 0 || idx > (1 << 20)) {
                throw new SchematicException("Palette index out of range for " + e.getKey());
            }
            maxIndex = Math.max(maxIndex, idx);
        }
        String[] states = new String[maxIndex + 1];
        for (Map.Entry<String, Object> e : paletteTag.entrySet()) {
            states[Nbt.asInt(e.getValue(), e.getKey())] = e.getKey();
        }

        Schematic.Builder builder = Schematic.builder(name, width, height, length);
        int[] remap = new int[states.length];
        for (int i = 0; i < states.length; i++) {
            remap[i] = states[i] == null ? 0 : builder.intern(states[i]);
        }

        // Sponge order (x + z*W + y*W*L) equals the internal linear layout.
        long volume = (long) width * height * length;
        int pos = 0;
        for (int i = 0; i < volume; i++) {
            int value = 0;
            int shift = 0;
            while (true) {
                if (pos >= data.length) {
                    throw new SchematicException("Block data is truncated");
                }
                int b = data[pos++] & 0xFF;
                value |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    break;
                }
                shift += 7;
                if (shift >= 35) {
                    throw new SchematicException("Malformed varint in block data");
                }
            }
            if (value < 0 || value >= remap.length) {
                throw new SchematicException("Block data references unknown palette index " + value);
            }
            builder.setLinear(i, remap[value]);
        }
        return builder.build();
    }

    // ---- Vanilla structure .nbt ---------------------------------------------

    private static Schematic readStructure(Map<String, Object> root, String name, int maxVolume)
            throws SchematicException {
        List<Object> size = Nbt.list(root, "size");
        if (size.size() != 3) {
            throw new SchematicException("Structure 'size' must have 3 entries");
        }
        int sx = Nbt.asInt(size.get(0), "size"), sy = Nbt.asInt(size.get(1), "size"), sz = Nbt.asInt(size.get(2), "size");
        requireVolume(sx, sy, sz, maxVolume);

        List<Object> palette;
        if (root.get("palette") instanceof List<?>) {
            palette = Nbt.list(root, "palette");
        } else {
            List<Object> palettes = Nbt.list(root, "palettes");
            if (palettes.isEmpty()) {
                throw new SchematicException("Structure has no palette");
            }
            palette = Nbt.asList(palettes.get(0), "palettes[0]");
        }

        Schematic.Builder builder = Schematic.builder(name, sx, sy, sz);
        int[] remap = remapPalette(builder, palette, "structure");
        for (Object o : Nbt.list(root, "blocks")) {
            Map<String, Object> block = Nbt.asCompound(o, "block entry");
            int state = Nbt.integer(block, "state");
            List<Object> pos = Nbt.list(block, "pos");
            if (pos.size() != 3) {
                throw new SchematicException("Block 'pos' must have 3 entries");
            }
            int x = Nbt.asInt(pos.get(0), "pos"), y = Nbt.asInt(pos.get(1), "pos"), z = Nbt.asInt(pos.get(2), "pos");
            if (state < 0 || state >= remap.length) {
                throw new SchematicException("Block references unknown palette index " + state);
            }
            if (x < 0 || y < 0 || z < 0 || x >= sx || y >= sy || z >= sz) {
                throw new SchematicException("Block position (" + x + "," + y + "," + z + ") is out of bounds");
            }
            builder.set(x, y, z, remap[state]);
        }
        return builder.build();
    }

    // ---- shared helpers -----------------------------------------------------

    private static int[] remapPalette(Schematic.Builder builder, List<Object> palette, String where)
            throws SchematicException {
        if (palette.isEmpty()) {
            throw new SchematicException("Empty palette in " + where);
        }
        int[] remap = new int[palette.size()];
        for (int i = 0; i < remap.length; i++) {
            remap[i] = builder.intern(stateString(Nbt.asCompound(palette.get(i), where + " palette entry")));
        }
        return remap;
    }

    private static String stateString(Map<String, Object> entry) throws SchematicException {
        String name = Nbt.string(entry, "Name");
        if (entry.get("Properties") instanceof Map<?, ?> props && !props.isEmpty()) {
            TreeMap<String, String> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> p : props.entrySet()) {
                sorted.put(String.valueOf(p.getKey()), String.valueOf(p.getValue()));
            }
            StringBuilder sb = new StringBuilder(name).append('[');
            boolean first = true;
            for (Map.Entry<String, String> p : sorted.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                sb.append(p.getKey()).append('=').append(p.getValue());
                first = false;
            }
            return sb.append(']').toString();
        }
        return name;
    }

    private static void requireVolume(long x, long y, long z, int maxVolume) throws SchematicException {
        if (x <= 0 || y <= 0 || z <= 0) {
            throw new SchematicException("Invalid schematic size " + x + "x" + y + "x" + z);
        }
        boolean tooLarge = x > maxVolume || y > maxVolume || z > maxVolume || x * y > maxVolume
                || x * y * z > maxVolume;
        if (tooLarge) {
            throw new SchematicException("Schematic is larger than maxVolume (" + maxVolume
                    + " blocks). Raise 'maxVolume' in config/schembot.json.");
        }
    }
}
