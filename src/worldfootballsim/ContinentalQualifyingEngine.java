package worldfootballsim;

import java.util.*;

final class UefaQualifyingEngine {

    public static class QualifyingResult {
        public final List<Club> winners;
        public final List<Club> losers;
        public final List<Match> matches;

        QualifyingResult(List<Club> winners, List<Club> losers, List<Match> matches) {
            this.winners = winners;
            this.losers = losers;
            this.matches = matches;
        }
    }


    public static class UefaSeasonAllocation {
        public final List<Club> uclLeaguePhase;
        public final List<Club> uelLeaguePhase;
        public final List<Club> ueclLeaguePhase;  
        public final List<Match> allQualifyingMatches;
        public final Map<String, Map<String, List<Match>>> qualifyingMatchesByCompetition;

        UefaSeasonAllocation(List<Club> ucl, List<Club> uel, List<Club> uecl,
                             List<Match> allMatches,
                             Map<String, Map<String, List<Match>>> matchesByCompetition) {
            this.uclLeaguePhase = ucl;
            this.uelLeaguePhase = uel;
            this.ueclLeaguePhase = uecl;
            this.allQualifyingMatches = allMatches;
            this.qualifyingMatchesByCompetition = matchesByCompetition;
        }
    }

    /**
     * Run all UEFA qualifying and produce final entrant lists for UCL/UEL/UECL.
     *
     * @param ranked         UEFA countries sorted by coefficient (excluded already filtered)
     * @param getClubsForCountry function to get ranked clubs for a country
     * @param bothExcluded   true if BOTH Russia AND Israel are excluded
     * @param oneExcluded    true if exactly ONE of Russia/Israel is excluded
     */
    public static UefaSeasonAllocation allocateAndQualify(
            List<CountryAssociation> ranked,
            java.util.function.Function<CountryAssociation, List<Club>> getClubsForCountry,
            boolean bothExcluded, boolean oneExcluded) {

        int totalNations = ranked.size();
        List<Match> allMatches = new ArrayList<>();
        Map<String, Map<String, List<Match>>> matchesByCompetition = new LinkedHashMap<>();
        Set<Club> globalQualified = new HashSet<>();

        List<Club> uclDirect = new ArrayList<>();

        for (int i = 0; i < Math.min(totalNations, 11); i++) {
            CountryAssociation ca = ranked.get(i);
            List<Club> clubs = getClubsForCountry.apply(ca);
            int rank = i + 1; // 1-based

            int slots;
            if (rank == 1) {
                slots = 5;
            } else if (rank >= 2 && rank <= 4) {
                slots = (rank == 2 && (oneExcluded || bothExcluded)) ? 5 : 4;
            } else if (rank == 5) {
                slots = 3;
            } else if (rank == 6) {
                slots = 2;
            } else {
                slots = (rank == 7 && bothExcluded) ? 2 : 1;
            }

            int added = 0;
            for (Club c : clubs) {
                if (added >= slots) break;
                if (globalQualified.contains(c)) continue;
                uclDirect.add(c);
                globalQualified.add(c);
                added++;
            }
        }

        List<Club> cpPoSeeds = new ArrayList<>();
        for (int i = 11; i < Math.min(15, totalNations); i++) {
            Club champ = getChampion(ranked.get(i), getClubsForCountry, globalQualified);
            if (champ != null) {
                cpPoSeeds.add(champ);
                globalQualified.add(champ);
            }
        }

        int cpQ2SeedStart = 17;
        int cpQ2SeedEnd = 26;

        List<Club> cpQ2Seeds = new ArrayList<>();
        for (int i = cpQ2SeedStart - 1; i < Math.min(cpQ2SeedEnd, totalNations); i++) {
            Club champ = getChampion(ranked.get(i), getClubsForCountry, globalQualified);
            if (champ != null) {
                cpQ2Seeds.add(champ);
                globalQualified.add(champ);
            }
        }

        List<Club> cpQ1Entrants = new ArrayList<>();
        for (int i = 26; i < totalNations; i++) {
            Club champ = getChampion(ranked.get(i), getClubsForCountry, globalQualified);
            if (champ != null) {
                cpQ1Entrants.add(champ);
                globalQualified.add(champ);
            }
        }

        QualifyingResult cpQ1 = simulateQualifyingRound("UCL CP Q1", cpQ1Entrants, true);
        addMatches("uefa_ucl", "Champions Path Q1", cpQ1.matches, matchesByCompetition, allMatches);

        List<Club> cpQ1LosersToUeclQ2 = new ArrayList<>();
        List<Club> cpQ1LosersToUeclQ3 = new ArrayList<>();
        for (int i = 0; i < cpQ1.losers.size(); i++) {
            if (cpQ1LosersToUeclQ2.size() < 12) {
                cpQ1LosersToUeclQ2.add(cpQ1.losers.get(i));
            } else {
                cpQ1LosersToUeclQ3.add(cpQ1.losers.get(i));
            }
        }

        List<Club> cpQ2Entrants = new ArrayList<>();
        cpQ2Entrants.addAll(cpQ1.winners);
        cpQ2Entrants.addAll(cpQ2Seeds);
        QualifyingResult cpQ2 = simulateQualifyingRound("UCL CP Q2", cpQ2Entrants, true);
        addMatches("uefa_ucl", "Champions Path Q2", cpQ2.matches, matchesByCompetition, allMatches);

        List<Club> cpQ2LosersToUelQ3 = new ArrayList<>(cpQ2.losers);

        QualifyingResult cpQ3 = simulateQualifyingRound("UCL CP Q3", cpQ2.winners, true);
        addMatches("uefa_ucl", "Champions Path Q3", cpQ3.matches, matchesByCompetition, allMatches);

        List<Club> cpQ3LosersToUelPo = new ArrayList<>(cpQ3.losers);

        List<Club> cpPoEntrants = new ArrayList<>();
        cpPoEntrants.addAll(cpQ3.winners);
        cpPoEntrants.addAll(cpPoSeeds);
        QualifyingResult cpPo = simulateQualifyingRound("UCL CP PO", cpPoEntrants, true);
        addMatches("uefa_ucl", "Champions Path Play-off", cpPo.matches, matchesByCompetition, allMatches);

        List<Club> uclFromChampionsPath = new ArrayList<>(cpPo.winners);

        List<Club> cpPoLosersToUel = new ArrayList<>(cpPo.losers);

        List<Club> lpQ2Entrants = new ArrayList<>();
        for (int i = 9; i < Math.min(15, totalNations); i++) {
            Club runner = getNthPlace(ranked.get(i), getClubsForCountry, globalQualified, 2);
            if (runner != null) {
                lpQ2Entrants.add(runner);
                globalQualified.add(runner);
            }
        }

        QualifyingResult lpQ2 = simulateQualifyingRound("UCL LP Q2", lpQ2Entrants, true);
        addMatches("uefa_ucl", "League Path Q2", lpQ2.matches, matchesByCompetition, allMatches);

        List<Club> lpQ2LosersToUelQ3 = new ArrayList<>(lpQ2.losers);

        List<Club> lpQ3Seeds = new ArrayList<>();
        if (totalNations > 4) {
            Club r5n4 = getNthPlace(ranked.get(4), getClubsForCountry, globalQualified, 4);
            if (r5n4 != null) { lpQ3Seeds.add(r5n4); globalQualified.add(r5n4); }
        }
        if (totalNations > 5) {
            Club r6n3 = getNthPlace(ranked.get(5), getClubsForCountry, globalQualified, 3);
            if (r6n3 != null) { lpQ3Seeds.add(r6n3); globalQualified.add(r6n3); }
        }
        if (totalNations > 6) {
            int r7pos = bothExcluded ? 3 : 2;
            Club r7n = getNthPlace(ranked.get(6), getClubsForCountry, globalQualified, r7pos);
            if (r7n != null) { lpQ3Seeds.add(r7n); globalQualified.add(r7n); }
        }
        for (int i = 7; i < Math.min(9, totalNations); i++) {
            Club r89n2 = getNthPlace(ranked.get(i), getClubsForCountry, globalQualified, 2);
            if (r89n2 != null) { lpQ3Seeds.add(r89n2); globalQualified.add(r89n2); }
        }

        List<Club> lpQ3Entrants = new ArrayList<>();
        lpQ3Entrants.addAll(lpQ2.winners);
        lpQ3Entrants.addAll(lpQ3Seeds);

        if (lpQ3Entrants.size() % 2 != 0 && lpQ3Entrants.size() > 1) {
            lpQ3Entrants.remove(lpQ3Entrants.size() - 1);
        }

        QualifyingResult lpQ3 = simulateQualifyingRound("UCL LP Q3", lpQ3Entrants, true);
        addMatches("uefa_ucl", "League Path Q3", lpQ3.matches, matchesByCompetition, allMatches);

        List<Club> lpQ3LosersToUel = new ArrayList<>(lpQ3.losers);

        QualifyingResult lpPo = simulateQualifyingRound("UCL LP PO", lpQ3.winners, true);
        addMatches("uefa_ucl", "League Path Play-off", lpPo.matches, matchesByCompetition, allMatches);

        List<Club> uclFromLeaguePath = new ArrayList<>(lpPo.winners);

        List<Club> lpPoLosersToUel = new ArrayList<>(lpPo.losers);

        List<Club> uclLeaguePhase = new ArrayList<>();
        uclLeaguePhase.addAll(uclDirect);
        uclLeaguePhase.addAll(uclFromChampionsPath);
        uclLeaguePhase.addAll(uclFromLeaguePath);
        while (uclLeaguePhase.size() > 36) {
            uclLeaguePhase.remove(uclLeaguePhase.size() - 1);
        }
        List<Club> uelDirect = new ArrayList<>();
        for (int i = 0; i < Math.min(4, totalNations); i++) {
            CountryAssociation ca = ranked.get(i);
            addUelDirectSlots(ca, getClubsForCountry, globalQualified, uelDirect, 2);
        }
        for (int i = 4; i < Math.min(6, totalNations); i++) {
            CountryAssociation ca = ranked.get(i);
            addUelDirectSlots(ca, getClubsForCountry, globalQualified, uelDirect, 1);
        }
        for (int i = 6; i < Math.min(15, totalNations); i++) {
            CountryAssociation ca = ranked.get(i);
            addUelDirectSlots(ca, getClubsForCountry, globalQualified, uelDirect, 1);
        }

        uelDirect.addAll(cpPoLosersToUel);
        uelDirect.addAll(lpQ3LosersToUel);
        uelDirect.addAll(lpPoLosersToUel);
        List<Club> uelQualEntrants = new ArrayList<>();
        for (int i = 15; i < totalNations; i++) {
            Club cw = getCupWinner(ranked.get(i), globalQualified);
            if (cw != null) { uelQualEntrants.add(cw); globalQualified.add(cw); }
            Club n2 = getNthPlace(ranked.get(i), getClubsForCountry, globalQualified, 2);
            if (n2 != null) { uelQualEntrants.add(n2); globalQualified.add(n2); }
        }

        uelQualEntrants.addAll(cpQ2LosersToUelQ3);
        uelQualEntrants.addAll(cpQ3LosersToUelPo);
        uelQualEntrants.addAll(lpQ2LosersToUelQ3);

        int uelRemaining = Math.max(0, 36 - uelDirect.size());
        List<Club> uelQualifiers = runQualifyingToTarget("UEL Qualifying", uelQualEntrants, uelRemaining,
            matchesByCompetition, allMatches, "uefa_uel");
        uelDirect.addAll(uelQualifiers);

        List<Club> uelLeaguePhase = new ArrayList<>(uelDirect);
        while (uelLeaguePhase.size() > 36) uelLeaguePhase.remove(uelLeaguePhase.size() - 1);

        List<Club> ueclEntrants = new ArrayList<>();
        ueclEntrants.addAll(cpQ1LosersToUeclQ2);
        ueclEntrants.addAll(cpQ1LosersToUeclQ3);

        for (int i = 0; i < Math.min(5, totalNations); i++) {
            Club n6 = getNthPlace(ranked.get(i), getClubsForCountry, globalQualified, 6);
            if (n6 != null) { ueclEntrants.add(n6); globalQualified.add(n6); }
        }
        for (int i = 5; i < Math.min(12, totalNations); i++) {
            Club n4 = getNthPlace(ranked.get(i), getClubsForCountry, globalQualified, 4);
            if (n4 != null) { ueclEntrants.add(n4); globalQualified.add(n4); }
        }
        for (int i = 13; i < Math.min(totalNations, 50); i++) {
            Club cw = getCupWinner(ranked.get(i), globalQualified);
            if (cw != null) { ueclEntrants.add(cw); globalQualified.add(cw); }
            Club n2 = getNthPlace(ranked.get(i), getClubsForCountry, globalQualified, 2);
            if (n2 != null) { ueclEntrants.add(n2); globalQualified.add(n2); }
            Club n3 = getNthPlace(ranked.get(i), getClubsForCountry, globalQualified, 3);
            if (n3 != null) { ueclEntrants.add(n3); globalQualified.add(n3); }
        }

        int ueclTarget = 36;
        List<Club> ueclLeaguePhase = runQualifyingToTarget("UECL Qualifying", ueclEntrants, ueclTarget,
            matchesByCompetition, allMatches, "uefa_uecl");
        while (ueclLeaguePhase.size() > 36) ueclLeaguePhase.remove(ueclLeaguePhase.size() - 1);

        return new UefaSeasonAllocation(uclLeaguePhase, uelLeaguePhase, ueclLeaguePhase,
            allMatches, matchesByCompetition);
    }

