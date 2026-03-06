package worldfootballsim;

import java.util.*;

public class KnockoutCup {
    private static final double DOMESTIC_CUP_ELO_K = 6.0;
    public static class RoundResult {
        public final String roundName;
        public final List<Match> matches;
        public RoundResult(String roundName, List<Match> matches) {
            this.roundName = roundName;
            this.matches = matches;
        }
    }

    private final String name;
    private final List<Club> entrants;
    private final boolean neutralFinal;

    private List<List<Match>> rounds;
    private int currentRound;
    private Club champion;
    private final Deque<Club> roundEntrants = new ArrayDeque<>();
    private final List<RoundResult> roundHistory = new ArrayList<>();
    private final List<Club> byeTeams = new ArrayList<>();
    private final Map<Club, Integer> roundsWonByClub = new HashMap<>();

    // coefficient points per country
    private final Map<String, Double> countryPoints = new HashMap<>();

    public KnockoutCup(String name, List<Club> entrants, boolean neutralFinal) {
        this.name = name;
        this.entrants = new ArrayList<>(entrants);
        this.neutralFinal = neutralFinal;
    }

    public String getName() { return name; }
    public Club getChampion() { return champion; }
    public boolean isComplete() { return champion != null; }
    public Map<String, Double> getCountryPoints() { return Collections.unmodifiableMap(countryPoints); }
    public List<RoundResult> getRoundHistory() { return new ArrayList<>(roundHistory); }
    public Map<Club, Integer> getRoundsWonByClub() { return Collections.unmodifiableMap(roundsWonByClub); }

    public void startNewSeason(int maxEntrants) {
        countryPoints.clear();
        roundsWonByClub.clear();
        champion = null;
        currentRound = 0;
        roundEntrants.clear();
        roundHistory.clear();
        byeTeams.clear();

        List<Club> pool = new ArrayList<>(entrants);
        pool.sort((a, b) -> Double.compare(b.getEloRating(), a.getEloRating()));
        if (pool.size() > maxEntrants) pool = new ArrayList<>(pool.subList(0, maxEntrants));

        int pow2 = 1;
        while (pow2 * 2 <= pool.size()) pow2 <<= 1;
        int prelimCount = Math.max(0, 2 * (pool.size() - pow2));
        int needed = pow2 * 2;

        List<Match> prelim = new ArrayList<>();
        if (prelimCount > 0) {
            int cutoff = Math.max(0, pool.size() - prelimCount);
            List<Club> prelimTeams = new ArrayList<>(pool.subList(cutoff, pool.size()));
            Collections.shuffle(prelimTeams, new Random(name.hashCode() ^ (long) entrants.size() * 17));
            for (int i = 0; i + 1 < prelimTeams.size(); i += 2) {
                prelim.add(new Match(prelimTeams.get(i), prelimTeams.get(i + 1), true));
            }
            byeTeams.addAll(pool.subList(0, cutoff));
        } else {
            Collections.shuffle(pool, new Random(name.hashCode() ^ (long) pool.size() * 13));
            for (Club club : pool) {
                roundEntrants.addLast(club);
            }
        }

        rounds = new ArrayList<>();
        if (!prelim.isEmpty()) {
            rounds.add(prelim);
        }
        rounds.add(new ArrayList<>());
        int teams = needed;
        while (teams > 2) {
            teams /= 2;
            rounds.add(new ArrayList<>());
        }
    }

    private List<Match> buildRound(List<Club> teams, boolean seeded) {
        List<Club> list = new ArrayList<>(teams);
        if (seeded) list.sort((a, b) -> Double.compare(b.getEloRating(), a.getEloRating()));
        else Collections.shuffle(list, new Random(name.hashCode() ^ (long) list.size() * 19));

        List<Match> out = new ArrayList<>();
        for (int i = 0; i + 1 < list.size(); i += 2) {
            out.add(new Match(list.get(i), list.get(i + 1), true));
        }
        return out;
    }

    public RoundResult simulateNextRound(boolean verbose) {
        return simulateNextRound(verbose, Double.NaN);
    }

