package worldfootballsim;

import java.util.*;

public class ContinentalTournament {
    public enum Phase { LEAGUE_PHASE, GROUPS, KNOCKOUT, DONE }

    public static class Group {
        public final String name;
        public final League league;
        public Group(String name, String confed, int idx) {
            this.name = name;
            this.league = new League(confed + " " + name, confed, 0);
        }
    }

    // Swiss league phase entry
    public static class SwissEntry {
        public final Club club;
        public int points, wins, draws, losses, goalsFor, goalsAgainst, gamesPlayed;
        public SwissEntry(Club club) { this.club = club; }
        public int goalDifference() { return goalsFor - goalsAgainst; }
    }

    private static final double CONTINENTAL_ELO_K = 18.0;

    private final String name;
    private final Confederation confederation;
    private final int targetTeams;
    private final boolean twoLegKnockouts;
    private final boolean neutralFinal;
    private final int groupSize;
    private final int matchesPerTeam;
    private final boolean useLeaguePhase;

    private List<Club> entrants = new ArrayList<>();

    // Region split support (AFC West/East)
    private java.util.function.Function<Club, String> regionMapper = null;
    private boolean regionSplit = false;
    private boolean regionSplitKnockout = false;

    // Group stage
    private List<Group> groups = new ArrayList<>();
    private Phase phase = Phase.GROUPS;
    private int groupMatchday = 0;
    private int groupMatchdayTarget = 6;

    // Swiss league phase (dual tables for region split)
    private List<SwissEntry> swissTable = new ArrayList<>();
    private List<SwissEntry> swissTableWest = new ArrayList<>();
    private List<SwissEntry> swissTableEast = new ArrayList<>();
    private List<List<Match>> swissRounds = new ArrayList<>();
    private int swissRoundIndex = 0;
    private final Set<String> swissPlayed = new HashSet<>();
    private final Set<String> swissPlayedWest = new HashSet<>();
    private final Set<String> swissPlayedEast = new HashSet<>();
    private int swissTotalRounds = 8;
    private Random swissRng = new Random(99);

    // Knockout
    private List<List<Match>> knockoutRounds = new ArrayList<>();
    private int knockoutIndex = 0;
    private final List<List<Match>> knockoutHistory = new ArrayList<>();
    private List<Match> playoffHistory = new ArrayList<>();

    private Club champion;

    // coefficient tracking
    public static class CountryCoeffData {
        public double qualifierPoints;
        public double mainStagePoints;
        public double knockoutPoints;
        public double bonusPoints;
        public int teamCount;

        public double totalPoints() { return qualifierPoints + mainStagePoints + knockoutPoints + bonusPoints; }
        public double average() { return teamCount > 0 ? totalPoints() / teamCount : 0; }
    }

    private static final class TierCoefficientProfile {
        final double qualifierWin;
        final double qualifierDraw;
        final double qualifierLoss;
        final double mainStageEntry;
        final double mainStageMatchWin;
        final double mainStageMatchDraw;
        final double mainStageMatchLoss;
        final double topFinishBonus;
        final double midHighFinishBonus;
        final double midLowFinishBonus;
        final double roundOf16Bonus;
        final double quarterFinalBonus;
        final double semiFinalBonus;
        final double finalBonus;
        final double championBonus;

        private TierCoefficientProfile(double qualifierWin, double qualifierDraw, double qualifierLoss,
                                       double mainStageEntry,
                                       double mainStageMatchWin, double mainStageMatchDraw, double mainStageMatchLoss,
                                       double topFinishBonus, double midHighFinishBonus, double midLowFinishBonus,
                                       double roundOf16Bonus, double quarterFinalBonus,
                                       double semiFinalBonus, double finalBonus, double championBonus) {
            this.qualifierWin = qualifierWin;
            this.qualifierDraw = qualifierDraw;
            this.qualifierLoss = qualifierLoss;
            this.mainStageEntry = mainStageEntry;
            this.mainStageMatchWin = mainStageMatchWin;
            this.mainStageMatchDraw = mainStageMatchDraw;
            this.mainStageMatchLoss = mainStageMatchLoss;
            this.topFinishBonus = topFinishBonus;
            this.midHighFinishBonus = midHighFinishBonus;
            this.midLowFinishBonus = midLowFinishBonus;
            this.roundOf16Bonus = roundOf16Bonus;
            this.quarterFinalBonus = quarterFinalBonus;
            this.semiFinalBonus = semiFinalBonus;
            this.finalBonus = finalBonus;
            this.championBonus = championBonus;
        }
    }

    private final Map<String, CountryCoeffData> countryCoefficients = new HashMap<>();
    private final Map<String, Set<String>> countryParticipants = new HashMap<>();
    private final Map<String, Map<String, Double>> countryClubPointBreakdown = new HashMap<>();
    private int tier;

    public ContinentalTournament(String name, Confederation confederation, int targetTeams,
                                  boolean twoLegKnockouts, boolean neutralFinal) {
        this(name, confederation, targetTeams, twoLegKnockouts, neutralFinal, 4, 6, false, 1);
    }

    public ContinentalTournament(String name, Confederation confederation, int targetTeams,
                                  boolean twoLegKnockouts, boolean neutralFinal,
                                  int groupSize, int matchesPerTeam, boolean useLeaguePhase, int tier) {
        this.name = name;
        this.confederation = confederation;
        this.targetTeams = targetTeams;
        this.twoLegKnockouts = twoLegKnockouts;
        this.neutralFinal = neutralFinal;
        this.groupSize = groupSize;
        this.matchesPerTeam = matchesPerTeam;
        this.useLeaguePhase = useLeaguePhase;
        this.tier = tier;
    }

    public String getName() { return name; }
    public Confederation getConfederation() { return confederation; }
    public Club getChampion() { return champion; }
    public boolean isComplete() { return champion != null; }
    public Phase getPhase() { return phase; }

    public Map<String, Double> getCountryPoints() {
        Map<String, Double> pts = new HashMap<>();
        for (Map.Entry<String, CountryCoeffData> e : countryCoefficients.entrySet()) {
            pts.put(e.getKey(), e.getValue().totalPoints());
        }
        return Collections.unmodifiableMap(pts);
    }
    public Map<String, Integer> getCountryTeamCounts() {
        Map<String, Integer> counts = new HashMap<>();
        for (Map.Entry<String, CountryCoeffData> e : countryCoefficients.entrySet()) {
            counts.put(e.getKey(), e.getValue().teamCount);
        }
        return Collections.unmodifiableMap(counts);
    }
    public Map<String, CountryCoeffData> getCountryCoefficients() { return Collections.unmodifiableMap(countryCoefficients); }

    public Map<String, Map<String, Double>> getCountryClubPointBreakdown() {
        Map<String, Map<String, Double>> snapshot = new HashMap<>();
        for (Map.Entry<String, Map<String, Double>> countryEntry : countryClubPointBreakdown.entrySet()) {
            snapshot.put(countryEntry.getKey(), Collections.unmodifiableMap(new LinkedHashMap<>(countryEntry.getValue())));
        }
        return Collections.unmodifiableMap(snapshot);
    }

    public void recordQualifyingMatches(List<Match> matches, boolean singleLeg) {
        if (matches == null || matches.isEmpty()) return;
        
        // Track all clubs that participate in qualifying (even if they lose)
        Set<Club> qualifyingParticipants = new HashSet<>();
        for (Match m : matches) {
            qualifyingParticipants.add(m.getHome());
            qualifyingParticipants.add(m.getAway());
        }
        
        // Award participation credit to all qualifying entrants
        for (Club c : qualifyingParticipants) {
            registerParticipant(c);
            TierCoefficientProfile profile = coeffProfile();
            addPoints(c, PointsBucket.BONUS, profile.qualifierWin * 0.5); // Minimum bonus for participation
        }
        
        // Award points based on qualifying match results
        for (Match m : matches) {
            if (!m.hasResult()) continue;
            awardQualifierPoints(m.getHome(), m.getAway(), m.getHomeGoals(), m.getAwayGoals());
        }
    }

    public void setEntrants(List<Club> entrants) {
        this.entrants = new ArrayList<>(entrants);
    }

    public List<Club> getEntrants() {
        return Collections.unmodifiableList(entrants);
    }

    /** Enable region split (e.g. AFC West/East). Mapper returns "West" or "East" per club. */
    public void setRegionSplit(java.util.function.Function<Club, String> mapper) {
        this.regionMapper = mapper;
        this.regionSplit = (mapper != null);
    }

