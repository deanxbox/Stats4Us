package com.deanxbox.stats4us.config;

import com.deanxbox.stats4us.Stats4UsMod;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ConfigManager {
    private static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .create();

    private final Path configPath;
    private Stats4UsConfig config = new Stats4UsConfig();

    public ConfigManager() {
        this.configPath = FabricLoader.getInstance().getConfigDir().resolve("stats4us.json");
    }

    public Stats4UsConfig config() {
        return config;
    }

    public void load() {
        if (!Files.exists(configPath)) {
            config = new Stats4UsConfig();
            save();
            return;
        }

        try (Reader reader = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
            Stats4UsConfig loaded = GSON.fromJson(reader, Stats4UsConfig.class);
            config = loaded == null ? new Stats4UsConfig() : loaded;
            validate();
            save();
        } catch (IOException | JsonSyntaxException exception) {
            Stats4UsMod.LOGGER.error("Failed to load Stats4Us config at {}. Falling back to defaults.", configPath, exception);
            config = new Stats4UsConfig();
            save();
        }
    }

    public void save() {
        try {
            Files.createDirectories(configPath.getParent());
            try (Writer writer = Files.newBufferedWriter(configPath, StandardCharsets.UTF_8)) {
                GSON.toJson(config, writer);
            }
        } catch (IOException exception) {
            Stats4UsMod.LOGGER.error("Failed to save Stats4Us config at {}.", configPath, exception);
        }
    }

    private void validate() {
        if (config.web == null) {
            config.web = new Stats4UsConfig.Web();
        }
        if (config.display == null) {
            config.display = new Stats4UsConfig.Display();
        }
        if (config.history == null) {
            config.history = new Stats4UsConfig.History();
        }
        if (config.web.bindAddress == null || config.web.bindAddress.isBlank()) {
            config.web.bindAddress = "0.0.0.0";
        }
        if (config.web.port < 1 || config.web.port > 65535) {
            config.web.port = 8765;
        }
        if (config.web.requestTimeoutSeconds < 1) {
            config.web.requestTimeoutSeconds = 10;
        }
        if (config.display.enabledStatTypes == null) {
            config.display.enabledStatTypes = new Stats4UsConfig.Display().enabledStatTypes;
        }
        if (config.display.enabledStats == null) {
            config.display.enabledStats = new Stats4UsConfig.Display().enabledStats;
        }
        if (config.display.hiddenStats == null) {
            config.display.hiddenStats = new Stats4UsConfig.Display().hiddenStats;
        }
        if (config.display.featuredStats == null) {
            config.display.featuredStats = new Stats4UsConfig.Display().featuredStats;
        }
        if (config.display.maxStatsPerPlayer < 0) {
            config.display.maxStatsPerPlayer = 0;
        }
        if (config.history.sampleIntervalSeconds < 30) {
            config.history.sampleIntervalSeconds = 30;
        }
        if (config.history.maxSamples < 2) {
            config.history.maxSamples = 2;
        }
        if (config.history.trackedStats == null) {
            config.history.trackedStats = new Stats4UsConfig.History().trackedStats;
        }
    }
}
