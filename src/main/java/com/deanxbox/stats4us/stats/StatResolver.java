package com.deanxbox.stats4us.stats;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.stats.Stat;
import net.minecraft.stats.StatType;
import net.minecraft.stats.Stats;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class StatResolver {
    private StatResolver() {
    }

    public static String key(final String typeId, final String valueId) {
        return typeId + "|" + valueId;
    }

    public static List<StatDescriptor> buildCatalog() {
        ensureVanillaStatsLoaded();
        List<StatDescriptor> catalog = new ArrayList<>();

        for (StatType<?> type : BuiltInRegistries.STAT_TYPE) {
            Identifier typeId = BuiltInRegistries.STAT_TYPE.getKey(type);
            if (typeId != null) {
                addType(catalog, type, typeId);
            }
        }

        catalog.sort(Comparator.comparing((StatDescriptor descriptor) -> descriptor.category)
            .thenComparing(descriptor -> descriptor.name)
            .thenComparing(descriptor -> descriptor.key));
        return catalog;
    }

    public static Stat<?> resolve(final String typeRaw, final String valueRaw) {
        ensureVanillaStatsLoaded();
        Identifier typeId = Identifier.parse(typeRaw);
        Identifier valueId = Identifier.parse(valueRaw);
        StatType<?> type = BuiltInRegistries.STAT_TYPE.getValue(typeId);
        if (type == null) {
            throw new IllegalArgumentException("Unknown stat type: " + typeId);
        }

        Object value = type.getRegistry().getValue(valueId);
        if (value == null) {
            throw new IllegalArgumentException("Unknown stat value " + valueId + " for type " + typeId);
        }

        return stat(type, value);
    }

    public static StatDescriptor resolveFlexible(final String query) {
        String normalizedQuery = normalizeQuery(query);
        List<StatDescriptor> matches = search(normalizedQuery);
        if (matches.isEmpty()) {
            String[] split = query.trim().split("\\s+");
            if (split.length == 2) {
                Stat<?> stat = resolve(split[0], split[1]);
                return descriptorFor(stat);
            }

            throw new IllegalArgumentException("Unknown stat: " + query + ". Try /stats4us stats " + query);
        }

        if (matches.size() > 1) {
            List<String> names = matches.stream().limit(5).map(descriptor -> descriptor.name + " (" + descriptor.key + ")").toList();
            throw new IllegalArgumentException("Ambiguous stat '" + query + "'. Matches: " + String.join(", ", names));
        }

        return matches.getFirst();
    }

    public static List<StatDescriptor> search(final String query) {
        String normalizedQuery = normalizeQuery(query);
        List<StatDescriptor> exact = new ArrayList<>();
        List<StatDescriptor> partial = new ArrayList<>();

        for (StatDescriptor descriptor : buildCatalog()) {
            List<String> candidates = List.of(
                descriptor.key,
                descriptor.name,
                descriptor.valueId,
                Identifier.parse(descriptor.valueId).getPath(),
                descriptor.category + " " + descriptor.name,
                descriptor.typeId + " " + descriptor.valueId
            );

            for (String candidate : candidates) {
                String normalizedCandidate = normalizeQuery(candidate);
                if (normalizedCandidate.equals(normalizedQuery)) {
                    exact.add(descriptor);
                    break;
                }
                if (!normalizedQuery.isBlank() && normalizedCandidate.contains(normalizedQuery)) {
                    partial.add(descriptor);
                    break;
                }
            }
        }

        return exact.isEmpty() ? partial : exact;
    }

    public static List<String> statSuggestions() {
        return buildCatalog().stream()
            .flatMap(descriptor -> List.of(descriptor.name, descriptor.key).stream())
            .distinct()
            .sorted()
            .toList();
    }

    public static List<String> statTypeSuggestions() {
        ensureVanillaStatsLoaded();
        return BuiltInRegistries.STAT_TYPE.keySet()
            .stream()
            .map(Identifier::toString)
            .sorted()
            .toList();
    }

    public static List<String> valueSuggestions(final String typeRaw) {
        ensureVanillaStatsLoaded();
        Identifier typeId = Identifier.tryParse(typeRaw);
        if (typeId == null) {
            return List.of();
        }

        StatType<?> type = BuiltInRegistries.STAT_TYPE.getValue(typeId);
        if (type == null) {
            return List.of();
        }

        return type.getRegistry().keySet()
            .stream()
            .map(Identifier::toString)
            .sorted()
            .toList();
    }

    private static StatDescriptor descriptorFor(final Stat<?> stat) {
        for (StatDescriptor descriptor : buildCatalog()) {
            if (descriptor.stat.equals(stat)) {
                return descriptor;
            }
        }

        throw new IllegalArgumentException("Unknown stat: " + stat.getName());
    }

    private static <T> void addType(final List<StatDescriptor> catalog, final StatType<T> type, final Identifier typeId) {
        Registry<T> registry = type.getRegistry();
        for (T value : registry) {
            Identifier valueId = registry.getKey(value);
            if (valueId == null) {
                continue;
            }

            String typeString = typeId.toString();
            String valueString = valueId.toString();
            catalog.add(new StatDescriptor(
                key(typeString, valueString),
                typeString,
                valueString,
                categoryName(typeId),
                statName(typeId, valueId),
                type.get(value)
            ));
        }
    }

    private static void ensureVanillaStatsLoaded() {
        Stats.DEATHS.toString();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Stat<?> stat(final StatType<?> type, final Object value) {
        return ((StatType) type).get(value);
    }

    private static String categoryName(final Identifier typeId) {
        return switch (typeId.getPath()) {
            case "custom" -> "General";
            case "mined" -> "Blocks Mined";
            case "crafted" -> "Items Crafted";
            case "used" -> "Items Used";
            case "broken" -> "Items Broken";
            case "picked_up" -> "Items Picked Up";
            case "dropped" -> "Items Dropped";
            case "killed" -> "Mobs Killed";
            case "killed_by" -> "Killed By";
            default -> titleCase(typeId.getPath());
        };
    }

    private static String statName(final Identifier typeId, final Identifier valueId) {
        String valueName = titleCase(valueId.getPath());
        return switch (typeId.getPath()) {
            case "custom" -> valueName;
            case "mined" -> valueName + " Mined";
            case "crafted" -> valueName + " Crafted";
            case "used" -> valueName + " Used";
            case "broken" -> valueName + " Broken";
            case "picked_up" -> valueName + " Picked Up";
            case "dropped" -> valueName + " Dropped";
            case "killed" -> valueName + " Killed";
            case "killed_by" -> "Killed By " + valueName;
            default -> valueName;
        };
    }

    private static String titleCase(final String path) {
        String[] parts = path.replace('/', '_').split("_");
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                builder.append(part.substring(1));
            }
        }

        return builder.toString();
    }

    private static String normalizeQuery(final String value) {
        return value.trim()
            .toLowerCase(Locale.ROOT)
            .replace('|', ' ')
            .replace(':', ' ')
            .replace('.', ' ')
            .replace('/', ' ')
            .replace('_', ' ')
            .replace('-', ' ')
            .replaceAll("\\s+", " ");
    }
}