    // ==================== HELPERS ====================

    private static Club getChampion(CountryAssociation ca,
                                     java.util.function.Function<CountryAssociation, List<Club>> getClubs,
                                     Set<Club> excluded) {
        List<Club> clubs = getClubs.apply(ca);
        if (clubs == null || clubs.isEmpty()) return null;
        for (Club c : clubs) {
            if (!excluded.contains(c)) return c;
        }
        return null;
    }

    private static Club getNthPlace(CountryAssociation ca,
                                     java.util.function.Function<CountryAssociation, List<Club>> getClubs,
                                     Set<Club> excluded, int position) {
        List<Club> clubs = getClubs.apply(ca);
        if (clubs == null) return null;
        int count = 0;
        for (Club c : clubs) {
            if (excluded.contains(c)) continue;
            count++;
            if (count == position) return c;
        }
        return null;
    }

    private static Club getCupWinner(CountryAssociation ca, Set<Club> excluded) {
        Club cw = ca.getLastCupWinner();
        if (cw != null && !excluded.contains(cw)) return cw;
        return null;
    }

    static QualifyingResult simulateQualifyingRound(String roundName, List<Club> entrants, boolean twoLegs) {
        List<Club> winners = new ArrayList<>();
        List<Club> losers = new ArrayList<>();
        List<Match> matches = new ArrayList<>();

        if (entrants.size() < 2) {
            winners.addAll(entrants);
            return new QualifyingResult(winners, losers, matches);
        }

        List<Club> seeded = new ArrayList<>(entrants);
        seeded.sort((a, b) -> Double.compare(b.getEloRating(), a.getEloRating()));
        if (seeded.size() % 2 != 0) {
            winners.add(seeded.remove(0));
        }

        List<Club> pool = new ArrayList<>(seeded);
        while (pool.size() >= 2) {
            Club high = pool.remove(0);
            int opponentIndex = -1;
            for (int i = pool.size() - 1; i >= 0; i--) {
                if (!pool.get(i).getCountry().equals(high.getCountry())) {
                    opponentIndex = i;
                    break;
                }
            }
            if (opponentIndex < 0) {
                opponentIndex = pool.size() - 1;
            }
            Club low = pool.remove(opponentIndex);

            if (twoLegs) {
                Match leg1 = new Match(low, high, true);
                MatchEngine.Score s1 = MatchEngine.play(leg1.getHome(), leg1.getAway(), true, false);
                leg1.setResult(s1.homeGoals, s1.awayGoals);
                Match leg2 = new Match(high, low, true);
                MatchEngine.Score s2 = MatchEngine.play(leg2.getHome(), leg2.getAway(), true, false);
                leg2.setResult(s2.homeGoals, s2.awayGoals);
                matches.add(leg1);
                matches.add(leg2);
                int aggHigh = s1.awayGoals + s2.homeGoals;
                int aggLow = s1.homeGoals + s2.awayGoals;
                Club winner;
                if (aggHigh > aggLow) winner = high;
                else if (aggLow > aggHigh) winner = low;
                else winner = MatchEngine.resolveKnockoutWinner(high, low);
                winners.add(winner);
                losers.add(winner == high ? low : high);
            } else {
                Match m = new Match(high, low, true);
                MatchEngine.Score s = MatchEngine.play(m.getHome(), m.getAway(), true, false);
                m.setResult(s.homeGoals, s.awayGoals);
                matches.add(m);
                Club winner;
                if (s.homeGoals > s.awayGoals) winner = high;
                else if (s.awayGoals > s.homeGoals) winner = low;
                else winner = MatchEngine.resolveKnockoutWinner(high, low);
                winners.add(winner);
                losers.add(winner == high ? low : high);
            }
        }
        return new QualifyingResult(winners, losers, matches);
    }