    public boolean isRegionSplit() { return regionSplit; }

    private static List<SwissEntry> sortSwissEntries(List<SwissEntry> entries) {
        List<SwissEntry> sorted = new ArrayList<>(entries);
        sorted.sort((a, b) -> {
            if (b.points != a.points) return Integer.compare(b.points, a.points);
            if (b.goalDifference() != a.goalDifference()) return Integer.compare(b.goalDifference(), a.goalDifference());
            if (b.goalsFor != a.goalsFor) return Integer.compare(b.goalsFor, a.goalsFor);
            return Double.compare(b.club.getEloRating(), a.club.getEloRating());
        });
        return sorted;
    }

    public List<SwissEntry> getSwissTable() {
        return sortSwissEntries(swissTable);
    }

    public List<SwissEntry> getSwissTableWest() { return sortSwissEntries(swissTableWest); }
    public List<SwissEntry> getSwissTableEast() { return sortSwissEntries(swissTableEast); }

    public List<Group> getGroups() { return Collections.unmodifiableList(groups); }
    public List<List<Match>> getKnockoutHistory() { return Collections.unmodifiableList(knockoutHistory); }
    public List<Match> getPlayoffHistory() { return Collections.unmodifiableList(playoffHistory); }

    public void startNewSeason() {
        countryCoefficients.clear();
        countryParticipants.clear();
        countryClubPointBreakdown.clear();
        champion = null;
        groupMatchday = 0;
        knockoutRounds.clear();
        knockoutIndex = 0;
        groups.clear();
        regionSplitKnockout = false;
        swissTable.clear();
        swissTableWest.clear();
        swissTableEast.clear();
        swissRounds.clear();
        swissRoundIndex = 0;
        swissPlayed.clear();
        swissPlayedWest.clear();
        swissPlayedEast.clear();
        knockoutHistory.clear();
        playoffHistory.clear();

        List<Club> selected = selectEntrantsToTarget(entrants, targetTeams);

        for (Club c : selected) {
            registerParticipant(c);
        }

        // Reaching main stage bonus
        for (Club c : selected) {
            awardStageBonus(c, "main_stage");
        }

        if (useLeaguePhase) {
            if (selected.size() >= 8) {
                phase = Phase.LEAGUE_PHASE;
                // FIX: Reset tournament-specific stats for Swiss league phase
                for (Club c : selected) {
                    c.resetSeasonStats();
                }
                buildSwissLeaguePhase(selected);
                return;
            }
        }

        int gs = groupSize > 0 ? groupSize : 4;
        groupMatchdayTarget = matchesPerTeam > 0 ? matchesPerTeam : (gs - 1) * 2;
        buildGroups(selected, gs);
        regionSplitKnockout = regionSplit && confederation == Confederation.ASIA && tier >= 2;

        if (groups.isEmpty()) {
            phase = Phase.KNOCKOUT;
            knockoutRounds = buildKnockoutBracket(selected, twoLegKnockouts);
            return;
        }

        phase = Phase.GROUPS;
        // FIX: Reset tournament-specific stats for all clubs entering group stage
        for (Club c : selected) {
            c.resetSeasonStats();
        }
        for (Group g : groups) {
            g.league.generateFixtures();
            g.league.startNewSeason();
        }
    }

    private List<Club> selectEntrantsToTarget(List<Club> entrants, int target) {
        List<Club> list = new ArrayList<>(entrants);
        list.sort((a, b) -> Double.compare(b.getEloRating(), a.getEloRating()));

        int t = Math.min(list.size(), target);
        if (useLeaguePhase) {
            return new ArrayList<>(list.subList(0, t));
        }
        int gs = groupSize > 0 ? groupSize : 4;
        int tDivGs = t - (t % gs);
        if (tDivGs >= gs * 2) {
            return new ArrayList<>(list.subList(0, tDivGs));
        }
        return list;
    }

    // ==================== SWISS LEAGUE PHASE ====================

    private void buildSwissLeaguePhase(List<Club> selected) {
        swissTable.clear();
        swissTableWest.clear();
        swissTableEast.clear();
        swissRounds.clear();
        swissPlayed.clear();
        swissPlayedWest.clear();
        swissPlayedEast.clear();
        swissRoundIndex = 0;
        swissTotalRounds = matchesPerTeam > 0 ? matchesPerTeam : 8;
        swissRng = new Random(99);

        if (regionSplit && regionMapper != null) {
            List<Club> westClubs = new ArrayList<>();
            List<Club> eastClubs = new ArrayList<>();
            for (Club c : selected) {
                SwissEntry entry = new SwissEntry(c);
                swissTable.add(entry); // keep combined for findEntry
                String region = regionMapper.apply(c);
                if ("West".equals(region)) {
                    swissTableWest.add(entry);
                    westClubs.add(c);
                } else {
                    swissTableEast.add(entry);
                    eastClubs.add(c);
                }
            }

            List<List<Match>> westRounds = buildPotBasedRounds(westClubs, swissTotalRounds, swissRng);
            List<List<Match>> eastRounds = buildPotBasedRounds(eastClubs, swissTotalRounds, swissRng);
            if (westRounds != null && eastRounds != null) {
                for (int i = 0; i < swissTotalRounds; i++) {
                    List<Match> roundMatches = new ArrayList<>();
                    roundMatches.addAll(westRounds.get(i));
                    roundMatches.addAll(eastRounds.get(i));
                    swissRounds.add(roundMatches);
                }
                return;
            }

            List<Match> roundMatches = new ArrayList<>();
            roundMatches.addAll(generateSwissRound(sortSwissEntries(swissTableWest), swissRng, swissPlayedWest));
            roundMatches.addAll(generateSwissRound(sortSwissEntries(swissTableEast), swissRng, swissPlayedEast));
            swissRounds.add(roundMatches);
            return;
        }

        for (Club c : selected) {
            swissTable.add(new SwissEntry(c));
        }

        List<List<Match>> rounds = buildPotBasedRounds(selected, swissTotalRounds, swissRng);
        if (rounds != null) {
            swissRounds.addAll(rounds);
        } else {
            List<SwissEntry> sorted = getSwissTable();
            swissRounds.add(generateSwissRound(sorted, swissRng, swissPlayed));
        }
    }

    private List<List<Match>> buildPotBasedRounds(List<Club> clubs, int rounds, Random rng) {
        if (clubs == null || clubs.size() < 2 || rounds <= 0) {
            return null;
        }
        int potCount = determinePotCount(rounds);
        if (potCount <= 0) {
            return null;
        }
        if (clubs.size() % potCount != 0) {
            return null;
        }
        int potSize = clubs.size() / potCount;
        int opponentsPerPot = rounds / potCount;
        if (opponentsPerPot * potCount != rounds) {
            return null;
        }

        List<Club> sorted = new ArrayList<>(clubs);
        sorted.sort((a, b) -> Double.compare(b.getEloRating(), a.getEloRating()));
        Map<Club, Integer> potIndex = new HashMap<>();
        Map<Club, int[]> remaining = new HashMap<>();
        for (int i = 0; i < sorted.size(); i++) {
            int pot = Math.min(i / potSize, potCount - 1);
            potIndex.put(sorted.get(i), pot);
        }
        for (Club club : sorted) {
            int[] targets = new int[potCount];
            Arrays.fill(targets, opponentsPerPot);
            remaining.put(club, targets);
        }

        for (int attempt = 0; attempt < 200; attempt++) {
            List<List<Match>> roundsOut = new ArrayList<>();
            Map<Club, int[]> remainingCopy = new HashMap<>();
            for (Map.Entry<Club, int[]> entry : remaining.entrySet()) {
                remainingCopy.put(entry.getKey(), entry.getValue().clone());
            }
            Set<String> played = new HashSet<>();
            boolean failed = false;

            for (int r = 0; r < rounds; r++) {
                List<Club> pool = new ArrayList<>(sorted);
                Collections.shuffle(pool, rng);
                List<Match> roundMatches = new ArrayList<>();
                while (!pool.isEmpty()) {
                    Club a = pool.remove(0);
                    Club b = findPotOpponent(a, pool, potIndex, remainingCopy, played);
                    if (b == null) {
                        failed = true;
                        break;
                    }
                    pool.remove(b);
                    applyPotMatch(a, b, potIndex, remainingCopy, played, roundMatches);
                }
                if (failed) {
                    break;
                }
                roundsOut.add(roundMatches);
            }

            if (!failed && remainingSatisfied(remainingCopy)) {
                return roundsOut;
            }
        }

        return null;
    }

