package worldfootballsim;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Objects;
import java.util.Random;

public class Club {
    public enum ClubArchetype {
        REVENUE      (new double[]{0.90, 0.02, 0.02, 0.02, 0.02, 0.02, 0.00}),
        YOUTH        (new double[]{0.02, 0.02, 0.02, 0.90, 0.02, 0.02, 0.00}),
        PURE_STRENGTH(new double[]{0.02, 0.02, 0.90, 0.02, 0.02, 0.02, 0.00}),
        SQUAD_DEPTH  (new double[]{0.02, 0.02, 0.02, 0.02, 0.02, 0.88, 0.02}),
        GOOD_MANAGER (new double[]{0.02, 0.02, 0.02, 0.02, 0.88, 0.02, 0.02}),
        FACILITY     (new double[]{0.02, 0.88, 0.02, 0.02, 0.02, 0.02, 0.02}),
        BALANCED     (new double[]{0.12, 0.12, 0.12, 0.12, 0.12, 0.12, 0.12});

        private final double[] baseWeights;
        ClubArchetype(double[] baseWeights) { this.baseWeights = baseWeights; }
        public double[] getBaseWeights() { return baseWeights; }
    }

    public static final int W_ECONOMY       = 0;
    public static final int W_STABILITY     = 1;
    public static final int W_STRENGTH_NOW  = 2;
    public static final int W_GROWTH        = 3;
    public static final int W_PRESTIGE      = 4;
    public static final int W_SURVIVAL      = 5;
    public static final int W_TALENT_TRADING = 6;
    public static final int NUM_WEIGHTS     = 7;

    private final String name;
    private final String country;
    private final Confederation confederation;
    private String domesticLeagueName;
    private int domesticLevel;
    private CountryAssociation countryAssociation;  // For coefficient access

    private final double baseRating;
    private double baselineStrength;
    private double historicalAnchorStrength;

    private double rawStrength;
    private double strengthTrend;
    private double managerRating;
    private int squadDepthPlayers;
    private double cohesion;
    private double availability;
    private double tacticalQuality;
    private double systemFit;
    private double youthRating;
    private double developmentRating;
    private double recruitmentQuality;
    private double financialPower;
    private double developmentQuality;
    private double squadStrengthDelta;

    // finance (points-based)
    private double revenueSeason;
    private double cash;
    private double wageBillWeekly;
    private double transferBudgetSeason;
    private double wageCapWeekly;
    private double pointsSpentSeason;
    private final Deque<Double> spendHistory = new ArrayDeque<>();
    private double rollingLoss3y;
    private double ffpSeverity;
    private int repeatOffenses;
    private int highSpendStreak;
    private boolean ffpPenaltyAppliedThisSeason;
    private boolean depthPenaltyAppliedThisSeason;
    
    // Phase 3: FFP Tracking - split by reason
    private int pointsDeductedThisSeason = 0;           // Legacy: total deductions
    private int ffpPointsDeductedThisSeason = 0;       // FFP-specific deductions
    private int floorPointsDeductedThisSeason = 0;     // Wage floor penalty deductions
    private double ffpSeverityAtSeasonEnd = 0.0;

    private double fatigue;
    private double morale;
    private double lastMatchTime;
    private final Deque<Double> recentMatches = new ArrayDeque<>();
    private double youthMaintenancePaid;
    private double developmentMaintenancePaid;
    private Random rng;

    private ClubArchetype archetype;
    private double moraleFloor;
    private double riskTolerance;
    private double resultsPressure;
    private double financialPressure;
    private double ffpPressure;
    private double infrastructurePressure;
    private final double[] currentWeights = new double[NUM_WEIGHTS];
    private int weeksSinceStrategyUpdate;
    private int nextFocusChangeYear;
    private final Deque<FocusEntry> focusHistory = new ArrayDeque<>();
    private int badRunStreak;
    private int nextTacticalShakeAt;
    private int nextManagerShakeAt;
    private int seasonsSimulated;
    private int nextGenerationCycle;
    private int successStreak;
    private int lastSeasonLevel;
    private double lastSeasonPointsPerGame;
    private int leagueTitleStreak;
    private int lastLeagueTitleYear;
    private boolean lastSeasonChampion;
    private int promotionCooldown;
    private double arrogance;
    private double mismanagementRisk;
    private int currentFiveYearTermStart;
    private boolean mismanagedInCurrentTerm;

    // Unassigned club system
    private boolean isUnassigned = false;
    private int seasonsUnassigned = 0;
    private double ratingAtDropdown = 0.0;
    private int friendliesPlayedThisSeason = 0;
    private int friendliesWonThisSeason = 0;

    private double wageTarget;
    private double transferTarget;
    private double youthSpendTarget;
    private double devSpendTarget;
    private double reserveTarget;

    private double eloRating;

    private int wins, draws, losses;
    private int goalsFor, goalsAgainst, points;

    private static final int MAX_TROPHY_YEARS = 50;
    private final Map<String, TrophyRecord> trophiesByCompetition = new HashMap<>();
    private final java.util.Set<Integer> trophySeasons = new java.util.HashSet<>();
    private final Deque<SeasonStanding> recentStandings = new ArrayDeque<>();
    private final Deque<Double> continentalCoefficientHistory = new ArrayDeque<>();
    private double rollingContinentalCoefficient = 0.0;

    public Club(String name, String country, Confederation confederation, String domesticLeagueName,
                double baseRating, double eloRating) {
        this.name = name;
        this.country = country;
        this.confederation = confederation;
        this.domesticLeagueName = domesticLeagueName;
        this.domesticLevel = 0;

        this.baseRating = clamp(baseRating, 20, 100);
        this.rng = new Random(name.hashCode());

        this.baselineStrength = compressRating(this.baseRating);
        this.historicalAnchorStrength = this.baselineStrength;
        this.rawStrength = clamp(baselineStrength, 0, 100);
        this.strengthTrend = 0.0;
        this.squadStrengthDelta = 0.0;
        applyRandomizedProfile();
        this.seasonsSimulated = 0;
        this.nextGenerationCycle = 8 + rng.nextInt(10);
        this.successStreak = 0;
        this.lastSeasonLevel = 0;

        if (eloRating > 0) this.eloRating = eloRating;
        else this.eloRating = 400 + ((baselineStrength - 20) * 15);

        ClubArchetype[] archetypes = ClubArchetype.values();
        archetype = archetypes[rng.nextInt(archetypes.length)];

        startNewSeason();
        computePressures();
        computeSpendingTargets();
    }

    public void resetRandom(long seed) {
        rng = new Random(seed ^ name.hashCode());
        baselineStrength = compressRating(getAverageRating()) + rng.nextGaussian() * 2.5;
        baselineStrength = clamp(baselineStrength, 15.0, 100.0);
        historicalAnchorStrength = baselineStrength;
        rawStrength = baselineStrength;
        applyRandomizedProfile();
        ClubArchetype[] archetypes = ClubArchetype.values();
        archetype = archetypes[rng.nextInt(archetypes.length)];
        applyRunVariance();
        nextGenerationCycle = 8 + rng.nextInt(10);
        successStreak = 0;
        lastSeasonLevel = 0;
    }

    public void applyRandomizedProfile() {
        double anchor = baseRating;

        // Labor & personnel stats  Eanchored to club's natural tier
        managerRating = clamp(tierCenter(anchor, 0.60) + rng.nextGaussian() * 12, 20, 100);
        tacticalQuality = clamp(tierCenter(anchor, 0.55) + rng.nextGaussian() * 14, 20, 100);
        systemFit = clamp(tierCenter(anchor, 0.40) + 10 + rng.nextGaussian() * 12, 20, 100);
        cohesion = clamp(tierCenter(anchor, 0.35) + 10 + rng.nextGaussian() * 14, 35, 100);
        availability = clamp(75 + (anchor - 50) * 0.15 + rng.nextGaussian() * 8, 55, 100);
        recruitmentQuality = clamp(tierCenter(anchor, 0.55) + rng.nextGaussian() * 14, 20, 100);
        financialPower = clamp(tierCenter(anchor, 0.65) + rng.nextGaussian() * 14, 20, 100);

        // Squad depth  Eloosely correlated with tier
        double depthCenter = 18 + (anchor - 20) * 0.10;
        squadDepthPlayers = (int) Math.round(clamp(depthCenter + rng.nextGaussian() * 2.5, 14, 36));

        // Facility stats  Eindependent of tier (any club can invest in youth)
        youthRating = clamp(45 + rng.nextGaussian() * 18, 20, 100);
        developmentRating = clamp(50 + rng.nextGaussian() * 16, 20, 100);
        developmentQuality = clamp(50 + rng.nextGaussian() * 16, 20, 100);

        moraleFloor = clamp(35 + rng.nextGaussian() * 10, 20, 70);
        riskTolerance = clamp(0.3 + rng.nextGaussian() * 0.2, 0.1, 0.9);
        nextTacticalShakeAt = 9 + rng.nextInt(8);
        nextManagerShakeAt = 13 + rng.nextInt(9);

        arrogance = clamp(45 + rng.nextGaussian() * 12, 10, 90);
        mismanagementRisk = clamp(0.08 + (arrogance / 100.0) * 0.22 + rng.nextDouble() * 0.06, 0.03, 0.70);
        currentFiveYearTermStart = 0;
        mismanagedInCurrentTerm = false;
    }