    private static void addMatches(String competitionKey, String roundName, List<Match> matches,
                                   Map<String, Map<String, List<Match>>> byCompetition,
                                   List<Match> allMatches) {
        if (matches == null || matches.isEmpty()) return;
        allMatches.addAll(matches);
        Map<String, List<Match>> byRound =
            byCompetition.computeIfAbsent(competitionKey, k -> new LinkedHashMap<>());
        byRound.computeIfAbsent(roundName, k -> new ArrayList<>()).addAll(matches);
    }

    private static void addUelDirectSlots(CountryAssociation ca,
                                          java.util.function.Function<CountryAssociation, List<Club>> getClubs,
                                          Set<Club> excluded,
                                          List<Club> target,
                                          int slots) {
        if (slots <= 0 || ca == null) return;
        Club cupWinner = ca.getLastCupWinner();
        if (cupWinner != null && !excluded.contains(cupWinner)) {
            target.add(cupWinner);
            excluded.add(cupWinner);
            slots--;
        }
        List<Club> clubs = getClubs.apply(ca);
        if (clubs == null) return;
        for (Club c : clubs) {
            if (slots <= 0) break;
            if (excluded.contains(c)) continue;
            target.add(c);
            excluded.add(c);
            slots--;
        }
    }

    private static List<Club> runQualifyingToTarget(String roundName,
                                                    List<Club> entrants,
                                                    int target,
                                                    Map<String, Map<String, List<Match>>> byCompetition,
                                                    List<Match> allMatches,
                                                    String competitionKey) {
        List<Club> pool = new ArrayList<>(entrants);
        if (target <= 0) return new ArrayList<>();
        if (pool.size() <= target) {
            return new ArrayList<>(pool);
        }

        List<Club> lastLosers = new ArrayList<>();
        while (pool.size() > target && pool.size() >= 2) {
            if (pool.size() % 2 != 0) {
                pool.remove(pool.size() - 1);
            }
            QualifyingResult qr = simulateQualifyingRound(roundName, pool, true);
            addMatches(competitionKey, roundName, qr.matches, byCompetition, allMatches);
            pool = new ArrayList<>(qr.winners);
            lastLosers = new ArrayList<>(qr.losers);
        }

        if (pool.size() < target && !lastLosers.isEmpty()) {
            lastLosers.sort((a, b) -> Double.compare(b.getEloRating(), a.getEloRating()));
            int idx = 0;
            while (pool.size() < target && idx < lastLosers.size()) {
                Club c = lastLosers.get(idx++);
                if (!pool.contains(c)) {
                    pool.add(c);
                }
            }
        }

        while (pool.size() > target) {
            pool.remove(pool.size() - 1);
        }
        return pool;
    }

    private UefaQualifyingEngine() {}
}

final class AfcQualifyingEngine {

    public static class AfcSeasonAllocation {
        public final List<Club> aclEliteEntrants;
        public final List<Club> aclTwoEntrants;
        public final List<Club> challengeEntrants; 
        public final List<Match> allQualifyingMatches;
        public final Map<String, Map<String, List<Match>>> qualifyingMatchesByCompetition;

        AfcSeasonAllocation(List<Club> elite, List<Club> two, List<Club> challenge,
                            List<Match> matches,
                            Map<String, Map<String, List<Match>>> matchesByCompetition) {
            this.aclEliteEntrants = elite;
            this.aclTwoEntrants = two;
            this.challengeEntrants = challenge;
            this.allQualifyingMatches = matches;
            this.qualifyingMatchesByCompetition = matchesByCompetition;
        }
    }

