package worldfootballsim;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public class SeasonWinnersExporter {
    public static final class LeagueEntry {
        private final int level;
        private final String scope;
        private final String country;
        private final String leagueName;

        public LeagueEntry(int level, String scope, String country, String leagueName) {
            this.level = level;
            this.scope = scope;
            this.country = country;
            this.leagueName = leagueName;
        }

        public int getLevel() { return level; }
        public String getScope() { return scope; }
        public String getCountry() { return country; }
        public String getLeagueName() { return leagueName; }
    }

    private final Path outputPath;
    private boolean headerWritten = false;
    private List<LeagueEntry> headerOrder = new ArrayList<>();

    public SeasonWinnersExporter(Path outputPath) {
        this.outputPath = outputPath;
        try {
            Files.deleteIfExists(outputPath);
        } catch (IOException ignored) {
            // best-effort cleanup
        }
    }

    public void exportSeason(int seasonYear,
                             Map<String, String> intercontinental,
                             Map<String, String> continental,
                             List<LeagueEntry> domesticEntries,
                             Map<String, String> domesticWinners) throws IOException {
        if (!headerWritten) {
            headerOrder = buildHeader(intercontinental, continental, domesticEntries);
            writeHeader(headerOrder);
            headerWritten = true;
        }

        Map<String, String> winnersByKey = new java.util.HashMap<>();
        for (Map.Entry<String, String> e : intercontinental.entrySet()) {
            winnersByKey.put(buildKey("Intercontinental", "", e.getKey(), 0), e.getValue());
        }
        for (Map.Entry<String, String> e : continental.entrySet()) {
            winnersByKey.put(buildKey("Continental", "", e.getKey(), 0), e.getValue());
        }
        for (Map.Entry<String, String> e : domesticWinners.entrySet()) {
            winnersByKey.put(e.getKey(), e.getValue());
        }

        List<String> row = new ArrayList<>();
        row.add(String.valueOf(seasonYear));
        for (LeagueEntry entry : headerOrder) {
            row.add(safeValue(winnersByKey.get(buildKey(entry.getScope(), entry.getCountry(), entry.getLeagueName(), entry.getLevel()))));
        }
        writeRow(row);
    }

    private List<LeagueEntry> buildHeader(Map<String, String> intercontinental,
                                          Map<String, String> continental,
                                          List<LeagueEntry> domestic) {
        List<LeagueEntry> entries = new ArrayList<>();
        for (Map.Entry<String, String> e : intercontinental.entrySet()) {
            entries.add(new LeagueEntry(0, "Intercontinental", "", e.getKey()));
        }
        for (Map.Entry<String, String> e : continental.entrySet()) {
            entries.add(new LeagueEntry(0, "Continental", "", e.getKey()));
        }

        List<LeagueEntry> domesticSorted = new ArrayList<>(domestic);
        domesticSorted.sort(Comparator.comparing(LeagueEntry::getCountry)
            .thenComparingInt(LeagueEntry::getLevel)
            .thenComparing(LeagueEntry::getLeagueName));
        entries.addAll(domesticSorted);
        return entries;
    }

    private void writeHeader(List<LeagueEntry> entries) throws IOException {
        List<String> levelRow = new ArrayList<>();
        levelRow.add("Year");
        for (LeagueEntry entry : entries) {
            levelRow.add(entry.getScope());
        }

        List<String> countryRow = new ArrayList<>();
        countryRow.add("");
        for (LeagueEntry entry : entries) {
            countryRow.add(entry.getCountry());
        }

        List<String> leagueRow = new ArrayList<>();
        leagueRow.add("");
        for (LeagueEntry entry : entries) {
            leagueRow.add(entry.getLeagueName());
        }

        try (BufferedWriter writer = Files.newBufferedWriter(outputPath, 
                java.nio.charset.StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            // Write UTF-8 BOM for Excel compatibility with special characters
            writer.write('\uFEFF');
            writeRow(writer, levelRow);
            writeRow(writer, countryRow);
            writeRow(writer, leagueRow);
        }
    }

    private void writeRow(List<String> row) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            writeRow(writer, row);
        }
    }

    private void writeRow(BufferedWriter writer, List<String> row) throws IOException {
        writer.write(String.join(",", escape(row)));
        writer.newLine();
    }

    private List<String> escape(List<String> row) {
        List<String> result = new ArrayList<>(row.size());
        for (String value : row) {
            if (value == null) {
                result.add("");
            } else if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
                result.add("\"" + value.replace("\"", "\"\"") + "\"");
            } else {
                result.add(value);
            }
        }
        return result;
    }

    private String safeValue(String value) {
        return value == null ? "" : value;
    }

    private String buildKey(String scope, String country, String leagueName, int level) {
        return scope + "|" + country + "|" + leagueName + "|" + level;
    }
}
