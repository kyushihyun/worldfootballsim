package worldfootballsim;

import java.util.*;

/**
 * Generates balanced round-robin fixtures using circle rotation algorithm.
 * Ensures:
 * - Each team plays every opponent exactly once home and once away
 * - No team plays itself
 * - No team appears twice in same round
 * - Home/away balance is enforced
 */
public class FixtureGenerator {

    /**
     * Generate complete round-robin fixtures (home and away).
     *
     * @param teams       List of teams to schedule
     * @param leagueName  Name for error reporting
     * @return List of rounds, each containing all matches for that round
     */
    public static List<List<Match>> generateFixtures(List<Club> teams, String leagueName) {
        if (teams.size() < 2) {
            return new ArrayList<>();
        }

        List<List<Match>> rounds = new ArrayList<>();

        // Prepare teams for rotation
        List<Club> rotation = new ArrayList<>(teams);
        boolean hasBye = (teams.size() % 2 == 1);
        if (hasBye) {
            rotation.add(null); // Bye team for odd-sized leagues
        }

        int n = rotation.size();
        int roundsSingle = n - 1; // Single round-robin
        int matchesPerRound = n / 2;

        // Generate first half (single round-robin)
        for (int r = 0; r < roundsSingle; r++) {
            List<Match> roundMatches = new ArrayList<>();

            for (int i = 0; i < matchesPerRound; i++) {
                Club t1 = rotation.get(i);
                Club t2 = rotation.get(n - 1 - i);

                if (t1 == null || t2 == null) continue; // Skip bye

                // Alternate home/away each round
                Club home = (r % 2 == 0) ? t1 : t2;
                Club away = (r % 2 == 0) ? t2 : t1;

                roundMatches.add(new Match(home, away, false));
            }

            rounds.add(roundMatches);

            // Rotate teams (keep first fixed, rotate rest)
            Club fixed = rotation.get(0);
            List<Club> rest = new ArrayList<>(rotation.subList(1, n));
            Club last = rest.remove(rest.size() - 1);
            rest.add(0, last);
            rotation.clear();
            rotation.add(fixed);
            rotation.addAll(rest);
        }

        // Generate second half (mirror of first half)
        int originalRounds = rounds.size();
        for (int r = 0; r < originalRounds; r++) {
            List<Match> firstHalf = rounds.get(r);
            List<Match> secondHalf = new ArrayList<>();

            for (Match m : firstHalf) {
                // Reverse home/away
                secondHalf.add(new Match(m.getAway(), m.getHome(), false));
            }

            rounds.add(secondHalf);
        }

        // Verify fixtures before returning
        verifyFixtures(rounds, teams.size(), leagueName);

        return rounds;
    }

    /**
     * Validate generated fixtures for correctness.
     *
     * @param rounds     Generated rounds
     * @param teamCount  Expected number of teams
     * @param leagueName League name for error reporting
     */
    private static void verifyFixtures(List<List<Match>> rounds, int teamCount, String leagueName) {
        int expectedRounds = (teamCount % 2 == 0) ? 2 * (teamCount - 1) : 2 * teamCount;

        if (rounds.size() != expectedRounds) {
            System.err.printf("[FIXTURE ERROR] %s: expected %d rounds, got %d%n",
                leagueName, expectedRounds, rounds.size());
        }

        // Check each club appears at most once per round
        for (int r = 0; r < rounds.size(); r++) {
            Set<Club> seenInRound = new HashSet<>();
            List<Match> roundMatches = rounds.get(r);

            for (Match m : roundMatches) {
                if (m.getHome() == null || m.getAway() == null) {
                    System.err.printf("[FIXTURE ERROR] %s round %d: null team in match%n", leagueName, r + 1);
                    continue;
                }

                if (m.getHome() == m.getAway()) {
                    System.err.printf("[FIXTURE ERROR] %s round %d: %s plays itself%n",
                        leagueName, r + 1, m.getHome().getName());
                }

                if (!seenInRound.add(m.getHome())) {
                    System.err.printf("[FIXTURE ERROR] %s round %d: %s appears twice%n",
                        leagueName, r + 1, m.getHome().getName());
                }

                if (!seenInRound.add(m.getAway())) {
                    System.err.printf("[FIXTURE ERROR] %s round %d: %s appears twice%n",
                        leagueName, r + 1, m.getAway().getName());
                }
            }
        }

        // Check every pair plays exactly once home and once away
        Map<String, Integer> pairCounts = new HashMap<>();
        for (List<Match> round : rounds) {
            for (Match m : round) {
                if (m.getHome() == null || m.getAway() == null) continue;

                String key = m.getHome().getName() + "|" + m.getHome().getCountry()
                    + " vs " + m.getAway().getName() + "|" + m.getAway().getCountry();
                pairCounts.merge(key, 1, Integer::sum);
            }
        }

        // Build complete team list (avoiding null byes)
        Set<Club> allTeams = new HashSet<>();
        for (List<Match> round : rounds) {
            for (Match m : round) {
                if (m.getHome() != null) allTeams.add(m.getHome());
                if (m.getAway() != null) allTeams.add(m.getAway());
            }
        }
        List<Club> uniqueTeams = new ArrayList<>(allTeams);

        // Check pairings
        for (int i = 0; i < uniqueTeams.size(); i++) {
            for (int j = i + 1; j < uniqueTeams.size(); j++) {
                Club a = uniqueTeams.get(i);
                Club b = uniqueTeams.get(j);

                String keyAB = a.getName() + "|" + a.getCountry()
                    + " vs " + b.getName() + "|" + b.getCountry();
                String keyBA = b.getName() + "|" + b.getCountry()
                    + " vs " + a.getName() + "|" + a.getCountry();

                int countAB = pairCounts.getOrDefault(keyAB, 0);
                int countBA = pairCounts.getOrDefault(keyBA, 0);

                if (countAB != 1) {
                    System.err.printf("[FIXTURE ERROR] %s: %s at home vs %s = %d times (expected 1)%n",
                        leagueName, a.getName(), b.getName(), countAB);
                }

                if (countBA != 1) {
                    System.err.printf("[FIXTURE ERROR] %s: %s at home vs %s = %d times (expected 1)%n",
                        leagueName, b.getName(), a.getName(), countBA);
                }
            }
        }
    }

    private FixtureGenerator() {
        // Utility class, non-instantiable
    }
}
