package worldfootballsim;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class LeagueConfigReader {
    public static Map<String, Map<Integer, LeagueConfig>> readLeagueConfig(String csvPath) throws IOException {
        Map<String, Map<Integer, LeagueConfig>> config = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(csvPath), StandardCharsets.UTF_8))) {
            String header = reader.readLine();
            if (header == null) return config;

            List<String> headers = OptaReader.parseCsvLinePublic(header);
            Map<String, Integer> idx = buildIndex(headers);

            String countryKey = requireColumn(idx, csvPath, "country", "pyramidcountryfinal", "pyramidcountry");
            String levelKey = requireColumn(idx, csvPath, "level", "domesticlevel", "leaguelevel");
            String leagueKey = requireColumn(idx, csvPath, "league", "domesticleaguesimname", "domesticleaguename");
            String divisionsKey = optionalColumn(idx, "divisionscountfixed", "divisionscount", "divisioncount");
            String sizesKey = optionalColumn(idx, "divisionsizesfixed", "divisionsizes", "divisionsize");
            String namesKey = optionalColumn(idx, "divisionnamesfixed", "divisionnames", "divisionname");
            String mergedKey = optionalColumn(idx, "leagueismergedregionalcompetition", "mergedregional", "ismergedregional");
            String totalKey = optionalColumn(idx, "clubsfixedtotal", "clubstotal", "clubsfixed");
            String promPlayoffKey = optionalColumn(idx, "hasPromotionPlayoff", "promotionplayoff", "promotionplayoffzone");
            String relegPlayoffKey = optionalColumn(idx, "hasRelegationPlayoff", "relegationplayoff", "relegationplayoffzone");

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                List<String> values = OptaReader.parseCsvLinePublic(line);

                String country = getByKey(values, idx, countryKey);
                int level = parseInt(getByKey(values, idx, levelKey), 0);
                String league = getByKey(values, idx, leagueKey);

                int divisionsCount = parseInt(getByKey(values, idx, divisionsKey), 0);
                List<Integer> divisionSizes = parseDivisionSizes(getByKey(values, idx, sizesKey));
                List<String> divisionNames = parseDivisionNames(getByKey(values, idx, namesKey));
                boolean mergedRegional = parseBoolean(getByKey(values, idx, mergedKey));
                int clubsFixedTotal = parseInt(getByKey(values, idx, totalKey), 0);
                boolean hasPromotionPlayoff = promPlayoffKey != null ? parseBoolean(getByKey(values, idx, promPlayoffKey)) : false;
                boolean hasRelegationPlayoff = relegPlayoffKey != null ? parseBoolean(getByKey(values, idx, relegPlayoffKey)) : false;

                if (country.isEmpty() || league.isEmpty() || level <= 0) continue;

                LeagueConfig entry = new LeagueConfig(league, divisionsCount, divisionSizes, divisionNames,
                    mergedRegional, clubsFixedTotal, hasPromotionPlayoff, hasRelegationPlayoff);
                config.computeIfAbsent(country, k -> new HashMap<>()).put(level, entry);
            }
        }
        return config;
    }

    private static Map<String, Integer> buildIndex(List<String> headers) {
        Map<String, Integer> idx = new HashMap<>();
        for (int i = 0; i < headers.size(); i++) {
            String normalized = normalizeHeader(headers.get(i));
            if (!normalized.isEmpty()) {
                idx.put(normalized, i);
            }
        }
        return idx;
    }

    private static String requireColumn(Map<String, Integer> idx, String csvPath, String... aliases) throws IOException {
        for (String alias : aliases) {
            if (idx.containsKey(alias)) {
                return alias;
            }
        }
        throw new IOException("Missing required column (" + String.join(", ", aliases) + ") in " + csvPath);
    }

    private static String optionalColumn(Map<String, Integer> idx, String... aliases) {
        for (String alias : aliases) {
            if (idx.containsKey(alias)) {
                return alias;
            }
        }
        return null;
    }

    private static String getByKey(List<String> values, Map<String, Integer> idx, String key) {
        Integer position = idx.get(key);
        if (position == null || position < 0 || position >= values.size()) return "";
        String value = values.get(position);
        return value == null ? "" : value.trim();
    }

    private static int parseInt(String s, int def) {
        if (s == null || s.trim().isEmpty()) return def;
        try { return (int)Math.round(Double.parseDouble(s.trim())); } catch (NumberFormatException e) { return def; }
    }

    private static boolean parseBoolean(String s) {
        if (s == null) return false;
        String t = s.trim().toLowerCase(Locale.ROOT);
        return t.equals("true") || t.equals("1") || t.equals("yes");
    }

    private static List<Integer> parseDivisionSizes(String raw) {
        if (raw == null || raw.trim().isEmpty()) return new ArrayList<>();
        String cleaned = raw.replace("\"", "");
        List<Integer> result = new ArrayList<>();
        for (String token : cleaned.split(",")) {
            String t = token.trim();
            if (t.isEmpty()) continue;
            try {
                result.add(Integer.parseInt(t));
            } catch (NumberFormatException ignored) {
                // skip invalid values
            }
        }
        return result;
    }

    private static List<String> parseDivisionNames(String raw) {
        if (raw == null || raw.trim().isEmpty()) return new ArrayList<>();
        String cleaned = raw.replace("\"", "");
        String[] tokens = cleaned.split("\\|");
        List<String> names = new ArrayList<>();
        for (String token : tokens) {
            String t = token.trim();
            if (!t.isEmpty()) names.add(t);
        }
        return names;
    }

    private static String normalizeHeader(String header) {
        if (header == null) return "";
        return header.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
    }

    public static final class LeagueConfig {
        private final String leagueName;
        private final int divisionsCount;
        private final List<Integer> divisionSizes;
        private final List<String> divisionNames;
        private final boolean mergedRegional;
        private final int clubsFixedTotal;
        private final boolean hasPromotionPlayoff;
        private final boolean hasRelegationPlayoff;

        private LeagueConfig(String leagueName, int divisionsCount, List<Integer> divisionSizes,
                             List<String> divisionNames, boolean mergedRegional, int clubsFixedTotal,
                             boolean hasPromotionPlayoff, boolean hasRelegationPlayoff) {
            this.leagueName = leagueName;
            this.divisionsCount = divisionsCount;
            this.divisionSizes = divisionSizes == null ? new ArrayList<>() : new ArrayList<>(divisionSizes);
            this.divisionNames = divisionNames == null ? new ArrayList<>() : new ArrayList<>(divisionNames);
            this.mergedRegional = mergedRegional;
            this.clubsFixedTotal = clubsFixedTotal;
            this.hasPromotionPlayoff = hasPromotionPlayoff;
            this.hasRelegationPlayoff = hasRelegationPlayoff;
        }

        public String getLeagueName() { return leagueName; }
        public int getDivisionsCount() { return divisionsCount; }
        public List<Integer> getDivisionSizes() { return new ArrayList<>(divisionSizes); }
        public List<String> getDivisionNames() { return new ArrayList<>(divisionNames); }
        public boolean isMergedRegional() { return mergedRegional; }
        public int getClubsFixedTotal() { return clubsFixedTotal; }
        public boolean hasPromotionPlayoff() { return hasPromotionPlayoff; }
        public boolean hasRelegationPlayoff() { return hasRelegationPlayoff; }
    }
}