    /**
     * Run all AFC qualifying and produce entrant lists for all 3 tiers.
     *
     * @param ranked              AFC countries sorted by coefficient (excluded already filtered)
     * @param getClubsForCountry  function to get ranked clubs for a country
     * @param previousEliteWinner previous ACL Elite winner (nullable)
     * @param previousTwoWinner   previous ACL Two winner (nullable)
     */
    public static AfcSeasonAllocation allocateAndQualify(
            List<CountryAssociation> ranked,
            java.util.function.Function<CountryAssociation, List<Club>> getClubsForCountry,
            Club previousEliteWinner, Club previousTwoWinner, Club previousChallengeWinner) {

        List<Match> allMatches = new ArrayList<>();
        Map<String, Map<String, List<Match>>> matchesByCompetition = new LinkedHashMap<>();
        Set<Club> globalQualified = new HashSet<>();
        List<CountryAssociation> westRanked = new ArrayList<>();
        List<CountryAssociation> eastRanked = new ArrayList<>();
        for (CountryAssociation ca : ranked) {
            if (RegionMapper.isAfcWest(ca.getName())) {
                westRanked.add(ca);
            } else if (RegionMapper.isAfcEast(ca.getName())) {
                eastRanked.add(ca);
            }
        }

        List<Club> elitePlayoffLosersWest = new ArrayList<>();
        List<Club> elitePlayoffLosersEast = new ArrayList<>();

        List<Club> eliteWest = allocateEliteRegion("West", westRanked, getClubsForCountry,
                globalQualified, elitePlayoffLosersWest, allMatches, matchesByCompetition);
        List<Club> eliteEast = allocateEliteRegion("East", eastRanked, getClubsForCountry,
                globalQualified, elitePlayoffLosersEast, allMatches, matchesByCompetition);

        if (previousEliteWinner != null) {
            addTitleholder(previousEliteWinner, eliteWest, eliteEast, globalQualified);
            if (RegionMapper.isAfcWest(previousEliteWinner.getCountry())) {
                addExtraSlot(eastRanked, getClubsForCountry, globalQualified, eliteEast, 12);
            } else {
                addExtraSlot(westRanked, getClubsForCountry, globalQualified, eliteWest, 12);
            }
        }
        if (previousTwoWinner != null) {
            addTitleholder(previousTwoWinner, eliteWest, eliteEast, globalQualified);
            if (RegionMapper.isAfcWest(previousTwoWinner.getCountry())) {
                addExtraSlot(eastRanked, getClubsForCountry, globalQualified, eliteEast, 12);
            } else {
                addExtraSlot(westRanked, getClubsForCountry, globalQualified, eliteWest, 12);
            }
        }

        padToTarget(eliteWest, westRanked, getClubsForCountry, globalQualified, 12);
        padToTarget(eliteEast, eastRanked, getClubsForCountry, globalQualified, 12);

        List<Club> eliteEntrants = new ArrayList<>();
        eliteEntrants.addAll(eliteWest);
        eliteEntrants.addAll(eliteEast);
        while (eliteEntrants.size() > 24) eliteEntrants.remove(eliteEntrants.size() - 1);

        List<Club> twoWest = allocateTwoRegion("West", westRanked, getClubsForCountry,
                globalQualified, elitePlayoffLosersWest, allMatches, matchesByCompetition);
        List<Club> twoEast = allocateTwoRegion("East", eastRanked, getClubsForCountry,
                globalQualified, elitePlayoffLosersEast, allMatches, matchesByCompetition);

        if (previousChallengeWinner != null) {
            if (RegionMapper.isAfcWest(previousChallengeWinner.getCountry())) {
                addExtraSlotFromRank(eastRanked, getClubsForCountry, globalQualified, twoEast, 8, 16);
            } else {
                addExtraSlotFromRank(westRanked, getClubsForCountry, globalQualified, twoWest, 8, 16);
            }
        }

        padToTarget(twoWest, westRanked, getClubsForCountry, globalQualified, 16);
        padToTarget(twoEast, eastRanked, getClubsForCountry, globalQualified, 16);

        List<Club> twoEntrants = new ArrayList<>();
        twoEntrants.addAll(twoWest);
        twoEntrants.addAll(twoEast);
        while (twoEntrants.size() > 32) twoEntrants.remove(twoEntrants.size() - 1);

        List<Club> challengeWest = allocateChallengeRegion("West", westRanked, getClubsForCountry,
                globalQualified, allMatches, matchesByCompetition);
        List<Club> challengeEast = allocateChallengeRegion("East", eastRanked, getClubsForCountry,
                globalQualified, allMatches, matchesByCompetition);

        List<Club> challengeEntrants = new ArrayList<>();
        challengeEntrants.addAll(challengeWest);
        challengeEntrants.addAll(challengeEast);

        return new AfcSeasonAllocation(eliteEntrants, twoEntrants, challengeEntrants, allMatches,
                matchesByCompetition);
    }

    private static List<Club> allocateEliteRegion(String region,
            List<CountryAssociation> regionRanked,
            java.util.function.Function<CountryAssociation, List<Club>> getClubsForCountry,
            Set<Club> globalQualified, List<Club> playoffLosers,
            List<Match> allMatches, Map<String, Map<String, List<Match>>> matchesByCompetition) {

        List<Club> entrants = new ArrayList<>();
        int totalInRegion = regionRanked.size();

        for (int i = 0; i < Math.min(3, totalInRegion); i++) {
            CountryAssociation ca = regionRanked.get(i);
            List<Club> clubs = getClubsForCountry.apply(ca);
            int added = 0;
            for (Club c : clubs) {
                if (added >= 2) break;
                if (globalQualified.contains(c)) continue;
                entrants.add(c);
                globalQualified.add(c);
                added++;
            }
        }

        for (int i = 3; i < Math.min(6, totalInRegion); i++) {
            Club champ = getChampion(regionRanked.get(i), getClubsForCountry, globalQualified);
            if (champ != null) {
                entrants.add(champ);
                globalQualified.add(champ);
            }
        }

        List<Club> playoffEntrants = new ArrayList<>();
        if (totalInRegion >= 3) {
            Club r3n3 = getNthPlace(regionRanked.get(2), getClubsForCountry, globalQualified, 3);
            if (r3n3 != null) { playoffEntrants.add(r3n3); globalQualified.add(r3n3); }
        }
        if (totalInRegion >= 4) {
            Club r4n2 = getNthPlace(regionRanked.get(3), getClubsForCountry, globalQualified, 2);
            if (r4n2 != null) { playoffEntrants.add(r4n2); globalQualified.add(r4n2); }
        }
        for (int i = 4; i < Math.min(6, totalInRegion) && playoffEntrants.size() < 2; i++) {
            Club next = getNextAvailable(regionRanked.get(i), getClubsForCountry, globalQualified);
            if (next != null) { playoffEntrants.add(next); globalQualified.add(next); }
        }

        if (playoffEntrants.size() >= 2) {
            if (playoffEntrants.size() % 2 != 0) playoffEntrants.remove(playoffEntrants.size() - 1);
            UefaQualifyingEngine.QualifyingResult po =
                    UefaQualifyingEngine.simulateQualifyingRound(
                            "ACL Elite PO " + region, playoffEntrants, true);
            allMatches.addAll(po.matches);
            addMatches(matchesByCompetition, "afc_acl_elite", "Play-off (" + region + ")", po.matches);
            entrants.addAll(po.winners);
            for (Club loser : po.losers) {
                playoffLosers.add(loser);
            }
        }


        return entrants;
    }


