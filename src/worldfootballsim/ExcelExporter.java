package worldfootballsim;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Exports simulation results to Excel format (CSV-based for compatibility)
 * Each run is saved as a separate sheet file, with aggregation support
 */
public class ExcelExporter {

    private static final String RESULTS_DIR = "simulation_results";
    private static final String GLOBAL_SUMMARY = "GLOBAL_SUMMARY.csv";
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    static {
        try {
            Files.createDirectories(Paths.get(RESULTS_DIR));
        } catch (IOException e) {
            System.err.println("Failed to create results directory: " + e.getMessage());
        }
    }

    public static String exportSeasonResults(int seasonYear, WorldFootballSimulator sim) {
        try {
            String timestamp = LocalDateTime.now().format(DATE_FORMAT);
            String filename = RESULTS_DIR + File.separator + "Season_" + seasonYear + "_" + timestamp + ".csv";
            
            StringBuilder sb = new StringBuilder();
            
            // Header with metadata
            sb.append("SEASON ").append(seasonYear).append(" RESULTS\n");
            sb.append("Generated: ").append(LocalDateTime.now()).append("\n\n");
            
            // GLOBAL CHAMPION
            sb.append("=== GLOBAL CHAMPION ===\n");
            sb.append("Club,Country,Competition\n");
            Club globalChamp = sim.globalClubCup != null ? sim.globalClubCup.getChampion() : null;
            if (globalChamp != null) {
                sb.append(escape(globalChamp.getName())).append(",")
                  .append(escape(globalChamp.getCountry())).append(",")
                  .append("Global Club Cup\n");
            }
            sb.append("\n");
            
            // CONTINENTAL CHAMPIONS
            sb.append("=== CONTINENTAL CHAMPIONS ===\n");
            sb.append("Competition,Club,Country,Confederation\n");
            for (Map.Entry<String, ContinentalTournament> e : sim.continentalTournaments.entrySet()) {
                Club champ = e.getValue().getChampion();
                if (champ != null) {
                    sb.append(escape(e.getValue().getName())).append(",")
                      .append(escape(champ.getName())).append(",")
                      .append(escape(champ.getCountry())).append(",")
                      .append(e.getValue().getConfederation().name()).append("\n");
                }
            }
            sb.append("\n");
            
            // DOMESTIC LEAGUE CHAMPIONS
            sb.append("=== DOMESTIC LEAGUE CHAMPIONS ===\n");
            sb.append("Rank,Club,Country,League,ELO Rating,Points,Games Played\n");
            
            List<League> topLeagues = new ArrayList<>();
            for (CountryAssociation ca : sim.countries.values()) {
                if (ca.getTopLeague() != null) topLeagues.add(ca.getTopLeague());
            }
            topLeagues.sort((a, b) -> {
                Club ca = a.getChampion();
                Club cb = b.getChampion();
                double eloA = (ca != null) ? ca.getEloRating() : 0;
                double eloB = (cb != null) ? cb.getEloRating() : 0;
                return Double.compare(eloB, eloA);
            });
            
            for (int i = 0; i < Math.min(15, topLeagues.size()); i++) {
                League l = topLeagues.get(i);
                Club c = l.getChampion();
                if (c != null) {
                    sb.append(i + 1).append(",")
                      .append(escape(c.getName())).append(",")
                      .append(escape(l.getCountry())).append(",")
                      .append(escape(l.getName())).append(",")
                      .append(String.format("%.0f", c.getEloRating())).append(",")
                      .append(c.getPoints()).append(",")
                      .append(c.getGamesPlayed()).append("\n");
                }
            }
            sb.append("\n");
            
            // CONFEDERATION SUMMARY
            sb.append("=== CONFEDERATION SUMMARY ===\n");
            sb.append("Confederation,Countries,Top Leagues\n");
            for (Confederation conf : Confederation.values()) {
                if (conf == Confederation.UNKNOWN) continue;
                List<CountryAssociation> cas = sim.countriesByConfed.getOrDefault(conf, new ArrayList<>());
                if (!cas.isEmpty()) {
                    int leagueCount = 0;
                    for (CountryAssociation ca : cas) {
                        if (ca.getTopLeague() != null) leagueCount++;
                    }
                    sb.append(conf.name().replace("_", " ")).append(",")
                      .append(cas.size()).append(",")
                      .append(leagueCount).append("\n");
                }
            }
            sb.append("\n");
            
            Files.write(Paths.get(filename), sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return filename;
            
        } catch (IOException e) {
            System.err.println("Error exporting to Excel: " + e.getMessage());
            return null;
        }
    }

    public static void aggregateResults() {
        try {
            File dir = new File(RESULTS_DIR);
            File[] files = dir.listFiles((d, name) -> name.startsWith("Season_") && name.endsWith(".csv"));
            
            if (files == null || files.length == 0) {
                return;
            }
            
            Map<String, Integer> globalWins = new LinkedHashMap<>();
            Map<String, Integer> continentalWins = new LinkedHashMap<>();
            Map<String, Integer> domesticWins = new LinkedHashMap<>();
            
            for (File f : files) {
                try (Scanner scanner = new Scanner(f, "UTF-8")) {
                    String section = "";
                    while (scanner.hasNextLine()) {
                        String line = scanner.nextLine();
                        
                        if (line.startsWith("=== GLOBAL CHAMPION ===")) {
                            section = "global";
                            scanner.nextLine(); // skip header
                        } else if (line.startsWith("=== CONTINENTAL CHAMPIONS ===")) {
                            section = "continental";
                            scanner.nextLine(); // skip header
                        } else if (line.startsWith("=== DOMESTIC LEAGUE CHAMPIONS ===")) {
                            section = "domestic";
                            scanner.nextLine(); // skip header
                        } else if (line.startsWith("===")) {
                            section = "";
                        } else if (!line.isEmpty() && !section.isEmpty() && !line.contains("Generated") && !line.contains("SEASON")) {
                            String[] parts = line.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)");
                            if (parts.length > 1) {
                                String club = parts[0].replaceAll("\"", "");
                                if ("global".equals(section) && parts.length >= 3) {
                                    globalWins.put(club, globalWins.getOrDefault(club, 0) + 1);
                                } else if ("continental".equals(section) && parts.length >= 2) {
                                    club = parts.length > 2 ? parts[1].replaceAll("\"", "") : club;
                                    continentalWins.put(club, continentalWins.getOrDefault(club, 0) + 1);
                                } else if ("domestic".equals(section) && parts.length >= 2 && !parts[0].matches("\\d+")) {
                                    club = parts[1].replaceAll("\"", "");
                                    domesticWins.put(club, domesticWins.getOrDefault(club, 0) + 1);
                                }
                            }
                        }
                    }
                } catch (IOException e) {
                    System.err.println("Error reading file: " + f.getName());
                }
            }
            
            // Write aggregation
            StringBuilder sb = new StringBuilder();
            sb.append("AGGREGATED RESULTS ACROSS ALL SEASONS\n");
            sb.append("Generated: ").append(LocalDateTime.now()).append("\n");
            sb.append("Files analyzed: ").append(files.length).append("\n\n");
            
            if (!globalWins.isEmpty()) {
                sb.append("=== GLOBAL CHAMPION WINS ===\n");
                sb.append("Club,Wins\n");
                globalWins.entrySet().stream()
                    .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                    .forEach(e -> sb.append(escape(e.getKey())).append(",").append(e.getValue()).append("\n"));
                sb.append("\n");
            }
            
            if (!continentalWins.isEmpty()) {
                sb.append("=== CONTINENTAL CHAMPION WINS ===\n");
                sb.append("Club,Wins\n");
                continentalWins.entrySet().stream()
                    .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                    .forEach(e -> sb.append(escape(e.getKey())).append(",").append(e.getValue()).append("\n"));
                sb.append("\n");
            }
            
            if (!domesticWins.isEmpty()) {
                sb.append("=== DOMESTIC LEAGUE WINS ===\n");
                sb.append("Club,Wins\n");
                domesticWins.entrySet().stream()
                    .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                    .forEach(e -> sb.append(escape(e.getKey())).append(",").append(e.getValue()).append("\n"));
            }
            
            String aggregateFile = RESULTS_DIR + File.separator + GLOBAL_SUMMARY;
            Files.write(Paths.get(aggregateFile), sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            
        } catch (Exception e) {
            System.err.println("Error aggregating results: " + e.getMessage());
        }
    }

    private static String escape(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    public static String getResultsDirectory() {
        return RESULTS_DIR;
    }
}
