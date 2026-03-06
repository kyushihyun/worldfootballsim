package worldfootballsim;

import java.util.*;

import worldfootballsim.LeagueConfigReader.LeagueConfig;

public class League {
    private final String name;
    private final String country;
    private final int level; // 1 = top division
    private final List<Club> clubs = new ArrayList<>();

    private final List<List<Match>> rounds = new ArrayList<>();
    private int currentRound = 0;
    private boolean started = false;
    private int targetSize = 0;
    private LeagueConfig leagueConfig = null;

    public League(String name, String country, int level) {
        this.name = name;
        this.country = country;
        this.level = level;
    }

    public void addClub(Club c) {
        if (c == null)
            return;
        if (!clubs.contains(c)) {
            clubs.add(c);
        }
    }

    public void removeClub(Club c) {
        if (c == null)
            return;
        clubs.removeIf(club -> club.equals(c));
    }

    public void startNewSeason() {
        dedupeClubs();
        for (Club c : clubs)
            c.resetSeasonStats();
        currentRound = 0;
        started = true;
    }

    public void generateFixtures() {
        dedupeClubs();
        rounds.clear();
        if (clubs.size() < 2)
            return;
        // Initialize targetSize once when fixtures are first generated
        // This ensures it's set deterministically during league initialization
        if (targetSize == 0) {
            targetSize = clubs.size();
        }

        List<Club> teams = new ArrayList<>(clubs);
        Collections.shuffle(teams, new Random(name.hashCode() ^ (long) clubs.size() * 31));

        // Use FixtureGenerator for robust fixture generation and validation
        List<List<Match>> generatedRounds = FixtureGenerator.generateFixtures(teams, name);
        rounds.clear();
        rounds.addAll(generatedRounds);
    }

    /**
     * Initialize target size explicitly during league setup.
     * This should be called right after clubs are added but before simulation.
     */
    public void initializeTargetSize() {
        if (targetSize == 0) {
            targetSize = clubs.size();
        }
    }

    public void setTargetSize(int targetSize) {
        if (targetSize > 0) {
            this.targetSize = targetSize;
        }
    }

    public boolean hasNextRound() {
        return started && currentRound < rounds.size();
    }

    public List<Match> simulateNextRound(boolean verbose) {
        return simulateNextRound(verbose, Double.NaN);
    }

    public List<Match> simulateNextRound(boolean verbose, double t) {
        if (!hasNextRound())
            return Collections.emptyList();
        List<Match> ms = rounds.get(currentRound);

        if (verbose)
            System.out.println("[" + name + "] Round " + (currentRound + 1));

        for (Match m : ms) {
            MatchEngine.Score s = MatchEngine.play(m.getHome(), m.getAway(), true, true, t);
            m.setResult(s.homeGoals, s.awayGoals);
            if (verbose)
                System.out.println("  " + m);
        }

        currentRound++;
        return ms;
    }

    public void sortTable() {
        dedupeClubs();
        clubs.sort((a, b) -> {
            if (a.getPoints() != b.getPoints())
                return Integer.compare(b.getPoints(), a.getPoints());
            if (a.getGoalDifference() != b.getGoalDifference())
                return Integer.compare(b.getGoalDifference(), a.getGoalDifference());
            if (a.getGoalsFor() != b.getGoalsFor())
                return Integer.compare(b.getGoalsFor(), a.getGoalsFor());
            return Double.compare(b.getEloRating(), a.getEloRating());
        });
    }

    public Club getChampion() {
        sortTable();
        return clubs.isEmpty() ? null : clubs.get(0);
    }

    public List<Club> getTopN(int n) {
        sortTable();
        int k = Math.max(0, Math.min(n, clubs.size()));
        return new ArrayList<>(clubs.subList(0, k));
    }

    public List<Club> getBottomN(int n) {
        sortTable();
        int k = Math.max(0, Math.min(n, clubs.size()));
        if (k == 0)
            return Collections.emptyList();
        return new ArrayList<>(clubs.subList(clubs.size() - k, clubs.size()));
    }

    public String getName() {
        return name;
    }

    public String getCountry() {
        return country;
    }

    public int getLevel() {
        return level;
    }

    public List<Club> getClubs() {
        return Collections.unmodifiableList(clubs);
    }

    public int getRoundsCount() {
        return rounds.size();
    }

    public int getTargetSize() {
        return targetSize;
    }

    public List<List<Match>> getRounds() {
        return Collections.unmodifiableList(rounds);
    }

    public int getCurrentRound() {
        return currentRound;
    }

    public void setLeagueConfig(LeagueConfig config) {
        this.leagueConfig = config;
    }

