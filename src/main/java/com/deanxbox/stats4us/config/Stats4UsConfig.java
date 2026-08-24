package com.deanxbox.stats4us.config;

import java.util.ArrayList;
import java.util.List;

public final class Stats4UsConfig {
    public Web web = new Web();
    public Display display = new Display();
    public History history = new History();

    public static final class Web {
        public boolean enabled = true;
        public String bindAddress = "0.0.0.0";
        public int port = 8765;
        public int requestTimeoutSeconds = 10;
    }

    public static final class Display {
        public boolean showZeroValues = false;
        public boolean showOfflinePlayers = true;
        public int maxStatsPerPlayer = 0;
        public List<String> enabledStatTypes = new ArrayList<>(List.of(
            "minecraft:custom",
            "minecraft:mined",
            "minecraft:crafted",
            "minecraft:used",
            "minecraft:broken",
            "minecraft:picked_up",
            "minecraft:dropped",
            "minecraft:killed",
            "minecraft:killed_by"
        ));
        public List<String> enabledStats = new ArrayList<>();
        public List<String> hiddenStats = new ArrayList<>();
        public List<String> hiddenPlayers = new ArrayList<>();
        public List<String> featuredStats = new ArrayList<>(List.of(
            "minecraft:custom|minecraft:play_time",
            "minecraft:custom|minecraft:deaths",
            "minecraft:custom|minecraft:mob_kills",
            "minecraft:custom|minecraft:player_kills",
            "minecraft:custom|minecraft:damage_dealt",
            "minecraft:custom|minecraft:damage_taken",
            "minecraft:custom|minecraft:walk_one_cm",
            "minecraft:custom|minecraft:jump"
        ));
    }

    public static final class History {
        public boolean enabled = true;
        public int sampleIntervalSeconds = 300;
        public int maxSamples = 2016;
        public List<String> trackedStats = new ArrayList<>(List.of(
            "minecraft:custom|minecraft:play_time",
            "minecraft:custom|minecraft:deaths",
            "minecraft:custom|minecraft:mob_kills",
            "minecraft:custom|minecraft:player_kills",
            "minecraft:custom|minecraft:damage_dealt",
            "minecraft:custom|minecraft:damage_taken",
            "minecraft:custom|minecraft:walk_one_cm",
            "minecraft:custom|minecraft:jump"
        ));
    }
}
