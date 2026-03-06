package worldfootballsim;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class ContinentalCompetitionDef {

    public enum StageType {
        QUALIFYING_KNOCKOUT,
        LEAGUE_PHASE,
        GROUP_STAGE,
        KNOCKOUT,
        FINAL;

        public static StageType fromCsv(String s) {
            if (s == null) return KNOCKOUT;
            switch (s.trim().toLowerCase()) {
                case "qualifying_knockout": return QUALIFYING_KNOCKOUT;
                case "league_phase":        return LEAGUE_PHASE;
                case "group_stage":         return GROUP_STAGE;
                case "knockout":            return KNOCKOUT;
                case "final":               return FINAL;
                default:                    return KNOCKOUT;
            }
        }
    }

    public static final class StageDef {
        private final String stageKey;
        private final int stageOrder;
        private final String stageName;
        private final StageType stageType;
        private final int teams;
        private final int ties;
        private final int legs;
        private final int groups;
        private final int groupSize;
        private final int matchesPerTeam;

        public StageDef(String stageKey, int stageOrder, String stageName, StageType stageType,
                        int teams, int ties, int legs, int groups, int groupSize, int matchesPerTeam) {
            this.stageKey = stageKey;
            this.stageOrder = stageOrder;
            this.stageName = stageName;
            this.stageType = stageType;
            this.teams = teams;
            this.ties = ties;
            this.legs = legs;
            this.groups = groups;
            this.groupSize = groupSize;
            this.matchesPerTeam = matchesPerTeam;
        }

        public String getStageKey() { return stageKey; }
        public int getStageOrder() { return stageOrder; }
        public String getStageName() { return stageName; }
        public StageType getStageType() { return stageType; }
        public int getTeams() { return teams; }
        public int getTies() { return ties; }
        public int getLegs() { return legs; }
        public int getGroups() { return groups; }
        public int getGroupSize() { return groupSize; }
        public int getMatchesPerTeam() { return matchesPerTeam; }
    }

    private final String competitionKey;
    private final String displayName;
    private final Confederation confederation;
    private final int tier; // 1-based from CSV (1=top, 2=second, 3=third)
    private final String format;
    private final List<StageDef> stages;
    private final int mainStageTeams;
    private final double coeffMultiplier;

    public ContinentalCompetitionDef(String competitionKey, String displayName, Confederation confederation,
                                      int tier, String format, List<StageDef> stages, double coeffMultiplier) {
        this.competitionKey = competitionKey;
        this.displayName = displayName;
        this.confederation = confederation;
        this.tier = tier;
        this.format = format;
        this.stages = stages != null ? new ArrayList<>(stages) : new ArrayList<>();
        this.mainStageTeams = deriveMainStageTeams();
        this.coeffMultiplier = coeffMultiplier;
    }

    private int deriveMainStageTeams() {
        int mainStageCount = 0;
        int mainStageStages = 0;
        for (StageDef s : stages) {
            if (s.stageType == StageType.LEAGUE_PHASE || s.stageType == StageType.GROUP_STAGE) {
                mainStageCount += s.teams;
                mainStageStages++;
            }
        }
        if (mainStageStages == 1) {
            return mainStageCount;
        }
        if (mainStageStages > 1) {
            return mainStageCount;
        }
        // fallback: first knockout stage teams or max teams
        int max = 0;
        for (StageDef s : stages) {
            if (s.teams > max) max = s.teams;
        }
        return max > 0 ? max : 32;
    }

    public String getCompetitionKey() { return competitionKey; }
    public String getDisplayName() { return displayName; }
    public Confederation getConfederation() { return confederation; }
    public int getTier() { return tier; }
    public String getFormat() { return format; }
    public List<StageDef> getStages() { return Collections.unmodifiableList(stages); }
    public int getMainStageTeams() { return mainStageTeams; }
    public int getTargetTeams() { return mainStageTeams; }
    public double getCoeffMultiplier() { return coeffMultiplier; }

    public StageDef findMainStage() {
        for (StageDef s : stages) {
            if (s.stageType == StageType.LEAGUE_PHASE || s.stageType == StageType.GROUP_STAGE) {
                return s;
            }
        }
        return stages.isEmpty() ? null : stages.get(0);
    }

    public StageDef findStage(String stageKey) {
        for (StageDef s : stages) {
            if (s.stageKey.equals(stageKey)) return s;
        }
        return null;
    }

    public ContinentalTournament createTournament() {
        StageDef main = findMainStage();
        boolean twoLeg = true;
        boolean neutralFinal = true;
        int groupSize = 4;
        int matchesPerTeam = 6;

        if (main != null) {
            if (main.stageType == StageType.LEAGUE_PHASE) {
                matchesPerTeam = main.matchesPerTeam > 0 ? main.matchesPerTeam : 8;
                groupSize = 0; // no groups for league phase
            } else if (main.stageType == StageType.GROUP_STAGE) {
                groupSize = main.groupSize > 0 ? main.groupSize : 4;
                matchesPerTeam = main.matchesPerTeam > 0 ? main.matchesPerTeam : (groupSize - 1) * 2;
            }
        }

        // Check if main knockout stages use 1-leg (exclude qualifying rounds)
        for (StageDef s : stages) {
            if (s.stageType == StageType.KNOCKOUT && s.legs == 1) {
                twoLeg = false;
                break;
            }
        }

        // Check final for neutral venue
        for (StageDef s : stages) {
            if (s.stageType == StageType.FINAL) {
                neutralFinal = (s.legs == 1);
                break;
            }
        }

        return new ContinentalTournament(displayName, confederation, mainStageTeams,
            twoLeg, neutralFinal, groupSize, matchesPerTeam,
            main != null && main.stageType == StageType.LEAGUE_PHASE, tier);
    }

    /**
     * Load all continental competition definitions from CSV files.
     * Returns a map from confederation to list of competition defs (sorted by tier).
     */
    public static Map<Confederation, List<ContinentalCompetitionDef>> loadFromCsv(
            String competitionsCsvPath, String stagesCsvPath) {

        // Parse stages first
        Map<String, List<StageDef>> stagesByKey = new LinkedHashMap<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                new FileInputStream(stagesCsvPath), StandardCharsets.UTF_8))) {
            String header = br.readLine();
            if (header == null) return buildAllDefs();

            List<String> cols = parseCsvLine(header);
            Map<String, Integer> idx = indexColumns(cols);

            String line;
            while ((line = br.readLine()) != null) {
                List<String> vals = parseCsvLine(line);
                String compKey = getVal(vals, idx, "competition_key");
                String stageKey = getVal(vals, idx, "stage_key");
                int stageOrder = getInt(vals, idx, "stage_order", 0);
                String stageName = getVal(vals, idx, "stage_name");
                String stageTypeStr = getVal(vals, idx, "stage_type");
                int teams = getInt(vals, idx, "teams", 0);
                int ties = getInt(vals, idx, "ties", 0);
                int legs = getInt(vals, idx, "legs", 0);
                int groupsCount = getInt(vals, idx, "groups", 0);
                int groupSz = getInt(vals, idx, "group_size", 0);
                int mpt = getInt(vals, idx, "matches_per_team", 0);

                StageDef sd = new StageDef(stageKey, stageOrder, stageName,
                    StageType.fromCsv(stageTypeStr), teams, ties, legs, groupsCount, groupSz, mpt);
                stagesByKey.computeIfAbsent(compKey, k -> new ArrayList<>()).add(sd);
            }
        } catch (IOException e) {
            System.err.println("Warning: Could not load stages CSV: " + e.getMessage());
            return buildAllDefs();
        }

        // Sort stages by order
        for (List<StageDef> list : stagesByKey.values()) {
            list.sort(Comparator.comparingInt(StageDef::getStageOrder));
        }

        // Parse competitions
        Map<Confederation, List<ContinentalCompetitionDef>> result = new LinkedHashMap<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                new FileInputStream(competitionsCsvPath), StandardCharsets.UTF_8))) {
            String header = br.readLine();
            if (header == null) return buildAllDefs();

            List<String> cols = parseCsvLine(header);
            Map<String, Integer> idx = indexColumns(cols);

            String line;
            while ((line = br.readLine()) != null) {
                List<String> vals = parseCsvLine(line);
                String compKey = getVal(vals, idx, "competition_key");
                String compName = getVal(vals, idx, "competition_name");
                String confStr = getVal(vals, idx, "confederation");
                int tier = getInt(vals, idx, "tier", 1);
                String format = getVal(vals, idx, "format");

                Confederation conf = Confederation.fromCsvName(confStr);
                if (conf == Confederation.UNKNOWN) continue;

                List<StageDef> stages = stagesByKey.getOrDefault(compKey, new ArrayList<>());

                double coeffMult = tier == 1 ? 1.0 : (tier == 2 ? 0.6 : 0.3);

                ContinentalCompetitionDef def = new ContinentalCompetitionDef(
                    compKey, compName, conf, tier, format, stages, coeffMult);

                result.computeIfAbsent(conf, k -> new ArrayList<>()).add(def);
            }
        } catch (IOException e) {
            System.err.println("Warning: Could not load competitions CSV: " + e.getMessage());
            return buildAllDefs();
        }

        // Sort by tier within each confederation
        for (List<ContinentalCompetitionDef> list : result.values()) {
            list.sort(Comparator.comparingInt(ContinentalCompetitionDef::getTier));
        }

        return result;
    }

    /**
     * Fallback: Build all continental competition definitions based on hardcoded defaults.
     */
    public static Map<Confederation, List<ContinentalCompetitionDef>> buildAllDefs() {
        Map<Confederation, List<ContinentalCompetitionDef>> defs = new LinkedHashMap<>();

        defs.put(Confederation.EUROPE, Arrays.asList(
            new ContinentalCompetitionDef("uefa_ucl", "UEFA Champions League", Confederation.EUROPE,
                1, "league_phase_36_8matches", Collections.emptyList(), 1.0),
            new ContinentalCompetitionDef("uefa_uel", "UEFA Europa League", Confederation.EUROPE,
                2, "league_phase_36_8matches", Collections.emptyList(), 0.7),
            new ContinentalCompetitionDef("uefa_uecl", "UEFA Conference League", Confederation.EUROPE,
                3, "league_phase_36_6matches", Collections.emptyList(), 0.4)
        ));

        defs.put(Confederation.ASIA, Arrays.asList(
            new ContinentalCompetitionDef("afc_acl_elite", "AFC Champions League Elite", Confederation.ASIA,
                1, "west_east_league_stage", Collections.emptyList(), 1.0),
            new ContinentalCompetitionDef("afc_acl_two", "AFC Champions League Two", Confederation.ASIA,
                2, "west_east_groups", Collections.emptyList(), 0.6),
            new ContinentalCompetitionDef("afc_challenge", "AFC Challenge League", Confederation.ASIA,
                3, "west_east_groups", Collections.emptyList(), 0.3)
        ));

        defs.put(Confederation.AFRICA, Arrays.asList(
            new ContinentalCompetitionDef("caf_cl", "CAF Champions League", Confederation.AFRICA,
                1, "2round_qualify_to_16_groups", Collections.emptyList(), 1.0),
            new ContinentalCompetitionDef("caf_cc", "CAF Confederation Cup", Confederation.AFRICA,
                2, "2round_qualify_to_16_groups", Collections.emptyList(), 0.6)
        ));

        defs.put(Confederation.SOUTH_AMERICA, Arrays.asList(
            new ContinentalCompetitionDef("conmebol_libertadores", "Copa Libertadores", Confederation.SOUTH_AMERICA,
                1, "3stage_qualify_to_32_groups", Collections.emptyList(), 1.0),
            new ContinentalCompetitionDef("conmebol_sudamericana", "Copa Sudamericana", Confederation.SOUTH_AMERICA,
                2, "first_stage_to_groups_32", Collections.emptyList(), 0.6)
        ));

        defs.put(Confederation.NORTH_CENTRAL_AMERICA, Arrays.asList(
            new ContinentalCompetitionDef("concacaf_cc", "CONCACAF Champions Cup", Confederation.NORTH_CENTRAL_AMERICA,
                1, "27_round1_22_byed_5", Collections.emptyList(), 1.0)
        ));

        defs.put(Confederation.OCEANIA, Arrays.asList(
            new ContinentalCompetitionDef("ofc_cl", "OFC Champions League", Confederation.OCEANIA,
                1, "qualifying_3_to_groups_8", Collections.emptyList(), 1.0)
        ));

        return defs;
    }

    /**
     * Load all continental competition definitions from a single merged CSV file.
     * Each row contains both competition metadata and one stage definition.
     * Returns a map from confederation to list of competition defs (sorted by tier).
     */
    public static Map<Confederation, List<ContinentalCompetitionDef>> loadFromMergedCsv(String mergedCsvPath) {
        // Group rows by competition_key, preserving competition-level metadata
        Map<String, String[]> compMeta = new LinkedHashMap<>(); // key -> [compName, confStr, tier, format]
        Map<String, List<StageDef>> stagesByKey = new LinkedHashMap<>();

        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                new FileInputStream(mergedCsvPath), StandardCharsets.UTF_8))) {
            String header = br.readLine();
            if (header == null) return buildAllDefs();

            List<String> cols = parseCsvLine(header);
            Map<String, Integer> idx = indexColumns(cols);

            String line;
            while ((line = br.readLine()) != null) {
                List<String> vals = parseCsvLine(line);
                String compKey = getVal(vals, idx, "competition_key");
                if (compKey.isEmpty()) continue;

                // Store competition-level metadata (first occurrence wins, all rows are identical)
                if (!compMeta.containsKey(compKey)) {
                    compMeta.put(compKey, new String[]{
                        getVal(vals, idx, "competition_name"),
                        getVal(vals, idx, "confederation"),
                        getVal(vals, idx, "tier"),
                        getVal(vals, idx, "format")
                    });
                }

                // Build stage def
                String stageKey = getVal(vals, idx, "stage_key");
                int stageOrder = getInt(vals, idx, "stage_order", 0);
                String stageName = getVal(vals, idx, "stage_name");
                String stageTypeStr = getVal(vals, idx, "stage_type");
                int teams = getInt(vals, idx, "teams", 0);
                int ties = getInt(vals, idx, "ties", 0);
                int legs = getInt(vals, idx, "legs", 0);
                int groupsCount = getInt(vals, idx, "groups", 0);
                int groupSz = getInt(vals, idx, "group_size", 0);
                int mpt = getInt(vals, idx, "matches_per_team", 0);

                StageDef sd = new StageDef(stageKey, stageOrder, stageName,
                    StageType.fromCsv(stageTypeStr), teams, ties, legs, groupsCount, groupSz, mpt);
                stagesByKey.computeIfAbsent(compKey, k -> new ArrayList<>()).add(sd);
            }
        } catch (IOException e) {
            System.err.println("Warning: Could not load merged CSV: " + e.getMessage());
            return buildAllDefs();
        }

        // Sort stages by order
        for (List<StageDef> list : stagesByKey.values()) {
            list.sort(Comparator.comparingInt(StageDef::getStageOrder));
        }

        // Build competition defs grouped by confederation
        Map<Confederation, List<ContinentalCompetitionDef>> result = new LinkedHashMap<>();
        for (Map.Entry<String, String[]> entry : compMeta.entrySet()) {
            String compKey = entry.getKey();
            String[] meta = entry.getValue();
            String compName = meta[0];
            Confederation conf = Confederation.fromCsvName(meta[1]);
            if (conf == Confederation.UNKNOWN) continue;
            int tier;
            try { tier = Integer.parseInt(meta[2]); } catch (NumberFormatException e) { tier = 1; }
            String format = meta[3];

            List<StageDef> stages = stagesByKey.getOrDefault(compKey, new ArrayList<>());
            double coeffMult = tier == 1 ? 1.0 : (tier == 2 ? 0.6 : 0.3);

            ContinentalCompetitionDef def = new ContinentalCompetitionDef(
                compKey, compName, conf, tier, format, stages, coeffMult);
            result.computeIfAbsent(conf, k -> new ArrayList<>()).add(def);
        }

        // Sort by tier within each confederation
        for (List<ContinentalCompetitionDef> list : result.values()) {
            list.sort(Comparator.comparingInt(ContinentalCompetitionDef::getTier));
        }

        return result;
    }

    // CSV parsing helpers
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
}
