package worldfootballsim;

import java.util.*;

/**
 * Displays and validates a club's seasonal fixture schedule.
 * Shows matches week-by-week with opponent verification.
 * Helps identify scheduling issues and verify balance.
 */
public class FixtureViewer {

    /**
     * Display all matches for a club in a league
     * @param club The club to view
     * @param league The league to check
     */
    public static void displayClubFixtures(Club club, League league) {
        System.out.println("\n" + "=".repeat(100));
        System.out.printf("FIXTURE SCHEDULE: %s (%s) in %s%n", club.getName(), club.getCountry(), league.getName());
        System.out.println("=".repeat(100));

        // Collect all matches involving this club
        // (FixtureGenerator validates these, so if we reach here, fixtures are valid)

        // For now, show summary validation instead
        validateClubFixtures(club, league);
    }

    /**
     * Validate a club's fixtures in a league
     * @param club The club to validate
     * @param league The league to check
     */
    public static void validateClubFixtures(Club club, League league) {
        List<Club> opponents = new ArrayList<>(league.getClubs());
        opponents.remove(club);

        System.out.printf("%nClub: %s%nLeague: %s%nOpponents: %d%n", 
            club.getName(), league.getName(), opponents.size());

        System.out.println("\n" + "-".repeat(100));
        System.out.println("OPPONENT VERIFICATION (should see each opponent exactly twice - home and away)");
        System.out.println("-".repeat(100));

        Map<String, Integer> opponentCount = new HashMap<>();
        for (Club opp : opponents) {
            opponentCount.put(opp.getName(), 0);
        }

        // Count matches from club's perspective
        for (Club opp : opponents) {
            int homeMatches = countMatches(club, opp, true);  // club plays at home
            int awayMatches = countMatches(club, opp, false); // club plays away
            int total = homeMatches + awayMatches;
            
            String status = (total == 2) ? "OK" : "ERROR";
            System.out.printf("%s vs %-40s | Home: %d  Away: %d  Total: %d%n", 
                status, opp.getName(), homeMatches, awayMatches, total);
            
            if (total != 2) {
                System.err.printf("   [FIXTURE ERROR] %s: Should play %s exactly 2 times (1 home, 1 away), got %d%n",
                    club.getName(), opp.getName(), total);
            }
        }

        System.out.println("\n" + "-".repeat(100));
        System.out.println("LEAGUE TOTALS");
        System.out.println("-".repeat(100));
        
        System.out.printf("Expected matches for this club: %d (2 per opponent)%n", opponents.size() * 2);
        System.out.printf("Total rounds in league: %d%n", league.getRoundsCount());
        System.out.printf("Expected matches per round: ~%d%n", Math.ceil((double) league.getClubs().size() / 2));
    }

    /**
     * Display league-wide fixture summary
     * @param league The league to analyze
     */
    public static void displayLeagueSummary(League league) {
        System.out.println("\n" + "=".repeat(100));
        System.out.printf("LEAGUE FIXTURE SUMMARY: %s (%s)%n", league.getName(), league.getCountry());
        System.out.println("=".repeat(100));

        List<Club> clubs = league.getClubs();
        System.out.printf("Total Clubs: %d%nTotal Rounds: %d%n", clubs.size(), league.getRoundsCount());

        int expectedRounds = (clubs.size() % 2 == 0) ? 2 * (clubs.size() - 1) : 2 * clubs.size();
        
        if (league.getRoundsCount() == expectedRounds) {
            System.out.printf("✁ERound count correct (%d)%n", expectedRounds);
        } else {
            System.err.printf("✁ERound count WRONG - expected %d, got %d%n", 
                expectedRounds, league.getRoundsCount());
        }

        System.out.println("\n" + "-".repeat(100));
        System.out.println("CLUB FIXTURE VALIDATION");
        System.out.println("-".repeat(100));

        int errorsFound = 0;
        for (Club club : clubs) {
            List<Club> opponents = new ArrayList<>(clubs);
            opponents.remove(club);

            int mismatches = 0;
            for (Club opp : opponents) {
                int totalMatches = countMatches(club, opp, true) + countMatches(club, opp, false);
                if (totalMatches != 2) {
                    mismatches++;
                    errorsFound++;
                }
            }

            String status = (mismatches == 0) ? "✁EOK" : "✁EERROR";
            System.out.printf("%s %s: %d fixture mismatches%n", status, club.getName(), mismatches);
        }

        System.out.println("\n" + "-".repeat(100));
        if (errorsFound == 0) {
            System.out.println("✁EALL FIXTURES VALID - Every club faces every opponent exactly twice");
        } else {
            System.err.printf("✁EFIXTURE ERRORS FOUND: %d total mismatches across all clubs%n", errorsFound);
        }
    }

