package com.deanxbox.stats4us;

import com.deanxbox.stats4us.command.Stats4UsCommands;
import com.deanxbox.stats4us.config.ConfigManager;
import com.deanxbox.stats4us.config.Stats4UsConfig;
import com.deanxbox.stats4us.stats.StatsService;
import com.deanxbox.stats4us.web.StatsWebServer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Stats4UsMod implements ModInitializer {
    public static final String MOD_ID = "stats4us";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static ConfigManager configManager;
    private static StatsService statsService;
    private static StatsWebServer webServer;
    private static int tickCounter;

    @Override
    public void onInitialize() {
        configManager = new ConfigManager();
        configManager.load();

        Stats4UsCommands.register();

        ServerLifecycleEvents.SERVER_STARTED.register(Stats4UsMod::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(Stats4UsMod::onServerStopping);
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            tickCounter++;
            if (statsService != null && tickCounter >= 20) {
                tickCounter = 0;
                statsService.tick();
            }
        });
    }

    private static void onServerStarted(final MinecraftServer server) {
        tickCounter = 0;
        statsService = new StatsService(server, configManager);
        startWebServer(server);
    }

    private static void onServerStopping(final MinecraftServer server) {
        stopWebServer();
        if (statsService != null) {
            statsService.close();
        }
        statsService = null;
        tickCounter = 0;
    }

    public static Stats4UsConfig config() {
        return configManager.config();
    }

    public static StatsService statsService() {
        return statsService;
    }

    public static void saveConfig() {
        configManager.save();
        if (statsService != null) {
            statsService.refreshConfig();
        }
    }

    public static void reload(final MinecraftServer server) {
        configManager.load();
        if (statsService != null) {
            statsService.refreshConfig();
        }

        stopWebServer();
        startWebServer(server);
    }

    public static String webAddress() {
        if (webServer == null || !webServer.isRunning()) {
            return "disabled";
        }

        return webServer.address();
    }

    private static void startWebServer(final MinecraftServer server) {
        Stats4UsConfig config = configManager.config();
        if (!config.web.enabled) {
            LOGGER.info("Stats4Us web dashboard disabled by config.");
            return;
        }

        webServer = new StatsWebServer(server, statsService, config);
        webServer.start();
    }

    private static void stopWebServer() {
        if (webServer != null) {
            webServer.stop();
            webServer = null;
        }
    }
}
