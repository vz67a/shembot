package dev.schembot.schematic;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Dependency-free NBT reader. Values map to: Byte, Short, Integer, Long, Float, Double,
 * byte[], String, List&lt;Object&gt;, Map&lt;String,Object&gt;, int[], long[].
 */
final class Nbt {
    private static final int MAX_DEPTH = 256;
    private static final int MAX_LENGTH = 1 << 26;

    private Nbt() {}

    static Map<String, Object> readFile(Path file) throws IOException, SchematicException {
        try (InputStream raw = new BufferedInputStream(Files.newInputStream(file), 1 << 16)) {
            raw.mark(2);
            int b0 = raw.read();
            int b1 = raw.read();
            raw.reset();
            boolean gzip = b0 == 0x1f && b1 == 0x8b;
            InputStream source = gzip ? new GZIPInputStream(raw, 1 << 16) : raw;
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(source, 1 << 16))) {
                int type = in.readUnsignedByte();
                if (type != 10) {
                    throw new SchematicException("Root tag is not a compound (type " + type + ")");
                }
                in.readUTF();
                return readCompound(in, 0);
            }
        } catch (EOFException e) {
            throw new SchematicException("Unexpected end of file (truncated or corrupt): " + file.getFileName(), e);
        }
    }

    private static Map<String, Object> readCompound(DataInputStream in, int depth)
            throws IOException, SchematicException {
        Map<String, Object> map = new LinkedHashMap<>();
        while (true) {
            int type = in.readUnsignedByte();
            if (type == 0) {
                return map;
            }
            String key = in.readUTF();
            map.put(key, readPayload(in, type, depth + 1));
        }
    }

    private static Object readPayload(DataInputStream in, int type, int depth)
            throws IOException, SchematicException {
        if (depth > MAX_DEPTH) {
            throw new SchematicException("NBT nesting too deep");
        }
        switch (type) {
            case 1:
                return in.readByte();
            case 2:
                return in.readShort();
            case 3:
                return in.readInt();
            case 4:
                return in.readLong();
            case 5:
                return in.readFloat();
            case 6:
                return in.readDouble();
            case 7: {
                byte[] data = new byte[length(in)];
                in.readFully(data);
                return data;
            }
            case 8:
                return in.readUTF();
            case 9: {
                int elementType = in.readUnsignedByte();
                int n = length(in);
                if (n > 0 && elementType == 0) {
                    throw new SchematicException("List with elements of type End");
                }
                List<Object> list = new ArrayList<>(Math.min(n, 1024));
                for (int i = 0; i < n; i++) {
                    list.add(readPayload(in, elementType, depth + 1));
                }
                return list;
            }
            case 10:
                return readCompound(in, depth);
            case 11: {
                int[] data = new int[length(in)];
                for (int i = 0; i < data.length; i++) {
                    data[i] = in.readInt();
                }
                return data;
            }
            case 12: {
                long[] data = new long[length(in)];
                for (int i = 0; i < data.length; i++) {
                    data[i] = in.readLong();
                }
                return data;
            }
            default:
                throw new SchematicException("Unknown NBT tag type " + type);
        }
    }

    private static int length(DataInputStream in) throws IOException, SchematicException {
        int n = in.readInt();
        if (n < 0 || n > MAX_LENGTH) {
            throw new SchematicException("Invalid NBT array/list length " + n);
        }
        return n;
    }

    // ---- typed accessors -------------------------------------------------

    @SuppressWarnings("unchecked")
    static Map<String, Object> asCompound(Object value, String what) throws SchematicException {
        if (value instanceof Map<?, ?>) {
            return (Map<String, Object>) value;
        }
        throw new SchematicException("Expected compound for " + what);
    }

    @SuppressWarnings("unchecked")
    static List<Object> asList(Object value, String what) throws SchematicException {
        if (value instanceof List<?>) {
            return (List<Object>) value;
        }
        throw new SchematicException("Expected list for " + what);
    }

    static int asInt(Object value, String what) throws SchematicException {
        if (value instanceof Number n) {
            return n.intValue();
        }
        throw new SchematicException("Expected number for " + what);
    }

    private static Object require(Map<String, Object> map, String key) throws SchematicException {
        Object value = map.get(key);
        if (value == null) {
            throw new SchematicException("Missing tag '" + key + "'");
        }
        return value;
    }

    static Map<String, Object> compound(Map<String, Object> map, String key) throws SchematicException {
        return asCompound(require(map, key), key);
    }

    static List<Object> list(Map<String, Object> map, String key) throws SchematicException {
        return asList(require(map, key), key);
    }

    static int integer(Map<String, Object> map, String key) throws SchematicException {
        return asInt(require(map, key), key);
    }

    static int unsignedShort(Map<String, Object> map, String key) throws SchematicException {
        return asInt(require(map, key), key) & 0xFFFF;
    }

    static String string(Map<String, Object> map, String key) throws SchematicException {
        if (require(map, key) instanceof String s) {
            return s;
        }
        throw new SchematicException("Expected string for '" + key + "'");
    }

    static byte[] bytes(Map<String, Object> map, String key) throws SchematicException {
        if (require(map, key) instanceof byte[] b) {
            return b;
        }
        throw new SchematicException("Expected byte array for '" + key + "'");
    }

    static long[] longs(Map<String, Object> map, String key) throws SchematicException {
        if (require(map, key) instanceof long[] l) {
            return l;
        }
        throw new SchematicException("Expected long array for '" + key + "'");
    }
}
