package dev.schembot.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import dev.schembot.SchemBotClient;
import dev.schembot.SchemBotConfig;
import dev.schembot.SchematicManager;
import dev.schembot.schematic.Schematic;
import dev.schembot.schematic.SchematicException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public final class SchemBotCommand {
    private static final int TOP_MATERIALS = 10;

    private SchemBotCommand() {}

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(ClientCommandManager.literal("schembot")
                .executes(SchemBotCommand::help)
                .then(ClientCommandManager.literal("list").executes(SchemBotCommand::list))
                .then(ClientCommandManager.literal("load")
                        .then(ClientCommandManager.argument("file", StringArgumentType.string())
                                .suggests(SchemBotCommand::suggestFiles)
                                .executes(SchemBotCommand::load)))
                .then(ClientCommandManager.literal("info").executes(SchemBotCommand::info))
                .then(ClientCommandManager.literal("unload").executes(SchemBotCommand::unload))
                .then(ClientCommandManager.literal("reload").executes(SchemBotCommand::reload))
                .then(ClientCommandManager.literal("debug")
                        .then(ClientCommandManager.argument("enabled", BoolArgumentType.bool())
                                .executes(SchemBotCommand::debug))));
    }

    private static int help(CommandContext<FabricClientCommandSource> ctx) {
        FabricClientCommandSource src = ctx.getSource();
        send(src, "SchemBot commands:", Formatting.AQUA);
        send(src, "  /schembot list            - schematics in the schematic directory", Formatting.GRAY);
        send(src, "  /schembot load <file>     - load .litematic / .schem / .nbt", Formatting.GRAY);
        send(src, "  /schembot info            - details of the loaded schematic", Formatting.GRAY);
        send(src, "  /schembot unload          - drop the loaded schematic", Formatting.GRAY);
        send(src, "  /schembot reload          - reload config", Formatting.GRAY);
        send(src, "  /schembot debug <bool>    - toggle debug logging", Formatting.GRAY);
        return 1;
    }

    private static int list(CommandContext<FabricClientCommandSource> ctx) {
        FabricClientCommandSource src = ctx.getSource();
        Path dir = SchemBotClient.config().resolveSchematicDir();
        List<String> files = SchematicManager.listFiles(dir);
        if (files.isEmpty()) {
            send(src, "No schematics in " + dir + " (supported: .litematic .schem .nbt)", Formatting.YELLOW);
            return 0;
        }
        send(src, files.size() + " schematic(s) in " + dir + ":", Formatting.AQUA);
        for (String f : files) {
            send(src, "  " + f, Formatting.GRAY);
        }
        return files.size();
    }

    private static int load(CommandContext<FabricClientCommandSource> ctx) {
        FabricClientCommandSource src = ctx.getSource();
        MinecraftClient client = src.getClient();
        String name = StringArgumentType.getString(ctx, "file");
        SchemBotConfig cfg = SchemBotClient.config();
        Path dir = cfg.resolveSchematicDir();

        Path file;
        try {
            file = SchematicManager.resolve(dir, name);
        } catch (SchematicException e) {
            src.sendError(Text.literal(e.getMessage()));
            return 0;
        }
        if (!Files.isRegularFile(file)) {
            src.sendError(Text.literal("File not found: " + name + " (looked in " + dir + ")"));
            return 0;
        }

        send(src, "Loading " + name + " ...", Formatting.GRAY);
        SchematicManager.loadAsync(file, cfg.maxVolume).whenComplete((loaded, error) ->
                client.execute(() -> {
                    if (error != null) {
                        Throwable cause = (error instanceof CompletionException && error.getCause() != null)
                                ? error.getCause() : error;
                        SchemBotClient.LOGGER.warn("Failed to load {}", file, cause);
                        src.sendError(Text.literal("Load failed: " + cause.getMessage()));
                        return;
                    }
                    sendSummary(src, loaded);
                }));
        return 1;
    }

    private static int info(CommandContext<FabricClientCommandSource> ctx) {
        FabricClientCommandSource src = ctx.getSource();
        Optional<SchematicManager.Loaded> active = SchematicManager.active();
        if (active.isEmpty()) {
            src.sendError(Text.literal("No schematic loaded. Use /schembot load <file>"));
            return 0;
        }
        sendSummary(src, active.get());
        return 1;
    }

    private static int unload(CommandContext<FabricClientCommandSource> ctx) {
        SchematicManager.unload();
        send(ctx.getSource(), "Schematic unloaded.", Formatting.GREEN);
        return 1;
    }

    private static int reload(CommandContext<FabricClientCommandSource> ctx) {
        SchemBotClient.reloadConfig();
        send(ctx.getSource(), "Config reloaded. Schematic directory: "
                + SchemBotClient.config().resolveSchematicDir(), Formatting.GREEN);
        return 1;
    }

    private static int debug(CommandContext<FabricClientCommandSource> ctx) {
        FabricClientCommandSource src = ctx.getSource();
        boolean enabled = BoolArgumentType.getBool(ctx, "enabled");
        SchemBotConfig cfg = SchemBotClient.config();
        cfg.debug = enabled;
        if (!cfg.save()) {
            src.sendError(Text.literal("Debug set in memory, but the config file could not be written."));
        }
        send(src, "Debug logging " + (enabled ? "enabled" : "disabled") + ".", Formatting.GREEN);
        return 1;
    }

    private static CompletableFuture<Suggestions> suggestFiles(
            CommandContext<FabricClientCommandSource> ctx, SuggestionsBuilder builder) {
        String remaining = builder.getRemainingLowerCase();
        for (String file : SchematicManager.listFiles(SchemBotClient.config().resolveSchematicDir())) {
            if (file.indexOf('"') >= 0 || file.indexOf('\\') >= 0) {
                continue;
            }
            String suggestion = file.indexOf(' ') >= 0 ? "\"" + file + "\"" : file;
            if (suggestion.toLowerCase(Locale.ROOT).startsWith(remaining)) {
                builder.suggest(suggestion);
            }
        }
        return builder.buildFuture();
    }

    private static void sendSummary(FabricClientCommandSource src, SchematicManager.Loaded loaded) {
        Schematic s = loaded.schematic();
        send(src, "Loaded: " + s.name() + " (" + loaded.file().getFileName() + ")", Formatting.GREEN);
        send(src, "  Size: " + s.sizeX() + " x " + s.sizeY() + " x " + s.sizeZ()
                + "  (volume " + fmt(s.volume()) + ")", Formatting.GRAY);
        send(src, "  Blocks to place: " + fmt(loaded.stats().placeable())
                + "  |  Palette states: " + s.paletteSize()
                + "  |  Loaded in " + loaded.loadMillis() + " ms", Formatting.GRAY);
        int shown = 0;
        for (Map.Entry<String, Long> e : loaded.stats().byBlock().entrySet()) {
            if (shown++ == TOP_MATERIALS) {
                send(src, "  ... " + (loaded.stats().byBlock().size() - TOP_MATERIALS) + " more block types",
                        Formatting.DARK_GRAY);
                break;
            }
            send(src, "  " + e.getKey() + " x " + fmt(e.getValue()), Formatting.GRAY);
        }
    }

    private static void send(FabricClientCommandSource src, String message, Formatting color) {
        src.sendFeedback(Text.literal(message).formatted(color));
    }

    private static String fmt(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }
}