    private int determinePotCount(int rounds) {
        if (rounds % 4 == 0 && rounds >= 8) {
            return 4;
        }
        if (rounds % 3 == 0 && rounds >= 6) {
            return 3;
        }
        return 0;
    }

    private Club findPotOpponent(Club club, List<Club> pool, Map<Club, Integer> potIndex,
                                 Map<Club, int[]> remaining, Set<String> played) {
        int clubPot = potIndex.get(club);
        int[] clubRemaining = remaining.get(club);
        Club fallback = null;
        for (Club candidate : pool) {
            int candPot = potIndex.get(candidate);
            if (clubRemaining[candPot] <= 0 || remaining.get(candidate)[clubPot] <= 0) {
                continue;
            }
            if (played.contains(pairKey(club, candidate))) {
                continue;
            }
            if (club.getCountry().equals(candidate.getCountry())) {
                if (fallback == null) {
                    fallback = candidate;
                }
                continue;
            }
            return candidate;
        }
        return fallback;
    }

    private void applyPotMatch(Club a, Club b, Map<Club, Integer> potIndex,
                               Map<Club, int[]> remaining, Set<String> played,
                               List<Match> roundMatches) {
        int potA = potIndex.get(a);
        int potB = potIndex.get(b);
        remaining.get(a)[potB]--;
        remaining.get(b)[potA]--;
        played.add(pairKey(a, b));
        roundMatches.add(new Match(a, b, true));
    }

    private boolean remainingSatisfied(Map<Club, int[]> remaining) {
        for (int[] counts : remaining.values()) {
            for (int count : counts) {
                if (count != 0) {
                    return false;
                }
            }
        }
        return true;
    }

    private List<Match> generateSwissRound(List<SwissEntry> sorted, Random rng, Set<String> played) {
        List<Match> matches = new ArrayList<>();
        Set<Club> paired = new HashSet<>();
        List<SwissEntry> pool = new ArrayList<>(sorted);
        Collections.shuffle(pool.subList(0, Math.min(pool.size(), pool.size())), rng);

        // Re-sort by points then ELO for pot-based seeded pairing
        pool.sort((a, b) -> {
            if (b.points != a.points) return Integer.compare(b.points, a.points);
            return Double.compare(b.club.getEloRating(), a.club.getEloRating());
        });

        for (int i = 0; i < pool.size(); i++) {
            SwissEntry a = pool.get(i);
            if (paired.contains(a.club)) continue;

            for (int j = i + 1; j < pool.size(); j++) {
                SwissEntry b = pool.get(j);
                if (paired.contains(b.club)) continue;

                String pk = pairKey(a.club, b.club);
                if (played.contains(pk)) continue;

                // Country protection: clubs from same country cannot face each other
                if (a.club.getCountry().equals(b.club.getCountry())) continue;

                played.add(pk);
                paired.add(a.club);
                paired.add(b.club);

                if (rng.nextBoolean()) {
                    matches.add(new Match(a.club, b.club, true));
                } else {
                    matches.add(new Match(b.club, a.club, true));
                }
                break;
            }

            // Relax country constraint if needed
            if (!paired.contains(a.club)) {
                for (int j = i + 1; j < pool.size(); j++) {
                    SwissEntry b = pool.get(j);
                    if (paired.contains(b.club)) continue;

                    String pk = pairKey(a.club, b.club);
                    if (played.contains(pk)) continue;

                    played.add(pk);
                    paired.add(a.club);
                    paired.add(b.club);
                    matches.add(new Match(a.club, b.club, true));
                    break;
                }
            }

            // Last resort: allow repeat match
            if (!paired.contains(a.club)) {
                for (int j = i + 1; j < pool.size(); j++) {
                    SwissEntry b = pool.get(j);
                    if (paired.contains(b.club)) continue;
                    paired.add(a.club);
                    paired.add(b.club);
                    matches.add(new Match(a.club, b.club, true));
                    break;
                }
            }
        }

        return matches;
    }

    private String pairKey(Club a, Club b) {
        String na = a.getName();
        String nb = b.getName();
        return na.compareTo(nb) < 0 ? na + "|" + nb : nb + "|" + na;
    }

    private SwissEntry findEntry(Club c) {
        for (SwissEntry e : swissTable) {
            if (e.club.equals(c)) return e;
        }
        return null;
    }

    // ==================== GROUP STAGE ====================

    private void buildGroups(List<Club> selected, int gs) {
        if (regionSplit && regionMapper != null) {
            buildRegionSplitGroups(selected, gs);
            return;
        }

        int teams = selected.size();
        int groupsCount = teams / gs;
        if (groupsCount < 2) {
            groups.clear();
            return;
        }

        List<Club> ordered = new ArrayList<>(selected);
        ordered.sort((a, b) -> Double.compare(b.getEloRating(), a.getEloRating()));

        int pots = gs;
        List<List<Club>> potLists = new ArrayList<>();
        for (int p = 0; p < pots; p++) potLists.add(new ArrayList<>());
        for (int i = 0; i < ordered.size(); i++) {
            int potIndex = Math.min(i / groupsCount, pots - 1);
            potLists.get(potIndex).add(ordered.get(i));
        }

        List<Group> gs2 = new ArrayList<>();
        for (int i = 0; i < groupsCount; i++) gs2.add(new Group("Group " + (char)('A' + i), name, i));

        Random rng = new Random(99);
        for (int pot = 0; pot < pots; pot++) {
            List<Club> potClubs = potLists.get(pot);
            Collections.shuffle(potClubs, rng);
            for (Club club : potClubs) {
                // Country protection: try to place in a group without same-country clubs
                int bestGroup = -1;
                for (int g = 0; g < groupsCount; g++) {
                    if (gs2.get(g).league.getClubs().size() >= gs) continue;
                    boolean conflict = false;
                    for (Club existing : gs2.get(g).league.getClubs()) {
                        if (existing.getCountry().equals(club.getCountry())) {
                            conflict = true;
                            break;
                        }
                    }
                    if (!conflict) { bestGroup = g; break; }
                }
                // Fallback: place in first group with space
                if (bestGroup < 0) {
                    for (int g = 0; g < groupsCount; g++) {
                        if (gs2.get(g).league.getClubs().size() < gs) { bestGroup = g; break; }
                    }
                }
                if (bestGroup >= 0) {
                    gs2.get(bestGroup).league.addClub(club);
                }
            }
        }
        groups = gs2;
    }

    /** Build region-separated groups: West clubs in West groups, East clubs in East groups */
    private void buildRegionSplitGroups(List<Club> selected, int gs) {
        List<Club> westClubs = new ArrayList<>();
        List<Club> eastClubs = new ArrayList<>();
        for (Club c : selected) {
            String region = regionMapper.apply(c);
            if ("West".equals(region)) westClubs.add(c);
            else eastClubs.add(c);
        }
        westClubs.sort((a, b) -> Double.compare(b.getEloRating(), a.getEloRating()));
        eastClubs.sort((a, b) -> Double.compare(b.getEloRating(), a.getEloRating()));

        int westGroups = westClubs.size() / gs;
        int eastGroups = eastClubs.size() / gs;
        if (westGroups < 1 && eastGroups < 1) { groups.clear(); return; }

        List<Group> gs2 = new ArrayList<>();
        char label = 'A';
        Random rng = new Random(99);

        // Build West groups
        for (int i = 0; i < westGroups; i++) {
            gs2.add(new Group("Group " + label + " (West)", name, gs2.size()));
            label++;
        }
        buildGroupsFromPool(westClubs, gs2, gs2.size() - westGroups, gs2.size(), gs, rng);

        // Build East groups
        int eastStart = gs2.size();
        for (int i = 0; i < eastGroups; i++) {
            gs2.add(new Group("Group " + label + " (East)", name, gs2.size()));
            label++;
        }
        buildGroupsFromPool(eastClubs, gs2, eastStart, gs2.size(), gs, rng);

        groups = gs2;
    }