    private static List<Club> allocateTwoRegion(String region,
            List<CountryAssociation> regionRanked,
            java.util.function.Function<CountryAssociation, List<Club>> getClubsForCountry,
            Set<Club> globalQualified, List<Club> elitePlayoffLosers,
            List<Match> allMatches, Map<String, Map<String, List<Match>>> matchesByCompetition) {

        List<Club> entrants = new ArrayList<>();
        int totalInRegion = regionRanked.size();

        // Include ACL Elite playoff losers (direct to groups)
        for (Club loser : elitePlayoffLosers) {
            if (!entrants.contains(loser)) {
                entrants.add(loser);
                globalQualified.add(loser);
            }
        }

        for (int i = 0; i < Math.min(6, totalInRegion); i++) {
            Club next = getNextAvailable(regionRanked.get(i), getClubsForCountry, globalQualified);
            if (next != null) {
                entrants.add(next);
                globalQualified.add(next);
            }
        }

        for (int i = 6; i < Math.min(8, totalInRegion); i++) {
            CountryAssociation ca = regionRanked.get(i);
            List<Club> clubs = getClubsForCountry.apply(ca);
            int added = 0;
            for (Club c : clubs) {
                if (added >= 2) break;
                if (globalQualified.contains(c)) continue;
                entrants.add(c);
                globalQualified.add(c);
                added++;
            }
        }

        for (int i = 8; i < Math.min(10, totalInRegion); i++) {
            Club champ = getChampion(regionRanked.get(i), getClubsForCountry, globalQualified);
            if (champ != null) {
                entrants.add(champ);
                globalQualified.add(champ);
            }
        }

        List<Club> playoffEntrants = new ArrayList<>();
        for (int i = 8; i < Math.min(12, totalInRegion); i++) {
            Club next = getNextAvailable(regionRanked.get(i), getClubsForCountry, globalQualified);
            if (next != null) { playoffEntrants.add(next); globalQualified.add(next); }
        }

        int playoffTarget = 6;
        int padIdx = 12;
        while (playoffEntrants.size() < playoffTarget && padIdx < totalInRegion) {
            Club next = getNextAvailable(regionRanked.get(padIdx), getClubsForCountry, globalQualified);
            if (next != null) {
                playoffEntrants.add(next);
                globalQualified.add(next);
            }
            padIdx++;
        }
        while (playoffEntrants.size() > playoffTarget) {
            playoffEntrants.remove(playoffEntrants.size() - 1);
        }

        if (playoffEntrants.size() >= 2) {
            if (playoffEntrants.size() % 2 != 0) playoffEntrants.remove(playoffEntrants.size() - 1);
            UefaQualifyingEngine.QualifyingResult po =
                    UefaQualifyingEngine.simulateQualifyingRound(
                            "ACL Two PO " + region, playoffEntrants, true);
            allMatches.addAll(po.matches);
            addMatches(matchesByCompetition, "afc_acl_two", "Play-off (" + region + ")", po.matches);
            entrants.addAll(po.winners);
        }

        return entrants;
    }

    private static List<Club> allocateChallengeRegion(String region,
            List<CountryAssociation> regionRanked,
            java.util.function.Function<CountryAssociation, List<Club>> getClubsForCountry,
            Set<Club> globalQualified, List<Match> allMatches,
            Map<String, Map<String, List<Match>>> matchesByCompetition) {

        List<Club> entrants = new ArrayList<>();
        int totalInRegion = regionRanked.size();

        for (int i = 10; i < Math.min(14, totalInRegion); i++) {
            Club next = getNextAvailable(regionRanked.get(i), getClubsForCountry, globalQualified);
            if (next != null) {
                entrants.add(next);
                globalQualified.add(next);
            }
        }

        List<Club> playoffEntrants = new ArrayList<>();
        for (int i = 14; i < totalInRegion; i++) {
            Club next = getNextAvailable(regionRanked.get(i), getClubsForCountry, globalQualified);
            if (next != null) {
                playoffEntrants.add(next);
                globalQualified.add(next);
            }
        }

        boolean isWest = "West".equals(region);
        int targetPrelim = isWest ? 16 : 8;

        int padRank = 14;
        while (playoffEntrants.size() < targetPrelim && padRank < totalInRegion) {
            Club next = getNextAvailable(regionRanked.get(padRank), getClubsForCountry, globalQualified);
            if (next != null) {
                playoffEntrants.add(next);
                globalQualified.add(next);
            }
            padRank++;
        }
        padRank = 10;
        while (playoffEntrants.size() < targetPrelim && padRank < totalInRegion) {
            Club next = getNextAvailable(regionRanked.get(padRank), getClubsForCountry, globalQualified);
            if (next != null) {
                playoffEntrants.add(next);
                globalQualified.add(next);
            }
            padRank++;
        }

        while (playoffEntrants.size() > targetPrelim) {
            playoffEntrants.remove(playoffEntrants.size() - 1);
        }

        if (playoffEntrants.size() >= 2) {
            if (playoffEntrants.size() % 2 != 0) playoffEntrants.remove(playoffEntrants.size() - 1);
            UefaQualifyingEngine.QualifyingResult po =
                    UefaQualifyingEngine.simulateQualifyingRound(
                            "AFC Challenge PO " + region, playoffEntrants, true);
            allMatches.addAll(po.matches);
            addMatches(matchesByCompetition, "afc_challenge", "Play-off (" + region + ")", po.matches);
            entrants.addAll(po.winners);
        }

        return entrants;
    }

    // ==================== HELPERS ====================

    private static void addExtraSlot(List<CountryAssociation> regionRanked,
            java.util.function.Function<CountryAssociation, List<Club>> getClubsForCountry,
            Set<Club> globalQualified, List<Club> targetList, int maxSize) {
        if (targetList.size() >= maxSize || regionRanked.isEmpty()) return;
        // Add to top-ranked nation in this region
        Club extra = getNextAvailable(regionRanked.get(0), getClubsForCountry, globalQualified);
        if (extra != null) {
            targetList.add(extra);
            globalQualified.add(extra);
        }
    }

    private static void addMatches(Map<String, Map<String, List<Match>>> byCompetition,
                                   String competitionKey,
                                   String roundName,
                                   List<Match> matches) {
        if (matches == null || matches.isEmpty()) return;
        Map<String, List<Match>> byRound =
            byCompetition.computeIfAbsent(competitionKey, k -> new LinkedHashMap<>());
        byRound.computeIfAbsent(roundName, k -> new ArrayList<>()).addAll(matches);
    }

    private static void addExtraSlotFromRank(List<CountryAssociation> regionRanked,
            java.util.function.Function<CountryAssociation, List<Club>> getClubsForCountry,
            Set<Club> globalQualified, List<Club> targetList, int startRankIndex, int maxSize) {
        if (targetList.size() >= maxSize || regionRanked.isEmpty()) return;
        int idx = Math.max(0, startRankIndex);
        while (idx < regionRanked.size() && targetList.size() < maxSize) {
            Club extra = getNextAvailable(regionRanked.get(idx), getClubsForCountry, globalQualified);
            if (extra != null) {
                targetList.add(extra);
                globalQualified.add(extra);
                return;
            }
            idx++;
        }
    }

    private static void addTitleholder(Club winner, List<Club> west, List<Club> east,
                                       Set<Club> globalQualified) {
        if (winner == null || globalQualified.contains(winner)) return;
        if (RegionMapper.isAfcWest(winner.getCountry())) {
            west.add(winner);
        } else {
            east.add(winner);
        }
        globalQualified.add(winner);
    }

    private static void padToTarget(List<Club> entrants,
            List<CountryAssociation> regionRanked,
            java.util.function.Function<CountryAssociation, List<Club>> getClubsForCountry,
            Set<Club> globalQualified, int target) {
        int pass = 0;
        while (entrants.size() < target && pass < 3) {
            for (int i = 0; i < regionRanked.size() && entrants.size() < target; i++) {
                Club next = getNextAvailable(regionRanked.get(i), getClubsForCountry, globalQualified);
                if (next != null) {
                    entrants.add(next);
                    globalQualified.add(next);
                }
            }
            pass++;
        }
        while (entrants.size() > target) entrants.remove(entrants.size() - 1);
    }

    private static Club getChampion(CountryAssociation ca,
            java.util.function.Function<CountryAssociation, List<Club>> getClubs,
            Set<Club> excluded) {
        List<Club> clubs = getClubs.apply(ca);
        if (clubs == null || clubs.isEmpty()) return null;
        for (Club c : clubs) {
            if (!excluded.contains(c)) return c;
        }
        return null;
    }

    private static Club getNthPlace(CountryAssociation ca,
            java.util.function.Function<CountryAssociation, List<Club>> getClubs,
            Set<Club> excluded, int position) {
        List<Club> clubs = getClubs.apply(ca);
        if (clubs == null) return null;
        int count = 0;
        for (Club c : clubs) {
            if (excluded.contains(c)) continue;
            count++;
            if (count == position) return c;
        }
        return null;
    }

