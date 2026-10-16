package dev.schembot;

import dev.schembot.command.SchemBotCommand;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SchemBotClient implements ClientModInitializer {
    public static final String MOD_ID = "schembot";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static volatile SchemBotConfig config = new SchemBotConfig();

    public static SchemBotConfig config() {
        return config;
    }

    public static void reloadConfig() {
        config = SchemBotConfig.load();
    }

    public static void debug(String format, Object... args) {
        if (config.debug) {
            LOGGER.info("[debug] " + format, args);
        }
    }

    @Override
    public void onInitializeClient() {
        reloadConfig();
        Path dir = config.resolveSchematicDir();
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            LOGGER.warn("Could not create schematic directory {}", dir, e);
        }
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                SchemBotCommand.register(dispatcher));
        LOGGER.info("SchemBot ready. Schematic directory: {}", dir);
    }
}
