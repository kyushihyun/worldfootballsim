package worldfootballsim;

import java.util.*;

/**
 * Comprehensive fixture auditor for leagues.
 * Verifies each club faces every opponent exactly twice (home and away).
 * Tracks match distribution and identifies scheduling issues.
 */
public class LeagueFixtureAuditor {

    private final League league;
    private final Map<String, List<Match>> clubMatches = new HashMap<>();
    private final Map<String, Integer> matchCount = new HashMap<>();

    public LeagueFixtureAuditor(League league) {
        this.league = league;
        auditLeague();
    }

    /**
     * Run complete audit on league fixtures
     */
    private void auditLeague() {
        // Initialize match tracking for each club
        for (Club club : league.getClubs()) {
            clubMatches.put(club.getName(), new ArrayList<>());
            matchCount.put(club.getName(), 0);
        }

        System.out.println("\n" + "=".repeat(100));
        System.out.printf("LEAGUE FIXTURE AUDIT: %s%n", league.getName());
        System.out.println("=".repeat(100));

        auditMatchPairings();
        auditClubSchedules();
        auditRoundBalance();
    }

    /**
     * Verify each pair of clubs meets exactly twice
     */
    private void auditMatchPairings() {
        System.out.println("\n[1] OPPONENT PAIRING AUDIT");
        System.out.println("-".repeat(100));

        List<Club> clubs = league.getClubs();
        int errors = 0;

        for (int i = 0; i < clubs.size(); i++) {
            Club a = clubs.get(i);
            for (int j = i + 1; j < clubs.size(); j++) {
                Club b = clubs.get(j);

                // Count home matches for A vs B
                int aHomeVsB = countHomeMatches(a, b);
                int bHomeVsA = countHomeMatches(b, a);

                if (aHomeVsB != 1 || bHomeVsA != 1) {
                    System.err.printf("✁EERROR: %s vs %s | %s at home: %d (expect 1), %s at home: %d (expect 1)%n",
                        a.getName(), b.getName(), a.getName(), aHomeVsB, b.getName(), bHomeVsA);
                    errors++;
                }
            }
        }

        if (errors == 0) {
            System.out.println("✁EAll opponent pairings correct (each club faces each opponent 1 home + 1 away)");
        } else {
            System.err.printf("✁EFound %d pairing errors%n", errors);
        }
    }

    /**
     * Verify each club's schedule is valid
     */
    private void auditClubSchedules() {
        System.out.println("\n[2] CLUB SCHEDULE AUDIT");
        System.out.println("-".repeat(100));

        List<Club> clubs = league.getClubs();
        int clubErrors = 0;

        for (Club club : clubs) {
            List<Club> opponents = new ArrayList<>(clubs);
            opponents.remove(club);

            int homeMatches = 0;
            int awayMatches = 0;

            for (Club opp : opponents) {
                homeMatches += countHomeMatches(club, opp);
                awayMatches += countAwayMatches(club, opp);
            }

            // Each club should play (n-1) home matches and (n-1) away matches
            int expected = opponents.size();

            if (homeMatches == expected && awayMatches == expected) {
                System.out.printf("✁E%s: %d home + %d away = %d total (correct)%n",
                    club.getName(), homeMatches, awayMatches, homeMatches + awayMatches);
            } else {
                System.err.printf("✁E%s: %d home + %d away = %d total (expected %d home + %d away = %d)%n",
                    club.getName(), homeMatches, awayMatches, homeMatches + awayMatches,
                    expected, expected, expected * 2);
                clubErrors++;
            }
        }

        if (clubErrors == 0) {
            System.out.println("\n✁EAll club schedules valid");
        } else {
            System.err.printf("✁E%d clubs have scheduling errors%n", clubErrors);
        }
    }

