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

public class CupWinnersExporter {
    public static final class CupEntry {
        private final String scope;
        private final String country;
        private final String cupName;

        public CupEntry(String scope, String country, String cupName) {
            this.scope = scope;
            this.country = country;
            this.cupName = cupName;
        }

        public String getScope() { return scope; }
        public String getCountry() { return country; }
        public String getCupName() { return cupName; }
    }

    private final Path outputPath;
    private boolean headerWritten = false;
    private List<CupEntry> headerOrder = new ArrayList<>();

    public CupWinnersExporter(Path outputPath) {
        this.outputPath = outputPath;
        try {
            Files.deleteIfExists(outputPath);
        } catch (IOException ignored) {
            // best-effort cleanup
        }
    }

    public void exportSeason(int seasonYear,
                             List<CupEntry> entries,
                             Map<String, String> winners) throws IOException {
        if (!headerWritten) {
            headerOrder = buildHeader(entries);
            writeHeader(headerOrder);
            headerWritten = true;
        }

        List<String> row = new ArrayList<>();
        row.add(String.valueOf(seasonYear));
        for (CupEntry entry : headerOrder) {
            row.add(safeValue(winners.get(buildKey(entry.getScope(), entry.getCountry(), entry.getCupName()))));
        }
        writeRow(row);
    }

    public static String buildKey(String scope, String country, String cupName) {
        return scope + "|" + country + "|" + cupName;
    }

    private List<CupEntry> buildHeader(List<CupEntry> entries) {
        List<CupEntry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparing(CupEntry::getCountry)
            .thenComparing(CupEntry::getCupName));
        return sorted;
    }

    private void writeHeader(List<CupEntry> entries) throws IOException {
        List<String> scopeRow = new ArrayList<>();
        scopeRow.add("Year");
        for (CupEntry entry : entries) {
            scopeRow.add(entry.getScope());
        }

        List<String> countryRow = new ArrayList<>();
        countryRow.add("");
        for (CupEntry entry : entries) {
            countryRow.add(entry.getCountry());
        }

        List<String> cupRow = new ArrayList<>();
        cupRow.add("");
        for (CupEntry entry : entries) {
            cupRow.add(entry.getCupName());
        }

        try (BufferedWriter writer = Files.newBufferedWriter(outputPath, 
                java.nio.charset.StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            // Write UTF-8 BOM for Excel compatibility with special characters
            writer.write('\uFEFF');
            writeRow(writer, scopeRow);
            writeRow(writer, countryRow);
            writeRow(writer, cupRow);
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
}
