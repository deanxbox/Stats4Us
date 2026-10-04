package com.deanxbox.stats4us.stats;

import com.deanxbox.stats4us.Stats4UsMod;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import com.mojang.authlib.GameProfile;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.ServerOpListEntry;
import net.minecraft.server.players.StoredUserEntry;
import net.minecraft.server.players.UserBanListEntry;
import net.minecraft.server.players.UserWhiteListEntry;

import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Resolves player UUIDs to usernames without needing the player to join or be whitelisted.
 *
 * <p>Names come from, in order: online players, the server's own user cache, the whitelist/ops/ban lists,
 * Stats4Us' persistent name cache, and finally a background Mojang profile lookup for anything still unknown.
 * Network lookups never run on the server thread.
 */
public final class PlayerNameResolver {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Type CACHE_TYPE = new TypeToken<Map<String, String>>() { }.getType();
    private static final long RETRY_AFTER_MILLIS = 6L * 60L * 60L * 1000L;
    private static final long LOOKUP_DELAY_MILLIS = 250L;

    private final MinecraftServer server;
    private final Path cachePath;
    private final Map<String, String> resolved = new ConcurrentHashMap<>();
    private final Map<String, Long> failedAt = new ConcurrentHashMap<>();
    private final Set<String> pending = ConcurrentHashMap.newKeySet();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Stats4Us-NameLookup");
        thread.setDaemon(true);
        return thread;
    });

    public PlayerNameResolver(final MinecraftServer server) {
        this.server = server;
        this.cachePath = FabricLoader.getInstance().getConfigDir().resolve("stats4us-names.json");
        loadCache();
    }

    /** Returns every UUID-to-name mapping currently known. Safe to call on the server thread. */
    public Map<String, String> knownNames() {
        Map<String, String> names = new HashMap<>(resolved);
        readUserCache(names);

        try {
            for (UserWhiteListEntry entry : server.getPlayerList().getWhiteList().getEntries()) {
                put(names, entry);
            }
            for (ServerOpListEntry entry : server.getPlayerList().getOps().getEntries()) {
                put(names, entry);
            }
            for (UserBanListEntry entry : server.getPlayerList().getBans().getEntries()) {
                put(names, entry);
            }
        } catch (Exception exception) {
            Stats4UsMod.LOGGER.debug("Failed to read player lists for Stats4Us names.", exception);
        }

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            GameProfile profile = player.getGameProfile();
            names.put(profile.id().toString(), profile.name());
        }

        return names;
    }

    /** Queues background lookups for ids that have no known name. */
    public void requestMissing(final Collection<String> ids) {
        long now = System.currentTimeMillis();
        for (String id : ids) {
            UUID uuid = parse(id);
            if (uuid == null || uuid.version() != 4 || resolved.containsKey(id)) {
                continue;
            }

            Long failed = failedAt.get(id);
            if (failed != null && now - failed < RETRY_AFTER_MILLIS) {
                continue;
            }

            if (pending.add(id)) {
                try {
                    executor.execute(() -> lookup(id, uuid));
                } catch (RuntimeException exception) {
                    pending.remove(id);
                }
            }
        }
    }

    public void close() {
        executor.shutdownNow();
        saveCache();
    }

    private void lookup(final String id, final UUID uuid) {
        try {
            Optional<GameProfile> profile = server.services().profileResolver().fetchById(uuid);
            if (profile.isPresent() && profile.get().name() != null && !profile.get().name().isBlank()) {
                resolved.put(id, profile.get().name());
                failedAt.remove(id);
                if (pending.size() <= 1) {
                    saveCache();
                }
            } else {
                failedAt.put(id, System.currentTimeMillis());
            }
            Thread.sleep(LOOKUP_DELAY_MILLIS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (Exception exception) {
            failedAt.put(id, System.currentTimeMillis());
            Stats4UsMod.LOGGER.debug("Stats4Us could not resolve a name for {}.", id, exception);
        } finally {
            pending.remove(id);
        }
    }

    private void readUserCache(final Map<String, String> names) {
        Path userCache = server.getFile("usercache.json");
        if (!Files.isRegularFile(userCache)) {
            return;
        }

        try (Reader reader = Files.newBufferedReader(userCache, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(reader);
            if (!root.isJsonArray()) {
                return;
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
            Stats4UsMod.LOGGER.debug("Failed to read usercache.json for Stats4Us names.", exception);
        }
    }

    private static void put(final Map<String, String> names, final StoredUserEntry<NameAndId> entry) {
        NameAndId user = entry.getUser();
        if (user != null && user.name() != null && !user.name().isBlank()) {
            names.put(user.id().toString(), user.name());
        }
    }

    private void loadCache() {
        if (!Files.isRegularFile(cachePath)) {
            return;
        }

        try (Reader reader = Files.newBufferedReader(cachePath, StandardCharsets.UTF_8)) {
            Map<String, String> cached = GSON.fromJson(reader, CACHE_TYPE);
            if (cached != null) {
                cached.forEach((id, name) -> {
                    if (id != null && name != null && !name.isBlank()) {
                        resolved.put(id, name);
                    }
                });
            }
        } catch (Exception exception) {
            Stats4UsMod.LOGGER.warn("Failed to load Stats4Us name cache at {}.", cachePath, exception);
        }
    }

    private synchronized void saveCache() {
        try {
            Files.createDirectories(cachePath.getParent());
            try (Writer writer = Files.newBufferedWriter(cachePath, StandardCharsets.UTF_8)) {
                GSON.toJson(new java.util.TreeMap<>(resolved), writer);
            }
        } catch (Exception exception) {
            Stats4UsMod.LOGGER.warn("Failed to save Stats4Us name cache at {}.", cachePath, exception);
        }
    }

    private static UUID parse(final String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