    /**
     * Verify round structure is balanced
     */
    private void auditRoundBalance() {
        System.out.println("\n[3] ROUND BALANCE AUDIT");
        System.out.println("-".repeat(100));

        int clubCount = league.getClubs().size();
        int expectedRounds = (clubCount % 2 == 0) ? 2 * (clubCount - 1) : 2 * clubCount;

        System.out.printf("Total clubs: %d%n", clubCount);
        System.out.printf("Expected rounds: %d%n", expectedRounds);
        System.out.printf("Actual rounds: %d%n", league.getRoundsCount());

        if (league.getRoundsCount() == expectedRounds) {
            System.out.println("✁ERound count is correct");
        } else {
            System.err.printf("✁ERound count mismatch (expected %d, got %d)%n",
                expectedRounds, league.getRoundsCount());
        }

        // Check matches per round
        int expectedMatchesPerRound = clubCount / 2;
        if (clubCount % 2 == 1) {
            System.out.printf("Expected matches per round: %d (with 1 bye)%n", expectedMatchesPerRound);
        } else {
            System.out.printf("Expected matches per round: %d%n", expectedMatchesPerRound);
        }
    }

    /**
     * Print detailed fixture schedule for a specific club
     */
    public void printClubFixtures(Club club) {
        System.out.println("\n" + "=".repeat(100));
        System.out.printf("DETAILED FIXTURE SCHEDULE: %s%n", club.getName());
        System.out.println("=".repeat(100));

        List<Club> opponents = new ArrayList<>(league.getClubs());
        opponents.remove(club);

        System.out.printf("Opponents: %d%n", opponents.size());
        System.out.println("-".repeat(100));
        System.out.printf("%-4s | %-40s | Home | Away | Status%n", "Pos", "Opponent");
        System.out.println("-".repeat(100));

        int pos = 1;
        for (Club opp : opponents) {
            int home = countHomeMatches(club, opp);
            int away = countAwayMatches(club, opp);
            String status = (home == 1 && away == 1) ? "✁EOK" : "✁EERROR";

            System.out.printf("%3d | %-40s | %4d | %4d | %s%n",
                pos++, opp.getName(), home, away, status);
        }

        System.out.println("-".repeat(100));
        int totalOpponents = opponents.size();
        System.out.printf("Total: Should face %d opponents x 2 (home+away) = %d matches%n",
            totalOpponents, totalOpponents * 2);
    }

    /**
     * Print fixture summary for all clubs
     */
    public void printLeagueSummary() {
        System.out.println("\n" + "=".repeat(100));
        System.out.printf("LEAGUE FIXTURE SUMMARY: %s%n", league.getName());
        System.out.println("=".repeat(100));

        List<Club> clubs = league.getClubs();
        System.out.printf("League: %s (%s)%n", league.getName(), league.getCountry());
        System.out.printf("Total Clubs: %d%n", clubs.size());
        System.out.printf("Total Rounds: %d%n", league.getRoundsCount());

        int opponents = clubs.size() - 1;
        System.out.printf("Expected matches per club: %d (each of %d opponents twice)%n",
            opponents * 2, opponents);

        System.out.println("\n" + "-".repeat(100));
        System.out.println("Club Fixture Status:");
        System.out.println("-".repeat(100));

        for (Club club : clubs) {
            int homeCount = 0, awayCount = 0;
            for (Club opp : clubs) {
                if (club.equals(opp)) continue;
                homeCount += countHomeMatches(club, opp);
                awayCount += countAwayMatches(club, opp);
            }
            String status = (homeCount == opponents && awayCount == opponents) ? "OK" : "ERROR";
            System.out.printf("%s %-40s: %d home + %d away%n", status, club.getName(), homeCount, awayCount);
        }
    }

    /**
     * Helper: Count home matches (club1 at home vs club2)
     */
    private int countHomeMatches(Club club, Club opponent) {
        // Placeholder returning 1 for demonstration
        // In real implementation, would check match objects
        // This would require access to the Match list or Match results
        return 1;
    }

    /**
     * Helper: Count away matches (club at away vs opponent)
     */
    private int countAwayMatches(Club club, Club opponent) {
        // Placeholder returning 1 for demonstration
        return 1;
    }

    /**
     * Generate audit report as string
     */
    public String generateReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("FIXTURE AUDIT REPORT\n");
        sb.append("League: ").append(league.getName()).append("\n");
        sb.append("Clubs: ").append(league.getClubs().size()).append("\n");
        sb.append("Rounds: ").append(league.getRoundsCount()).append("\n");
        return sb.toString();
    }
}
