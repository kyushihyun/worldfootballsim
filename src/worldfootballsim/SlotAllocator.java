package worldfootballsim;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class SlotAllocator {

    public static class TieredSlots {
        // competitionKey -> countryName -> slotCount
        public final Map<String, Map<String, Integer>> slotsByCompetition;

        public TieredSlots(Map<String, Map<String, Integer>> slotsByCompetition) {
            this.slotsByCompetition = slotsByCompetition;
        }

        public Map<String, Integer> forCompetition(String competitionKey) {
            return slotsByCompetition.getOrDefault(competitionKey, Collections.emptyMap());
        }

        public Map<String, Integer> forTier(int tier, List<ContinentalCompetitionDef> defs) {
            for (ContinentalCompetitionDef def : defs) {
                if (def.getTier() == tier) {
                    return forCompetition(def.getCompetitionKey());
                }
            }
            return Collections.emptyMap();
        }
    }

    // Legacy compatibility wrapper
    public static class Slots {
        public final Map<String, Integer> championsCup;
        public final Map<String, Integer> secondaryCup;
        public Slots(Map<String, Integer> championsCup, Map<String, Integer> secondaryCup) {
            this.championsCup = championsCup;
            this.secondaryCup = secondaryCup;
        }
    }

    public static Slots allocate(Confederation conf, List<CountryAssociation> countries) {
        List<ContinentalCompetitionDef> defs = ContinentalCompetitionDef.buildAllDefs()
            .getOrDefault(conf, Collections.emptyList());
        TieredSlots tiered = allocateTiered(conf, countries, defs, null);
        return new Slots(tiered.forTier(1, defs), tiered.forTier(2, defs));
    }

    /**
     * Allocate slots for all tiers of continental competition for a confederation.
     * Countries are ranked by 5-year rolling coefficient.
     * @param uefaRulesCsvPath path to uefa_association_rank_rules.csv (nullable for non-UEFA)
     */
    public static TieredSlots allocateTiered(Confederation conf, List<CountryAssociation> countries,
                                              List<ContinentalCompetitionDef> defs,
                                              String uefaRulesCsvPath) {
        List<CountryAssociation> ranked = new ArrayList<>(countries);
        ranked.sort((a, b) -> Double.compare(b.getRollingCoefficient(), a.getRollingCoefficient()));

        Map<String, Map<String, Integer>> result = new LinkedHashMap<>();
        for (ContinentalCompetitionDef def : defs) {
            result.put(def.getCompetitionKey(), new HashMap<>());
        }

        if (conf == Confederation.EUROPE && uefaRulesCsvPath != null) {
            List<UefaRule> rules = loadUefaRules(uefaRulesCsvPath);
            if (!rules.isEmpty()) {
                allocateUefaFromRules(ranked, defs, result, rules);
                return new TieredSlots(result);
            }
        }

        // Coefficient-based allocation for all confederations
        allocateByCoefficient(conf, ranked, defs, result);
        return new TieredSlots(result);
    }

    // ==================== UEFA CSV-DRIVEN ALLOCATION ====================

    private static final class UefaRule {
        final String competitionKey;
        final int rankMin, rankMax;
        final List<String> domesticSlots;

        UefaRule(String competitionKey, int rankMin, int rankMax,
                 List<String> domesticSlots, String entryRound) {
            this.competitionKey = competitionKey;
            this.rankMin = rankMin;
            this.rankMax = rankMax;
            this.domesticSlots = domesticSlots;
        }
    }

    private static List<UefaRule> loadUefaRules(String csvPath) {
        List<UefaRule> rules = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                new FileInputStream(csvPath), StandardCharsets.UTF_8))) {
            String header = br.readLine();
            if (header == null) return rules;

            List<String> cols = parseCsvLine(header);
            Map<String, Integer> idx = indexColumns(cols);

            String line;
            while ((line = br.readLine()) != null) {
                List<String> vals = parseCsvLine(line);
                String compKey = getVal(vals, idx, "competition_key");
                int rankMin = getInt(vals, idx, "rank_min", 0);
                int rankMax = getInt(vals, idx, "rank_max", 0);
                String slotsStr = getVal(vals, idx, "domestic_slots");
                String entryRound = getVal(vals, idx, "entry_round");

                List<String> slots = new ArrayList<>();
                for (String s : slotsStr.split(",")) {
                    String trimmed = s.trim();
                    if (!trimmed.isEmpty()) slots.add(trimmed);
                }

                if (!compKey.isEmpty() && rankMin > 0 && !slots.isEmpty()) {
                    rules.add(new UefaRule(compKey, rankMin, rankMax, slots, entryRound));
                }
            }
        } catch (IOException e) {
            System.err.println("Warning: Could not load UEFA rules CSV: " + e.getMessage());
        }
        return rules;
    }

    private static void allocateUefaFromRules(List<CountryAssociation> ranked,
                                               List<ContinentalCompetitionDef> defs,
                                               Map<String, Map<String, Integer>> result,
                                               List<UefaRule> rules) {
        // Track filled counts per competition
        Map<String, Integer> filled = new HashMap<>();
        Map<String, Integer> targets = new HashMap<>();
        for (ContinentalCompetitionDef def : defs) {
            filled.put(def.getCompetitionKey(), 0);
            targets.put(def.getCompetitionKey(), def.getTargetTeams());
        }

        for (int i = 0; i < ranked.size(); i++) {
            String country = ranked.get(i).getName();
            int rank = i + 1;

            for (UefaRule rule : rules) {
                if (rank < rule.rankMin || rank > rule.rankMax) continue;

                Map<String, Integer> compSlots = result.get(rule.competitionKey);
                if (compSlots == null) continue;

                int target = targets.getOrDefault(rule.competitionKey, 0);
                int current = filled.getOrDefault(rule.competitionKey, 0);

                int slotsForCountry = rule.domesticSlots.size();
                slotsForCountry = Math.min(slotsForCountry, target - current);

                if (slotsForCountry > 0) {
                    int existing = compSlots.getOrDefault(country, 0);
                    compSlots.put(country, existing + slotsForCountry);
                    filled.put(rule.competitionKey, current + slotsForCountry);
                }
            }
        }

        // Fill remaining spots from top-ranked
        for (ContinentalCompetitionDef def : defs) {
            String key = def.getCompetitionKey();
            int f = filled.getOrDefault(key, 0);
            int t = targets.getOrDefault(key, 0);
            if (f < t) {
                fillRemaining(result.get(key), ranked, t, f, 6);
            }
        }
    }

    // ==================== COEFFICIENT-BASED ALLOCATION ====================

    private static void allocateByCoefficient(Confederation conf, List<CountryAssociation> ranked,
                                               List<ContinentalCompetitionDef> defs,
                                               Map<String, Map<String, Integer>> result) {
        for (ContinentalCompetitionDef def : defs) {
            int target = def.getTargetTeams();
            if (target <= 0) continue;

            Map<String, Integer> slots = result.computeIfAbsent(def.getCompetitionKey(), k -> new HashMap<>());
            int filled = 0;

            int[] pattern = getSlotPattern(conf, def.getTier());

            for (int i = 0; i < ranked.size() && filled < target; i++) {
                int slotsForRank = (i < pattern.length) ? pattern[i] : 1;
                slotsForRank = Math.min(slotsForRank, target - filled);
                if (slotsForRank > 0) {
                    slots.put(ranked.get(i).getName(), slotsForRank);
                    filled += slotsForRank;
                }
            }

            fillRemaining(slots, ranked, target, filled, 5);
        }
    }

    private static int[] getSlotPattern(Confederation conf, int tier) {
        switch (conf) {
            case ASIA:
                if (tier == 1) return new int[]{3, 3, 2, 2, 2, 1, 1, 1};
                if (tier == 2) return new int[]{2, 2, 2, 2, 1, 1, 1, 1};
                return new int[]{1, 1, 1, 1, 1, 1, 1, 1};
            case AFRICA:
                if (tier == 1) return new int[]{2, 2, 2, 2, 1, 1, 1, 1, 1, 1, 1, 1};
                return new int[]{2, 2, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1};
            case SOUTH_AMERICA:
                if (tier == 1) return new int[]{6, 6, 5, 4, 3, 2, 1, 1, 1, 1};
                return new int[]{6, 6, 4, 3, 3, 2, 2, 1, 1, 1};
            case NORTH_CENTRAL_AMERICA:
                return new int[]{3, 3, 2, 2, 1, 1, 1, 1};
            case OCEANIA:
                return new int[]{3, 3, 2, 2, 1, 1};
            case EUROPE:
                if (tier == 1) return new int[]{4, 4, 4, 4, 3, 2, 1, 1, 1, 1};
                if (tier == 2) return new int[]{2, 2, 2, 2, 2, 1, 1, 1, 1, 1, 1, 1, 1};
                return new int[]{1, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 3, 3, 3, 3};
            default:
                return new int[]{2, 2, 1, 1, 1, 1};
        }
    }

    private static void fillRemaining(Map<String, Integer> slots, List<CountryAssociation> ranked,
                                       int target, int filled, int maxPerCountry) {
        int cursor = 0;
        int safety = ranked.size() * maxPerCountry;
        while (filled < target && !ranked.isEmpty() && safety-- > 0) {
            String country = ranked.get(cursor % ranked.size()).getName();
            int current = slots.getOrDefault(country, 0);
            if (current < maxPerCountry) {
                slots.put(country, current + 1);
                filled++;
            }
            cursor++;
        }
    }

    // ==================== CSV PARSING HELPERS ====================

    private static List<String> parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                inQuotes = !inQuotes;
            } else if (ch == ',' && !inQuotes) {
                fields.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        fields.add(current.toString().trim());
        return fields;
    }

    private static Map<String, Integer> indexColumns(List<String> header) {
        Map<String, Integer> map = new HashMap<>();
        for (int i = 0; i < header.size(); i++) {
            map.put(header.get(i).trim().toLowerCase(), i);
        }
        return map;
    }

    private static String getVal(List<String> vals, Map<String, Integer> idx, String col) {
        Integer i = idx.get(col.toLowerCase());
        if (i == null || i >= vals.size()) return "";
        return vals.get(i).trim();
    }

    private static int getInt(List<String> vals, Map<String, Integer> idx, String col, int fallback) {
        String v = getVal(vals, idx, col);
        if (v.isEmpty()) return fallback;
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private SlotAllocator() {}
}