    private static Club getNextAvailable(CountryAssociation ca,
            java.util.function.Function<CountryAssociation, List<Club>> getClubs,
            Set<Club> excluded) {
        List<Club> clubs = getClubs.apply(ca);
        if (clubs == null) return null;
        for (Club c : clubs) {
            if (!excluded.contains(c)) return c;
        }
        return null;
    }

    private AfcQualifyingEngine() {}
}

final class CafQualifyingEngine {

    public static class CafSeasonAllocation {
        public final List<Club> clGroupStageEntrants;   // ~24 teams for groups
        public final List<Match> allQualifyingMatches;
        public final Map<String, List<Match>> qualifyingMatchesByRound;

        CafSeasonAllocation(List<Club> cl, List<Match> matches,
                            Map<String, List<Match>> matchesByRound) {
            this.clGroupStageEntrants = cl;
            this.allQualifyingMatches = matches;
            this.qualifyingMatchesByRound = matchesByRound;
        }
    }

    /**
     * Run all CAF Champions League qualifying and produce the group-stage entrant list.
     *
     * @param ranked              CAF countries sorted by coefficient (excluded already filtered)
     * @param getClubsForCountry  function to get ranked clubs for a country
     * @param previousClWinner    previous CL winner (null if first season or not applicable)
     */
    public static CafSeasonAllocation allocateAndQualify(
            List<CountryAssociation> ranked,
            java.util.function.Function<CountryAssociation, List<Club>> getClubsForCountry,
            Club previousClWinner) {

        int totalNations = ranked.size();
        List<Match> allMatches = new ArrayList<>();
        Map<String, List<Match>> matchesByRound = new LinkedHashMap<>();
        Set<Club> globalQualified = new HashSet<>();

        // Determine whether the previous CL winner qualifies normally (rank 1-12 champion)
        boolean previousWinnerQualifiedNormally = false;
        if (previousClWinner != null) {
            for (int i = 0; i < Math.min(12, totalNations); i++) {
                Club champ = peekChampion(ranked.get(i), getClubsForCountry);
                if (champ != null && champ.equals(previousClWinner)) {
                    previousWinnerQualifiedNormally = true;
                    break;
                }
            }
        }

        List<Club> prelimEntrants = new ArrayList<>();
        // Preliminary Round: Rank 43-46 (indices 42-45, 0-based)
        for (int i = Math.max(42, 0); i < totalNations; i++) {
            Club champ = getChampion(ranked.get(i), getClubsForCountry, globalQualified);
            if (champ != null) {
                prelimEntrants.add(champ);
                globalQualified.add(champ);
            }
        }

        Club prelimWinner = null;
        if (prelimEntrants.size() >= 2) {
            UefaQualifyingEngine.QualifyingResult prelim =
                    UefaQualifyingEngine.simulateQualifyingRound("CAF CL Preliminary", prelimEntrants, true);
            allMatches.addAll(prelim.matches);
            matchesByRound.put("Preliminary Round", prelim.matches);
            prelimWinner = prelim.winners.isEmpty() ? null : prelim.winners.get(0);
        } else if (prelimEntrants.size() == 1) {
            prelimWinner = prelimEntrants.get(0);
        }

        // If previous CL winner did NOT qualify normally, preliminary winner must
        // play rank 43's champion in an extra qualifying tie before joining FQR.
        if (prelimWinner != null && previousClWinner != null && !previousWinnerQualifiedNormally) {
            int rank43Index = Math.min(42, totalNations - 1); // 0-based 42 = rank 43
            Club rank43Champ = getChampion(ranked.get(rank43Index), getClubsForCountry, globalQualified);
            if (rank43Champ != null) {
                globalQualified.add(rank43Champ);
                List<Club> extraTie = new ArrayList<>();
                extraTie.add(prelimWinner);
                extraTie.add(rank43Champ);
                UefaQualifyingEngine.QualifyingResult extra =
                        UefaQualifyingEngine.simulateQualifyingRound("CAF CL Extra Qualifying", extraTie, true);
                allMatches.addAll(extra.matches);
                matchesByRound.put("Extra Qualifying", extra.matches);
                prelimWinner = extra.winners.isEmpty() ? null : extra.winners.get(0);
            }
        }

        // ================================================================
        // STEP 2: FIRST QUALIFYING ROUND (FQR)  E~30 clubs
        // Rank 13-42/43 champions + preliminary qualifier
        // ================================================================
        int fqrEnd = 42; // exclusive, 0-based. Rank 43-46 go to preliminary round
        List<Club> fqrEntrants = new ArrayList<>();
        for (int i = 12; i < Math.min(fqrEnd, totalNations); i++) { // 0-based 12 = rank 13
            Club champ = getChampion(ranked.get(i), getClubsForCountry, globalQualified);
            if (champ != null) {
                fqrEntrants.add(champ);
                globalQualified.add(champ);
            }
        }
        if (prelimWinner != null) {
            fqrEntrants.add(prelimWinner);
        }

        // Ensure even  Egive bye to best seed if odd
        UefaQualifyingEngine.QualifyingResult fqr =
                UefaQualifyingEngine.simulateQualifyingRound("CAF CL FQR", fqrEntrants, true);
        allMatches.addAll(fqr.matches);
        matchesByRound.put("First Qualifying Round", fqr.matches);

        // ================================================================
        // STEP 3: SECOND QUALIFYING ROUND (SQR)  E~32 clubs
        // FQR winners + 16 seeded entrants from higher-ranked nations
        //   Rank 1-12: 2nd-place clubs (12 clubs)
        //   Rank 9-12: league champions (4 clubs)
        // ================================================================
        List<Club> sqrSeeds = new ArrayList<>();

        // Rank 1-12 league 2nd-place clubs
        for (int i = 0; i < Math.min(12, totalNations); i++) {
            Club runner = getNthPlace(ranked.get(i), getClubsForCountry, globalQualified, 2);
            if (runner != null) {
                sqrSeeds.add(runner);
                globalQualified.add(runner);
            }
        }

        // Rank 9-12 league champions
        for (int i = 8; i < Math.min(12, totalNations); i++) {
            Club champ = getChampion(ranked.get(i), getClubsForCountry, globalQualified);
            if (champ != null) {
                sqrSeeds.add(champ);
                globalQualified.add(champ);
            }
        }

        List<Club> sqrEntrants = new ArrayList<>();
        sqrEntrants.addAll(sqrSeeds);
        sqrEntrants.addAll(fqr.winners);

        int sqrTarget = 32;
        while (sqrEntrants.size() > sqrTarget) {
            sqrEntrants.remove(sqrEntrants.size() - 1);
        }

        UefaQualifyingEngine.QualifyingResult sqr =
                UefaQualifyingEngine.simulateQualifyingRound("CAF CL SQR", sqrEntrants, true);
        allMatches.addAll(sqr.matches);
        matchesByRound.put("Second Qualifying Round", sqr.matches);

        // ================================================================
        // STEP 4: GROUP STAGE  E~24 clubs in 6 groups of 4
        // SQR winners + rank 1-8 league champions (direct)
        // ================================================================
        List<Club> groupStage = new ArrayList<>();
        List<Club> directChamps = new ArrayList<>();

        // Rank 1-8 league champions  Edirect to group stage
        for (int i = 0; i < Math.min(8, totalNations); i++) {
            Club champ = getChampion(ranked.get(i), getClubsForCountry, globalQualified);
            if (champ != null) {
                directChamps.add(champ);
                groupStage.add(champ);
                globalQualified.add(champ);
            }
        }

        // Previous CL winner gets a direct spot (if not already qualified)
        if (previousClWinner != null && !groupStage.contains(previousClWinner)) {
            groupStage.add(previousClWinner);
            globalQualified.add(previousClWinner);
        }

        // SQR winners
        for (Club w : sqr.winners) {
            if (!groupStage.contains(w)) {
                groupStage.add(w);
            }
        }

        int groupTarget = 24;
        if (groupStage.size() > groupTarget) {
            Set<Club> protectedClubs = new HashSet<>(directChamps);
            if (previousClWinner != null) {
                protectedClubs.add(previousClWinner);
            }
            List<Club> removable = new ArrayList<>();
            for (Club c : groupStage) {
                if (!protectedClubs.contains(c)) {
                    removable.add(c);
                }
            }
            removable.sort(Comparator.comparingDouble(Club::getEloRating));
            int idx = 0;
            while (groupStage.size() > groupTarget && idx < removable.size()) {
                groupStage.remove(removable.get(idx++));
            }
        }

        // Pad to 24 if under
        int padIdx = 0;
        while (groupStage.size() < groupTarget && padIdx < totalNations) {
            Club extra = getNthPlace(ranked.get(padIdx), getClubsForCountry, globalQualified, 3);
            if (extra != null && !groupStage.contains(extra)) {
                groupStage.add(extra);
                globalQualified.add(extra);
            }
            padIdx++;
        }
        // Trim to 24 if over
        while (groupStage.size() > groupTarget) {
            groupStage.remove(groupStage.size() - 1);
        }

        return new CafSeasonAllocation(groupStage, allMatches, matchesByRound);
    }

