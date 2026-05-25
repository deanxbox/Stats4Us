package com.deanxbox.stats4us.stats;

import java.util.ArrayList;
import java.util.List;

public final class StatsSnapshotDto {
    public String generatedAt;
    public String minecraftVersion;
    public String statsPath;
    public int onlinePlayers;
    public int totalPlayers;
    public int totalAvailableStats;
    public List<PlayerStatsDto> players = new ArrayList<>();
    public List<StatInfoDto> catalog = new ArrayList<>();
    public List<HistorySampleDto> history = new ArrayList<>();
    public List<String> enabledStatTypes = new ArrayList<>();
    public boolean showZeroValues;
}