    private void buildGroupsFromPool(List<Club> pool, List<Group> allGroups,
                                      int startIdx, int endIdx, int gs, Random rng) {
        int groupsCount = endIdx - startIdx;
        if (groupsCount <= 0) return;

        int pots = gs;
        List<List<Club>> potLists = new ArrayList<>();
        for (int p = 0; p < pots; p++) potLists.add(new ArrayList<>());
        for (int i = 0; i < pool.size() && i < groupsCount * gs; i++) {
            int potIndex = Math.min(i / groupsCount, pots - 1);
            potLists.get(potIndex).add(pool.get(i));
        }

        for (int pot = 0; pot < pots; pot++) {
            List<Club> potClubs = potLists.get(pot);
            Collections.shuffle(potClubs, rng);
            for (Club club : potClubs) {
                int bestGroup = -1;
                for (int g = startIdx; g < endIdx; g++) {
                    if (allGroups.get(g).league.getClubs().size() >= gs) continue;
                    boolean conflict = false;
                    for (Club existing : allGroups.get(g).league.getClubs()) {
                        if (existing.getCountry().equals(club.getCountry())) {
                            conflict = true;
                            break;
                        }
                    }
                    if (!conflict) { bestGroup = g; break; }
                }
                if (bestGroup < 0) {
                    for (int g = startIdx; g < endIdx; g++) {
                        if (allGroups.get(g).league.getClubs().size() < gs) { bestGroup = g; break; }
                    }
                }
                if (bestGroup >= 0) {
                    allGroups.get(bestGroup).league.addClub(club);
                }
            }
        }
    }

    private void advanceFromRegionSplitGroups() {
        List<Group> westGroups = new ArrayList<>();
        List<Group> eastGroups = new ArrayList<>();
        for (Group g : groups) {
            if (isWestGroup(g)) {
                westGroups.add(g);
            } else {
                eastGroups.add(g);
            }
        }

        List<Club> westAdv = new ArrayList<>();
        List<Club> eastAdv = new ArrayList<>();

        if (tier == 3) {
            for (Group g : westGroups) {
                g.league.sortTable();
                if (!g.league.getClubs().isEmpty()) {
                    westAdv.add(g.league.getClubs().get(0));
                }
            }
            Club bestRunnerUp = pickBestRunnerUp(westGroups);
            if (bestRunnerUp != null) {
                westAdv.add(bestRunnerUp);
            }

            for (Group g : eastGroups) {
                g.league.sortTable();
                List<Club> clubs = g.league.getClubs();
                for (int i = 0; i < Math.min(2, clubs.size()); i++) {
                    eastAdv.add(clubs.get(i));
                }
            }
        } else {
            for (Group g : groups) {
                g.league.sortTable();
                List<Club> clubs = g.league.getClubs();
                List<Club> target = isWestGroup(g) ? westAdv : eastAdv;
                for (int i = 0; i < Math.min(2, clubs.size()); i++) {
                    target.add(clubs.get(i));
                }
            }
        }

        for (Club c : westAdv) {
            awardStageBonus(c, "round_of_16");
        }
        for (Club c : eastAdv) {
            awardStageBonus(c, "round_of_16");
        }

        phase = Phase.KNOCKOUT;
        knockoutRounds = buildRegionSplitKnockoutBracket(westAdv, eastAdv, twoLegKnockouts);
    }

    private Club pickBestRunnerUp(List<Group> groups) {
        List<Club> runners = new ArrayList<>();
        for (Group g : groups) {
            g.league.sortTable();
            List<Club> clubs = g.league.getClubs();
            if (clubs.size() > 1) {
                runners.add(clubs.get(1));
            }
        }
        if (runners.isEmpty()) return null;
        runners.sort((a, b) -> {
            if (b.getPoints() != a.getPoints()) return Integer.compare(b.getPoints(), a.getPoints());
            if (b.getGoalDifference() != a.getGoalDifference()) {
                return Integer.compare(b.getGoalDifference(), a.getGoalDifference());
            }
            if (b.getGoalsFor() != a.getGoalsFor()) return Integer.compare(b.getGoalsFor(), a.getGoalsFor());
            return Double.compare(b.getEloRating(), a.getEloRating());
        });
        return runners.get(0);
    }

    private List<Club> pickBestThirdPlaces(List<Club> candidates, int count) {
        if (candidates == null || candidates.isEmpty() || count <= 0) {
            return new ArrayList<>();
        }
        List<Club> ordered = new ArrayList<>(candidates);
        ordered.sort((a, b) -> {
            if (b.getPoints() != a.getPoints()) return Integer.compare(b.getPoints(), a.getPoints());
            if (b.getGoalDifference() != a.getGoalDifference()) {
                return Integer.compare(b.getGoalDifference(), a.getGoalDifference());
            }
            if (b.getGoalsFor() != a.getGoalsFor()) return Integer.compare(b.getGoalsFor(), a.getGoalsFor());
            return Double.compare(b.getEloRating(), a.getEloRating());
        });
        if (ordered.size() > count) {
            ordered = ordered.subList(0, count);
        }
        return new ArrayList<>(ordered);
    }

    private void applyGroupFinishBonuses() {
        for (Group g : groups) {
            g.league.sortTable();
            List<Club> clubs = g.league.getClubs();
            for (int i = 0; i < clubs.size(); i++) {
                Club club = clubs.get(i);
                if (i == 0) {
                    awardStageBonus(club, "main_stage_finish_top");
                } else if (i == 1) {
                    awardStageBonus(club, "main_stage_finish_mid_high");
                } else if (i == 2) {
                    awardStageBonus(club, "main_stage_finish_mid_low");
                }
            }
        }
    }

    private void applyLeagueFinishBonuses(List<SwissEntry> sorted) {
        if (sorted == null || sorted.isEmpty()) {
            return;
        }
        int size = sorted.size();
        int topCut = Math.max(1, (int) Math.floor(size * 0.25));
        int midHighCut = Math.max(topCut, (int) Math.floor(size * 0.50));
        int midLowCut = Math.max(midHighCut, (int) Math.floor(size * 0.75));

        for (int i = 0; i < size; i++) {
            Club club = sorted.get(i).club;
            if (i < topCut) {
                awardStageBonus(club, "main_stage_finish_top");
            } else if (i < midHighCut) {
                awardStageBonus(club, "main_stage_finish_mid_high");
            } else if (i < midLowCut) {
                awardStageBonus(club, "main_stage_finish_mid_low");
            }
        }
    }

    private boolean isWestGroup(Group g) {
        String nameLower = g.name.toLowerCase(Locale.ROOT);
        if (nameLower.contains("west")) return true;
        if (nameLower.contains("east")) return false;
        if (!g.league.getClubs().isEmpty() && regionMapper != null) {
            return "West".equals(regionMapper.apply(g.league.getClubs().get(0)));
        }
        return true;
    }

    private List<List<Match>> buildRegionSplitKnockoutBracket(List<Club> westTeams, List<Club> eastTeams, boolean twoLegs) {
        List<List<Match>> rounds = new ArrayList<>();
        List<Match> first = new ArrayList<>();
        if (westTeams.size() >= 2) {
            first.addAll(buildSeededRoundMatches(westTeams, twoLegs, false));
        }
        if (eastTeams.size() >= 2) {
            first.addAll(buildSeededRoundMatches(eastTeams, twoLegs, false));
        }
        rounds.add(first);

        int totalRounds = Math.max(roundsForTeams(westTeams.size()), roundsForTeams(eastTeams.size())) + 1;
        for (int i = 1; i < totalRounds; i++) {
            rounds.add(new ArrayList<>());
        }
        return rounds;
    }

    private int roundsForTeams(int size) {
        int rounds = 0;
        int remaining = Math.max(0, size);
        while (remaining > 1) {
            remaining /= 2;
            rounds++;
        }
        return rounds;
    }

    private List<Match> buildSeededRoundMatches(List<Club> teams, boolean twoLegs, boolean isFinal) {
        List<Club> seeded = new ArrayList<>(teams);
        seeded.sort((a, b) -> Double.compare(b.getEloRating(), a.getEloRating()));

        int pow2 = 1;
        while (pow2 < seeded.size()) pow2 <<= 1;
        if (seeded.size() < pow2) pow2 >>= 1;
        while (seeded.size() > pow2) seeded.remove(seeded.size() - 1);

        List<Match> matches = new ArrayList<>();
        int half = seeded.size() / 2;
        for (int i = 0; i < half; i++) {
            Club a = seeded.get(i);
            Club b = seeded.get(seeded.size() - 1 - i);
            if (twoLegs && !isFinal) {
                matches.add(new Match(a, b, true));
                matches.add(new Match(b, a, true));
            } else {
                matches.add(new Match(a, b, true));
            }
        }
        return matches;
    }