    /**
     * Same qualifying structure as CAF CL, but excludes all clubs that participated
     * in the CL (including qualifying rounds). Used for CAF Confederation Cup.
     */
    public static CafSeasonAllocation allocateAndQualifyExcluding(
            List<CountryAssociation> ranked,
            java.util.function.Function<CountryAssociation, List<Club>> getClubsForCountry,
            Club previousCcWinner, Set<Club> excludedClubs) {

        // Create a wrapper that skips excluded clubs
        java.util.function.Function<CountryAssociation, List<Club>> filteredClubs = ca -> {
            List<Club> all = getClubsForCountry.apply(ca);
            List<Club> filtered = new java.util.ArrayList<>();
            for (Club c : all) {
                if (!excludedClubs.contains(c)) filtered.add(c);
            }
            return filtered;
        };

        return allocateAndQualify(ranked, filteredClubs, previousCcWinner);
    }

    // ==================== HELPERS ====================

    /**
     * Peek at the champion without consuming from the excluded set.
     * Used to check if previousClWinner qualifies normally.
     */
    private static Club peekChampion(CountryAssociation ca,
                                      java.util.function.Function<CountryAssociation, List<Club>> getClubs) {
        List<Club> clubs = getClubs.apply(ca);
        if (clubs == null || clubs.isEmpty()) return null;
        return clubs.get(0);
    }

    private static Club getChampion(CountryAssociation ca,
                                     java.util.function.Function<CountryAssociation, List<Club>> getClubs,
                                     Set<Club> excluded) {
        List<Club> clubs = getClubs.apply(ca);
        if (clubs == null || clubs.isEmpty()) return null;
        for (Club c : clubs) {
            if (!excluded.contains(c)) return c;
        }
        return null;
    }

    private static Club getNthPlace(CountryAssociation ca,
                                     java.util.function.Function<CountryAssociation, List<Club>> getClubs,
                                     Set<Club> excluded, int position) {
        List<Club> clubs = getClubs.apply(ca);
        if (clubs == null) return null;
        int count = 0;
        for (Club c : clubs) {
            if (excluded.contains(c)) continue;
            count++;
            if (count == position) return c;
        }
        return null;
    }

    private CafQualifyingEngine() {}
}



/**
 * Simulates CONCACAF Champions Cup qualifying and produces the 16-team R16 field.
 * 30 nations. Previous winner does NOT auto-qualify  Ethey must earn a spot
 * through their nation's normal allocation.
 *
 * Structure:
 *   Direct to R16 (10 clubs):
 *     Rank 1-2: top 2 clubs each (champion + 2nd place) = 4
 *     Rank 3-8: league champion each = 6
 *
 *   Qualifying (24 clubs ↁE6 winners):
 *     Rank 7-30 league winners enter qualifying. Rank 7-8 champions already
 *     went direct, so their 2nd-place club enters instead.
 *     Round 1: 24 clubs ↁE12 winners (2-leg)
 *     Round 2: 12 clubs ↁE6 winners (2-leg)
 *
 *   Round of 16: 10 auto + 6 qualifying winners = 16 clubs
 */
final class ConcacafQualifyingEngine {

    public static class ConcacafSeasonAllocation {
        public final List<Club> ccR16Entrants;          // 16 teams for R16
        public final List<Match> allQualifyingMatches;
        public final Map<String, List<Match>> qualifyingMatchesByRound;

        ConcacafSeasonAllocation(List<Club> cc, List<Match> matches,
                                 Map<String, List<Match>> matchesByRound) {
            this.ccR16Entrants = cc;
            this.allQualifyingMatches = matches;
            this.qualifyingMatchesByRound = matchesByRound;
        }
    }

