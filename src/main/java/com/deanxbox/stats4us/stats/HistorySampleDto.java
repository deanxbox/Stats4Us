package com.deanxbox.stats4us.stats;

import java.util.LinkedHashMap;
import java.util.Map;

public final class HistorySampleDto {
    public String timestamp;
    public int onlinePlayers;
    public int totalPlayers;
    public Map<String, Integer> totals = new LinkedHashMap<>();
    public Map<String, PlayerHistoryDto> players = new LinkedHashMap<>();
}