    // ==================== MATCH SIMULATION ====================

    public boolean simulateNextMatchWindow(boolean verbose) {
        if (isComplete()) return false;

        if (phase == Phase.LEAGUE_PHASE) {
            return simulateSwissRound(verbose);
        }

        if (phase == Phase.GROUPS) {
            return simulateGroupMatchday(verbose);
        }

        if (phase == Phase.KNOCKOUT) {
            return simulateKnockoutRound(verbose);
        }

        return false;
    }

    private boolean simulateSwissRound(boolean verbose) {
        if (swissRoundIndex >= swissTotalRounds) {
            advanceFromLeaguePhase();
            return true;
        }

        // Generate this round's matches if not already generated
        if (swissRoundIndex >= swissRounds.size()) {
            if (regionSplit && regionMapper != null) {
                List<Match> roundMatches = new ArrayList<>();
                roundMatches.addAll(generateSwissRound(sortSwissEntries(swissTableWest), swissRng, swissPlayedWest));
                roundMatches.addAll(generateSwissRound(sortSwissEntries(swissTableEast), swissRng, swissPlayedEast));
                swissRounds.add(roundMatches);
            } else {
                List<SwissEntry> sorted = getSwissTable();
                swissRounds.add(generateSwissRound(sorted, swissRng, swissPlayed));
            }
        }

        List<Match> round = swissRounds.get(swissRoundIndex);
        if (verbose) System.out.println("\n[" + name + "] League Phase Matchday " + (swissRoundIndex + 1));

        for (Match m : round) {
            MatchEngine.Score s = MatchEngine.play(m.getHome(), m.getAway(), true, false, Double.NaN, CONTINENTAL_ELO_K);
            m.setResult(s.homeGoals, s.awayGoals);
            awardMainStagePoints(m.getHome(), m.getAway(), m.getHomeGoals(), m.getAwayGoals());

            SwissEntry he = findEntry(m.getHome());
            SwissEntry ae = findEntry(m.getAway());
            if (he != null && ae != null) {
                he.gamesPlayed++;
                ae.gamesPlayed++;
                he.goalsFor += s.homeGoals;
                he.goalsAgainst += s.awayGoals;
                ae.goalsFor += s.awayGoals;
                ae.goalsAgainst += s.homeGoals;

                if (s.homeGoals > s.awayGoals) {
                    he.points += 3; he.wins++;
                    ae.losses++;
                } else if (s.awayGoals > s.homeGoals) {
                    ae.points += 3; ae.wins++;
                    he.losses++;
                } else {
                    he.points += 1; he.draws++;
                    ae.points += 1; ae.draws++;
                }
            }

            if (verbose) System.out.println("  " + m);
        }

        swissRoundIndex++;

        if (swissRoundIndex >= swissTotalRounds) {
            advanceFromLeaguePhase();
        }
        return true;
    }

    private void advanceFromLeaguePhase() {
        if (regionSplit && regionMapper != null) {
            advanceFromRegionSplitLeaguePhase();
            return;
        }

        applyLeagueFinishBonuses(getSwissTable());

        List<SwissEntry> sorted = getSwissTable();
        int directAdvance = Math.min(8, sorted.size());
        int playoffCount = Math.min(16, sorted.size() - directAdvance);

        List<Club> directR16 = new ArrayList<>();
        for (int i = 0; i < directAdvance; i++) {
            directR16.add(sorted.get(i).club);
        }

        if (playoffCount >= 2) {
            List<Club> playoffTeams = new ArrayList<>();
            for (int i = directAdvance; i < directAdvance + playoffCount; i++) {
                playoffTeams.add(sorted.get(i).club);
            }
            List<Club> playoffWinners = simulateSeededPlayoffRound(playoffTeams);
            List<Club> orderedWinners = orderPlayoffWinners(sorted, directAdvance, playoffCount, playoffWinners);
            List<Match> firstRound = buildSeededRoundMatches(directR16, orderedWinners, twoLegKnockouts);
            for (Club c : directR16) awardStageBonus(c, "round_of_16");
            for (Club c : orderedWinners) awardStageBonus(c, "round_of_16");
            phase = Phase.KNOCKOUT;
            knockoutRounds = buildPreseededBracket(firstRound, roundsForTeams(directR16.size() + orderedWinners.size()));
        } else {
            for (Club c : directR16) awardStageBonus(c, "round_of_16");
            phase = Phase.KNOCKOUT;
            knockoutRounds = buildKnockoutBracket(directR16, twoLegKnockouts);
        }
    }

    /**
     * AFC-style region split advance: top 8 from each region's league,
     * first knockout round is within-region (1v8, 2v7 etc),
     * then 8 winners cross-paired East vs West, normal bracket after.
     */
    private void advanceFromRegionSplitLeaguePhase() {
        List<SwissEntry> westSorted = getSwissTableWest();
        List<SwissEntry> eastSorted = getSwissTableEast();

        applyLeagueFinishBonuses(westSorted);
        applyLeagueFinishBonuses(eastSorted);

        int perRegion = Math.min(8, Math.min(westSorted.size(), eastSorted.size()));

        List<Club> westTop = new ArrayList<>();
        List<Club> eastTop = new ArrayList<>();
        for (int i = 0; i < perRegion; i++) {
            westTop.add(westSorted.get(i).club);
            eastTop.add(eastSorted.get(i).club);
        }

        for (Club c : westTop) awardStageBonus(c, "round_of_16");
        for (Club c : eastTop) awardStageBonus(c, "round_of_16");

        // First bracket round: within-region (1v8, 2v7, etc)
        List<Club> westWinners = simulatePlayoffRound(westTop);
        List<Club> eastWinners = simulatePlayoffRound(eastTop);

        // Cross-pair: randomly pair West winners vs East winners
        Collections.shuffle(westWinners, swissRng);
        Collections.shuffle(eastWinners, swissRng);
        List<Club> crossTeams = new ArrayList<>();
        int crossCount = Math.min(westWinners.size(), eastWinners.size());
        for (int i = 0; i < crossCount; i++) {
            crossTeams.add(westWinners.get(i));
            crossTeams.add(eastWinners.get(i));
        }

        phase = Phase.KNOCKOUT;
        // Build bracket from cross-paired teams (they're already interleaved W-E-W-E)
        knockoutRounds = buildCrossPairedBracket(crossTeams, twoLegKnockouts);
    }

    /** Build knockout bracket where odd/even indices are pre-paired opponents */
    private List<List<Match>> buildCrossPairedBracket(List<Club> teams, boolean twoLegs) {
        List<Match> first = new ArrayList<>();
        for (int i = 0; i + 1 < teams.size(); i += 2) {
            Club a = teams.get(i);
            Club b = teams.get(i + 1);
            if (twoLegs) {
                first.add(new Match(a, b, true));
                first.add(new Match(b, a, true));
            } else {
                first.add(new Match(a, b, true));
            }
        }

        List<List<Match>> rounds = new ArrayList<>();
        rounds.add(first);
        int remaining = teams.size() / 2;
        while (remaining > 1) {
            remaining /= 2;
            rounds.add(new ArrayList<>());
        }
        return rounds;
    }

    private List<Club> simulatePlayoffRound(List<Club> teams) {
        List<Club> sorted = new ArrayList<>(teams);
        sorted.sort((a, b) -> Double.compare(b.getEloRating(), a.getEloRating()));

        List<Club> winners = new ArrayList<>();
        int half = sorted.size() / 2;
        for (int i = 0; i < half; i++) {
            Club high = sorted.get(i);
            Club low = sorted.get(sorted.size() - 1 - i);

            if (twoLegKnockouts) {
                Match leg1 = new Match(high, low, true);
                Match leg2 = new Match(low, high, true);
                MatchEngine.Score s1 = MatchEngine.play(high, low, true, false, Double.NaN, CONTINENTAL_ELO_K);
                leg1.setResult(s1.homeGoals, s1.awayGoals);
                MatchEngine.Score s2 = MatchEngine.play(low, high, true, false, Double.NaN, CONTINENTAL_ELO_K);
                leg2.setResult(s2.homeGoals, s2.awayGoals);
                awardKnockoutPoints(high, low, s1.homeGoals, s1.awayGoals);
                awardKnockoutPoints(low, high, s2.homeGoals, s2.awayGoals);
                playoffHistory.add(leg1);
                playoffHistory.add(leg2);

                int aggHigh = s1.homeGoals + s2.awayGoals;
                int aggLow = s1.awayGoals + s2.homeGoals;

                if (aggHigh > aggLow) winners.add(high);
                else if (aggLow > aggHigh) winners.add(low);
                else winners.add(MatchEngine.resolveKnockoutWinner(high, low));
            } else {
                Match m = new Match(high, low, true);
                MatchEngine.Score s = MatchEngine.play(high, low, true, false, Double.NaN, CONTINENTAL_ELO_K);
                m.setResult(s.homeGoals, s.awayGoals);
                awardKnockoutPoints(high, low, s.homeGoals, s.awayGoals);
                playoffHistory.add(m);
                if (s.homeGoals > s.awayGoals) winners.add(high);
                else if (s.awayGoals > s.homeGoals) winners.add(low);
                else winners.add(MatchEngine.resolveKnockoutWinner(high, low));
            }
        }
        return winners;
    }

