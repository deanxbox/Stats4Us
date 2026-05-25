package com.deanxbox.stats4us.stats;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PlayerStatsDto {
    public String uuid;
    public String name;
    public boolean online;
    public int shownStats;
    public Map<String, Integer> values = new LinkedHashMap<>();
    public List<StatValueDto> stats = new ArrayList<>();
    public List<StatValueDto> featured = new ArrayList<>();
}
