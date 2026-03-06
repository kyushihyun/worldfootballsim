package worldfootballsim;

import java.util.Random;


public class MatchEngine {
    private static Random RNG = new Random();

    public static class Score {
        public final int homeGoals;
        public final int awayGoals;
        public Score(int homeGoals, int awayGoals) {
            this.homeGoals = homeGoals;
            this.awayGoals = awayGoals;
        }
    }

    public static Score play(Club home, Club away, boolean applyHomeAdvantage, boolean countForLeagueStats) {
        return play(home, away, applyHomeAdvantage, countForLeagueStats, Double.NaN);
    }

    public static Score play(Club home, Club away, boolean applyHomeAdvantage, boolean countForLeagueStats, double t) {
        return play(home, away, applyHomeAdvantage, countForLeagueStats, t, 12.0);
    }

    public static Score play(Club home, Club away, boolean applyHomeAdvantage, boolean countForLeagueStats, double t, double eloK) {
        home.prepareForMatch(t);
        away.prepareForMatch(t);

        double homeStrength = calculateTeamStrength(home);
        double awayStrength = calculateTeamStrength(away);

        if (applyHomeAdvantage) homeStrength *= 1.045;

        double d = (homeStrength - awayStrength) / 16.0;
        double adj = Math.tanh(d) * 0.55;
        double homeExpected = clamp(1.40 + adj, 0.40, 3.5);
        double awayExpected = clamp(1.10 - adj, 0.40, 3.5);

        int homeGoals = sampleGoals(homeExpected);
        int awayGoals = sampleGoals(awayExpected);

        if (!countForLeagueStats && homeGoals == awayGoals) {
            double strengthGap = homeStrength - awayStrength;
            double tiebreakChance = clamp(Math.abs(strengthGap) / 18.0, 0.0, 1.0) * 0.18;
            if (RNG.nextDouble() < tiebreakChance) {
                if (strengthGap >= 0) homeGoals++;
                else awayGoals++;
            }
        }

        double expectedHome = expectedOutcome(home, away);
        updateElo(home, away, homeGoals, awayGoals, expectedHome, eloK);

        int homePoints = (homeGoals > awayGoals) ? 3 : (homeGoals == awayGoals ? 1 : 0);
        int awayPoints = (awayGoals > homeGoals) ? 3 : (homeGoals == awayGoals ? 1 : 0);

        if (countForLeagueStats) {
            home.addLeagueMatchResult(homeGoals, awayGoals);
            away.addLeagueMatchResult(awayGoals, homeGoals);
        }

        home.finishMatch(t, homePoints, expectedHome * 3.0, 1.0, awayStrength, homeStrength);
        away.finishMatch(t, awayPoints, (1.0 - expectedHome) * 3.0, 1.0, homeStrength, awayStrength);

        return new Score(homeGoals, awayGoals);
    }

    public static Club resolveKnockoutWinner(Club a, Club b) {
        double eloA = a.getEloRating();
        double eloB = b.getEloRating();
        double expectedA = 1.0 / (1.0 + Math.pow(10.0, (eloB - eloA) / 400.0));
        return (RNG.nextDouble() < expectedA) ? a : b;
    }

    public static void setSeed(long seed) {
        RNG = new Random(seed);
    }

    public static double jitter(double spread) {
        if (spread <= 0.0) return 0.0;
        return (RNG.nextDouble() * 2.0 - 1.0) * spread;
    }

    private static double calculateTeamStrength(Club c) {
        return c.getMatchStrength(c.getSquadStrength());
    }

    private static int sampleGoals(double expected) {
        double L = Math.exp(-expected);
        int k = 0;
        double p = 1;
        do {
            k++;
            p *= RNG.nextDouble();
        } while (p > L && k < 8);
        int raw = k - 1;

        double dampened = expected + (raw - expected) * 0.87;
        int goals = (int) Math.round(Math.max(0, dampened));
        if (RNG.nextDouble() < 0.015) goals += 1;
        
        return Math.max(0, goals);
    }

    private static void updateElo(Club home, Club away, int hg, int ag, double expectedHome, double k) {
        double outcomeHome = (hg > ag) ? 1.0 : (hg == ag ? 0.5 : 0.0);
        double deltaHome = k * (outcomeHome - expectedHome);
        home.updateElo(deltaHome);
        away.updateElo(-deltaHome);
    }

    private static double expectedOutcome(Club home, Club away) {
        double eloHome = home.getEloRating();
        double eloAway = away.getEloRating();
        return 1.0 / (1.0 + Math.pow(10.0, (eloAway - eloHome) / 400.0));
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}



