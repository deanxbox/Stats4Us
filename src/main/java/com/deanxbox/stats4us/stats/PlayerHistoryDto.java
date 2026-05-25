package com.deanxbox.stats4us.stats;

import java.util.LinkedHashMap;
import java.util.Map;

public final class PlayerHistoryDto {
    public String name;
    public boolean online;
    public Map<String, Integer> values = new LinkedHashMap<>();
}