    private List<Club> simulateSeededPlayoffRound(List<Club> teams) {
        List<Club> winners = new ArrayList<>();
        if (teams == null || teams.size() < 2) {
            if (teams != null) winners.addAll(teams);
            return winners;
        }

        int half = teams.size() / 2;
        for (int i = 0; i < half; i++) {
            Club high = teams.get(i);
            Club low = teams.get(teams.size() - 1 - i);

            if (twoLegKnockouts) {
                Match leg1 = new Match(high, low, true);
                Match leg2 = new Match(low, high, true);
                MatchEngine.Score s1 = MatchEngine.play(high, low, true, false, Double.NaN, CONTINENTAL_ELO_K);
                leg1.setResult(s1.homeGoals, s1.awayGoals);
                MatchEngine.Score s2 = MatchEngine.play(low, high, true, false, Double.NaN, CONTINENTAL_ELO_K);
                leg2.setResult(s2.homeGoals, s2.awayGoals);
                awardKnockoutPoints(high, low, s1.homeGoals, s1.awayGoals);
                awardKnockoutPoints(low, high, s2.homeGoals, s2.awayGoals);
                playoffHistory.add(leg1);
                playoffHistory.add(leg2);

                int aggHigh = s1.homeGoals + s2.awayGoals;
                int aggLow = s1.awayGoals + s2.homeGoals;
                if (aggHigh > aggLow) winners.add(high);
                else if (aggLow > aggHigh) winners.add(low);
                else winners.add(MatchEngine.resolveKnockoutWinner(high, low));
            } else {
                Match m = new Match(high, low, true);
                MatchEngine.Score s = MatchEngine.play(high, low, true, false, Double.NaN, CONTINENTAL_ELO_K);
                m.setResult(s.homeGoals, s.awayGoals);
                awardKnockoutPoints(high, low, s.homeGoals, s.awayGoals);
                playoffHistory.add(m);

                if (s.homeGoals > s.awayGoals) winners.add(high);
                else if (s.awayGoals > s.homeGoals) winners.add(low);
                else winners.add(MatchEngine.resolveKnockoutWinner(high, low));
            }
        }
        return winners;
    }

    private List<Club> orderPlayoffWinners(List<SwissEntry> sorted, int directAdvance,
                                           int playoffCount, List<Club> playoffWinners) {
        Map<Club, Integer> seedMap = new HashMap<>();
        for (int i = directAdvance; i < Math.min(sorted.size(), directAdvance + playoffCount); i++) {
            seedMap.put(sorted.get(i).club, i + 1);
        }

        Map<Integer, Club> winnerBySeed = new HashMap<>();
        for (Club c : playoffWinners) {
            Integer seed = seedMap.get(c);
            if (seed != null) {
                winnerBySeed.put(seed, c);
            }
        }

        List<Club> ordered = new ArrayList<>();
        Set<Club> used = new HashSet<>();
        if (playoffCount >= 16 && seedMap.size() >= 8) {
            int[][] pairs = {
                {16, 17}, {15, 18}, {14, 19}, {13, 20},
                {12, 21}, {11, 22}, {10, 23}, {9, 24}
            };
            for (int[] pair : pairs) {
                Club winner = winnerBySeed.get(pair[0]);
                if (winner == null) {
                    winner = winnerBySeed.get(pair[1]);
                }
                if (winner != null) {
                    ordered.add(winner);
                    used.add(winner);
                }
            }
        }

        if (ordered.size() < playoffWinners.size()) {
            playoffWinners.sort((a, b) -> Integer.compare(seedMap.getOrDefault(a, 999),
                seedMap.getOrDefault(b, 999)));
            for (Club c : playoffWinners) {
                if (!used.contains(c)) {
                    ordered.add(c);
                }
            }
        }

        return ordered;
    }

    private List<Match> buildSeededRoundMatches(List<Club> directSeeds,
                                                List<Club> playoffWinners,
                                                boolean twoLegs) {
        List<Match> matches = new ArrayList<>();
        int count = Math.min(directSeeds.size(), playoffWinners.size());
        for (int i = 0; i < count; i++) {
            Club high = directSeeds.get(i);
            Club low = playoffWinners.get(i);
            if (twoLegs) {
                matches.add(new Match(high, low, true));
                matches.add(new Match(low, high, true));
            } else {
                matches.add(new Match(high, low, true));
            }
        }
        return matches;
    }

    private List<List<Match>> buildPreseededBracket(List<Match> firstRound, int totalRounds) {
        List<List<Match>> rounds = new ArrayList<>();
        rounds.add(firstRound);
        int roundsToAdd = Math.max(1, totalRounds) - 1;
        for (int i = 0; i < roundsToAdd; i++) {
            rounds.add(new ArrayList<>());
        }
        return rounds;
    }

    private boolean simulateGroupMatchday(boolean verbose) {
        if (groupMatchday >= groupMatchdayTarget) {
            applyGroupFinishBonuses();

            if (regionSplitKnockout && regionSplit && regionMapper != null) {
                advanceFromRegionSplitGroups();
                return true;
            }

            List<Club> adv = new ArrayList<>();
            List<Club> thirdPlaceCandidates = new ArrayList<>();
            int advPerGroup = 2;
            for (Group g : groups) {
                g.league.sortTable();
                List<Club> clubs = g.league.getClubs();
                for (int i = 0; i < Math.min(advPerGroup, clubs.size()); i++) {
                    adv.add(clubs.get(i));
                }
                if (clubs.size() > advPerGroup) {
                    thirdPlaceCandidates.add(clubs.get(advPerGroup));
                }
            }

            if (confederation == Confederation.AFRICA && tier == 1 && groups.size() >= 6) {
                adv.addAll(pickBestThirdPlaces(thirdPlaceCandidates, 2));
            }

            // Award R16 bonus to advancing clubs
            for (Club c : adv) {
                awardStageBonus(c, "round_of_16");
            }

            phase = Phase.KNOCKOUT;
            knockoutRounds = buildKnockoutBracket(adv, twoLegKnockouts);
            return true;
        }

        if (verbose) System.out.println("\n[" + name + "] Group Matchday " + (groupMatchday + 1));
        for (Group g : groups) {
            List<Match> played = g.league.simulateNextRound(false);
            for (Match m : played) {
                awardMainStagePoints(m.getHome(), m.getAway(), m.getHomeGoals(), m.getAwayGoals());
            }
            if (verbose && !played.isEmpty()) {
                // FIX: Guard against empty league (prevent IndexOutOfBoundsException)
                List<Club> topClubs = g.league.getTopN(1);
                String leader = !topClubs.isEmpty() ? topClubs.get(0).getName() : "N/A";
                System.out.println("  " + g.name + ": " + leader + " leading");
            }
        }
        groupMatchday++;
        return true;
    }

