package dev.schembot;

import dev.schembot.schematic.Schematic;
import dev.schembot.schematic.SchematicException;
import dev.schembot.schematic.SchematicLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

public final class SchematicManager {
    public record Loaded(Path file, Schematic schematic, Schematic.Stats stats, long loadMillis) {}

    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "schembot-loader");
        t.setDaemon(true);
        return t;
    });

    private static volatile Loaded active;

    private SchematicManager() {}

    public static Optional<Loaded> active() {
        return Optional.ofNullable(active);
    }

    public static void unload() {
        active = null;
    }

    public static CompletableFuture<Loaded> loadAsync(Path file, int maxVolume) {
        return CompletableFuture.supplyAsync(() -> {
            long start = System.nanoTime();
            try {
                Schematic schematic = SchematicLoader.load(file, maxVolume);
                Schematic.Stats stats = schematic.stats();
                long millis = (System.nanoTime() - start) / 1_000_000L;
                Loaded loaded = new Loaded(file, schematic, stats, millis);
                active = loaded;
                SchemBotClient.debug("Loaded '{}' size={}x{}x{} palette={} placeable={} in {} ms",
                        schematic.name(), schematic.sizeX(), schematic.sizeY(), schematic.sizeZ(),
                        schematic.paletteSize(), stats.placeable(), millis);
                return loaded;
            } catch (IOException | SchematicException e) {
                throw new CompletionException(e);
            }
        }, LOADER);
    }

    public static List<String> listFiles(Path dir) {
        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(SchematicLoader::isSupportedName)
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
        } catch (IOException e) {
            SchemBotClient.debug("Could not list {}: {}", dir, e.toString());
            return List.of();
        }
    }

    /** Resolves a user-supplied file name inside {@code dir}; rejects path traversal. */
    public static Path resolve(Path dir, String name) throws SchematicException {
        try {
            Path resolved = dir.resolve(name).normalize();
            if (!resolved.startsWith(dir)) {
                throw new SchematicException("File must be inside the schematic directory");
            }
            return resolved;
        } catch (java.nio.file.InvalidPathException e) {
            throw new SchematicException("Invalid file name: " + name);
        }
    }
}
