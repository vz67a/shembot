package dev.schembot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonIOException;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

public final class SchemBotConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String DEFAULT_DIR = "schematics";
    private static final int DEFAULT_MAX_VOLUME = 16_777_216;
    private static final int HARD_MAX_VOLUME = 67_108_864;

    /** Relative paths resolve against the game directory. */
    public String schematicDir = DEFAULT_DIR;
    /** Largest schematic bounding box (in blocks) the loader accepts. */
    public int maxVolume = DEFAULT_MAX_VOLUME;
    public volatile boolean debug = false;

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("schembot.json");
    }

    public static SchemBotConfig load() {
        Path path = path();
        SchemBotConfig cfg = null;
        if (Files.isRegularFile(path)) {
            try (Reader reader = Files.newBufferedReader(path)) {
                cfg = GSON.fromJson(reader, SchemBotConfig.class);
            } catch (IOException | JsonParseException e) {
                SchemBotClient.LOGGER.warn("Could not read {}; using defaults (file left untouched)", path, e);
            }
        }
        if (cfg == null) {
            cfg = new SchemBotConfig();
        }
        cfg.sanitize();
        if (!Files.exists(path)) {
            cfg.save();
        }
        return cfg;
    }

    public boolean save() {
        Path path = path();
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path)) {
                GSON.toJson(this, writer);
            }
            return true;
        } catch (IOException | JsonIOException e) {
            SchemBotClient.LOGGER.warn("Could not write {}", path, e);
            return false;
        }
    }

    public Path resolveSchematicDir() {
        Path p;
        try {
            p = Path.of(schematicDir);
        } catch (InvalidPathException e) {
            SchemBotClient.LOGGER.warn("Invalid schematicDir '{}'; using '{}'", schematicDir, DEFAULT_DIR);
            p = Path.of(DEFAULT_DIR);
        }
        Path base = p.isAbsolute() ? p : FabricLoader.getInstance().getGameDir().resolve(p);
        return base.toAbsolutePath().normalize();
    }

    private void sanitize() {
        if (schematicDir == null || schematicDir.isBlank()) {
            schematicDir = DEFAULT_DIR;
        }
        maxVolume = Math.max(1, Math.min(maxVolume, HARD_MAX_VOLUME));
    }
}
