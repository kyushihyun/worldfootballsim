package worldfootballsim;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Export season winners in normalized format for analytical purposes.
 * Format: season, competition_type, competition_name, country, winner_club_name, winner_country
 * This is the canonical format for analysis; the pivot format is generated as a post-process.
 */
public class NormalizedWinnersExporter {
    
    public enum CompetitionType {
        DOMESTIC_LEAGUE("Domestic League"),
        DOMESTIC_CUP("Domestic Cup"),
        CONTINENTAL("Continental"),
        INTERCONTINENTAL("Intercontinental");
        
        public final String label;
        CompetitionType(String label) {
            this.label = label;
        }
    }
    
    private final Path outputPath;
    
    public NormalizedWinnersExporter(Path outputPath) {
        this.outputPath = outputPath;
        try {
            Files.createDirectories(outputPath.getParent());
        } catch (IOException ignored) {
            // best-effort
        }
    }
    
    /**
     * Export a single winner record in normalized format.
     * @param season Year of competition
     * @param type Competition type (league, cup, continental, etc)
     * @param competitionName Name of specific competition (e.g., "Premier League", "Champions League")
     * @param competitionCountry Country for competition (empty for intercontinental)
     * @param winnerClubName Name of winning club
     * @param winnerCountry Country of winning club
     */
    public void recordWinner(int season, CompetitionType type, String competitionName, 
                            String competitionCountry, String winnerClubName, String winnerCountry) throws IOException {
        boolean isNewFile = !Files.exists(outputPath);
        
        try (BufferedWriter writer = Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            
            if (isNewFile) {
                writer.write("season,competition_type,competition_name,competition_country,winner_club_name,winner_country\n");
            }
            
            List<String> row = new ArrayList<>();
            row.add(String.valueOf(season));
            row.add(type.label);
            row.add(escapeCSV(competitionName));
            row.add(escapeCSV(competitionCountry));
            row.add(escapeCSV(winnerClubName));
            row.add(escapeCSV(winnerCountry));
            
            writer.write(String.join(",", quoteFields(row)));
            writer.newLine();
        }
    }
    
    /**
     * Export multiple winners in batch (e.g., all league winners for a season).
     */
    public void recordLeagueWinners(int season, Map<String, String> domesticWinners) throws IOException {
        boolean isNewFile = !Files.exists(outputPath);
        
        try (BufferedWriter writer = Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            
            if (isNewFile) {
                writer.write("season,competition_type,competition_name,competition_country,winner_club_name,winner_country\n");
            }
            
            for (Map.Entry<String, String> entry : domesticWinners.entrySet()) {
                String[] parts = entry.getKey().split("\\|");
                if (parts.length < 2) continue;
                
                String country = parts[0];
                String leagueName = parts[1];
                String winnerName = entry.getValue();
                
                List<String> row = new ArrayList<>();
                row.add(String.valueOf(season));
                row.add(CompetitionType.DOMESTIC_LEAGUE.label);
                row.add(escapeCSV(leagueName));
                row.add(escapeCSV(country));
                row.add(escapeCSV(winnerName));
                row.add(escapeCSV(country)); // Assume winner is from same country
                
                writer.write(String.join(",", quoteFields(row)));
                writer.newLine();
            }
        }
    }
    
    private List<String> quoteFields(List<String> row) {
        List<String> result = new ArrayList<>(row.size());
        for (String value : row) {
            if (value == null || value.isEmpty()) {
                result.add("\"\"");
            } else {
                result.add("\"" + value + "\"");
            }
        }
        return result;
    }
    
    private String escapeCSV(String field) {
        if (field == null) return "";
        return field.replace("\"", "\"\"");
    }
}
