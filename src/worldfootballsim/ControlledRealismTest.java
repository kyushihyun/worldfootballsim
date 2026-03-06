package worldfootballsim;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Controlled test harness for verifying realism tuning impact.
 * 
 * Runs multiple simulations with fixed seeds to measure:
 * - Goals per match
 * - Draw rate
 * - Points spread (1st-20th)
 * - Champion points
 * - Unique champions (10-year window)
 * - Elite club stability
 * - Morale/upset behavior diagnostics
 * 
 * Usage: java worldsim.ControlledRealismTest <num_runs> <base_seed>
 * Example: java worldsim.ControlledRealismTest 30 12345
 */
public class ControlledRealismTest {
    
    private static final int SIMULATION_START_SEASON = 2026;
    private static final int SIMULATION_DURATION = 10;
    private static final String CSV_FILE = "clubs_utf8.csv";
    private static final String LEAGUES_FILE = "leagues_utf8.csv";
    
    private int numRuns;
    private long baseSeed;
    private List<SimulationSnapshot> results = new ArrayList<>();
    
    public static void main(String[] args) throws Exception {
        int runs = 30;
        long seed = 12345L;
        
        if (args.length >= 1) runs = Integer.parseInt(args[0]);
        if (args.length >= 2) seed = Long.parseLong(args[1]);
        
        ControlledRealismTest test = new ControlledRealismTest(runs, seed);
        test.runTestSuite();
        test.printResults();
        test.exportToCSV();
    }
    
    public ControlledRealismTest(int numRuns, long baseSeed) {
        this.numRuns = numRuns;
        this.baseSeed = baseSeed;
    }
    
    private void runTestSuite() {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("CONTROLLED REALISM TEST SUITE");
        System.out.println("=".repeat(80));
        System.out.printf("Runs: %d | Base Seed: %d | Horizon: %d-%d (%d seasons)\n",
                numRuns, baseSeed, SIMULATION_START_SEASON,
                SIMULATION_START_SEASON + SIMULATION_DURATION - 1, SIMULATION_DURATION);
        System.out.println("=".repeat(80) + "\n");
        
        for (int i = 1; i <= numRuns; i++) {
            long seed = baseSeed + i;
            System.out.printf("  [%2d/%2d] Running simulation with seed %d...", i, numRuns, seed);
            System.out.flush();
            
            try {
                SimulationSnapshot snap = runSingleSimulation(seed);
                results.add(snap);
                System.out.printf(" %d unique champions, %.2f goals/match\n",
                        snap.uniqueChampions, snap.avgGoalsPerMatch);
            } catch (Exception e) {
                System.out.printf(" ERROR: %s\n", e.getMessage());
            }
        }
        
        System.out.println("\n" + "=".repeat(80));
    }
    
    private SimulationSnapshot runSingleSimulation(long seed) throws Exception {
        WorldFootballSimulator sim = new WorldFootballSimulator(SIMULATION_START_SEASON, false);
        sim.setSuppressSeasonSummary(true);
        sim.loadConfiguration("global_config.csv", "preset_coefficient.csv");
        sim.loadFromOpta(CSV_FILE, LEAGUES_FILE);
        sim.setRandomSeed(seed);
        
        SimulationSnapshot snap = new SimulationSnapshot(seed);
        
        for (int season = 0; season < SIMULATION_DURATION; season++) {
            sim.startSeason();
            sim.simulateSeason();
            collectSeasonMetrics(sim, snap);
            if (season < SIMULATION_DURATION - 1) {
                sim.applyPromotionAndRelegationForNextSeason();
                sim.clearSeasonState();
                sim.setSeasonYear(sim.getSeasonYear() + 1);
            }
        }
        
        snap.computeAggregates();
        return snap;
    }
    
