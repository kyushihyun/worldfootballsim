package worldfootballsim;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Normalized relational export system for simulation results.
 * 
 * Instead of pivot format (season rows, competition columns),
 * uses multiple CSVs with stable schemas:
 * - run_manifest.csv
 * - competitions.csv
 * - competition_seasons.csv
 * - standings.csv
 * - knockout_matches.csv
 * - movement.csv
 * - qualification.csv
 * - club_season_snapshot.csv
 * 
 * Immediately useful:
 * - standings.csv: Full table for every league/cup group stage
 * - club_season_snapshot.csv: Club hidden state (strength, morale, finance)
 * 
 * TODO:
 * - knockout_matches.csv: Cup knockout bracket results
 * - movement.csv: Promotions/relegations
 * - qualification.csv: Continental qualification tracking
 */
public class NormalizedExporter {
    
    private Path exportDir;
    private long runSeed;
    private int startYear;
    private int endYear;
    
    private PrintWriter manifestWriter;
    private PrintWriter competitionsWriter;
    private PrintWriter competitionSeasonsWriter;
    private PrintWriter standingsWriter;
    private PrintWriter clubSnapshotWriter;
    private PrintWriter knockoutWriter;
    private PrintWriter movementWriter;
    
    private Set<String> exportedCompetitions = new HashSet<>();
    private int competitionIdCounter = 1;
    
    public NormalizedExporter(long runSeed, int startYear, int endYear) throws IOException {
        this.runSeed = runSeed;
        this.startYear = startYear;
        this.endYear = endYear;
        
        // Create export directory
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        this.exportDir = Paths.get("exports", String.format("run_seed%d_%d-%d_%s", 
            runSeed, startYear, endYear, timestamp));
        Files.createDirectories(exportDir);
        
        initializeWriters();
    }
    
    private void initializeWriters() throws IOException {
        // Use try-with-resources to ensure proper closure on exceptions
        // Run manifest (1 row)
        manifestWriter = new PrintWriter(
            new OutputStreamWriter(
                new FileOutputStream(exportDir.resolve("run_manifest.csv").toFile()),
                StandardCharsets.UTF_8),
            true);
        manifestWriter.println("run_id,base_seed,start_year,end_year,timestamp,config_version");
        
        // Competitions (static, one row per competition)
        competitionsWriter = new PrintWriter(
            new OutputStreamWriter(
                new FileOutputStream(exportDir.resolve("competitions.csv").toFile()),
                StandardCharsets.UTF_8),
            true);
        competitionsWriter.println("competition_id,name,type,scope,confederation,country,tier,teams_target");
        
        // Competition seasons (one row per competition per season)
        competitionSeasonsWriter = new PrintWriter(
            new OutputStreamWriter(
                new FileOutputStream(exportDir.resolve("competition_seasons.csv").toFile()),
                StandardCharsets.UTF_8),
            true);
        competitionSeasonsWriter.println("comp_season_id,competition_id,season_year,winner_club_id,runner_up_club_id,format_notes");
        
        // Standings (many rows: one per club per competition per season)
        standingsWriter = new PrintWriter(
            new OutputStreamWriter(
                new FileOutputStream(exportDir.resolve("standings.csv").toFile()),
                StandardCharsets.UTF_8),
            true);
        standingsWriter.println("comp_season_id,club_id,club_name,position,pld,w,d,l,gf,ga,gd,pts");
        
        // Club season snapshots (one row per club per season)
        clubSnapshotWriter = new PrintWriter(
            new OutputStreamWriter(
                new FileOutputStream(exportDir.resolve("club_season_snapshot.csv").toFile()),
                StandardCharsets.UTF_8),
            true);
        clubSnapshotWriter.println("season_year,club_id,club_name,country,tier," +
            "raw_strength,historical_anchor,elo_strength,squad_strength,avg_match_strength," +
            "morale_avg,morale_min,morale_max,fatigue_avg," +
            "financial_power,youth_rating,development_rating,recruitment_rating," +
            "promoted_flag,relegated_flag,european_matches");
        
        // Knockout matches (matches in cup knockouts)
        knockoutWriter = new PrintWriter(
            new OutputStreamWriter(
                new FileOutputStream(exportDir.resolve("knockout_matches.csv").toFile()),
                StandardCharsets.UTF_8),
            true);
        knockoutWriter.println("comp_season_id,stage,match_index,leg,home_club_id,away_club_id," +
            "home_goals,away_goals,agg_home,agg_away,winner_club_id");
        
        // Movement (promotions/relegations)
        movementWriter = new PrintWriter(
            new OutputStreamWriter(
                new FileOutputStream(exportDir.resolve("movement.csv").toFile()),
                StandardCharsets.UTF_8),
            true);
        movementWriter.println("season_year,country,club_id,club_name,from_tier,to_tier,movement_type");
    }
    
    /**
     * Write run manifest (call once at start)
     */
    public void writeRunManifest(String configVersion) {
        manifestWriter.printf("%d,%d,%d,%d,%s,%s\n",
            runSeed, runSeed, startYear, endYear,
            LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
            configVersion);
        manifestWriter.flush();
    }
    