    public void printTable(int limit) {
        sortTable();
        System.out.println("\n" + name + " (" + country + ") Table");
        System.out.printf("%-4s %-28s %3s %3s %3s %3s %4s %4s %4s\n",
                "Pos", "Club", "P", "W", "D", "L", "GF", "GA", "Pts");

        int total = Math.min(limit, clubs.size());
        List<Zone> zones = buildZones(total, clubs.size());
        int index = 0;
        for (Zone zone : zones) {
            if (zone.label != null && !zone.label.isEmpty()) {
                String tag = "[ " + zone.label + " ]";
                int dashLen = Math.max(0, 60 - tag.length());
                System.out.println(tag + " " + "-".repeat(dashLen));
            }
            for (int i = 0; i < zone.count && index < total; i++) {
                Club c = clubs.get(index);
                System.out.printf("%-4d %-28s %3d %3d %3d %3d %4d %4d %4d\n",
                        index + 1, truncate(c.getName(), 28), c.getGamesPlayed(),
                        c.getWins(), c.getDraws(), c.getLosses(),
                        c.getGoalsFor(), c.getGoalsAgainst(), c.getPoints());
                index++;
            }
        }
    }

    public void printTableSimple(int limit) {
        sortTable();
        System.out.println("\n" + name + " (" + country + ") Table");
        System.out.printf("%-4s %-28s %3s %3s %3s %3s %4s %4s %4s\n",
                "Pos", "Club", "P", "W", "D", "L", "GF", "GA", "Pts");
        int total = Math.min(limit, clubs.size());
        for (int i = 0; i < total; i++) {
            Club c = clubs.get(i);
            System.out.printf("%-4d %-28s %3d %3d %3d %3d %4d %4d %4d\n",
                    i + 1, truncate(c.getName(), 28), c.getGamesPlayed(),
                    c.getWins(), c.getDraws(), c.getLosses(),
                    c.getGoalsFor(), c.getGoalsAgainst(), c.getPoints());
        }
    }

    private List<Zone> buildZones(int total, int totalClubs) {
        List<Zone> zones = new ArrayList<>();
        if (total == 0)
            return zones;

        if (totalClubs <= 5) {
            zones.add(new Zone("", total));
            return zones;
        }

        MovementRules rules = calculateMovementRules(level, totalClubs);
        int champions = rules.champions;
        int promotion = Math.max(0, rules.autoPromotionSlots - champions);
        int playoff = rules.promotionPlayoffSlots;
        int relegationPlayoff = rules.relegationPlayoffSlots;
        int relegated = rules.relegationSlots;

        // Check if leagueConfig has explicit playoff definitions
        boolean hasPromotionPlayoff = leagueConfig != null && leagueConfig.hasPromotionPlayoff();
        boolean hasRelegationPlayoff = leagueConfig != null && leagueConfig.hasRelegationPlayoff();

        int safe = total - champions - promotion - playoff - relegationPlayoff - relegated;
        if (safe < 0) {
            safe = Math.max(0, total - champions - relegated);
            promotion = Math.max(0, total - champions - safe - relegated);
            playoff = 0;
            relegationPlayoff = 0;
        }

        zones.add(new Zone("Winner", champions));
        if (promotion > 0) {
            zones.add(new Zone("Automatic promotion", promotion));
        }
        if (hasPromotionPlayoff && playoff > 0) {
            zones.add(new Zone("Promotion play-off zone", playoff));
        }
        if (safe > 0) {
            zones.add(new Zone("Safe zone", safe));
        }
        if (hasRelegationPlayoff && relegationPlayoff > 0) {
            zones.add(new Zone("Relegation play-off zone", relegationPlayoff));
        }
        if (relegated > 0) {
            zones.add(new Zone("Relegation zone", relegated));
        }
        return zones;
    }

    public static MovementRules calculateMovementRules(int level, int totalClubs) {
        int champions = 1;
        int autoPromotionSlots = level > 1 ? (totalClubs >= 16 ? 2 : 1) : 0;
        int promotionPlayoffSlots = level > 1 ? (totalClubs >= 18 ? 4 : (totalClubs >= 14 ? 2 : 0)) : 0;
        int relegationPlayoffSlots = level > 1 ? (totalClubs >= 18 ? 1 : 0) : 0;
        int relegationSlots = totalClubs >= 18 ? 3 : 1;
        return new MovementRules(champions, autoPromotionSlots, promotionPlayoffSlots, relegationPlayoffSlots,
                relegationSlots);
    }

    public static final class MovementRules {
        public final int champions;
        public final int autoPromotionSlots;
        public final int promotionPlayoffSlots;
        public final int relegationPlayoffSlots;
        public final int relegationSlots;

        private MovementRules(int champions, int autoPromotionSlots, int promotionPlayoffSlots,
                int relegationPlayoffSlots, int relegationSlots) {
            this.champions = champions;
            this.autoPromotionSlots = autoPromotionSlots;
            this.promotionPlayoffSlots = promotionPlayoffSlots;
            this.relegationPlayoffSlots = relegationPlayoffSlots;
            this.relegationSlots = relegationSlots;
        }
    }

    private static final class Zone {
        private final String label;
        private final int count;

        private Zone(String label, int count) {
            this.label = label;
            this.count = count;
        }
    }

    private String truncate(String s, int n) {
        if (s.length() <= n)
            return s;
        return s.substring(0, Math.max(0, n - 3)) + "...";
    }

    private void dedupeClubs() {
        if (clubs.size() < 2)
            return;
        List<Club> unique = new ArrayList<>(new LinkedHashSet<>(clubs));
        if (unique.size() != clubs.size()) {
            clubs.clear();
            clubs.addAll(unique);
        }
    }
}