    private boolean simulateKnockoutRound(boolean verbose) {
        if (knockoutRounds == null || knockoutRounds.isEmpty()) return false;
        if (knockoutIndex >= knockoutRounds.size()) return false;

        List<Match> round = knockoutRounds.get(knockoutIndex);
        boolean isFinal = (knockoutIndex == knockoutRounds.size() - 1);

        if (round.isEmpty()) return false;

        if (verbose) System.out.println("\n[" + name + "] Knockout Round " + (knockoutIndex + 1) + " (" + round.size() + " matches)");

        List<Club> winners = new ArrayList<>();

        if (twoLegKnockouts && !isFinal) {
            for (int i = 0; i + 1 < round.size(); i += 2) {
                Match leg1 = round.get(i);
                Match leg2 = round.get(i + 1);

                MatchEngine.Score s1 = MatchEngine.play(leg1.getHome(), leg1.getAway(), true, false, Double.NaN, CONTINENTAL_ELO_K);
                leg1.setResult(s1.homeGoals, s1.awayGoals);
                awardKnockoutPoints(leg1.getHome(), leg1.getAway(), leg1.getHomeGoals(), leg1.getAwayGoals());

                MatchEngine.Score s2 = MatchEngine.play(leg2.getHome(), leg2.getAway(), true, false, Double.NaN, CONTINENTAL_ELO_K);
                leg2.setResult(s2.homeGoals, s2.awayGoals);
                awardKnockoutPoints(leg2.getHome(), leg2.getAway(), leg2.getHomeGoals(), leg2.getAwayGoals());

                int aggHome = leg1.getHomeGoals() + leg2.getAwayGoals();
                int aggAway = leg1.getAwayGoals() + leg2.getHomeGoals();
                Club homeTeam = leg1.getHome();
                Club awayTeam = leg1.getAway();

                Club winner;
                if (aggHome > aggAway) winner = homeTeam;
                else if (aggAway > aggHome) winner = awayTeam;
                else winner = MatchEngine.resolveKnockoutWinner(homeTeam, awayTeam);
                winners.add(winner);

                if (verbose) {
                    System.out.println("  " + leg1);
                    System.out.println("  " + leg2 + " | Agg " + aggHome + "-" + aggAway + " -> " + winner.getName());
                }
            }
        } else {
            for (Match m : round) {
                boolean homeAdv = !(isFinal && neutralFinal);
                MatchEngine.Score s = MatchEngine.play(m.getHome(), m.getAway(), homeAdv, false, Double.NaN, CONTINENTAL_ELO_K);
                m.setResult(s.homeGoals, s.awayGoals);
                awardKnockoutPoints(m.getHome(), m.getAway(), m.getHomeGoals(), m.getAwayGoals());

                Club winner;
                if (m.getHomeGoals() > m.getAwayGoals()) winner = m.getHome();
                else if (m.getAwayGoals() > m.getHomeGoals()) winner = m.getAway();
                else winner = MatchEngine.resolveKnockoutWinner(m.getHome(), m.getAway());
                winners.add(winner);

                if (verbose) System.out.println("  " + m + (m.getHomeGoals() == m.getAwayGoals() ? " (pens)" : ""));
            }
        }

        knockoutHistory.add(new ArrayList<>(round));

        // Award stage bonuses based on rounds remaining (counting from final backwards)
        int roundsFromFinal = knockoutRounds.size() - 1 - knockoutIndex;
        // roundsFromFinal: 0=Final, 1=SF, 2=QF
        if (roundsFromFinal == 2) {
            for (Club w : winners) awardStageBonus(w, "quarter_final");
        } else if (roundsFromFinal == 1) {
            for (Club w : winners) awardStageBonus(w, "semi_final");
        }
        if (isFinal) {
            // Both finalists get final bonus; award to both participants
            if (twoLegKnockouts) {
                for (int i = 0; i + 1 < round.size(); i += 2) {
                    awardStageBonus(round.get(i).getHome(), "final");
                    awardStageBonus(round.get(i).getAway(), "final");
                }
            } else {
                for (Match m : round) {
                    awardStageBonus(m.getHome(), "final");
                    awardStageBonus(m.getAway(), "final");
                }
            }
        }

        if (winners.size() == 1) {
            champion = winners.get(0);
            awardStageBonus(champion, "champion");
            phase = Phase.DONE;
            if (verbose) System.out.println("Champion: " + champion.getName());
            return true;
        }

        knockoutIndex++;
        if (knockoutIndex < knockoutRounds.size()) {
            knockoutRounds.set(knockoutIndex, buildNextKnockoutRound(winners, twoLegKnockouts, knockoutIndex == knockoutRounds.size() - 1));
        } else {
            knockoutRounds.add(buildNextKnockoutRound(winners, twoLegKnockouts, true));
        }
        return true;
    }

    // ==================== REPORTING ====================

    public String getCurrentStageName() {
        switch (phase) {
            case LEAGUE_PHASE: return "League Phase (Matchday " + swissRoundIndex + "/" + swissTotalRounds + ")";
            case GROUPS: return "Group Stage (Matchday " + groupMatchday + "/" + groupMatchdayTarget + ")";
            case KNOCKOUT: return "Knockout Round " + (knockoutIndex + 1);
            case DONE: return "Completed";
            default: return "Unknown";
        }
    }

    public List<String> getStandingsLines(int maxLines) {
        List<String> lines = new ArrayList<>();
        if (phase == Phase.LEAGUE_PHASE
                || ((phase == Phase.KNOCKOUT || phase == Phase.DONE) && !swissTable.isEmpty())) {
            if (regionSplit && !swissTableWest.isEmpty()) {
                // Show separate West and East tables
                lines.add("West Region:");
                addSwissTableLines(lines, getSwissTableWest(), maxLines);
                lines.add("");
                lines.add("East Region:");
                addSwissTableLines(lines, getSwissTableEast(), maxLines);
            } else {
                addSwissTableLines(lines, getSwissTable(), maxLines);
            }
        } else if (phase == Phase.GROUPS || (phase == Phase.DONE && !groups.isEmpty())) {
            for (Group g : groups) {
                lines.add(g.name + ":");
                lines.add(String.format("  %-4s %-40s %3s %3s %3s %3s %4s %3s %3s %3s",
                    "Pos", "Club", "Pld", "W", "D", "L", "GD", "GF", "GA", "Pts"));
                g.league.sortTable();
                List<Club> clubs = g.league.getClubs();
                for (int i = 0; i < Math.min(maxLines, clubs.size()); i++) {
                    Club c = clubs.get(i);
                    lines.add(String.format("  %-4d %-40s %3d %3d %3d %3d %4d %3d %3d %3d",
                        i + 1, truncate(c.getNameWithCountry(), 40),
                        c.getGamesPlayed(), c.getWins(), c.getDraws(), c.getLosses(),
                        c.getGoalDifference(), c.getGoalsFor(), c.getGoalsAgainst(), c.getPoints()));
                }
                lines.add("");
            }
        }

        // Show playoff matches if any
        if (!playoffHistory.isEmpty()) {
            lines.add("");
            lines.add("Knockout Playoffs:");
            for (Match m : playoffHistory) {
                lines.add("  " + formatContinentalMatch(m));
            }
        }

        // Show knockout bracket results
        if (!knockoutHistory.isEmpty()) {
            String[] roundNames = {"Final", "Semi-Finals", "Quarter-Finals", "Round of 16", "Round of 32"};
            int totalRounds = knockoutHistory.size();
            for (int r = 0; r < totalRounds; r++) {
                int fromFinal = totalRounds - 1 - r;
                String rName = fromFinal < roundNames.length ? roundNames[fromFinal] : "Round " + (r + 1);
                lines.add("");
                lines.add(rName + ":");
                List<Match> round = knockoutHistory.get(r);
                for (Match m : round) {
                    lines.add("  " + formatContinentalMatch(m));
                }
            }
        }

        return lines;
    }

    private void addSwissTableLines(List<String> lines, List<SwissEntry> sorted, int maxLines) {
        lines.add(String.format("%-4s %-40s %3s %3s %3s %3s %4s %3s %3s %3s",
            "Pos", "Club", "Pld", "W", "D", "L", "GD", "GF", "GA", "Pts"));
        for (int i = 0; i < Math.min(maxLines, sorted.size()); i++) {
            SwissEntry e = sorted.get(i);
            lines.add(String.format("%-4d %-40s %3d %3d %3d %3d %4d %3d %3d %3d",
                i + 1, truncate(e.club.getNameWithCountry(), 40), e.gamesPlayed,
                e.wins, e.draws, e.losses, e.goalDifference(),
                e.goalsFor, e.goalsAgainst, e.points));
        }
    }

    private String formatContinentalMatch(Match m) {
        String h = m.getHome().getNameWithCountry();
        String a = m.getAway().getNameWithCountry();
        if (!m.hasResult()) return h + " vs " + a;
        return h + " " + m.getHomeGoals() + " - " + m.getAwayGoals() + " " + a;
    }