    private double tierCenter(double rating, double weight) {
        return 50.0 * (1.0 - weight) + rating * weight;
    }

    private void applyRunVariance() {
        double strengthShift = rng.nextGaussian() * 3.0;
        rawStrength = clamp(rawStrength + strengthShift, 15.0, 100.0);
        strengthTrend = clamp(strengthTrend + rng.nextGaussian() * 1.0, -5.0, 4.0);

        managerRating = clamp(managerRating + rng.nextGaussian() * 4.0, 20.0, 100.0);
        tacticalQuality = clamp(tacticalQuality + rng.nextGaussian() * 4.0, 20.0, 100.0);
        systemFit = clamp(systemFit + rng.nextGaussian() * 3.5, 20.0, 100.0);
        cohesion = clamp(cohesion + rng.nextGaussian() * 4.0, 35.0, 100.0);
        availability = clamp(availability + rng.nextGaussian() * 3.0, 55.0, 100.0);

        youthRating = clamp(youthRating + rng.nextGaussian() * 4.0, 20.0, 100.0);
        developmentRating = clamp(developmentRating + rng.nextGaussian() * 4.0, 20.0, 100.0);
        recruitmentQuality = clamp(recruitmentQuality + rng.nextGaussian() * 4.0, 20.0, 100.0);
        financialPower = clamp(financialPower + rng.nextGaussian() * 4.0, 20.0, 100.0);
        developmentQuality = clamp(developmentQuality + rng.nextGaussian() * 4.0, 20.0, 100.0);

        double eloShift = rng.nextGaussian() * 20.0 + strengthShift * 12.0;
        eloRating = Math.max(100, eloRating + eloShift);
    }