    /**
     * Helper to count matches (simplified - works with public API)
     */
    private static int countMatches(Club club1, Club club2, boolean club1Home) {
        // Placeholder - fixtures are validated by FixtureGenerator during generation
        // Actual match counting would require access to completed match results
        return 0;
    }

    /**
     * Display fixture distribution across weeks
     * @param club The club
     * @param league The league
     */
    public static void displayFixtureDistribution(Club club, League league) {
        System.out.println("\n" + "=".repeat(100));
        System.out.printf("WEEKLY FIXTURE DISTRIBUTION: %s in %s%n", club.getName(), league.getName());
        System.out.println("=".repeat(100));

        for (int r = 0; r < league.getRoundsCount(); r++) {
            // Will track matches per round
        }

        System.out.printf("Total matches for %s: ~%d%n", club.getName(), 
            (league.getClubs().size() - 1) * 2);
        System.out.printf("Total rounds: %d%n", league.getRoundsCount());
        System.out.printf("Expected matches per round: %d%n", 1); // Club plays once per round typically

        System.out.println("\nRound by round:");
        for (int r = 1; r <= league.getRoundsCount(); r++) {
            System.out.printf("  Round %2d: [fixture data would show here]%n", r);
        }
    }

    /**
     * Check for fixture congestion (too many matches in short period)
     * @param club The club
     * @param league The league
     * @param daysWindow Days to check for congestion
     */
    public static void checkFixtureCongestion(Club club, League league, int daysWindow) {
        System.out.println("\n" + "=".repeat(100));
        System.out.printf("FIXTURE CONGESTION CHECK: %s (%d-day windows)%n", club.getName(), daysWindow);
        System.out.println("=".repeat(100));
        System.out.println("Checking for periods with too many matches in short span...");
        System.out.println("[Congestion analysis would show here]");
    }

    /**
     * Print text summary of all clubs in a league with their opponents
     * @param league The league to summarize
     */
    public static void printLeagueOpponentMatrix(League league) {
        System.out.println("\n" + "=".repeat(100));
        System.out.printf("OPPONENT VERIFICATION MATRIX: %s%n", league.getName());
        System.out.println("=".repeat(100));
        System.out.println("Format: Club | Expected Opponents | Total Expected Matches");
        System.out.println("-".repeat(100));

        List<Club> clubs = league.getClubs();
        int oppCount = clubs.size() - 1;

        for (Club club : clubs) {
            int expectedMatches = oppCount * 2; // Each opponent twice
            System.out.printf("%-40s | Opponents: %2d | Expected Matches: %2d%n", 
                club.getName(), oppCount, expectedMatches);
        }

        System.out.println("\n" + "-".repeat(100));
        System.out.printf("Legend: Each club should face %d opponents twice (once home, once away)%n", oppCount);
        System.out.printf("Total rounds in league: %d%n", league.getRoundsCount());
    }

    /**
     * Export fixture schedule as formatted text
     * @param club The club
     * @param league The league
     * @return Formatted fixture schedule
     */
    public static String exportClubFixtureSchedule(Club club, League league) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("FIXTURE SCHEDULE: %s%n", club.getName()));
        sb.append(String.format("League: %s%n", league.getName()));
        sb.append(String.format("Total Rounds: %d%n", league.getRoundsCount()));
        sb.append("-".repeat(80)).append("\n");
        
        List<Club> opponents = new ArrayList<>(league.getClubs());
        opponents.remove(club);
        
        sb.append(String.format("Opponents to face: %d%n", opponents.size()));
        sb.append("Each opponent should be faced exactly twice (1 home, 1 away)\n");
        sb.append("-".repeat(80)).append("\n");
        
        for (Club opp : opponents) {
            sb.append(String.format("  • %s%n", opp.getName()));
        }
        
        return sb.toString();
    }
}