    /**
     * Allocate slots and run qualifying for the CONCACAF Champions Cup.
     *
     * @param ranked           CONCACAF countries sorted by coefficient (best first)
     * @param getClubsForCountry returns ranked clubs for a country (champion first)
     * @param previousWinner   tracked but does NOT receive any special entry path
     * @return allocation containing 16 R16 entrants and all qualifying matches
     */
    public static ConcacafSeasonAllocation allocateAndQualify(
            List<CountryAssociation> ranked,
            java.util.function.Function<CountryAssociation, List<Club>> getClubsForCountry,
            Club previousWinner) {

        int totalNations = ranked.size();
        List<Match> allMatches = new ArrayList<>();
        Map<String, List<Match>> matchesByRound = new LinkedHashMap<>();
        Set<Club> globalQualified = new HashSet<>();

        // ================================================================
        // STEP 1: DIRECT TO R16 (10 clubs)
        // ================================================================
        List<Club> r16Direct = new ArrayList<>();

        // Rank 1-2: champion + 2nd place each = 4 clubs
        for (int i = 0; i < Math.min(2, totalNations); i++) {
            CountryAssociation ca = ranked.get(i);
            List<Club> clubs = getClubsForCountry.apply(ca);
            int added = 0;
            for (Club c : clubs) {
                if (added >= 2) break;
                if (globalQualified.contains(c)) continue;
                r16Direct.add(c);
                globalQualified.add(c);
                added++;
            }
        }

        // Rank 3-8: league champion each = 6 clubs
        for (int i = 2; i < Math.min(8, totalNations); i++) {
            Club champ = getChampion(ranked.get(i), getClubsForCountry, globalQualified);
            if (champ != null) {
                r16Direct.add(champ);
                globalQualified.add(champ);
            }
        }

        // ================================================================
        // STEP 2: FIRST QUALIFYING ROUND (6 clubs from Rank 27-32)
        // Rank 27-32: league champion each = 6 clubs -> 3 winners
        // ================================================================
        List<Club> fqrEntrants = new ArrayList<>();
        for (int i = 26; i < Math.min(32, totalNations); i++) {
            Club champ = getChampion(ranked.get(i), getClubsForCountry, globalQualified);
            if (champ != null) {
                fqrEntrants.add(champ);
                globalQualified.add(champ);
            }
        }

        List<Club> fqrWinners = new ArrayList<>();
        if (fqrEntrants.size() >= 2) {
            // Ensure even for pairing
            if (fqrEntrants.size() % 2 != 0) {
                fqrEntrants.remove(fqrEntrants.size() - 1);
            }
            UefaQualifyingEngine.QualifyingResult fqr =
                    UefaQualifyingEngine.simulateQualifyingRound("CC First Qualifying", fqrEntrants, true);
            fqrWinners.addAll(fqr.winners);
            allMatches.addAll(fqr.matches);
            matchesByRound.put("First Qualifying Round", fqr.matches);
        } else if (fqrEntrants.size() == 1) {
            fqrWinners.add(fqrEntrants.get(0));
        }

        // ================================================================
        // STEP 3: SECOND QUALIFYING ROUND (24 clubs)
        // Rank 6-26: league champion each (next highest if rank 3-8 already went direct)
        // + 3 FQR winners = 24 clubs -> 12 winners
        // ================================================================
        List<Club> sqrEntrants = new ArrayList<>();

        // Rank 6-26: league champions (21 clubs)
        // But Rank 3-8 already have champions in R16, so we take next available or 2nd place
        for (int i = 2; i < Math.min(26, totalNations); i++) {
            if (i < 8) {
                // Rank 3-8: champion went direct, so take 2nd place
                Club runner = getNthPlace(ranked.get(i), getClubsForCountry, globalQualified, 2);
                if (runner != null) {
                    sqrEntrants.add(runner);
                    globalQualified.add(runner);
                }
            } else {
                // Rank 9-26: league champion
                Club champ = getChampion(ranked.get(i), getClubsForCountry, globalQualified);
                if (champ != null) {
                    sqrEntrants.add(champ);
                    globalQualified.add(champ);
                }
            }
        }

        // Add FQR winners (3 clubs)
        sqrEntrants.addAll(fqrWinners);

        // Ensure even for pairing
        if (sqrEntrants.size() % 2 != 0 && sqrEntrants.size() > 1) {
            sqrEntrants.remove(sqrEntrants.size() - 1);
        }

        // Round 1: 24 -> 12 (2-leg)
        UefaQualifyingEngine.QualifyingResult r1 =
                UefaQualifyingEngine.simulateQualifyingRound("CC Qualifying R1", sqrEntrants, true);
        allMatches.addAll(r1.matches);
        matchesByRound.put("Qualifying Round 1", r1.matches);

        // Round 2: 12 -> 6 (2-leg)
        List<Club> r2Entrants = new ArrayList<>(r1.winners);
        if (r2Entrants.size() % 2 != 0 && r2Entrants.size() > 1) {
            r2Entrants.remove(r2Entrants.size() - 1);
        }

        UefaQualifyingEngine.QualifyingResult r2 =
                UefaQualifyingEngine.simulateQualifyingRound("CC Qualifying R2", r2Entrants, true);
        allMatches.addAll(r2.matches);
        matchesByRound.put("Qualifying Round 2", r2.matches);

        // ================================================================
        // STEP 4: ASSEMBLE R16 (16 clubs)
        // ================================================================
        List<Club> r16Entrants = new ArrayList<>();
        r16Entrants.addAll(r16Direct);
        r16Entrants.addAll(r2.winners);

        // Pad if under 16 (pull from R2 losers, then R1 losers)
        List<Club> padPool = new ArrayList<>();
        padPool.addAll(r2.losers);
        padPool.addAll(r1.losers);
        int padIdx = 0;
        while (r16Entrants.size() < 16 && padIdx < padPool.size()) {
            Club c = padPool.get(padIdx++);
            if (!r16Entrants.contains(c)) {
                r16Entrants.add(c);
            }
        }

        // Trim if over 16
        while (r16Entrants.size() > 16) {
            r16Entrants.remove(r16Entrants.size() - 1);
        }

        return new ConcacafSeasonAllocation(r16Entrants, allMatches, matchesByRound);
    }

    // ==================== HELPERS ====================

    private static Club getChampion(CountryAssociation ca,
                                     java.util.function.Function<CountryAssociation, List<Club>> getClubs,
                                     Set<Club> excluded) {
        List<Club> clubs = getClubs.apply(ca);
        if (clubs == null || clubs.isEmpty()) return null;
        for (Club c : clubs) {
            if (!excluded.contains(c)) return c;
        }
        return null;
    }

    private static Club getNthPlace(CountryAssociation ca,
                                     java.util.function.Function<CountryAssociation, List<Club>> getClubs,
                                     Set<Club> excluded, int position) {
        List<Club> clubs = getClubs.apply(ca);
        if (clubs == null) return null;
        int count = 0;
        for (Club c : clubs) {
            if (excluded.contains(c)) continue;
            count++;
            if (count == position) return c;
        }
        return null;
    }

    private ConcacafQualifyingEngine() {}
}



final class OfcQualifyingEngine {

    public static class OfcSeasonAllocation {
        public final List<Club> ofcClEntrants;     // 8 teams
        public final List<Match> allQualifyingMatches;
        public final Map<String, List<Match>> qualifyingMatchesByRound;

        OfcSeasonAllocation(List<Club> cl, List<Match> matches,
                            Map<String, List<Match>> matchesByRound) {
            this.ofcClEntrants = cl;
            this.allQualifyingMatches = matches;
            this.qualifyingMatchesByRound = matchesByRound;
        }
    }

    public static OfcSeasonAllocation allocateAndQualify(
            List<CountryAssociation> ranked,
            java.util.function.Function<CountryAssociation, List<Club>> getClubsForCountry) {
        
        List<Match> allMatches = new ArrayList<>();
        Map<String, List<Match>> matchesByRound = new LinkedHashMap<>();
        Set<Club> globalQualified = new HashSet<>();
        List<Club> entrants = new ArrayList<>();
        
        int totalNations = ranked.size(); // should be 7
        
        // Direct allocation to 8-club group stage:
        // Rank 1: 2 clubs
        // Rank 2-7: 1 club each
        for (int i = 0; i < totalNations; i++) {
            CountryAssociation ca = ranked.get(i);
            List<Club> clubs = getClubsForCountry.apply(ca);
            int rank = i + 1;
            
            int slots = (rank == 1) ? 2 : 1; // Rank 1 gets 2, rest get 1
            
            int added = 0;
            for (Club c : clubs) {
                if (added >= slots) break;
                if (globalQualified.contains(c)) continue;
                entrants.add(c);
                globalQualified.add(c);
                added++;
            }
        }
        
        // Pad to 8 if needed (shouldn't happen with 7 nations: 2+1+1+1+1+1+1=8)
        if (entrants.size() < 8) {
            for (int i = 0; i < totalNations && entrants.size() < 8; i++) {
                List<Club> clubs = getClubsForCountry.apply(ranked.get(i));
                for (Club c : clubs) {
                    if (globalQualified.contains(c)) continue;
                    entrants.add(c);
                    globalQualified.add(c);
                    if (entrants.size() >= 8) break;
                }
            }
        }
        
        // Trim to 8
        while (entrants.size() > 8) entrants.remove(entrants.size() - 1);
        
        return new OfcSeasonAllocation(entrants, allMatches, matchesByRound);
    }
    
    private OfcQualifyingEngine() {}
}

