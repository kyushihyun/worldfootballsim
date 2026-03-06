package worldfootballsim;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class OptaReader {

    public static List<Club> readClubs(String csvPath) throws IOException {
        List<Club> clubs = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(csvPath), StandardCharsets.UTF_8))) {
            String header = br.readLine();
            if (header == null) return clubs;

            List<String> h = parseCSVLine(header);
            Map<String, Integer> idx = buildIndex(h);

            String nameKey = requireColumn(idx, csvPath, "clubname", "contestantname");
            String countryKey = requireColumn(idx, csvPath, "pyramidcountryfinal", "origincountry", "country", "pyramidcountry");
            String confKey = requireColumn(idx, csvPath, "pyramidconfederationfinal", "originconfederation", "confederation", "pyramidconfederation");
            String leagueKey = requireColumn(idx, csvPath, "domesticleaguesimname", "domesticleaguename", "domesticleague", "leaguename");
            String ratingKey = requireColumn(idx, csvPath, "currentrating", "seasonaveragerating", "seasonrating");
            String levelKey = optionalColumn(idx, "domesticlevel", "leaguelevel", "level");

            Random rng = new Random(1337);
            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                List<String> v = parseCSVLine(line);

                String name = getByKey(v, idx, nameKey);
                String country = normalizeCountryName(getByKey(v, idx, countryKey));
                String conf = getByKey(v, idx, confKey);
                String league = getByKey(v, idx, leagueKey);
                double rating = parseDouble(getByKey(v, idx, ratingKey), 60.0);
                int level = parseInt(getByKey(v, idx, levelKey), 0);

                if (name.isEmpty() || country.isEmpty() || league.isEmpty()) continue;

                Confederation c = Confederation.fromOptaName(conf);
                Club club = Club.fromOpta(name, country, c, league, rating, rng);
                club.setDomesticLevel(level);
                clubs.add(club);
            }
        }
        return clubs;
    }

    private static String get(List<String> values, int index) {
        if (index < 0 || index >= values.size()) return "";
        String s = values.get(index);
        return s == null ? "" : s.trim();
    }

    private static double parseDouble(String s, double def) {
        if (s == null || s.trim().isEmpty()) return def;
        try { return Double.parseDouble(s.trim()); } catch (NumberFormatException e) { return def; }
    }

    private static int parseInt(String s, int def) {
        if (s == null || s.trim().isEmpty()) return def;
        try { return (int)Math.round(Double.parseDouble(s.trim())); } catch (NumberFormatException e) { return def; }
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
        if (position == null) return "";
        return get(values, position);
    }

    private static String normalizeHeader(String header) {
        if (header == null) return "";
        return header.replace("\uFEFF", "")
                .replaceAll("[^A-Za-z0-9]", "")
                .toLowerCase(Locale.ROOT);
    }

    public static List<String> parseCsvLinePublic(String line) {
        return parseCSVLine(line);
    }

    // CSV parser supporting quoted commas and escaped quotes
    private static List<String> parseCSVLine(String line) {
        List<String> result = new ArrayList<>();
        if (line == null) {
            return result;
        }
        if (!line.isEmpty() && line.charAt(0) == '\uFEFF') {
            line = line.substring(1);
        }
        StringBuilder sb = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    sb.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (ch == ',' && !inQuotes) {
                result.add(sb.toString());
                sb.setLength(0);
            } else {
                sb.append(ch);
            }
        }
        result.add(sb.toString());
        return result;
    }
    
    /**
     * Normalize country names for consistency.
     * Handles special cases like Hong Kong, China.
     */
    private static String normalizeCountryName(String countryName) {
        if (countryName == null || countryName.isEmpty()) {
            return countryName;
        }
    
        String normalized = countryName.trim();
        
        // Special case: Hong Kong, China -> Hong Kong
        if ("Hong Kong".equalsIgnoreCase(normalized) || "Hong Kong, China".equalsIgnoreCase(normalized)) {
            return "Hong Kong";
        }
        
        return normalized;
    }
}
