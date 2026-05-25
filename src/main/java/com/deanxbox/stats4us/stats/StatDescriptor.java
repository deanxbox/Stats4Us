package com.deanxbox.stats4us.stats;

import net.minecraft.stats.Stat;

public final class StatDescriptor {
    public final String key;
    public final String typeId;
    public final String valueId;
    public final String category;
    public final String name;
    public final Stat<?> stat;

    public StatDescriptor(
        final String key,
        final String typeId,
        final String valueId,
        final String category,
        final String name,
        final Stat<?> stat
    ) {
        this.key = key;
        this.typeId = typeId;
        this.valueId = valueId;
        this.category = category;
        this.name = name;
        this.stat = stat;
    }
}