    private void collectSeasonMetrics(WorldFootballSimulator sim, SimulationSnapshot snap) {
        // Collect league standings from all top-tier leagues
        List<League> leagues = sim.allLeagues;
        SeasonMetrics metrics = new SeasonMetrics(sim.getSeasonYear());
        
        double totalGoals = 0;
        int totalMatches = 0;
        int totalDraws = 0;
        League referenceLeague = null;
        double referenceLeagueAvgElo = Double.NEGATIVE_INFINITY;

        for (League league : leagues) {
            if (league.getLevel() != 1) continue; // Only top tier

            List<Club> standings = league.getClubs();
            if (standings.isEmpty()) continue;

            league.sortTable();
            
            // Collect stats from all clubs in this league
            for (Club club : standings) {
                totalGoals += club.getGoalsFor() + club.getGoalsAgainst();
                totalMatches += club.getGamesPlayed();
                totalDraws += club.getDraws();
            }
            
            double leagueAvgElo = standings.stream().mapToDouble(Club::getEloRating).average().orElse(0);
            if (leagueAvgElo > referenceLeagueAvgElo) {
                referenceLeagueAvgElo = leagueAvgElo;
                referenceLeague = league;
            }
        }

        if (referenceLeague != null) {
            List<Club> referenceStandings = referenceLeague.getClubs();
            if (!referenceStandings.isEmpty()) {
                metrics.championPoints = referenceStandings.get(0).getPoints();
                metrics.firstPlaceClub = referenceStandings.get(0).getName();

                if (referenceStandings.size() >= 4) {
                    metrics.fourthPlacePoints = referenceStandings.get(3).getPoints();
                }
                if (referenceStandings.size() >= 2) {
                    metrics.pointsSpread = referenceStandings.get(0).getPoints()
                            - referenceStandings.get(referenceStandings.size() - 1).getPoints();
                }
                if (referenceStandings.size() >= 18) {
                    metrics.seventeenthPlacePoints = referenceStandings.get(16).getPoints();
                    metrics.eighteenthPlacePoints = referenceStandings.get(17).getPoints();
                }
            }
        }
        
        if (totalMatches > 0) {
            metrics.goalsPerMatch = totalGoals / totalMatches;
            metrics.drawRate = (double) totalDraws / totalMatches;
        }
        
        snap.addSeasonMetrics(metrics);
    }
    
    private void printResults() {
        System.out.println("\nAGGREGATE RESULTS ACROSS ALL RUNS");
        System.out.println("=".repeat(80));

        if (results.isEmpty()) {
            System.out.println("No successful simulation runs to report.");
            System.out.println("=".repeat(80));
            return;
        }
        
        // Compute aggregate statistics
        double avgGoalsPerMatch = results.stream().mapToDouble(r -> r.avgGoalsPerMatch).average().orElse(0);
        double stdGoalsPerMatch = computeStdDev(results.stream().mapToDouble(r -> r.avgGoalsPerMatch).toArray());
        
        double avgDrawRate = results.stream().mapToDouble(r -> r.avgDrawRate).average().orElse(0);
        double stdDrawRate = computeStdDev(results.stream().mapToDouble(r -> r.avgDrawRate).toArray());
        
        double avgPointsSpread = results.stream().mapToDouble(r -> r.avgPointsSpread).average().orElse(0);
        double stdPointsSpread = computeStdDev(results.stream().mapToDouble(r -> r.avgPointsSpread).toArray());
        
        double avgChampPoints = results.stream().mapToDouble(r -> r.avgChampionPoints).average().orElse(0);
        double stdChampPoints = computeStdDev(results.stream().mapToDouble(r -> r.avgChampionPoints).toArray());
        
        double avgUniqueChamps = results.stream().mapToDouble(r -> r.uniqueChampions).average().orElse(0);
        
        List<Integer> championCounts = results.stream()
                .map(r -> r.uniqueChampions)
                .collect(Collectors.toList());
        Collections.sort(championCounts);
        
        System.out.printf("MATCH ENVIRONMENT\n");
        System.out.printf("  Goals per match:       %.3f +/- %.3f (target: 2.5-2.8, real: 2.806)\n",
                avgGoalsPerMatch, stdGoalsPerMatch);
        System.out.printf("  Draw rate:             %.2f%% +/- %.2f%% (target: 24-26%%, real: 23.42%%)\n",
                avgDrawRate * 100, stdDrawRate * 100);
        System.out.printf("  Points spread (1-20):  %.1f +/- %.1f (target: 55-65, real: 69.45)\n",
                avgPointsSpread, stdPointsSpread);
        System.out.printf("  Champion points:       %.1f +/- %.1f (target: 85-90, real: 91.0)\n",
                avgChampPoints, stdChampPoints);
        
        System.out.printf("\nCOMPETITIVE BALANCE\n");
        System.out.printf("  Unique champions (10yr): %.1f (target: 5-6, old: 8)\n", avgUniqueChamps);
        System.out.printf("  Distribution: min=%d, q1=%d, median=%d, q3=%d, max=%d\n",
                championCounts.get(0),
                championCounts.get(championCounts.size() / 4),
                championCounts.get(championCounts.size() / 2),
                championCounts.get(3 * championCounts.size() / 4),
                championCounts.get(championCounts.size() - 1));
        
        System.out.printf("\nELITE CLUB STABILITY\n");
        printEliteStability();
        
        System.out.println("\n" + "=".repeat(80));
    }
    
    private void printEliteStability() {
        double avgTopChampionShare = results.stream()
                .mapToDouble(r -> r.topChampionShare)
                .average()
                .orElse(0);
        double avgRepeatChampionRate = results.stream()
                .mapToDouble(r -> r.repeatChampionRate)
                .average()
                .orElse(0);
        double avgLongestStreak = results.stream()
                .mapToDouble(r -> r.longestChampionStreak)
                .average()
                .orElse(0);

        System.out.printf("  Top champion title share: %.2f%%%n", avgTopChampionShare * 100);
        System.out.printf("  Back-to-back champion rate: %.2f%%%n", avgRepeatChampionRate * 100);
        System.out.printf("  Longest champion streak (avg): %.2f seasons%n", avgLongestStreak);
    }
    
