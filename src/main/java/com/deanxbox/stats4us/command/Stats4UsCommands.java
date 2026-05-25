package com.deanxbox.stats4us.command;

import com.deanxbox.stats4us.Stats4UsMod;
import com.deanxbox.stats4us.stats.StatDescriptor;
import com.deanxbox.stats4us.stats.StatResolver;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.stats.ServerStatsCounter;
import net.minecraft.stats.Stat;
import net.minecraft.world.level.storage.LevelResource;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

public final class Stats4UsCommands {
    private static final Permission EDIT_STATS_PERMISSION = new Permission.HasCommandLevel(PermissionLevel.GAMEMASTERS);
    private static final int MAX_CHAT_ROWS = 20;

    private Stats4UsCommands() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
            literal("stats4us")
                .requires(source -> source.permissions().hasPermission(EDIT_STATS_PERMISSION))
                .executes(Stats4UsCommands::help)
                .then(literal("help")
                    .executes(Stats4UsCommands::help))
                .then(literal("reload")
                    .executes(Stats4UsCommands::reload))
                .then(literal("web")
                    .executes(Stats4UsCommands::web))
                .then(literal("stats")
                    .executes(context -> listStats(context, ""))
                    .then(argument("search", StringArgumentType.greedyString())
                        .suggests(Stats4UsCommands::suggestStats)
                        .executes(context -> listStats(context, StringArgumentType.getString(context, "search")))))
                .then(literal("player")
                    .then(argument("player", StringArgumentType.word())
                        .suggests(Stats4UsCommands::suggestPlayers)
                        .executes(context -> playerStats(context, ""))
                        .then(argument("search", StringArgumentType.greedyString())
                            .suggests(Stats4UsCommands::suggestStats)
                            .executes(context -> playerStats(context, StringArgumentType.getString(context, "search"))))))
                .then(literal("get")
                    .then(argument("player", StringArgumentType.word())
                        .suggests(Stats4UsCommands::suggestPlayers)
                        .executes(context -> playerStats(context, ""))
                        .then(argument("stat", StringArgumentType.greedyString())
                            .suggests(Stats4UsCommands::suggestStats)
                            .executes(Stats4UsCommands::get))))
                .then(literal("set")
                    .then(argument("player", StringArgumentType.word())
                        .suggests(Stats4UsCommands::suggestPlayers)
                        .then(argument("amount", IntegerArgumentType.integer(0))
                            .then(argument("stat", StringArgumentType.greedyString())
                                .suggests(Stats4UsCommands::suggestStats)
                                .executes(context -> set(context, false))))))
                .then(literal("add")
                    .then(argument("player", StringArgumentType.word())
                        .suggests(Stats4UsCommands::suggestPlayers)
                        .then(argument("amount", IntegerArgumentType.integer())
                            .then(argument("stat", StringArgumentType.greedyString())
                                .suggests(Stats4UsCommands::suggestStats)
                                .executes(context -> set(context, true))))))
        ));
    }

    private static int help(final CommandContext<CommandSourceStack> context) {
        context.getSource().sendSuccess(() -> Component.literal("""
            Stats4Us commands:
            /stats4us web
            /stats4us reload
            /stats4us stats [search]
            /stats4us player <online/offline player or uuid> [search]
            /stats4us get <online/offline player or uuid> <stat name/key>
            /stats4us set <online/offline player or uuid> <amount> <stat name/key>
            /stats4us add <online/offline player or uuid> <amount> <stat name/key>
            Examples: /stats4us get Steve deaths | /stats4us set Steve 0 deaths
            """), false);
        return 1;
    }

    private static int reload(final CommandContext<CommandSourceStack> context) {
        Stats4UsMod.reload(context.getSource().getServer());
        context.getSource().sendSuccess(() -> Component.literal("Stats4Us config reloaded. Web dashboard: " + Stats4UsMod.webAddress()), true);
        return 1;
    }

    private static int web(final CommandContext<CommandSourceStack> context) {
        context.getSource().sendSuccess(() -> Component.literal("Stats4Us web dashboard: " + Stats4UsMod.webAddress()), false);
        return 1;
    }

    private static int listStats(final CommandContext<CommandSourceStack> context, final String search) {
        List<StatDescriptor> stats = search.isBlank() ? StatResolver.buildCatalog() : StatResolver.search(search);
        context.getSource().sendSuccess(
            () -> Component.literal("Stats4Us stats matching '" + (search.isBlank() ? "all" : search) + "' (" + stats.size() + "):"),
            false
        );

        for (StatDescriptor descriptor : stats.stream().limit(MAX_CHAT_ROWS).toList()) {
            context.getSource().sendSuccess(
                () -> Component.literal("- " + descriptor.name + " [" + descriptor.category + "] key: " + descriptor.key),
                false
            );
        }

        if (stats.size() > MAX_CHAT_ROWS) {
            context.getSource().sendSuccess(() -> Component.literal("Showing first " + MAX_CHAT_ROWS + ". Add a search term to narrow results."), false);
        }

        return stats.size();
    }

    private static int playerStats(final CommandContext<CommandSourceStack> context, final String search) {
        try {
            StatsTarget target = resolveTarget(context);
            List<StatDescriptor> stats = search.isBlank() ? StatResolver.buildCatalog() : StatResolver.search(search);
            List<StatDescriptor> nonZeroStats = stats.stream()
                .filter(descriptor -> target.stats.getValue(descriptor.stat) != 0)
                .toList();

            context.getSource().sendSuccess(
                () -> Component.literal(target.displayName + " stats matching '" + (search.isBlank() ? "all non-zero" : search) + "' (" + nonZeroStats.size() + ")" + target.statusSuffix() + ":"),
                false
            );

            for (StatDescriptor descriptor : nonZeroStats.stream().limit(MAX_CHAT_ROWS).toList()) {
                int value = target.stats.getValue(descriptor.stat);
                context.getSource().sendSuccess(
                    () -> Component.literal("- " + descriptor.name + ": " + descriptor.stat.format(value) + " (" + value + ") [" + descriptor.key + "]"),
                    false
                );
            }

            if (nonZeroStats.size() > MAX_CHAT_ROWS) {
                context.getSource().sendSuccess(() -> Component.literal("Showing first " + MAX_CHAT_ROWS + ". Add a search term to narrow results."), false);
            }

            return nonZeroStats.size();
        } catch (IllegalArgumentException exception) {
            context.getSource().sendFailure(Component.literal(exception.getMessage()));
            return 0;
        }
    }

    private static int get(final CommandContext<CommandSourceStack> context) {
        try {
            StatsTarget target = resolveTarget(context);
            StatDescriptor descriptor = statDescriptor(context);
            int value = target.stats.getValue(descriptor.stat);
            context.getSource().sendSuccess(
                () -> Component.literal(target.displayName + " " + descriptor.name + " = " + descriptor.stat.format(value) + " (" + value + ") [" + descriptor.key + "]" + target.statusSuffix()),
                false
            );
            return value;
        } catch (IllegalArgumentException exception) {
            context.getSource().sendFailure(Component.literal(exception.getMessage()));
            return 0;
        }
    }

    private static int set(final CommandContext<CommandSourceStack> context, final boolean add) {
        try {
            StatsTarget target = resolveTarget(context);
            StatDescriptor descriptor = statDescriptor(context);
            Stat<?> stat = descriptor.stat;
            int amount = IntegerArgumentType.getInteger(context, "amount");
            int oldValue = target.stats.getValue(stat);
            int newValue = add ? (int) Math.max(0, Math.min(Integer.MAX_VALUE, (long) oldValue + amount)) : amount;

            target.stats.setValue(target.onlinePlayer, stat, newValue);
            target.stats.save();
            if (target.onlinePlayer != null) {
                target.stats.sendStats(target.onlinePlayer);
            }

            String action = add ? "Updated" : "Set";
            String executor = context.getSource().getTextName();
            Stats4UsMod.LOGGER.info(
                "{} {} {} {} from {} ({}) to {} ({}) [{}]{}",
                executor,
                action.toLowerCase(),
                target.displayName,
                descriptor.name,
                stat.format(oldValue),
                oldValue,
                stat.format(newValue),
                newValue,
                descriptor.key,
                target.statusSuffix()
            );
            context.getSource().sendSuccess(
                () -> Component.literal(executor + " " + action.toLowerCase() + " " + target.displayName + " " + descriptor.name + " to " + stat.format(newValue) + " (" + newValue + ") [" + descriptor.key + "]" + target.statusSuffix()),
                true
            );
            return newValue;
        } catch (IllegalArgumentException exception) {
            context.getSource().sendFailure(Component.literal(exception.getMessage()));
            return 0;
        }
    }

    private static StatDescriptor statDescriptor(final CommandContext<CommandSourceStack> context) {
        return StatResolver.resolveFlexible(StringArgumentType.getString(context, "stat"));
    }

    private static StatsTarget resolveTarget(final CommandContext<CommandSourceStack> context) {
        MinecraftServer server = context.getSource().getServer();
        String rawPlayer = StringArgumentType.getString(context, "player");

        ServerPlayer onlineByName = server.getPlayerList().getPlayer(rawPlayer);
        if (onlineByName != null) {
            return new StatsTarget(onlineByName.getGameProfile().name(), onlineByName.getUUID().toString(), onlineByName.getStats(), onlineByName, true);
        }

        UUID rawUuid = parseUuid(rawPlayer);
        if (rawUuid != null) {
            ServerPlayer onlineByUuid = server.getPlayerList().getPlayer(rawUuid);
            if (onlineByUuid != null) {
                return new StatsTarget(onlineByUuid.getGameProfile().name(), onlineByUuid.getUUID().toString(), onlineByUuid.getStats(), onlineByUuid, true);
            }
        }

        Map<String, String> namesById = loadKnownNames(server);
        Path statsDirectory = server.getWorldPath(LevelResource.PLAYER_STATS_DIR);
        PlayerFile targetFile = findPlayerFile(rawPlayer, rawUuid, namesById, statsDirectory);
        if (targetFile == null) {
            throw new IllegalArgumentException("Unknown offline player '" + rawPlayer + "'. Use a UUID or a player with an existing stats file/usercache entry.");
        }

        ServerStatsCounter stats = new ServerStatsCounter(server, targetFile.path);
        return new StatsTarget(targetFile.name, targetFile.id, stats, null, false);
    }

    private static PlayerFile findPlayerFile(final String rawPlayer, final UUID rawUuid, final Map<String, String> namesById, final Path statsDirectory) {
        if (rawUuid != null) {
            String id = rawUuid.toString();
            return new PlayerFile(id, namesById.getOrDefault(id, id), statsDirectory.resolve(id + ".json"));
        }

        for (Map.Entry<String, String> entry : namesById.entrySet()) {
            if (entry.getValue().equalsIgnoreCase(rawPlayer)) {
                return new PlayerFile(entry.getKey(), entry.getValue(), statsDirectory.resolve(entry.getKey() + ".json"));
            }
        }

        Path legacyNameFile = statsDirectory.resolve(rawPlayer + ".json");
        if (Files.isRegularFile(legacyNameFile)) {
            return new PlayerFile(rawPlayer, rawPlayer, legacyNameFile);
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(statsDirectory, "*.json")) {
            for (Path file : stream) {
                String id = stripJson(file.getFileName().toString());
                if (id.equalsIgnoreCase(rawPlayer)) {
                    return new PlayerFile(id, namesById.getOrDefault(id, id), file);
                }
            }
        } catch (Exception ignored) {
            return null;
        }

        return null;
    }

    private static Map<String, String> loadKnownNames(final MinecraftServer server) {
        Map<String, String> names = new LinkedHashMap<>();
        Path userCache = server.getFile("usercache.json");
        if (!Files.isRegularFile(userCache)) {
            return names;
        }

        try (Reader reader = Files.newBufferedReader(userCache, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(reader);
            if (!root.isJsonArray()) {
                return names;
            }

            for (JsonElement element : root.getAsJsonArray()) {
                if (!element.isJsonObject()) {
                    continue;
                }

                JsonObject object = element.getAsJsonObject();
                JsonElement uuid = object.get("uuid");
                JsonElement name = object.get("name");
                if (uuid != null && name != null) {
                    names.put(uuid.getAsString(), name.getAsString());
                }
            }
        } catch (Exception exception) {
            Stats4UsMod.LOGGER.debug("Failed to read usercache.json for Stats4Us command suggestions.", exception);
        }

        return names;
    }

    private static CompletableFuture<Suggestions> suggestStats(final CommandContext<CommandSourceStack> context, final SuggestionsBuilder builder) {
        return suggestMatching(StatResolver.statSuggestions(), builder);
    }

    private static CompletableFuture<Suggestions> suggestPlayers(final CommandContext<CommandSourceStack> context, final SuggestionsBuilder builder) {
        MinecraftServer server = context.getSource().getServer();
        for (String name : server.getPlayerNames()) {
            suggestIfMatches(builder, name);
        }

        loadKnownNames(server).values()
            .stream()
            .sorted(String.CASE_INSENSITIVE_ORDER)
            .forEach(name -> suggestIfMatches(builder, name));

        Path statsDirectory = server.getWorldPath(LevelResource.PLAYER_STATS_DIR);
        if (Files.isDirectory(statsDirectory)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(statsDirectory, "*.json")) {
                for (Path file : stream) {
                    suggestIfMatches(builder, stripJson(file.getFileName().toString()));
                }
            } catch (Exception ignored) {
            }
        }

        return builder.buildFuture();
    }

    private static CompletableFuture<Suggestions> suggestMatching(final Iterable<String> values, final SuggestionsBuilder builder) {
        for (String value : values) {
            suggestIfMatches(builder, value);
        }

        return builder.buildFuture();
    }

    private static void suggestIfMatches(final SuggestionsBuilder builder, final String value) {
        String remaining = builder.getRemaining().toLowerCase();
        if (value.toLowerCase().contains(remaining)) {
            builder.suggest(value);
        }
    }

    private static UUID parseUuid(final String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static String stripJson(final String fileName) {
        return fileName.endsWith(".json") ? fileName.substring(0, fileName.length() - ".json".length()) : fileName;
    }

    private record StatsTarget(
        String displayName,
        String id,
        ServerStatsCounter stats,
        ServerPlayer onlinePlayer,
        boolean online
    ) {
        private String statusSuffix() {
            return online ? " (online)" : " (offline)";
        }
    }

    private record PlayerFile(String id, String name, Path path) {
    }
}