    private String truncate(String s, int n) {
        if (s.length() <= n) return s;
        return s.substring(0, n - 3) + "...";
    }

    // ==================== HELPERS ====================

    private TierCoefficientProfile coeffProfile() {
        if (tier == 1) {
            return new TierCoefficientProfile(1.0, 0.5, 0.0, 3.0,
                    2.0, 1.0, 0.0,
                    3.0, 2.0, 1.0,
                    1.0, 1.0, 1.0, 1.0, 1.0);
        }
        if (tier == 2) {
            return new TierCoefficientProfile(1.0, 0.5, 0.0, 2.0,
                    2.0, 1.0, 0.0,
                    2.0, 1.5, 0.5,
                    0.75, 0.75, 0.75, 0.75, 0.75);
        }
        return new TierCoefficientProfile(1.0, 0.5, 0.0, 1.0,
                2.0, 1.0, 0.0,
                1.0, 0.5, 0.25,
                0.5, 0.5, 0.5, 0.5, 0.5);
    }

    private void awardQualifierPoints(Club home, Club away, int homeGoals, int awayGoals) {
        TierCoefficientProfile profile = coeffProfile();
        applyResultPoints(home, away, homeGoals, awayGoals,
                profile.qualifierWin, profile.qualifierDraw, profile.qualifierLoss,
                PointsBucket.QUALIFIER);
    }

    private void awardMainStagePoints(Club home, Club away, int homeGoals, int awayGoals) {
        TierCoefficientProfile profile = coeffProfile();
        applyResultPoints(home, away, homeGoals, awayGoals,
                profile.mainStageMatchWin, profile.mainStageMatchDraw, profile.mainStageMatchLoss,
                PointsBucket.MAIN_STAGE);
    }

    private void awardKnockoutPoints(Club home, Club away, int homeGoals, int awayGoals) {
        TierCoefficientProfile profile = coeffProfile();
        applyResultPoints(home, away, homeGoals, awayGoals,
                profile.mainStageMatchWin, profile.mainStageMatchDraw, profile.mainStageMatchLoss,
                PointsBucket.KNOCKOUT);
    }

    private enum PointsBucket { QUALIFIER, MAIN_STAGE, KNOCKOUT, BONUS }

    private String participantKey(Club club) {
        String country = club.getCountry() == null ? "" : club.getCountry();
        String league = club.getDomesticLeagueName() == null ? "" : club.getDomesticLeagueName();
        return club.getName() + "|" + country + "|" + league;
    }

    private void registerParticipant(Club club) {
        if (club == null || club.getCountry() == null) {
            return;
        }

        String country = club.getCountry();
        String participantKey = participantKey(club);

        Set<String> participants = countryParticipants.computeIfAbsent(country, k -> new LinkedHashSet<>());
        participants.add(participantKey);

        Map<String, Double> pointsByClub = countryClubPointBreakdown.computeIfAbsent(country, k -> new LinkedHashMap<>());
        pointsByClub.putIfAbsent(participantKey, 0.0);

        CountryCoeffData data = countryCoefficients.computeIfAbsent(country, k -> new CountryCoeffData());
        data.teamCount = participants.size();
    }

    private void applyResultPoints(Club home, Club away, int homeGoals, int awayGoals,
                                   double winPts, double drawPts, double lossPts,
                                   PointsBucket bucket) {
        if (homeGoals > awayGoals) {
            addPoints(home, bucket, winPts);
            addPoints(away, bucket, lossPts);
        } else if (awayGoals > homeGoals) {
            addPoints(away, bucket, winPts);
            addPoints(home, bucket, lossPts);
        } else {
            addPoints(home, bucket, drawPts);
            addPoints(away, bucket, drawPts);
        }
    }

    private void addPoints(Club club, PointsBucket bucket, double pts) {
        if (club == null || club.getCountry() == null || pts <= 0.0) {
            return;
        }

        registerParticipant(club);
        String country = club.getCountry();
        String participantKey = participantKey(club);

        CountryCoeffData data = countryCoefficients.computeIfAbsent(country, k -> new CountryCoeffData());
        switch (bucket) {
            case QUALIFIER:
                data.qualifierPoints += pts;
                break;
            case MAIN_STAGE:
                data.mainStagePoints += pts;
                break;
            case KNOCKOUT:
                data.knockoutPoints += pts;
                break;
            case BONUS:
                data.bonusPoints += pts;
                break;
        }

        countryClubPointBreakdown
                .computeIfAbsent(country, k -> new LinkedHashMap<>())
                .merge(participantKey, pts, Double::sum);
        
        // Award points to the club's individual coefficient
        club.addContinentalCoefficientPoints(pts);
    }

    public void awardStageBonus(Club club, String stageName) {
        TierCoefficientProfile profile = coeffProfile();
        double bonus = 0.0;
        switch (stageName) {
            case "main_stage":
                bonus = profile.mainStageEntry;
                break;
            case "main_stage_finish_top":
                bonus = profile.topFinishBonus;
                break;
            case "main_stage_finish_mid_high":
                bonus = profile.midHighFinishBonus;
                break;
            case "main_stage_finish_mid_low":
                bonus = profile.midLowFinishBonus;
                break;
            case "round_of_16":
                bonus = profile.roundOf16Bonus;
                break;
            case "quarter_final":
                bonus = profile.quarterFinalBonus;
                break;
            case "semi_final":
                bonus = profile.semiFinalBonus;
                break;
            case "final":
                bonus = profile.finalBonus;
                break;
            case "champion":
                bonus = profile.championBonus;
                break;
            default:
                break;
        }
        addPoints(club, PointsBucket.BONUS, bonus);
    }

    private List<List<Match>> buildKnockoutBracket(List<Club> teams, boolean twoLegs) {
        List<Club> seeded = new ArrayList<>(teams);
        seeded.sort((a, b) -> Double.compare(b.getEloRating(), a.getEloRating()));

        int pow2 = 1;
        while (pow2 < seeded.size()) pow2 <<= 1;
        if (seeded.size() < pow2) {
            // Trim to largest power-of-2 that fits (lower-ranked teams are eliminated)
            pow2 >>= 1;
        }
        while (seeded.size() > pow2) seeded.remove(seeded.size() - 1);

        List<Match> first = new ArrayList<>();
        int half = seeded.size() / 2;
        for (int i = 0; i < half; i++) {
            Club a = seeded.get(i);
            Club b = seeded.get(seeded.size() - 1 - i);
            if (twoLegs) {
                first.add(new Match(a, b, true));
                first.add(new Match(b, a, true));
            } else {
                first.add(new Match(a, b, true));
            }
        }

        List<List<Match>> rounds = new ArrayList<>();
        rounds.add(first);
        int remaining = seeded.size();
        while (remaining > 1) {
            remaining /= 2;
            rounds.add(new ArrayList<>());
        }
        return rounds;
    }

    private List<Match> buildNextKnockoutRound(List<Club> winners, boolean twoLegs, boolean isFinal) {
        if (regionSplitKnockout && regionSplit && regionMapper != null) {
            List<Club> west = new ArrayList<>();
            List<Club> east = new ArrayList<>();
            for (Club c : winners) {
                String region = regionMapper.apply(c);
                if ("West".equals(region)) {
                    west.add(c);
                } else {
                    east.add(c);
                }
            }

            if (west.size() == 1 && east.size() == 1) {
                List<Club> finalTeams = new ArrayList<>();
                finalTeams.add(west.get(0));
                finalTeams.add(east.get(0));
                return buildSeededRoundMatches(finalTeams, twoLegs, isFinal);
            }

            List<Match> matches = new ArrayList<>();
            if (west.size() > 1) {
                matches.addAll(buildSeededRoundMatches(west, twoLegs, isFinal));
            }
            if (east.size() > 1) {
                matches.addAll(buildSeededRoundMatches(east, twoLegs, isFinal));
            }
            return matches;
        }

        List<Club> list = new ArrayList<>(winners);
        list.sort((a, b) -> Double.compare(b.getEloRating(), a.getEloRating()));

        List<Match> out = new ArrayList<>();
        int half = list.size() / 2;
        for (int i = 0; i < half; i++) {
            Club a = list.get(i);
            Club b = list.get(list.size() - 1 - i);
            if (twoLegs && !isFinal) {
                out.add(new Match(a, b, true));
                out.add(new Match(b, a, true));
            } else {
                out.add(new Match(a, b, true));
            }
        }
        return out;
    }
}
