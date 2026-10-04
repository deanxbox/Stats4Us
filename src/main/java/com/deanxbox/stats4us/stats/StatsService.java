package com.deanxbox.stats4us.stats;

import com.deanxbox.stats4us.Stats4UsMod;
import com.deanxbox.stats4us.config.ConfigManager;
import com.deanxbox.stats4us.config.Stats4UsConfig;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.authlib.GameProfile;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.ServerStatsCounter;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public final class StatsService {
    private static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .create();

    private final MinecraftServer server;
    private final ConfigManager configManager;
    private final List<StatDescriptor> catalog;
    private final Path historyPath;
    private final List<HistorySampleDto> history = new ArrayList<>();
    private final Map<Path, CachedStats> offlineStatsCache = new HashMap<>();
    private final PlayerNameResolver nameResolver;

    private Stats4UsConfig config;
    private Set<String> enabledStatTypes;
    private Set<String> enabledStats;
    private Set<String> hiddenStats;
    private Set<String> hiddenPlayers;
    private long lastHistorySampleMillis;

    public StatsService(final MinecraftServer server, final ConfigManager configManager) {
        this.server = server;
        this.configManager = configManager;
        this.catalog = StatResolver.buildCatalog();
        this.nameResolver = new PlayerNameResolver(server);
        this.historyPath = FabricLoader.getInstance().getConfigDir().resolve("stats4us-history.json");
        refreshConfig();
        loadHistory();
        sampleHistory(true);
    }

    public void refreshConfig() {
        this.config = configManager.config();
        this.enabledStatTypes = new HashSet<>(config.display.enabledStatTypes);
        this.enabledStats = new HashSet<>(config.display.enabledStats);
        this.hiddenStats = new HashSet<>(config.display.hiddenStats);
        this.hiddenPlayers = config.display.hiddenPlayers.stream()
            .map(value -> value.toLowerCase(Locale.ROOT))
            .collect(Collectors.toSet());
        offlineStatsCache.clear();
    }

    public void tick() {
        if (!config.history.enabled) {
            return;
        }

        long now = System.currentTimeMillis();
        long intervalMillis = config.history.sampleIntervalSeconds * 1000L;
        if (now - lastHistorySampleMillis >= intervalMillis) {
            sampleHistory(false);
        }
    }

    public PlayerNameResolver names() {
        return nameResolver;
    }

    public void close() {
        nameResolver.close();
        sampleHistory(true);
        saveHistory();
    }

    public StatsSnapshotDto snapshot() {
        StatsSnapshotDto snapshot = new StatsSnapshotDto();
        snapshot.generatedAt = Instant.now().toString();
        snapshot.minecraftVersion = server.getServerVersion();
        snapshot.statsPath = statsPath().toAbsolutePath().toString();
        snapshot.showZeroValues = config.display.showZeroValues;
        snapshot.enabledStatTypes = new ArrayList<>(config.display.enabledStatTypes);
        snapshot.onlinePlayers = 0;
        snapshot.catalog = displayedCatalog();
        snapshot.totalAvailableStats = snapshot.catalog.size();
        snapshot.history = List.of();

        Map<String, String> knownNames = loadKnownNames();
        Map<UUID, PlayerStatsDto> players = new LinkedHashMap<>();

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            GameProfile profile = player.getGameProfile();
            knownNames.put(profile.id().toString(), profile.name());
            if (isHiddenPlayer(profile.id().toString())) {
                continue;
            }
            players.put(profile.id(), fromOnlinePlayer(player));
            snapshot.onlinePlayers++;
        }

        if (config.display.showOfflinePlayers) {
            for (Path file : statFiles()) {
                if (isHiddenPlayer(idFromStatsFile(file))) {
                    continue;
                }
                UUID uuid = uuidFromStatsFile(file);
                if (uuid != null && players.containsKey(uuid)) {
                    continue;
                }

                PlayerStatsDto playerStats = fromStatsFile(file, knownNames);
                if (playerStats != null) {
                    if (uuid != null) {
                        players.put(uuid, playerStats);
                    } else {
                        snapshot.players.add(playerStats);
                    }
                }
            }
        }

        snapshot.players.addAll(players.values());
        snapshot.players.sort(Comparator
            .comparing((PlayerStatsDto player) -> !player.online)
            .thenComparing(player -> player.name == null ? player.uuid : player.name, String.CASE_INSENSITIVE_ORDER));
        snapshot.totalPlayers = snapshot.players.size();
        nameResolver.requestMissing(snapshot.players.stream().filter(player -> player.uuid.equals(player.name)).map(player -> player.uuid).toList());

        return snapshot;
    }

    public Path statsPath() {
        return server.getWorldPath(LevelResource.PLAYER_STATS_DIR);
    }

    private PlayerStatsDto fromOnlinePlayer(final ServerPlayer player) {
        Map<String, Integer> rawValues = new HashMap<>();
        ServerStatsCounter counter = player.getStats();
        for (StatDescriptor descriptor : catalog) {
            rawValues.put(descriptor.key, counter.getValue(descriptor.stat));
        }

        PlayerStatsDto dto = playerDto(player.getGameProfile().id().toString(), player.getGameProfile().name(), true);
        fillStats(dto, rawValues);
        return dto;
    }

    private void sampleHistory(final boolean force) {
        if (!config.history.enabled) {
            return;
        }

        long now = System.currentTimeMillis();
        if (!force && now - lastHistorySampleMillis < config.history.sampleIntervalSeconds * 1000L) {
            return;
        }

        HistorySampleDto sample = new HistorySampleDto();
        sample.timestamp = Instant.now().toString();
        sample.onlinePlayers = 0;

        Map<String, String> knownNames = loadKnownNames();
        Map<String, HistoryPlayerSource> players = new LinkedHashMap<>();

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            GameProfile profile = player.getGameProfile();
            knownNames.put(profile.id().toString(), profile.name());
            if (isHiddenPlayer(profile.id().toString())) {
                continue;
            }
            players.put(profile.id().toString(), new HistoryPlayerSource(profile.name(), true, valuesForOnlinePlayer(player)));
            sample.onlinePlayers++;
        }

        if (config.display.showOfflinePlayers) {
            for (Path file : statFiles()) {
                String id = idFromStatsFile(file);
                if (id == null || isHiddenPlayer(id) || players.containsKey(id)) {
                    continue;
                }

                players.put(id, new HistoryPlayerSource(knownNames.getOrDefault(id, id), false, readStatsFile(file)));
            }
        }

        sample.totalPlayers = players.size();
        for (Map.Entry<String, HistoryPlayerSource> entry : players.entrySet()) {
            PlayerHistoryDto player = new PlayerHistoryDto();
            player.name = entry.getValue().name;
            player.online = entry.getValue().online;

            for (String trackedStat : config.history.trackedStats) {
                int value = entry.getValue().values.getOrDefault(trackedStat, 0);
                player.values.put(trackedStat, value);
                sample.totals.merge(trackedStat, (long) value, Long::sum);
            }

            sample.players.put(entry.getKey(), player);
        }

        history.add(sample);
        trimHistory();
        lastHistorySampleMillis = now;
        saveHistory();
    }

    private Map<String, Integer> valuesForOnlinePlayer(final ServerPlayer player) {
        Map<String, Integer> values = new HashMap<>();
        ServerStatsCounter counter = player.getStats();
        for (StatDescriptor descriptor : catalog) {
            values.put(descriptor.key, counter.getValue(descriptor.stat));
        }

        return values;
    }

    private void trimHistory() {
        while (history.size() > config.history.maxSamples) {
            history.removeFirst();
        }
    }

    private void loadHistory() {
        if (!Files.isRegularFile(historyPath)) {
            return;
        }

        try (Reader reader = Files.newBufferedReader(historyPath, StandardCharsets.UTF_8)) {
            HistoryFile file = GSON.fromJson(reader, HistoryFile.class);
            if (file != null && file.samples != null) {
                history.clear();
                history.addAll(file.samples);
                trimHistory();
            }
        } catch (Exception exception) {
            Stats4UsMod.LOGGER.warn("Failed to load Stats4Us history at {}.", historyPath, exception);
        }
    }

    private void saveHistory() {
        try {
            Files.createDirectories(historyPath.getParent());
            HistoryFile file = new HistoryFile();
            file.samples = history;
            try (Writer writer = Files.newBufferedWriter(historyPath, StandardCharsets.UTF_8)) {
                GSON.toJson(file, writer);
            }
        } catch (IOException exception) {
            Stats4UsMod.LOGGER.warn("Failed to save Stats4Us history at {}.", historyPath, exception);
        }
    }

    private PlayerStatsDto fromStatsFile(final Path file, final Map<String, String> knownNames) {
        String fileName = file.getFileName().toString();
        String id = fileName.endsWith(".json") ? fileName.substring(0, fileName.length() - ".json".length()) : fileName;
        Map<String, Integer> rawValues = readStatsFile(file);
        String name = knownNames.getOrDefault(id, id);
        PlayerStatsDto dto = playerDto(id, name, false);
        fillStats(dto, rawValues);
        return dto;
    }

    private PlayerStatsDto playerDto(final String uuid, final String name, final boolean online) {
        PlayerStatsDto dto = new PlayerStatsDto();
        dto.uuid = uuid;
        dto.name = name;
        dto.online = online;
        return dto;
    }

    private void fillStats(final PlayerStatsDto player, final Map<String, Integer> rawValues) {
        for (StatDescriptor descriptor : catalog) {
            if (!isDisplayed(descriptor)) {
                continue;
            }

            int raw = rawValues.getOrDefault(descriptor.key, 0);
            player.values.put(descriptor.key, raw);
            if (!config.display.showZeroValues && raw == 0) {
                continue;
            }

            player.stats.add(toDto(descriptor, raw));
        }

        if (config.display.maxStatsPerPlayer > 0 && player.stats.size() > config.display.maxStatsPerPlayer) {
            player.stats = new ArrayList<>(player.stats.subList(0, config.display.maxStatsPerPlayer));
        }

        for (String featuredKey : config.display.featuredStats) {
            StatDescriptor descriptor = descriptorByKey(featuredKey);
            if (descriptor != null) {
                player.featured.add(toDto(descriptor, rawValues.getOrDefault(featuredKey, 0)));
            }
        }

        player.shownStats = player.stats.size();
    }

    private boolean isDisplayed(final StatDescriptor descriptor) {
        if (hiddenStats.contains(descriptor.key)) {
            return false;
        }

        if (!enabledStats.isEmpty()) {
            return enabledStats.contains(descriptor.key);
        }

        return enabledStatTypes.isEmpty() || enabledStatTypes.contains(descriptor.typeId);
    }

    private boolean isHiddenPlayer(final String id) {
        return id != null && hiddenPlayers.contains(id.toLowerCase(Locale.ROOT));
    }

    private List<StatInfoDto> displayedCatalog() {
        List<StatInfoDto> stats = new ArrayList<>();
        for (StatDescriptor descriptor : catalog) {
            if (isDisplayed(descriptor)) {
                stats.add(toInfoDto(descriptor));
            }
        }

        return stats;
    }

    private StatDescriptor descriptorByKey(final String key) {
        for (StatDescriptor descriptor : catalog) {
            if (descriptor.key.equals(key)) {
                return descriptor;
            }
        }

        return null;
    }

    private StatValueDto toDto(final StatDescriptor descriptor, final int raw) {
        StatValueDto dto = new StatValueDto();
        dto.key = descriptor.key;
        dto.type = descriptor.typeId;
        dto.value = descriptor.valueId;
        dto.category = descriptor.category;
        dto.name = descriptor.name;
        dto.raw = raw;
        dto.formatted = descriptor.stat.format(raw);
        return dto;
    }

    private StatInfoDto toInfoDto(final StatDescriptor descriptor) {
        StatInfoDto dto = new StatInfoDto();
        dto.key = descriptor.key;
        dto.type = descriptor.typeId;
        dto.value = descriptor.valueId;
        dto.category = descriptor.category;
        dto.name = descriptor.name;
        return dto;
    }

    private List<Path> statFiles() {
        List<Path> files = new ArrayList<>();
        Path statsPath = statsPath();
        if (!Files.isDirectory(statsPath)) {
            return files;
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(statsPath, "*.json")) {
            for (Path file : stream) {
                files.add(file);
            }
        } catch (IOException exception) {
            Stats4UsMod.LOGGER.warn("Failed to list stats files in {}", statsPath, exception);
        }

        files.sort(Comparator.comparing(path -> path.getFileName().toString()));
        return files;
    }

    private Map<String, Integer> readStatsFile(final Path file) {
        try {
            Path cacheKey = file.toAbsolutePath().normalize();
            long lastModifiedMillis = Files.getLastModifiedTime(file).toMillis();
            CachedStats cached = offlineStatsCache.get(cacheKey);
            if (cached != null && cached.lastModifiedMillis == lastModifiedMillis) {
                return cached.values;
            }

            Map<String, Integer> values = parseStatsFile(file);
            offlineStatsCache.put(cacheKey, new CachedStats(lastModifiedMillis, values));
            return values;
        } catch (Exception exception) {
            Stats4UsMod.LOGGER.warn("Failed to read stats file {}", file, exception);
            return Map.of();
        }
    }

    private Map<String, Integer> parseStatsFile(final Path file) {
        Map<String, Integer> values = new HashMap<>();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(reader);
            if (!root.isJsonObject()) {
                return values;
            }

            JsonObject stats = root.getAsJsonObject().getAsJsonObject("stats");
            if (stats == null) {
                return values;
            }

            for (Map.Entry<String, JsonElement> typeEntry : stats.entrySet()) {
                if (!typeEntry.getValue().isJsonObject()) {
                    continue;
                }

                for (Map.Entry<String, JsonElement> valueEntry : typeEntry.getValue().getAsJsonObject().entrySet()) {
                    if (valueEntry.getValue().isJsonPrimitive() && valueEntry.getValue().getAsJsonPrimitive().isNumber()) {
                        values.put(StatResolver.key(typeEntry.getKey(), valueEntry.getKey()), valueEntry.getValue().getAsInt());
                    }
                }
            }
        } catch (Exception exception) {
            Stats4UsMod.LOGGER.warn("Failed to read stats file {}", file, exception);
        }

        return values;
    }

    private Map<String, String> loadKnownNames() {
        return nameResolver.knownNames();
    }

    private UUID uuidFromStatsFile(final Path file) {
        String id = idFromStatsFile(file);
        if (id == null) {
            return null;
        }

        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private String idFromStatsFile(final Path file) {
        String fileName = file.getFileName().toString();
        if (!fileName.endsWith(".json")) {
            return null;
        }

        return fileName.substring(0, fileName.length() - ".json".length());
    }

    private record HistoryPlayerSource(String name, boolean online, Map<String, Integer> values) {
    }

    private record CachedStats(long lastModifiedMillis, Map<String, Integer> values) {
    }

    private static final class HistoryFile {
        public List<HistorySampleDto> samples = new ArrayList<>();
    }
}