    /**
     * Register a competition once (domestic league, cup, continental tournament)
     */
    public String getOrCreateCompetition(String name, String type, String scope,
                                        String confederation, String country, int tier, int teamsTarget) {
        String compId = sanitizeId(name);
        if (exportedCompetitions.contains(compId)) {
            return compId;
        }
        
        competitionsWriter.printf("%s,%s,%s,%s,%s,%s,%d,%d\n",
            compId, name, type, scope, confederation, country, tier, teamsTarget);
        competitionsWriter.flush();
        exportedCompetitions.add(compId);
        return compId;
    }
    
    /**
     * Export league or group stage standings
     */
    public void exportStandings(League league, int seasonYear) {
        String compId = getOrCreateCompetition(
            league.getName(),
            "league",
            "domestic",
            "N/A",
            league.getCountry(),
            league.getLevel(),
            league.getTargetSize());
        
        String compSeasonId = compId + "_" + seasonYear;
        
        league.sortTable();
        List<Club> standings = league.getClubs();
        
        // Competition season summary
        Club winner = standings.isEmpty() ? null : standings.get(0);
        Club runnerUp = standings.size() >= 2 ? standings.get(1) : null;
        competitionSeasonsWriter.printf("%s,%s,%d,%s,%s,\n",
            compSeasonId,
            compId,
            seasonYear,
            winner != null ? sanitizeId(winner.getName()) : "unknown",
            runnerUp != null ? sanitizeId(runnerUp.getName()) : "");
        competitionSeasonsWriter.flush();
        
        // Full standings
        for (int i = 0; i < standings.size(); i++) {
            Club club = standings.get(i);
            standingsWriter.printf("%s,%s,%s,%d,%d,%d,%d,%d,%d,%d,%d,%d\n",
                compSeasonId,
                sanitizeId(club.getName()),
                club.getName(),
                i + 1,  // position
                club.getGamesPlayed(),
                club.getWins(),
                club.getDraws(),
                club.getLosses(),
                club.getGoalsFor(),
                club.getGoalsAgainst(),
                club.getGoalDifference(),
                club.getPoints());
        }
        standingsWriter.flush();
    }
    
    /**
     * Export club season snapshot (most diagnostic file)
     * Captures hidden state that explains "why" outcomes happened
     */
    public void exportClubSeasonSnapshot(Club club, int seasonYear, boolean promoted, boolean relegated, int europeanMatches) {
        // Calculate averages for the season
        // Note: Club should track these during season; we're reading them from current state
        
        // Get squad strength baseline
        double squadStrength = club.getSquadStrength();
        
        clubSnapshotWriter.printf(
            "%d,%s,%s,%s,%d," +  // season, club_id, name, country, tier
            "%.2f,%.2f,%.2f,%.2f,%.2f," +  // raw, anchor, elo, squad, avg_match
            "%.1f,%.1f,%.1f,%.2f," +  // morale_avg, min, max, fatigue_avg
            "%.1f,%.1f,%.1f,%.1f," +  // financial, youth, dev, recruitment
            "%s,%s,%d\n",  // promoted, relegated, european_matches
            seasonYear,
            sanitizeId(club.getName()),
            club.getName(),
            club.getCountry(),
            club.getDomesticLevel(),
            club.getRawStrength(),
            club.getBaseRating(),  // Historical anchor (use base rating as proxy)
            club.getEloRating(),
            squadStrength,
            club.getMatchStrength(squadStrength),
            club.getMorale(),  // Should be average over season (TODO: track history)
            club.getMorale(),  // Should be min (TODO: track history)
            club.getMorale(),  // Should be max (TODO: track history)
            0.0,  // fatigue average (TODO: track history)
            club.getFinancialPower(),
            club.getYouthRating(),
            club.getDevelopmentRating(),
            club.getRecruitmentQuality(),
            promoted ? "true" : "false",
            relegated ? "true" : "false",
            europeanMatches);
        clubSnapshotWriter.flush();
    }
    
    /**
     * Export movement (promotions/relegations) for the season
     */
    public void exportMovement(int seasonYear, String country, Club club, int fromTier, int toTier, String movementType) {
        if (fromTier == toTier) return;  // No movement
        
        movementWriter.printf("%d,%s,%s,%s,%d,%d,%s\n",
            seasonYear,
            country,
            sanitizeId(club.getName()),
            club.getName(),
            fromTier,
            toTier,
            movementType);  // PROMOTED, RELEGATED, PLAYOFF_UP, PLAYOFF_DOWN
        movementWriter.flush();
    }
    
    /**
     * Close all writers
     */
    public void close() throws IOException {
        manifestWriter.close();
        competitionsWriter.close();
        competitionSeasonsWriter.close();
        standingsWriter.close();
        clubSnapshotWriter.close();
        knockoutWriter.close();
        movementWriter.close();
        System.out.println("\nExport complete: " + exportDir.toAbsolutePath());
    }
    
    /**
     * Utility: Create safe IDs from names
     * Remove spaces, punctuation, lowercase
     */
    private static String sanitizeId(String name) {
        return name.toLowerCase()
            .replaceAll("[^a-z0-9]+", "_")
            .replaceAll("^_|_$", "");
    }
}