    public static Club fromOpta(String name, String country, Confederation conf, String domesticLeague, double baseRating, Random rng) {
        return new Club(name, country, conf, domesticLeague, clamp(baseRating, 20, 100), 0);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double compressRating(double rating) {
        double center = 65.0;
        double compression = 0.72;
        return center + (rating - center) * compression;
    }

    private static double eloToStrength(double elo) {
        return clamp((elo - 400.0) / 15.0 + 20.0, 10.0, 120.0);
    }

    public void resetSeasonStats() {
        wins = draws = losses = 0;
        goalsFor = goalsAgainst = 0;
        points = 0;
    }

    public void startNewSeason() {
        startNewSeason(0);
    }

    public void startNewSeason(int seasonYear) {
        if (seasonYear > 0) {
            updateFocusStrategy(seasonYear);
        } else if (nextFocusChangeYear <= 0) {
            nextFocusChangeYear = 5;
        }

        evolveArroganceAndMismanagement(seasonYear);

        lastSeasonChampion = false;
        applyPromotionShock();
        beginSeasonEconomy();
        applySeasonMomentum();
        resetSeasonStats();
        youthMaintenancePaid = 0.0;
        developmentMaintenancePaid = 0.0;
        recentMatches.clear();
        lastMatchTime = Double.NaN;
        weeksSinceStrategyUpdate = 0;
        pointsSpentSeason = 0.0;
        ffpPenaltyAppliedThisSeason = false;
        depthPenaltyAppliedThisSeason = false;
        // Phase 3: Reset FFP tracking fields
        pointsDeductedThisSeason = 0;
        ffpPointsDeductedThisSeason = 0;
        floorPointsDeductedThisSeason = 0;
        ffpSeverityAtSeasonEnd = 0.0;
        ffpSeverity = 0.0;  // CRITICAL: Reset active FFP severity for new season
        // Reset unassigned friendly stats for new season
        friendliesPlayedThisSeason = 0;
        friendliesWonThisSeason = 0;
        if (isUnassigned) {
            seasonsUnassigned++;
        }
    }

    public void addLeagueMatchResult(int gf, int ga) {
        goalsFor += gf;
        goalsAgainst += ga;
        if (gf > ga) { wins++; points += 3; }
        else if (gf == ga) { draws++; points += 1; }
        else { losses++; }
    }

    public void prepareForMatch(double t) {
        if (Double.isNaN(t) || Double.isNaN(lastMatchTime)) {
            return;
        }
        double restDays = Math.max(0.0, (t - lastMatchTime) * 7.0);
        double recoveryRate = 0.02;
        fatigue = round2(clamp(fatigue - recoveryRate * restDays, 0.0, 1.0));
        availability = round2(clamp(availability + 0.5 * restDays, 60.0, 100.0));
    }

    public void finishMatch(double t, int pointsEarned, double expectedPoints, double intensity,
                            double opponentStrength, double ownStrength) {
        if (Double.isNaN(t)) {
            return;
        }
        double baseMatchLoad = 0.08;
        double travelFactor = 1.0;
        fatigue = round2(clamp(fatigue + baseMatchLoad * intensity * travelFactor, 0.0, 1.0));

        double restBonus = Double.isNaN(lastMatchTime) ? 1.0 : clamp((t - lastMatchTime) / 2.0, 0.0, 1.0);
        double moraleK = 2.5;
        double moraleDecay = 1.0;
        double moraleDelta = moraleK * (pointsEarned - expectedPoints);
        morale = round2(clamp(morale + moraleDelta - moraleDecay * (1.0 - restBonus), 0.0, 100.0));
        morale = round2(clamp(morale - fatigue * 8.0, 0.0, 100.0));

        lastMatchTime = t;
        recentMatches.addLast(t);
        while (!recentMatches.isEmpty() && recentMatches.peekFirst() < t - 2.0) {
            recentMatches.removeFirst();
        }

        availability = round2(clamp(availability - (2.0 + 8.0 * fatigue), 60.0, 100.0));
        cohesion = round2(clamp(cohesion + 0.05, 40.0, 100.0));

        applyMatchFormDrift(pointsEarned, expectedPoints);

        if (pointsEarned == 3) {
            badRunStreak = 0;
        } else {
            badRunStreak++;
            applyUpsetPressure(pointsEarned, expectedPoints, opponentStrength, ownStrength);
            if (badRunStreak >= nextTacticalShakeAt) {
                tacticalQuality = round2(clamp(20 + rng.nextDouble() * 80.0, 20.0, 100.0));
                nextTacticalShakeAt = 9 + rng.nextInt(8);
            }
            if (badRunStreak >= nextManagerShakeAt) {
                managerRating = round2(clamp(20 + rng.nextDouble() * 80.0, 20.0, 100.0));
                nextManagerShakeAt = 13 + rng.nextInt(9);
                badRunStreak = 0;
            }
        }
    }

    private void applyUpsetPressure(int pointsEarned, double expectedPoints,
                                    double opponentStrength, double ownStrength) {
        if (ownStrength <= 0 || opponentStrength <= 0) {
            return;
        }
        if (pointsEarned >= expectedPoints) {
            return;
        }
        double gap = ownStrength - opponentStrength;
        if (gap <= 6.0) {
            return;
        }
        double severity = clamp((gap - 6.0) / 8.0, 0.0, 1.0);
        double moraleHit = (1.5 + rng.nextDouble() * 3.0) * severity;
        double cohesionHit = (0.5 + rng.nextDouble() * 1.5) * severity;
        morale = round2(clamp(morale - moraleHit, 0.0, 100.0));
        cohesion = round2(clamp(cohesion - cohesionHit, 35.0, 100.0));
        tacticalQuality = round2(clamp(tacticalQuality - severity * 1.0, 20.0, 100.0));
        managerRating = clamp(managerRating - severity * 0.75, 18.0, 100.0);
        badRunStreak += (severity >= 0.6 ? 1 : 0);
    }

    private void applyMatchFormDrift(int pointsEarned, double expectedPoints) {
        double resultDelta = (pointsEarned - expectedPoints) * 0.6;
        double noise = rng.nextGaussian() * 0.6;
        managerRating = clamp(managerRating + resultDelta + noise * 0.5, 20.0, 100.0);
        tacticalQuality = clamp(tacticalQuality + resultDelta + noise * 0.6, 20.0, 100.0);
        systemFit = clamp(systemFit + resultDelta * 0.4 + rng.nextGaussian() * 0.4, 20.0, 100.0);
        cohesion = round2(clamp(cohesion + resultDelta * 0.6 + rng.nextGaussian() * 0.5, 35.0, 100.0));
        availability = round2(clamp(availability + rng.nextGaussian() * 0.4, 55.0, 100.0));

        youthRating = clamp(youthRating + rng.nextGaussian() * 0.15, 20.0, 100.0);
        developmentRating = clamp(developmentRating + rng.nextGaussian() * 0.15, 20.0, 100.0);

        double regression = clamp((expectedPoints - pointsEarned) / 3.0, 0.0, 1.5);
        regression *= clamp(1.0 - youthRating / 120.0, 0.4, 1.0);
        double dropRoll = regression + rng.nextDouble() * 0.25;
        int drop = dropRoll > 1.05 ? 2 : (dropRoll > 0.55 ? 1 : 0);
        if (drop > 0) {
            squadDepthPlayers = Math.max(14, squadDepthPlayers - drop);
        } else if (pointsEarned == 3 && rng.nextDouble() < 0.08) {
            squadDepthPlayers = Math.min(36, squadDepthPlayers + 1);
        }

        if (squadDepthPlayers > 32) {
            int trim = 1 + rng.nextInt(2);
            squadDepthPlayers = Math.max(28, squadDepthPlayers - trim);
        } else if (squadDepthPlayers < 16 && rng.nextDouble() < 0.25) {
            squadDepthPlayers = Math.min(20, squadDepthPlayers + 1);
        }

        if (squadDepthPlayers < 17 && !depthPenaltyAppliedThisSeason) {
            int deduction = domesticLevel <= 1 ? 6 : (domesticLevel <= 3 ? 4 : 2);
            applyPointDeduction(deduction);
            depthPenaltyAppliedThisSeason = true;
        }
    }

    public void weeklyTick(double t, boolean transferWindow) {
        double weeklyRevenue = 0.0;
        double requiredYouth = requiredMaintenance(youthRating) / 52.0;
        double requiredDev = requiredMaintenance(developmentRating) / 52.0;

        double wageMultiplier = clamp(0.85 + 0.3 * (managerRating / 100.0) + 0.2 * (recruitmentQuality / 100.0), 0.8, 1.2);
        double adjustedWageBill = wageBillWeekly * wageMultiplier;

        double targetYouth = youthSpendTarget / 52.0;
        double targetDev = devSpendTarget / 52.0;

        double youthSpend = Math.max(requiredYouth, targetYouth);
        double devSpend = Math.max(requiredDev, targetDev);

        double minSpend = (requiredYouth + requiredDev) * 0.65;
        double availableCash = cash + weeklyRevenue - adjustedWageBill;
        double plannedSpend = adjustedWageBill + youthSpend + devSpend;

        if (availableCash < minSpend) {
            double ratio = availableCash / Math.max(minSpend, 1.0);
            youthSpend *= ratio;
            devSpend *= ratio;
        } else if (availableCash < plannedSpend) {
            double shortfall = plannedSpend - availableCash;
            double reducible = Math.max(1.0, (youthSpend - requiredYouth) + (devSpend - requiredDev));
            double cutRatio = clamp(shortfall / reducible, 0.0, 1.0);
            youthSpend -= (youthSpend - requiredYouth) * cutRatio;
            devSpend -= (devSpend - requiredDev) * cutRatio;
        }

        youthMaintenancePaid += youthSpend;
        developmentMaintenancePaid += devSpend;

        cash = round2(cash + weeklyRevenue - adjustedWageBill - youthSpend - devSpend);
        pointsSpentSeason = round2(pointsSpentSeason + adjustedWageBill + youthSpend + devSpend);
        if (cash < 0) {
            cash = 0;
        }

        if (cash < reserveTarget && availableCash < plannedSpend) {
            morale = round2(clamp(morale - 0.8, 0.0, 100.0));
        }

        availability = round2(clamp(availability + rng.nextGaussian() * 0.5, 60.0, 100.0));
        cohesion = round2(clamp(cohesion + (transferWindow ? -0.05 : 0.02), 40.0, 100.0));
        applyWeeklyDevelopmentBoost();

        weeksSinceStrategyUpdate++;
        if (weeksSinceStrategyUpdate >= 4) {
            updateStrategyWeights();
            computePressures();
            computeSpendingTargets();
            weeksSinceStrategyUpdate = 0;
        }
    }

    public void endSeasonUpdate() {
        seasonsSimulated++;
        double reqYouth = requiredMaintenance(youthRating);
        double reqDev = requiredMaintenance(developmentRating);
        double youthCoverage = reqYouth > 0 ? youthMaintenancePaid / reqYouth : 1.0;
        double devCoverage = reqDev > 0 ? developmentMaintenancePaid / reqDev : 1.0;

        double devGrowthMultiplier = 0.8 + 0.6 * (developmentRating / 100.0);
        double youthGrowthMultiplier = 0.7 + 0.4 * (developmentRating / 100.0);
        youthRating = round2(updateFacilityRating(youthRating, youthCoverage, 0.6, youthGrowthMultiplier));
        developmentRating = round2(updateFacilityRating(developmentRating, devCoverage, 0.4, devGrowthMultiplier));

        spendHistory.addLast(pointsSpentSeason);
        if (spendHistory.size() > 3) spendHistory.removeFirst();
        rollingLoss3y = 0.0;
        for (double s : spendHistory) {
            rollingLoss3y += s;
        }

        ffpSeverity = round2(clamp(rollingLoss3y / 90.0, 0.0, 1.2));
        // NOTE: Cash-based modifiers (+0.10, +0.08) removed because they pushed FFP into global violation
        // FFP should be primarily about spending discipline, not liquidity
        // (liquidity issues are handled separately via morale, wage penalties, squad depth reductions)
        // if (latePaymentsFlag) {
        //     ffpSeverity = round2(clamp(ffpSeverity + 0.10, 0.0, 1.2));
        // }
        // if (cash < reserveTarget * 0.5) {
        //     ffpSeverity = round2(clamp(ffpSeverity + 0.08, 0.0, 1.2));
        // }

        if (pointsSpentSeason > 30.0) {
            highSpendStreak++;
        } else {
            highSpendStreak = 0;
        }
        if (highSpendStreak >= 3 && !ffpPenaltyAppliedThisSeason) {
            applyPointDeduction(6);
            ffpPenaltyAppliedThisSeason = true;
            repeatOffenses++;
        }

        youthMaintenancePaid = 0.0;
        developmentMaintenancePaid = 0.0;
        applyFacilitySeasonalDecay();
        applySeasonAging();
        applyRawStrengthDecay();
        applySuccessTax();
        applyDynastyFatigue();
        applyGenerationCycle();
        evolveHistoricalAnchor();
        // Mean-reversion toward baseline, but allow baseline to drift upward for overperformers
        if (rawStrength > baselineStrength + 2.0) {
            double uplift = clamp((rawStrength - baselineStrength) * 0.12, 0.0, 2.5);
            baselineStrength = clamp(baselineStrength + uplift, 15.0, 100.0);
        } else if (rawStrength < baselineStrength - 3.0) {
            double downDrift = clamp((baselineStrength - rawStrength) * 0.06, 0.0, 1.5);
            baselineStrength = clamp(baselineStrength - downDrift, 15.0, 100.0);
        }
        baselineStrength = clamp(baselineStrength + (historicalAnchorStrength - baselineStrength) * 0.25, 12.0, 105.0);
        double regressionStrength = 0.06;
        rawStrength = clamp(rawStrength + (baselineStrength - rawStrength) * regressionStrength, 14.0, 100.0);
        if (promotionCooldown > 0) {
            promotionCooldown--;
        }
        lastSeasonLevel = domesticLevel;
        
        // Update rolling 5-year continental coefficient history
        updateContinentalCoefficientHistory();
    }

    public void performAudit(boolean seasonEnd) {
        double severity = ffpSeverity;
        
        // Check for transfer overspending violations
        double transferSeverity = calculateTransferOverspendingSeverity();
        if (transferSeverity > 0.0) {
            severity = Math.max(severity, transferSeverity);
        }
        
        // AUDIT COUNTER: Increment audit counter to prove audits ran
        SeasonalEventLog log = SeasonalEventLog.getInstance();
        
        // Mid-season audit: only punish egregious cases (severity >= 1.0, not 0.85)
        // This prevents flagging "normal high spenders" and only catches true violators
        if (!seasonEnd && severity >= 1.0 && !ffpPenaltyAppliedThisSeason) {
            log.incrementFFPAudit();
            log.incrementFFPThresholdBreach();
            applyFFPPointDeduction(3);
            ffpPenaltyAppliedThisSeason = true;
            log.incrementFFPPenalty();
            log.logFFPViolation(this, severity, morale, 3);
            return;
        }

        if (seasonEnd) {
            // AUDIT COUNTER: Count final season audit
            log.incrementFFPAudit();
            if (severity >= 0.85) {
                log.incrementFFPThresholdBreach();
            }
            
            // Record FFP severity at season end
            ffpSeverityAtSeasonEnd = severity;
            // Only log FFP violations if FFP actually caused deductions
            if (ffpPointsDeductedThisSeason > 0) {
                log.incrementFFPPenalty();
                log.logFFPViolation(this, severity, morale, ffpPointsDeductedThisSeason);
            }
            // Log floor penalties separately
            if (floorPointsDeductedThisSeason > 0) {
                log.incrementFloorCheck();
                log.incrementFloorThresholdBreach();
                log.incrementFloorPenalty();
                log.logFloorPenalty(this, morale, floorPointsDeductedThisSeason);
            }
        }
    }

    /**
     * Calculate FFP severity from net economic loss in transfers
     * FFP violation: Net loss > 2x transfer budget
     */
    private double calculateTransferOverspendingSeverity() {
        // Calculate net economy change from transfers
        // rollingLoss3y tracks total points spent vs revenue over 3 years
        double netLossThisSeason = pointsSpentSeason - revenueSeason;
        
        if (netLossThisSeason <= 0 || transferBudgetSeason <= 0) {
            return 0.0;  // No loss, no violation
        }
        
        // FFP threshold should target aggressive overspending, not routine transfer activity.
        double budgetThreshold = transferBudgetSeason * 2.0;
        double operatingThreshold = revenueSeason * 0.55 + cash * 0.30;
        double ffpThreshold = Math.max(budgetThreshold, operatingThreshold);
        
        if (netLossThisSeason > ffpThreshold) {
            // Calculate severity: how much over the threshold
            double excess = netLossThisSeason - ffpThreshold;
            // Severity scales: 1x over = 0.25, 2x over = 0.5, 3x+ = 1.0+
            double severity = (excess / ffpThreshold) * 0.25;
            return clamp(severity, 0.0, 1.5);
        }
        
        return 0.0;
    }

    public double getAverageRating() {
        return baseRating;
    }

    public double getSquadStrength() {
        double youthFactor = (youthRating - 50.0) / 50.0;
        double devFactor = (developmentRating - 50.0) / 50.0;
        double devMultiplier = 0.5 + 0.5 * (youthRating / 100.0);
        double facilityBoost = 0.02 * youthFactor + 0.03 * devFactor * devMultiplier;
        double dynamicStrength = clamp((rawStrength + strengthTrend + squadStrengthDelta) * (1.0 + facilityBoost), 10.0,
                120.0);

        // Get blending weights from configuration
        double currentWeight = SimulatorConfigReader.getSquadStrengthCurrentWeight();
        double inertiaWeight = SimulatorConfigReader.getSquadStrengthInertiaWeight();
        double eloWeight = SimulatorConfigReader.getSquadStrengthEloWeight();
        
        // Normalize weights in case they don't sum to 1.0
        double totalWeight = currentWeight + inertiaWeight + eloWeight;
        if (totalWeight > 0) {
            currentWeight /= totalWeight;
            inertiaWeight /= totalWeight;
            eloWeight /= totalWeight;
        }

        // ELO provides match result feedback but shouldn't dominate
        double eloAnchor = eloToStrength(eloRating);
        
        // Blend using configured weights
        double blended = dynamicStrength * currentWeight + historicalAnchorStrength * inertiaWeight + eloAnchor * eloWeight;
        
        // Apply tier-based skill floor from config: clubs can't be weaker than their tier's baseline
        double tierFloor = getTierBasedSkillFloor();
        double result = Math.max(blended, tierFloor);
        
        return round2(clamp(result, 10.0, 120.0));
    }

    private double getTierBasedSkillFloor() {
        return SimulatorConfigReader.getTierSkillFloor(domesticLevel);
    }

    public double getMatchStrength(double baseStrength) {
        int load7 = countMatchesSince(2.0);
        int load14 = countMatchesSince(4.0);
        double loadPenalty = 1.0;
        if (load7 >= 2) loadPenalty *= 0.96;
        if (load14 >= 4) loadPenalty *= 0.96;

        double fatigueFactor = 1.0 - fatigue * 0.3;
        double moraleFactor = 1.0 + ((morale - 50.0) / 100.0) * 0.1;

        // First season: only basepower + morale + fatigue matter
        if (seasonsSimulated == 0) {
            return round2(baseStrength * fatigueFactor * moraleFactor * loadPenalty);
        }

        double managerForm = jitterAround(managerRating);
        double tacticalForm = jitterAround(tacticalQuality);
        double systemForm = jitterAround(systemFit);
        double cohesionForm = jitterAround(cohesion);
        double availabilityForm = jitterAround(availability);
        double depthForm = jitterAround(depthRating());

        double wManager = 0.7, wTactical = 0.7, wSystem = 0.5,
               wCohesion = 0.6, wAvailability = 0.8, wDepth = 0.6;
        double weightedSum = managerForm * wManager + tacticalForm * wTactical + systemForm * wSystem
            + cohesionForm * wCohesion + availabilityForm * wAvailability + depthForm * wDepth;
        double weightTotal = wManager + wTactical + wSystem + wCohesion + wAvailability + wDepth;
        double averageForm = weightedSum / weightTotal;
        double formMultiplier = 1.0 + ((averageForm - 50.0) / 50.0) * 0.03;

        double promotionPenalty = 1.0;
        if (promotionCooldown > 0) {
            // Gentler for single-level jumps (0.96), harsher for multi-level (0.93)
            int gap = Math.max(0, lastSeasonLevel - domesticLevel);
            promotionPenalty = gap >= 2 ? 0.93 : 0.96;
        }

        return round2(baseStrength * formMultiplier * fatigueFactor * moraleFactor
            * loadPenalty * promotionPenalty);
    }

    public String getName() { return name; }
    public String getNameWithCountry() {
        return name + " (" + country + ")";
    }
    public String getCountry() { return country; }
    public Confederation getConfederation() { return confederation; }
    public String getDomesticLeagueName() { return domesticLeagueName; }

    public void setDomesticLeagueName(String domesticLeagueName) {
        if (domesticLeagueName != null && !domesticLeagueName.trim().isEmpty()) {
            this.domesticLeagueName = domesticLeagueName.trim();
        }
    }

    public void setCountryAssociation(CountryAssociation assoc) {
        this.countryAssociation = assoc;
    }

    public CountryAssociation getCountryAssociation() {
        return countryAssociation;
    }

    public int getDomesticLevel() { return domesticLevel; }

    public void setDomesticLevel(int domesticLevel) {
        if (domesticLevel > 0) {
            this.domesticLevel = domesticLevel;
        }
    }

    /**
     * Apply exponential strength penalty on relegation if cohesion is low.
     * Low cohesion = broken team trust = severe performance drop.
     * Formula: penalty = 1.0 - ((50 - cohesion) / 50) ^ 2
     * 
     * If cohesion = 50: no penalty (1.0)
     * If cohesion = 40: 4% penalty (0.96)
     * If cohesion = 30: 16% penalty (0.84)
     * If cohesion = 20: 36% penalty (0.64)
     */
    public void applyRelegationCohesionPenalty() {
        if (cohesion < 50.0) {
            double cohesionGap = (50.0 - cohesion) / 50.0;
            double penalty = 1.0 - (cohesionGap * cohesionGap);
            
            // Apply penalty to rawStrength (the core squad quality)
            double strengthReduction = (100.0 - rawStrength) * (1.0 - penalty);
            rawStrength = clamp(rawStrength - strengthReduction, 15.0, 100.0);
            
            // Also reduce trend slightly (team is demoralized)
            strengthTrend = clamp(strengthTrend - 1.0, -5.0, 4.0);
            
            // Reduce morale further due to team breakdown
            morale = clamp(morale - 8.0, moraleFloor, 100.0);
            
            // Notify if severity and log to SeasonalEventLog
            double penaltyPercent = (1.0 - penalty) * 100;
            if (cohesion <= 35.0) {
                SeasonalEventLog.getInstance().logrelegationCrisis(this, cohesion, strengthReduction, penaltyPercent);
            }
        }
    }

    public double getBaseRating() { return baseRating; }
    public double getEloRating() { return eloRating; }
    public double getFatigue() { return fatigue; }
    public double getMorale() { return morale; }
    public int getSquadDepthPlayers() { return squadDepthPlayers; }
    public double getCohesion() { return cohesion; }
    public double getAvailability() { return availability; }

    public int getPoints() { return points; }
    public int getGoalDifference() { return goalsFor - goalsAgainst; }
    public int getGoalsFor() { return goalsFor; }
    public int getGoalsAgainst() { return goalsAgainst; }
    public int getWins() { return wins; }
    public int getDraws() { return draws; }
    public int getLosses() { return losses; }
    public int getGamesPlayed() { return wins + draws + losses; }

    public ClubArchetype getArchetype() { return archetype; }
    public double getMoraleFloor() { return moraleFloor; }
    public double getRiskTolerance() { return riskTolerance; }
    public double getResultsPressure() { return resultsPressure; }
    public double getFinancialPressure() { return financialPressure; }
    public double getFfpPressure() { return ffpPressure; }
    public double getInfrastructurePressure() { return infrastructurePressure; }
    public double[] getCurrentWeights() { return currentWeights.clone(); }
    public double getArrogance() { return arrogance; }
    public double getMismanagementRisk() { return mismanagementRisk; }
    public double getWageTarget() { return wageTarget; }
    public double getTransferTarget() { return transferTarget; }
    public double getTransferBudgetSeason() { return transferBudgetSeason; }
    public double getYouthSpendTarget() { return youthSpendTarget; }
    public double getDevSpendTarget() { return devSpendTarget; }
    public double getReserveTarget() { return reserveTarget; }
    public int getNextFocusChangeYear() { return nextFocusChangeYear; }
    public List<FocusEntry> getFocusHistory() { return new ArrayList<>(focusHistory); }
    public double getStrengthTrend() { return strengthTrend; }
    public double getSquadStrengthDelta() { return squadStrengthDelta; }
    public double getCash() { return cash; }
    public double getWageBillWeekly() { return wageBillWeekly; }
    public double getWageCapWeekly() { return wageCapWeekly; }
    public double getRevenueSeason() { return revenueSeason; }
    public double getPointsSpentSeason() { return pointsSpentSeason; }
    public double getRollingLoss3y() { return rollingLoss3y; }
    public double getFfpSeverity() { return ffpSeverity; }
    public int getRepeatOffenses() { return repeatOffenses; }
    public int getFFPPointsDeductedThisSeason() { return ffpPointsDeductedThisSeason; }
    public int getFloorPointsDeductedThisSeason() { return floorPointsDeductedThisSeason; }
    public int getBadRunStreak() { return badRunStreak; }
    public double getTacticalQuality() { return tacticalQuality; }
    public double getSystemFit() { return systemFit; }
    public double getRecruitmentQuality() { return recruitmentQuality; }
    public double getFinancialPower() { return financialPower; }
    public double getDevelopmentQuality() { return developmentQuality; }
    public double getYouthRating() { return youthRating; }
    public double getDevelopmentRating() { return developmentRating; }
    public double getManagerRating() { return managerRating; }
    public double getRawStrength() { return rawStrength; }
    
    // Phase 3: FFP Tracking getters
    public int getPointsDeductedThisSeason() { return pointsDeductedThisSeason; }
    public double getFfpSeverityAtSeasonEnd() { return ffpSeverityAtSeasonEnd; }

    public void adjustTransferBudget(double delta) {
        transferBudgetSeason = round2(Math.max(0.0, transferBudgetSeason + delta));
    }

    public void adjustCash(double delta) {
        cash = round2(Math.max(0.0, cash + delta));
    }

    public void spendSeasonPoints(double amount) {
        if (amount <= 0.0) return;
        cash = round2(Math.max(0.0, cash - amount));
        pointsSpentSeason = round2(pointsSpentSeason + amount);
    }

    public void earnSeasonPoints(double amount) {
        if (amount <= 0.0) return;
        cash = round2(Math.min(100.0, cash + amount));
    }

    public void applyTransferImpact(double strengthDelta, double cohesionDelta) {
        squadStrengthDelta = round2(clamp(squadStrengthDelta + strengthDelta, -25.0, 25.0));
        cohesion = round2(clamp(cohesion + cohesionDelta, 35.0, 100.0));
    }

    public void applyParityAdjustment(double strengthDelta, double financeDelta, double cohesionDelta, double moraleDelta) {
        rawStrength = clamp(rawStrength + strengthDelta * 0.55, 12.0, 100.0);
        squadStrengthDelta = round2(clamp(squadStrengthDelta + strengthDelta, -25.0, 25.0));
        financialPower = clamp(financialPower + financeDelta, 18.0, 100.0);
        recruitmentQuality = clamp(recruitmentQuality + financeDelta * 0.9, 18.0, 100.0);
        managerRating = clamp(managerRating + financeDelta * 0.6, 18.0, 100.0);
        morale = round2(clamp(morale + moraleDelta, 0.0, 100.0));
        cohesion = round2(clamp(cohesion + cohesionDelta, 35.0, 100.0));
    }

    public void applyEconomyPoints(double delta) {
        if (delta == 0.0) return;
        financialPower = clamp(financialPower + delta, 18.0, 100.0);
    }

    public void addTrophy(String competition, int year) {
        TrophyRecord record = trophiesByCompetition.computeIfAbsent(competition, k -> new TrophyRecord());
        record.add(year);
        trophySeasons.add(year);
    }

    public void markLeagueChampion(int seasonYear) {
        if (seasonYear <= 0) return;
        if (lastLeagueTitleYear == seasonYear - 1) {
            leagueTitleStreak++;
        } else {
            leagueTitleStreak = 1;
        }
        lastLeagueTitleYear = seasonYear;
        lastSeasonChampion = true;
    }

    public boolean hasTrophyInSeason(int seasonYear) {
        return trophySeasons.contains(seasonYear);
    }

    public String trophySummary(int maxEntries) {
        if (trophiesByCompetition.isEmpty()) return "No trophies recorded.";
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (Map.Entry<String, TrophyRecord> e : trophiesByCompetition.entrySet()) {
            if (count >= maxEntries) { sb.append("..."); break; }
            if (count > 0) sb.append("; ");
            sb.append(e.getKey()).append(" x").append(e.getValue().getTotalCount());
            count++;
        }
        return sb.toString();
    }

    public int getTrophyCount(String competition) {
        TrophyRecord record = trophiesByCompetition.get(competition);
        return record == null ? 0 : record.getTotalCount();
    }

    public void addSeasonContinentalCoefficient(double seasonPoints) {
        if (continentalCoefficientHistory.size() == 5) {
            continentalCoefficientHistory.removeFirst();
        }
        continentalCoefficientHistory.addLast(Math.max(0.0, seasonPoints));
        double sum = 0.0;
        for (double value : continentalCoefficientHistory) {
            sum += value;
        }
        rollingContinentalCoefficient = sum;
    }

    public double getRollingContinentalCoefficient() {
        return rollingContinentalCoefficient;
    }

    public List<Double> getContinentalCoefficientHistory() {
        return new ArrayList<>(continentalCoefficientHistory);
    }

    /**
     * Add coefficient points earned from continental cup performance.
     * Called during continental match result recording.
     */
    public void addContinentalCoefficientPoints(double points) {
        if (points > 0) {
            rollingContinentalCoefficient += points;
        }
    }

    /**
     * Record season end and update rolling 5-year coefficient history.
     * Called during season rollover.
     */
    public void updateContinentalCoefficientHistory() {
        continentalCoefficientHistory.addLast(rollingContinentalCoefficient);
        if (continentalCoefficientHistory.size() > 5) {
            continentalCoefficientHistory.removeFirst();
        }
        rollingContinentalCoefficient = 0.0; // Reset for next season
    }

    public List<Integer> getTrophyYears(String competition) {
        TrophyRecord record = trophiesByCompetition.get(competition);
        if (record == null) {
            return List.of();
        }
        return record.getRecentYears();
    }

    public void updateElo(double delta) {
        eloRating = Math.max(100, eloRating + delta);
    }

    public void recordSeasonStanding(int seasonYear, String leagueName, int leagueLevel, int position, int points) {
        recentStandings.addLast(new SeasonStanding(seasonYear, leagueName, leagueLevel, position, points));
        while (recentStandings.size() > 10) {
            recentStandings.removeFirst();
        }
    }

    public List<SeasonStanding> getRecentStandings() {
        return new ArrayList<>(recentStandings);
    }

    private void evolveArroganceAndMismanagement(int seasonYear) {
        int effectiveYear = seasonYear > 0 ? seasonYear : (currentFiveYearTermStart <= 0 ? 1 : currentFiveYearTermStart);

        if (currentFiveYearTermStart <= 0) {
            currentFiveYearTermStart = effectiveYear;
            mismanagedInCurrentTerm = false;
        } else if (effectiveYear >= currentFiveYearTermStart + 5) {
            currentFiveYearTermStart = effectiveYear;
            mismanagedInCurrentTerm = false;
        }

        arrogance = round2(clamp(arrogance + rng.nextGaussian() * 4.0, 0.0, 100.0));
        double baseRisk = 0.06 + (arrogance / 100.0) * 0.34;

        if (!mismanagedInCurrentTerm) {
            double termTriggerChance = clamp(0.10 + (arrogance / 100.0) * 0.35, 0.10, 0.70);
            if (rng.nextDouble() < termTriggerChance) {
                mismanagedInCurrentTerm = true;
            }
        }

        double termPenalty = mismanagedInCurrentTerm ? 0.24 : 0.0;
        mismanagementRisk = clamp(baseRisk + termPenalty + rng.nextGaussian() * 0.03, 0.02, 0.95);

        if (mismanagedInCurrentTerm) {
            double decisionShock = mismanagementRisk * (0.6 + rng.nextDouble() * 1.1);
            financialPower = clamp(financialPower - decisionShock * 3.0, 18.0, 100.0);
            cohesion = round2(clamp(cohesion - decisionShock * 2.0, 35.0, 100.0));
            morale = round2(clamp(morale - decisionShock * 2.4, 0.0, 100.0));
            recruitmentQuality = clamp(recruitmentQuality - decisionShock * 1.8, 18.0, 100.0);
        }
    }

    @Override
    public String toString() {
        return name + " (" + country + " | " + domesticLeagueName + " | ELO " + String.format("%.0f", eloRating) + ")";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Club)) return false;
        Club c = (Club)o;
        return name.equals(c.name) && country.equals(c.country);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, country);
    }

    public void updateStrategyWeights() {
        double[] base = archetype.getBaseWeights();
        System.arraycopy(base, 0, currentWeights, 0, NUM_WEIGHTS);

        int gamesPlayed = getGamesPlayed();
        if (morale < moraleFloor) {
            if (gamesPlayed > 0 && points < gamesPlayed * 1.2) {
                currentWeights[W_SURVIVAL]     += 0.15;
                currentWeights[W_STRENGTH_NOW] += 0.15;
                currentWeights[W_GROWTH]       -= 0.10;
                currentWeights[W_STABILITY]    -= 0.10;
            }
            if (cash < revenueSeason * 0.05) {
                currentWeights[W_ECONOMY]  += 0.20;
                currentWeights[W_PRESTIGE] -= 0.10;
            }
        }

        if (mismanagementRisk > 0.20) {
            double drift = mismanagementRisk * 0.18;
            currentWeights[W_PRESTIGE] += drift;
            currentWeights[W_STRENGTH_NOW] += drift * 0.75;
            currentWeights[W_ECONOMY] -= drift * 0.80;
            currentWeights[W_STABILITY] -= drift * 0.60;
        }

        double sum = 0;
        for (int i = 0; i < NUM_WEIGHTS; i++) {
            if (currentWeights[i] < 0) currentWeights[i] = 0;
            sum += currentWeights[i];
        }
        if (sum > 0) {
            for (int i = 0; i < NUM_WEIGHTS; i++) currentWeights[i] /= sum;
        }
    }

    public void computePressures() {
        int gamesPlayed = getGamesPlayed();
        double expectedPoints = gamesPlayed * 1.5 * (clamp(rawStrength + strengthTrend, 20.0, 110.0) / 80.0);
        resultsPressure = round2(clamp((expectedPoints - points) / Math.max(1, gamesPlayed) * 10, 0, 100));

        financialPressure = round2(clamp((pointsSpentSeason / Math.max(1.0, revenueSeason)) * 100, 0, 100));

        ffpPressure = round2(clamp(ffpSeverity * 100, 0, 100));

        double youthDev = Math.abs(youthRating - 50.0);
        double devDev = Math.abs(developmentRating - 50.0);
        infrastructurePressure = round2(clamp((youthDev + devDev) / 2.0, 0, 100));
    }

    public void computeSpendingTargets() {
        double wEconomy  = currentWeights[W_ECONOMY];
        double wStability = currentWeights[W_STABILITY];
        double wStrength = currentWeights[W_STRENGTH_NOW];
        double wGrowth   = currentWeights[W_GROWTH];
        double wPrestige = currentWeights[W_PRESTIGE];
        double wSurvival = currentWeights[W_SURVIVAL];

        wageTarget       = round2(revenueSeason * (0.30 + 0.20 * (wStrength + wPrestige)));
        transferTarget   = round2(revenueSeason * (0.10 + 0.20 * (wStrength + wSurvival)));
        youthSpendTarget = round2(4.0 + revenueSeason * (0.02 + 0.05 * wGrowth));
        devSpendTarget   = round2(4.0 + revenueSeason * (0.02 + 0.05 * wStability));

        double minReserve = 8.0;
        reserveTarget    = round2(Math.max(wSurvival >= 0.10 ? 0 : minReserve,
                                           6.0 + revenueSeason * (0.04 + 0.06 * wEconomy)));
    }

    private void beginSeasonEconomy() {
        // Revenue now depends on: tier level, results, financial power, facility quality, and nation coefficient
        // Base revenue tied to tier (higher tier = more matches + higher attendance + sponsorship)
        double tierMultiplier = Math.max(0.4, 1.0 - (domesticLevel * 0.12));  // Tier 1 = 1.0, Tier 5 = 0.52
        double lastSeasonPerf = lastSeasonPointsPerGame > 0 ? Math.min(lastSeasonPointsPerGame / 2.0, 1.0) : 0.5;  // 0-1 based on points/game
        double facilityMultiplier = (developmentRating + youthRating + recruitmentQuality) / 150.0;  // 0.33-2.0
        
        // Nation coefficient multiplier: Strong nations (coeff 30-200) get bonus, weak nations (coeff 0-15) get penalty
        // Coefficient range typically 0-200, median ~40
        double nationCoefficient = countryAssociation != null ? countryAssociation.getRollingCoefficient() : 40.0;
        double coefficientMultiplier = clamp(nationCoefficient / 40.0, 0.3, 2.5);  // 0.3x for coeff=12, 2.5x for coeff=100+
        
        revenueSeason = round2(clamp(
            25.0 + (financialPower * 0.35) + (lastSeasonPerf * 20.0) + (tierMultiplier * 15.0) + (facilityMultiplier * 10.0),
            15.0, 120.0
        ));
        revenueSeason = round2(revenueSeason * coefficientMultiplier);
        
        cash = round2(revenueSeason);
        
        // Wage bill depends on squad depth and manager rating (not just static)
        double wageSeason = clamp(10.0 + (squadDepthPlayers - 14) * 0.5 + managerRating / 20.0, 6.0, 35.0);
        wageBillWeekly = round2(wageSeason / 52.0);
        
        // Wage cap should be 70% of revenue, but clubs in lower tiers need flexibility
        double wageCapRatio = 0.70 - (domesticLevel * 0.05);  // Tier 1: 70%, Tier 5: 50%
        wageCapWeekly = round2((revenueSeason * wageCapRatio) / 52.0);
        
        transferBudgetSeason = round2(clamp(
            3.0 + (financialPower / 15.0) + (recruitmentQuality / 30.0) + (lastSeasonPerf * 8.0),
            2.0, 30.0
        ));
        
        fatigue = round2(clamp(fatigue * 0.6, 0.0, 1.0));
        morale = round2(clamp(50.0 + (morale - 50.0) * 0.4, 0.0, 100.0));
        cohesion = round2(clamp(cohesion * 0.98 + 1.0, 40.0, 100.0));
        availability = round2(clamp(availability + 5.0, 60.0, 100.0));
        recentMatches.clear();
        lastMatchTime = Double.NaN;

        // Apply financial floor penalties if unable to afford tier wages
        applyFinancialFloorPenalty();
        
        if (ffpSeverity > 0.6 || badRunStreak >= 2) {
            wageCapWeekly = round2(wageCapWeekly * 0.9);
            transferBudgetSeason = round2(transferBudgetSeason * 0.7);
        }

        updateStrategyWeights();
        computePressures();
        computeSpendingTargets();
        weeksSinceStrategyUpdate = 0;
    }

    private void applySeasonMomentum() {
        int gamesPlayed = getGamesPlayed();
        if (gamesPlayed == 0) return;

        double pointsPerGame = getPoints() / Math.max(1.0, gamesPlayed);
        double overPerf = pointsPerGame - 1.35;
        double developmentBoost = (developmentRating - 50.0) / 50.0;
        double budgetBoost = (financialPower - 50.0) / 50.0;

        double driftNoise = rng.nextGaussian() * 0.35;
        double elitePenalty = clamp((rawStrength - 82.0) / 30.0, 0.0, 1.0) * (0.5 + rng.nextDouble());
        strengthTrend = clamp(strengthTrend * 0.55 + overPerf * 0.8 + driftNoise - elitePenalty, -6.0, 3.5);

        double retention = clamp(0.85 + 0.05 * developmentBoost + 0.05 * budgetBoost, 0.75, 0.95);
        double momentum = clamp(overPerf * 4.5, -2.5, 3.0) * (0.6 + 0.4 * developmentBoost);

        tacticalQuality = round2(clamp(tacticalQuality * retention + momentum * 0.5, 20.0, 100.0));
        cohesion = round2(clamp(cohesion * retention + momentum, 35.0, 100.0));
        availability = round2(clamp(availability + momentum * 1.2, 55.0, 100.0));

        if (overPerf > 0.35) {
            squadDepthPlayers = Math.min(36, squadDepthPlayers + 1);
        } else if (overPerf < -0.35) {
            squadDepthPlayers = Math.max(14, squadDepthPlayers - 1);
        }

        if (badRunStreak >= 2) {
            morale = round2(clamp(morale - 6.0, 0.0, 100.0));
            cohesion = round2(clamp(cohesion - 4.0, 35.0, 100.0));
        } else if (overPerf > 0.6) {
            managerRating = round2(clamp(managerRating + 0.8, 20.0, 100.0));
        }

        lastSeasonPointsPerGame = pointsPerGame;
    }

    private void applyWeeklyDevelopmentBoost() {
        double youthBoost = (youthRating / 100.0) * 0.006;
        double devBoost = (developmentRating / 100.0) * 0.008;
        squadStrengthDelta = round2(clamp(squadStrengthDelta + youthBoost + devBoost, -25.0, 25.0));
    }

    private void applyFacilitySeasonalDecay() {
        double focusMultiplier = (archetype == ClubArchetype.YOUTH || archetype == ClubArchetype.FACILITY) ? 0.3 : 1.0;
        double youthDecay = (0.4 + rng.nextDouble() * 1.1) * focusMultiplier;
        double devDecay = (0.4 + rng.nextDouble() * 1.0) * focusMultiplier;
        youthRating = round2(clamp(youthRating - youthDecay, 20.0, 100.0));
        developmentRating = round2(clamp(developmentRating - devDecay, 20.0, 100.0));
    }

    private void applySeasonAging() {
        // Lower clubs have smaller squads and less to lose from aging
        double tierScale = clamp(0.5 + rawStrength / 120.0, 0.55, 1.0);
        double ageDecay = rng.nextDouble() * 3.2 * tierScale;
        double tierPenalty = clamp((rawStrength - 80.0) / 40.0, 0.0, 1.0) * (0.6 + rng.nextDouble() * 0.8);
        double successPenalty = clamp(lastSeasonPointsPerGame - 1.7, 0.0, 1.5) * (0.6 + rng.nextDouble());
        double youthShield = clamp(1.1 - youthRating / 120.0, 0.5, 1.1);
        double decay = (ageDecay + tierPenalty + successPenalty) * youthShield;
        squadStrengthDelta = round2(clamp(squadStrengthDelta - decay, -25.0, 25.0));
    }

    private void applyRawStrengthDecay() {
        double dynastyPenalty = leagueTitleStreak >= 2
            ? (0.25 * (leagueTitleStreak - 1) + rng.nextDouble() * 0.5)
            : 0.0;
        double elitePenalty = clamp((rawStrength - 70.0) / 25.0, 0.0, 1.0) * (0.8 + rng.nextDouble() * 0.8);
        double youthShield = clamp(1.05 - youthRating / 140.0, 0.6, 1.05);
        // Weaker clubs decay less  Ethey have less to lose
        double tierShield = clamp(1.2 - rawStrength / 90.0, 0.4, 1.0);
        double baselineDecay = (0.5 + rng.nextDouble() * 0.5 + dynastyPenalty) * youthShield * tierShield;
        rawStrength = clamp(rawStrength - baselineDecay - elitePenalty * youthShield, 14.0, 100.0);
    }

    private void applySuccessTax() {
        boolean successful = lastSeasonPointsPerGame >= 1.75;
        successStreak = successful ? successStreak + 1 : 0;
        if (successStreak >= 3) {
            double multiplier = 1.0 + (successStreak - 3) * 0.35;
            double tax = (0.7 + rng.nextDouble() * 1.6) * multiplier;
            financialPower = clamp(financialPower - tax, 18.0, 100.0);
            recruitmentQuality = clamp(recruitmentQuality - tax * 0.95, 18.0, 100.0);
            managerRating = clamp(managerRating - tax * 0.85, 18.0, 100.0);
            developmentQuality = clamp(developmentQuality - tax * 0.4, 18.0, 100.0);
        }
    }

    private void applyDynastyFatigue() {
        if (!lastSeasonChampion && successStreak < 2) {
            return;
        }
        double dominance = clamp(lastSeasonPointsPerGame - 1.9, 0.0, 1.2);
        double streakFactor = Math.max(0, leagueTitleStreak - 1) * 0.35;
        double tax = (0.6 + rng.nextDouble() * 1.4) * (1.0 + dominance + streakFactor);

        financialPower = clamp(financialPower - tax, 18.0, 100.0);
        recruitmentQuality = clamp(recruitmentQuality - tax * 0.9, 18.0, 100.0);
        managerRating = clamp(managerRating - tax * 0.7, 18.0, 100.0);
        tacticalQuality = clamp(tacticalQuality - tax * 0.6, 20.0, 100.0);

        double moraleHit = (1.0 + rng.nextDouble() * 3.0) * (0.5 + dominance);
        morale = round2(clamp(morale - moraleHit, 0.0, 100.0));
        cohesion = round2(clamp(cohesion - moraleHit * 0.8, 35.0, 100.0));

        rawStrength = clamp(rawStrength - (0.2 + rng.nextDouble() * 0.6) * (1.0 + dominance + streakFactor),
            12.0, 100.0);
        squadStrengthDelta = round2(clamp(squadStrengthDelta
            - (0.8 + rng.nextDouble() * 1.6) * (0.6 + dominance + streakFactor), -25.0, 25.0));
    }

    private void evolveHistoricalAnchor() {
        double baseAnchor = compressRating(baseRating);
        double pointsSignal = clamp((lastSeasonPointsPerGame - 1.20) * 0.8, -1.2, 1.2);
        double infrastructureSignal = (((youthRating + developmentRating + recruitmentQuality) / 3.0) - 50.0) / 50.0;
        double financialSignal = (financialPower - 50.0) / 50.0;
        double identityPull = (baseAnchor - historicalAnchorStrength) * 0.04;
        double stochasticDrift = rng.nextGaussian() * 0.9;

        double yearlyDrift = pointsSignal + infrastructureSignal * 0.8 + financialSignal * 0.5
                + identityPull + stochasticDrift;
        double minAnchor = Math.max(10.0, baseAnchor - 22.0);
        double maxAnchor = Math.min(112.0, baseAnchor + 26.0);
        historicalAnchorStrength = clamp(historicalAnchorStrength + yearlyDrift, minAnchor, maxAnchor);

        // Rare era-shift events create long-run historical turnover over centuries.
         if (rng.nextDouble() < 0.018) {
            double eraShock = 4.0 + rng.nextDouble() * 10.0;
            if (rng.nextBoolean()) {
                eraShock = -eraShock;
            }
            double rebuildBias = (infrastructureSignal + financialSignal) * 3.5;
            historicalAnchorStrength = clamp(historicalAnchorStrength + eraShock + rebuildBias, minAnchor, maxAnchor);
            rawStrength = clamp(rawStrength + eraShock * 0.45 + rng.nextGaussian() * 1.2, 12.0, 100.0);
            squadStrengthDelta = round2(clamp(squadStrengthDelta + eraShock * 0.55, -25.0, 25.0));
        }
    }

    private void applyGenerationCycle() {
        if (seasonsSimulated < nextGenerationCycle) {
            return;
        }
        double rebuildPotential = ((youthRating + developmentRating + financialPower + recruitmentQuality) / 4.0 - 50.0)
                / 50.0;
        double cycleSwing = (3.0 + rng.nextDouble() * 8.0) * (rng.nextBoolean() ? 1.0 : -1.0);
        double netShift = cycleSwing + rebuildPotential * 4.0 + rng.nextGaussian() * 1.2;

        squadStrengthDelta = round2(clamp(squadStrengthDelta + netShift, -25.0, 25.0));
        rawStrength = clamp(rawStrength + netShift * 0.35, 12.0, 100.0);
        historicalAnchorStrength = clamp(historicalAnchorStrength + netShift * 0.25, 10.0, 112.0);

        double cohesionShift = netShift >= 0 ? (1.0 + rng.nextDouble() * 3.0) : -(2.0 + rng.nextDouble() * 4.0);
        cohesion = round2(clamp(cohesion + cohesionShift, 35.0, 100.0));
        morale = round2(clamp(morale + (netShift >= 0 ? 2.0 : -3.0), 0.0, 100.0));

        nextGenerationCycle = seasonsSimulated + 8 + rng.nextInt(13);
    }

    private void applyPromotionShock() {
        if (lastSeasonLevel <= 0 || domesticLevel <= 0) {
            return;
        }
        if (domesticLevel < lastSeasonLevel) {
            // Promoted  Eadapt tactically; single-level jumps are gentler
            int gapLevels = lastSeasonLevel - domesticLevel;
            double levelGap = clamp(gapLevels / 3.0, 0.30, 1.0);
            double shockScale = gapLevels >= 2 ? 1.0 : 0.6;
            double adaptShock = (2.0 + rng.nextDouble() * 3.0) * levelGap * shockScale;
            tacticalQuality = round2(clamp(tacticalQuality - adaptShock, 20.0, 100.0));
            systemFit = round2(clamp(systemFit - adaptShock * 0.8, 20.0, 100.0));
            cohesion = round2(clamp(cohesion - (1.5 + rng.nextDouble() * 2.5) * levelGap * shockScale, 35.0, 100.0));
            morale = round2(clamp(morale - (1.0 + rng.nextDouble() * 2.0) * levelGap * shockScale, 0.0, 100.0));
            // Promoted clubs get a morale boost from the achievement
            morale = round2(clamp(morale + 4.0 + rng.nextDouble() * 6.0, 0.0, 100.0));
            promotionCooldown = Math.max(promotionCooldown, 1);
        } else if (domesticLevel > lastSeasonLevel) {
            // Relegated  Eexodus of players and staff
            double levelGap = clamp((domesticLevel - lastSeasonLevel) / 3.0, 0.30, 1.0);
            double exodus = (3.0 + rng.nextDouble() * 5.0) * levelGap;
            managerRating = clamp(managerRating - exodus * 0.7, 18.0, 100.0);
            recruitmentQuality = clamp(recruitmentQuality - exodus * 0.6, 18.0, 100.0);
            financialPower = clamp(financialPower - exodus * 0.8, 18.0, 100.0);
            squadDepthPlayers = Math.max(14, squadDepthPlayers - (int)(levelGap * 3));
            rawStrength = clamp(rawStrength - exodus * 0.4, 14.0, 100.0);
            // Morale hit but remaining players band together
            morale = round2(clamp(morale - exodus * 0.5, 0.0, 100.0));
            cohesion = round2(clamp(cohesion + (1.0 + rng.nextDouble() * 2.0), 35.0, 100.0));
        }
    }

    private void updateFocusStrategy(int seasonYear) {
        if (nextFocusChangeYear <= 0) {
            nextFocusChangeYear = seasonYear + 5;
            focusHistory.addLast(new FocusEntry(seasonYear, archetype));
            return;
        }
        if (seasonYear < nextFocusChangeYear) {
            return;
        }

        ClubArchetype old = archetype;
        archetype = pickWeakestFocus();

        nextFocusChangeYear = seasonYear + 5;
        if (archetype != old) {
            focusHistory.addLast(new FocusEntry(seasonYear, archetype));
            while (focusHistory.size() > 10) {
                focusHistory.removeFirst();
            }
        }
    }

    private ClubArchetype pickWeakestFocus() {
        double revenueScore = financialPower;
        double youthScore = youthRating;
        double strengthScore = clamp(rawStrength + strengthTrend + squadStrengthDelta, 0.0, 120.0) / 1.2;
        double depthScore = clamp((squadDepthPlayers - 14.0) / 22.0, 0.0, 1.0) * 100.0;
        double managerScore = (managerRating + tacticalQuality) / 2.0;
        double facilityScore = developmentRating;

        ClubArchetype weakest = ClubArchetype.REVENUE;
        double weakestScore = revenueScore;
        if (youthScore < weakestScore) { weakest = ClubArchetype.YOUTH; weakestScore = youthScore; }
        if (strengthScore < weakestScore) { weakest = ClubArchetype.PURE_STRENGTH; weakestScore = strengthScore; }
        if (depthScore < weakestScore) { weakest = ClubArchetype.SQUAD_DEPTH; weakestScore = depthScore; }
        if (managerScore < weakestScore) { weakest = ClubArchetype.GOOD_MANAGER; weakestScore = managerScore; }
        if (facilityScore < weakestScore) { weakest = ClubArchetype.FACILITY; }
        return weakest;
    }

    private double requiredMaintenance(double rating) {
        return 8.0;
    }

    private double updateFacilityRating(double rating, double coverage, double baseDecay, double growthMultiplier) {
        double decay = clamp(baseDecay + 2.0 * (1.0 - coverage), 0.1, 2.5);
        double multiplier = clamp(growthMultiplier, 0.7, 1.4);
        double growth = coverage > 1.0 ? clamp(0.4 * (coverage - 1.0) * multiplier, 0.0, 0.6) : 0.0;
        return clamp(rating - decay + growth, 0.0, 100.0);
    }

    private void applyPointDeduction(int points) {
        this.points = Math.max(0, this.points - points);
        this.pointsDeductedThisSeason += points;
    }

    private void applyFFPPointDeduction(int points) {
        applyPointDeduction(points);
        this.ffpPointsDeductedThisSeason += points;
    }

    private void applyFloorPointDeduction(int points) {
        applyPointDeduction(points);
        this.floorPointsDeductedThisSeason += points;
    }

    public void applyAdministrativePointDeduction(int points) {
        if (points <= 0) {
            return;
        }
        applyFloorPointDeduction(points);
    }

    private double jitterAround(double value) {
        return clamp(value + (rng.nextDouble() * 3.0 - 1.5), 20.0, 100.0);
    }

    private void applyFinancialFloorPenalty() {
        // If a club can't afford wages for its tier, apply consequences
        // Adjust floor based on nation coefficient: weaker nations have higher minimum (harder to survive)
        double nationCoefficient = countryAssociation != null ? countryAssociation.getRollingCoefficient() : 40.0;
        double coefficientPenalty = Math.max(1.0, 40.0 / Math.max(5.0, nationCoefficient));  // Weak nation: 1.0-8x penalty
        
        double minimumWageForTier = (8.0 + (domesticLevel * 1.5)) * coefficientPenalty;  // Adjusted by nation strength
        
        if (wageBillWeekly < minimumWageForTier * 0.5) {
            // Severe financial crisis: deduct league points, reduce squad depth
            // Weaker nations get harsher penalties
            int pointDeduction = (int) Math.ceil(3.0 * coefficientPenalty * 0.3);  // 3-24 points based on nation
            applyFloorPointDeduction(Math.min(pointDeduction, 10));  // Cap at 10 points
            squadDepthPlayers = Math.max(14, squadDepthPlayers - (int)Math.ceil(coefficientPenalty * 0.5));
            // NOTE: FFP severity is NOT modified here—financial floor is a separate penalty system
            morale = clamp(morale - (5.0 + coefficientPenalty * 2.0), 0.0, 100.0);
        } else if (wageBillWeekly < minimumWageForTier * 0.8) {
            // Moderate financial strain: reduce squad depth slightly
            // Weaker nations get more severe consequences
            int depthReduction = rng.nextBoolean() ? 1 : 0;
            if (coefficientPenalty > 1.5) {
                depthReduction = (int) Math.ceil(coefficientPenalty * 0.3);  // Extra reduction for weak nations
            }
            squadDepthPlayers = Math.max(14, squadDepthPlayers - depthReduction);
            // NOTE: FFP severity is NOT modified here—financial floor is a separate penalty system
            morale = clamp(morale - (2.0 + coefficientPenalty * 1.0), 0.0, 100.0);
        }
    }

    private double depthRating() {
        double rating = 70.0 + (squadDepthPlayers - 18) * 1.4;
        if (squadDepthPlayers > 30) {
            rating -= (squadDepthPlayers - 30) * 2.2;
        }
        return clamp(rating, 35.0, 100.0);
    }

    private int countMatchesSince(double window) {
        if (Double.isNaN(lastMatchTime)) return 0;
        double threshold = lastMatchTime - window;
        int count = 0;
        for (double t : recentMatches) {
            if (t >= threshold) count++;
        }
        return count;
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    public static final class SeasonStanding {
        private final int seasonYear;
        private final String leagueName;
        private final int leagueLevel;
        private final int position;
        private final int points;

        private SeasonStanding(int seasonYear, String leagueName, int leagueLevel, int position, int points) {
            this.seasonYear = seasonYear;
            this.leagueName = leagueName;
            this.leagueLevel = leagueLevel;
            this.position = position;
            this.points = points;
        }

        public int getSeasonYear() { return seasonYear; }
        public String getLeagueName() { return leagueName; }
        public int getLeagueLevel() { return leagueLevel; }
        public int getPosition() { return position; }
        public int getPoints() { return points; }
    }

    private static final class TrophyRecord {
        private int totalCount;
        private final Deque<Integer> recentYears = new ArrayDeque<>();

        private void add(int year) {
            totalCount++;
            recentYears.addLast(year);
            while (recentYears.size() > MAX_TROPHY_YEARS) {
                recentYears.removeFirst();
            }
        }

        private int getTotalCount() { return totalCount; }

        private List<Integer> getRecentYears() {
            return new ArrayList<>(recentYears);
        }
    }

    public static final class FocusEntry {
        private final int seasonYear;
        private final ClubArchetype archetype;

        private FocusEntry(int seasonYear, ClubArchetype archetype) {
            this.seasonYear = seasonYear;
            this.archetype = archetype;
        }

        public int getSeasonYear() { return seasonYear; }
        public ClubArchetype getArchetype() { return archetype; }
    }

    // Unassigned club system - getters and setters
    public boolean isUnassigned() {
        return isUnassigned;
    }

    public void setUnassigned(boolean unassigned) {
        isUnassigned = unassigned;
    }

    public int getSeasonsUnassigned() {
        return seasonsUnassigned;
    }

    public void setSeasonsUnassigned(int seasons) {
        this.seasonsUnassigned = seasons;
    }

    public void incrementSeasonsUnassigned() {
        this.seasonsUnassigned++;
    }

    public double getRatingAtDropdown() {
        return ratingAtDropdown;
    }

    public void setRatingAtDropdown(double rating) {
        this.ratingAtDropdown = rating;
    }

    public int getFriendliesPlayedThisSeason() {
        return friendliesPlayedThisSeason;
    }

    public void setFriendliesPlayedThisSeason(int count) {
        this.friendliesPlayedThisSeason = count;
    }

    public int getFriendliesWonThisSeason() {
        return friendliesWonThisSeason;
    }

    public void setFriendliesWonThisSeason(int count) {
        this.friendliesWonThisSeason = count;
    }

    public void recordFriendlyWin() {
        this.friendliesPlayedThisSeason++;
        this.friendliesWonThisSeason++;
    }

    public void recordFriendlyDraw() {
        this.friendliesPlayedThisSeason++;
    }

    public void recordFriendlyLoss() {
        this.friendliesPlayedThisSeason++;
    }

    public void applyRatingPenaltyForDropdown(double penalty) {
        this.ratingAtDropdown = this.rawStrength;
        this.rawStrength = Math.max(15.0, this.rawStrength - penalty);
    }

    public void applyRatingChangeFromFriendly(int ratingDelta) {
        this.rawStrength = clamp(this.rawStrength + ratingDelta, 15.0, 100.0);
    }
    
    /**
     * Apply financial and institutional recovery when a club returns from unassigned status.
     * This breaks the dropout spiral by giving struggling clubs a real chance to recover.
     */
    public void applyRecoveryBonusOnReassignment(double recoveryStrength) {
         // Randomize recovery bonuses for diversity (not hardcoded values)
         // Vary by ±30% to reduce predictability and create interesting club outcomes
         Random rand = new Random(Double.doubleToLongBits(recoveryStrength) ^ System.nanoTime());
         
         // Revenue: base 5-15, scaled by recovery strength, with ±30% variance
         double baseRevenue = 5.0 + (10.0 * recoveryStrength);
         double revenueLift = baseRevenue * (0.7 + (0.6 * rand.nextDouble()));
         revenueSeason = clamp(revenueSeason + revenueLift, 15.0, 100.0);
         
         // Cash: base 20-50, scaled by recovery strength, with ±30% variance
         double baseCash = 20.0 + (30.0 * recoveryStrength);
         double cashBoost = baseCash * (0.7 + (0.6 * rand.nextDouble()));
         cash = clamp(cash + cashBoost, 10.0, 100.0);
         
         // Youth: base 2-5, scaled by recovery strength, with ±30% variance
         double baseYouth = 2.0 + (3.0 * recoveryStrength);
         double youthBoost = baseYouth * (0.7 + (0.6 * rand.nextDouble()));
         youthRating = clamp(youthRating + youthBoost, 10.0, 100.0);
         
         // Modest morale recovery
         morale = clamp(morale + 10.0, 20.0, 100.0);
         
         // REMOVED: Recovery announcements suppressed for cleaner output
         // Recovery bonuses are silently applied to reduce console clutter
     }
    
    /**
     * Increase youth academy players per season for struggling clubs (helps long-term recovery).
     * Called during endSeasonUpdate for clubs with low development.
     */
    public void boostYouthAcademyOutput(double developmentFactor) {
        // Base: 0.3-0.5 new players per season depending on development
        // Bonus: +0.1 to +0.2 for struggling clubs
        double youthProductionIncrease = 0.1 + (0.1 * developmentFactor);
        youthRating = clamp(youthRating + youthProductionIncrease, 10.0, 100.0);
    }
}