    private void exportToCSV() throws IOException {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String filename = String.format("realism_test_results_%s.csv", timestamp);
        
        StringBuilder csv = new StringBuilder();
        csv.append("run,seed,avg_goals_per_match,avg_draw_rate,avg_points_spread,avg_champion_points,unique_champions\n");
        
        for (SimulationSnapshot snap : results) {
            csv.append(String.format("%d,%d,%.4f,%.4f,%.2f,%.2f,%d\n",
                    snap.runNumber,
                    snap.seed,
                    snap.avgGoalsPerMatch,
                    snap.avgDrawRate,
                    snap.avgPointsSpread,
                    snap.avgChampionPoints,
                    snap.uniqueChampions));
        }
        
        Files.write(Paths.get(filename), csv.toString().getBytes());
        System.out.printf("Results exported to: %s\n", filename);
    }
    
    private double computeStdDev(double[] values) {
        if (values.length == 0) return 0;
        double mean = Arrays.stream(values).average().orElse(0);
        double variance = Arrays.stream(values)
                .map(v -> Math.pow(v - mean, 2))
                .average()
                .orElse(0);
        return Math.sqrt(variance);
    }
    
    // Inner classes for data collection
    
    static class SimulationSnapshot {
        int runNumber;
        long seed;
        List<SeasonMetrics> seasons = new ArrayList<>();
        
        // Aggregates
        double avgGoalsPerMatch;
        double avgDrawRate;
        double avgPointsSpread;
        double avgChampionPoints;
        int uniqueChampions;
        Set<String> champions = new HashSet<>();
        List<String> seasonChampions = new ArrayList<>();
        Map<String, Integer> championTitles = new HashMap<>();
        double topChampionShare;
        double repeatChampionRate;
        int longestChampionStreak;
        
        SimulationSnapshot(long seed) {
            this.seed = seed;
            this.runNumber = (int) (seed % 1000);
        }
        
        void addSeasonMetrics(SeasonMetrics metrics) {
            seasons.add(metrics);
            String champion = metrics.firstPlaceClub;
            seasonChampions.add(champion);
            if (champion != null && !champion.isBlank() && !"Unknown".equals(champion)) {
                champions.add(champion);
                championTitles.merge(champion, 1, Integer::sum);
            }
        }
        
        void computeAggregates() {
            avgGoalsPerMatch = seasons.stream()
                    .mapToDouble(s -> s.goalsPerMatch)
                    .average()
                    .orElse(0);
            
            avgDrawRate = seasons.stream()
                    .mapToDouble(s -> s.drawRate)
                    .average()
                    .orElse(0);
            
            avgPointsSpread = seasons.stream()
                    .mapToDouble(s -> s.pointsSpread)
                    .average()
                    .orElse(0);
            
            avgChampionPoints = seasons.stream()
                    .mapToDouble(s -> s.championPoints)
                    .average()
                    .orElse(0);
            
            uniqueChampions = champions.size();

            int maxTitles = championTitles.values().stream().mapToInt(v -> v).max().orElse(0);
            topChampionShare = seasons.isEmpty() ? 0.0 : (double) maxTitles / seasons.size();

            int repeatCount = 0;
            for (int i = 1; i < seasonChampions.size(); i++) {
                if (Objects.equals(seasonChampions.get(i), seasonChampions.get(i - 1))
                        && !"Unknown".equals(seasonChampions.get(i))) {
                    repeatCount++;
                }
            }
            repeatChampionRate = seasonChampions.size() <= 1 ? 0.0 : (double) repeatCount / (seasonChampions.size() - 1);

            int currentStreak = 0;
            String lastChampion = null;
            int bestStreak = 0;
            for (String champion : seasonChampions) {
                if (champion == null || champion.isBlank() || "Unknown".equals(champion)) {
                    currentStreak = 0;
                    lastChampion = null;
                    continue;
                }
                if (champion.equals(lastChampion)) {
                    currentStreak++;
                } else {
                    currentStreak = 1;
                    lastChampion = champion;
                }
                bestStreak = Math.max(bestStreak, currentStreak);
            }
            longestChampionStreak = bestStreak;
        }
    }
    
    static class SeasonMetrics {
        int season;
        double goalsPerMatch = 0;
        double drawRate = 0;
        double pointsSpread = 0;
        double championPoints = 0;
        double fourthPlacePoints = 0;
        double seventeenthPlacePoints = 0;
        double eighteenthPlacePoints = 0;
        String firstPlaceClub = "Unknown";
        
        SeasonMetrics(int season) {
            this.season = season;
        }
    }
}