    public RoundResult simulateNextRound(boolean verbose, double t) {
        if (isComplete() || rounds == null || currentRound >= rounds.size()) return null;
        List<Match> ms = rounds.get(currentRound);
        if (ms.isEmpty()) {
            ms = buildRoundFromEntrants(currentRound);
            if (ms.isEmpty()) return null;
            rounds.set(currentRound, ms);
        }

        boolean isFinal = (currentRound == rounds.size() - 1);
        String roundName = roundLabel(ms.size(), isFinal);

        if (verbose) System.out.println("\n[" + name + "] " + roundName);

        List<Club> winners = new ArrayList<>();
        for (Match m : ms) {
            boolean homeAdv = !(isFinal && neutralFinal);
            MatchEngine.Score s = MatchEngine.play(m.getHome(), m.getAway(), homeAdv, false, t);
            m.setResult(s.homeGoals, s.awayGoals);

            if (s.homeGoals > s.awayGoals) {
                winners.add(m.getHome());
                addCountryPoints(m.getHome().getCountry(), 2);
                addCountryPoints(m.getAway().getCountry(), 0);
                updateCupElo(m.getHome(), m.getAway());
                roundsWonByClub.put(m.getHome(), roundsWonByClub.getOrDefault(m.getHome(), 0) + 1);
            } else if (s.awayGoals > s.homeGoals) {
                winners.add(m.getAway());
                addCountryPoints(m.getAway().getCountry(), 2);
                addCountryPoints(m.getHome().getCountry(), 0);
                updateCupElo(m.getAway(), m.getHome());
                roundsWonByClub.put(m.getAway(), roundsWonByClub.getOrDefault(m.getAway(), 0) + 1);
            } else {
                Club win = MatchEngine.resolveKnockoutWinner(m.getHome(), m.getAway());
                winners.add(win);
                addCountryPoints(m.getHome().getCountry(), 1);
                addCountryPoints(m.getAway().getCountry(), 1);
                updateCupElo(win, win == m.getHome() ? m.getAway() : m.getHome());
                roundsWonByClub.put(win, roundsWonByClub.getOrDefault(win, 0) + 1);
            }

            if (verbose) System.out.println("  " + m + (s.homeGoals == s.awayGoals ? " (pens)" : ""));
        }

        RoundResult result = new RoundResult(roundName, ms);
        roundHistory.add(result);
        if (winners.size() == 1) {
            champion = winners.get(0);
            return result;
        }

        if (!byeTeams.isEmpty()) {
            winners.addAll(byeTeams);
            byeTeams.clear();
        }
        roundEntrants.clear();
        for (Club club : winners) {
            roundEntrants.addLast(club);
        }
        if (currentRound + 1 < rounds.size()) rounds.set(currentRound + 1, new ArrayList<>());
        else rounds.add(new ArrayList<>());

        currentRound++;
        return result;
    }

    private List<Match> buildRoundFromEntrants(int roundIndex) {
        if (roundEntrants.isEmpty()) return new ArrayList<>();
        List<Club> teams = new ArrayList<>(roundEntrants);
        roundEntrants.clear();
        boolean seeded = roundIndex > 1;
        return buildRound(teams, seeded);
    }

    private void updateCupElo(Club winner, Club loser) {
        double expected = expectedOutcome(winner.getEloRating(), loser.getEloRating());
        double delta = DOMESTIC_CUP_ELO_K * (1.0 - expected);
        winner.updateElo(delta);
        loser.updateElo(-delta);
    }

    private void addCountryPoints(String country, double pts) {
        countryPoints.put(country, countryPoints.getOrDefault(country, 0.0) + pts);
    }

    private String roundLabel(int matches, boolean isFinal) {
        if (isFinal && matches == 1) return "Final";
        if (!byeTeams.isEmpty()) return "Preliminary Round";
        return "Round of " + (matches * 2);
    }

    private double expectedOutcome(double eloA, double eloB) {
        return 1.0 / (1.0 + Math.pow(10.0, (eloB - eloA) / 400.0));
    }
}
