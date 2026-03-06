package worldfootballsim;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class SimulatorConfigReader {

    private static final Map<String, Double> globalConfig = new HashMap<>();
    private static final Map<String, Map<Integer, Double>> nationCoefficients = new HashMap<>();

    /**
     * Reads global simulator configuration from CSV.
     * Format: parameter_category,parameter_name,current_value,description,min_value,max_value
     */
    public static void readGlobalConfig(String csvPath) throws IOException {
        globalConfig.clear();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(csvPath), StandardCharsets.UTF_8))) {
            String header = reader.readLine();
            if (header == null) return;

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty() || line.startsWith("#")) continue;

                List<String> values = OptaReader.parseCsvLinePublic(line);
                if (values.size() < 3) continue;

                String category = values.get(0).trim();
                String name = values.get(1).trim();
                String valueStr = values.get(2).trim();

                if (name.isEmpty() || valueStr.isEmpty()) continue;

                String key = category.isEmpty() ? name : (category + "." + name);
                try {
                    double value = parseValue(valueStr);
                    globalConfig.put(key.toLowerCase(Locale.ROOT), value);
                } catch (NumberFormatException e) {
                    // Skip non-numeric values (like "true"/"false")
                    if ("true".equalsIgnoreCase(valueStr)) {
                        globalConfig.put(key.toLowerCase(Locale.ROOT), 1.0);
                    } else if ("false".equalsIgnoreCase(valueStr)) {
                        globalConfig.put(key.toLowerCase(Locale.ROOT), 0.0);
                    }
                }
            }
        }
    }

    /**
     * Reads nation coefficient configuration from CSV.
     * Format: nation,confederation,year1,year2,...
     * 
     * Supports flexible year columns. Headers like "2021", "2022", etc. are parsed as years.
     * Also supports descriptive headers like "coefficient_2022".
     * 
     * Example:
     *   nation,confederation,2021,2022,2023,2024,2025
     *   England,EUROPE,24.357,21,23,17.375,29.464
     */
    public static void readNationCoefficients(String csvPath) throws IOException {
        nationCoefficients.clear();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(csvPath), StandardCharsets.UTF_8))) {
            String header = reader.readLine();
            if (header == null) return;

            // Parse header to identify year columns
            List<String> headers = OptaReader.parseCsvLinePublic(header);
            Map<Integer, Integer> yearColumnIndex = new HashMap<>();
            
            // Enable coefficient parser diagnostics with: -Dworldsim.coeff.debug=true
            boolean debugMode = Boolean.getBoolean("worldsim.coeff.debug");
            if (debugMode) System.out.println("[CoeffConfig] Headers: " + headers);
            
            for (int i = 2; i < headers.size(); i++) {
                String h = headers.get(i).trim();
                if (h.isEmpty()) continue;
                
                // Try parsing as direct year (e.g., "2021", "2022")
                try {
                    int year = Integer.parseInt(h);
                    yearColumnIndex.put(year, i);
                    if (debugMode) System.out.println("[CoeffConfig] Found year " + year + " at column " + i);
                    continue;
                } catch (NumberFormatException e) {
                    // Not direct year
                }
                
                // Try extracting year from descriptive header (e.g., "coefficient_2022" ↁE2022)
                if (h.contains("_")) {
                    String[] parts = h.split("_");
                    try {
                        int year = Integer.parseInt(parts[parts.length - 1]);
                        yearColumnIndex.put(year, i);
                        if (debugMode) System.out.println("[CoeffConfig] Found year " + year + " in descriptive header at column " + i);
                    } catch (NumberFormatException e) {
                        // Not a year-based column, skip
                    }
                }
            }
            
            if (debugMode) System.out.println("[CoeffConfig] Year column index: " + yearColumnIndex);

            String line;
            int lineNum = 0;
            while ((line = reader.readLine()) != null) {
                lineNum++;
                if (line.trim().isEmpty() || line.startsWith("#")) continue;

                List<String> values = OptaReader.parseCsvLinePublic(line);
                if (values.size() < 3) continue;

                String nation = values.get(0).trim();
                if (nation.isEmpty()) continue;

                Map<Integer, Double> coeffsByYear = new HashMap<>();
                try {
                    for (Map.Entry<Integer, Integer> entry : yearColumnIndex.entrySet()) {
                        int year = entry.getKey();
                        int colIndex = entry.getValue();
                        if (colIndex < values.size()) {
                            String valueStr = values.get(colIndex).trim();
                            if (!valueStr.isEmpty()) {
                                coeffsByYear.put(year, parseValue(valueStr));
                            }
                        }
                    }
                } catch (NumberFormatException e) {
                    // Skip nations with invalid coefficient data
                    if (debugMode) System.out.println("[CoeffConfig] Parse error for nation " + nation);
                    continue;
                }

                if (!coeffsByYear.isEmpty()) {
                    nationCoefficients.put(nation.toLowerCase(Locale.ROOT), coeffsByYear);
                    if (debugMode && nation.equalsIgnoreCase("England")) {
                        System.out.println("[CoeffConfig] Loaded " + nation + ": " + coeffsByYear);
                    }
                }
            }
            
            if (debugMode) System.out.println("[CoeffConfig] Total nations loaded: " + nationCoefficients.size());
        }
    }

    /**
     * Get a global configuration parameter value.
     * Key format: "category.parameter_name" (case-insensitive)
     */
    public static double getGlobalConfig(String key, double defaultValue) {
        return globalConfig.getOrDefault(key.toLowerCase(Locale.ROOT), defaultValue);
    }

    /**
     * Get a nation's coefficient for a specific year.
     */
    public static double getNationCoefficient(String nation, int year, double defaultValue) {
        Map<Integer, Double> coeffs = nationCoefficients.get(nation.toLowerCase(Locale.ROOT));
        if (coeffs == null) return defaultValue;
        return coeffs.getOrDefault(year, defaultValue);
    }

    /**
     * Get the most recent available coefficient for a nation (up to specified year).
     * Useful when starting simulation in year with incomplete rolling data.
     */
    public static double getNationCoefficientLatest(String nation, int maxYear, double defaultValue) {
        Map<Integer, Double> coeffs = nationCoefficients.get(nation.toLowerCase(Locale.ROOT));
        if (coeffs == null) return defaultValue;

        // Search backward from maxYear to find the most recent coefficient
        for (int year = maxYear; year >= Integer.MIN_VALUE; year--) {
            if (coeffs.containsKey(year)) {
                return coeffs.get(year);
            }
            // Don't search forever; stop after reasonable range
            if (maxYear - year > 100) break;
        }
        return defaultValue;
    }

    /**
     * Calculate rolling average coefficient for a nation across available years.
     * Handles incomplete data gracefully (e.g., starting simulation with only 3-4 years of data).
     * @param nation Nation name
     * @param maxYear Last year to include in rolling average
     * @param windowSize Number of years in rolling window (e.g., 5 for UEFA-style)
     * @param defaultValue Value if nation not found
     * @return Average of available coefficients within window
     */
    public static double getNationCoefficientRollingAverage(String nation, int maxYear, int windowSize, double defaultValue) {
        Map<Integer, Double> coeffs = nationCoefficients.get(nation.toLowerCase(Locale.ROOT));
        if (coeffs == null || coeffs.isEmpty()) return defaultValue;

        // Collect available coefficients within rolling window
        double sum = 0.0;
        int count = 0;
        for (int year = maxYear; year >= maxYear - windowSize + 1; year--) {
            if (coeffs.containsKey(year)) {
                sum += coeffs.get(year);
                count++;
            }
        }

        // Return average of available data, or default if none found
        return count > 0 ? sum / count : defaultValue;
    }

    /**
     * Get rolling average using default 5-year UEFA-style window.
     * @param nation Nation name
     * @param maxYear Current simulation year
     * @param defaultValue Value if nation not found or no data available
     * @return 5-year rolling average (or less if starting simulation)
     */
    public static double getNationCoefficientRolling5Year(String nation, int maxYear, double defaultValue) {
        return getNationCoefficientRollingAverage(nation, maxYear, 5, defaultValue);
    }

    /**
     * Get all loaded global configuration parameters.
     */
    public static Map<String, Double> getAllGlobalConfig() {
        return new HashMap<>(globalConfig);
    }

    /**
     * Get all loaded nation coefficients.
     */
    public static Map<String, Map<Integer, Double>> getAllNationCoefficients() {
        return new HashMap<>(nationCoefficients);
    }

    /**
     * Print all loaded nation coefficients to console for verification.
     */
    public static void printLoadedCoefficients() {
        System.out.println("\n=== LOADED NATION COEFFICIENTS ===");
        System.out.println("Total nations: " + nationCoefficients.size());
        for (String nation : nationCoefficients.keySet()) {
            Map<Integer, Double> coeffs = nationCoefficients.get(nation);
            System.out.println(nation + ": " + coeffs);
        }
        System.out.println("=================================\n");
    }

    /**
     * Print specific nation coefficients.
     */
    public static void printNationCoefficients(String nation) {
        Map<Integer, Double> coeffs = nationCoefficients.get(nation.toLowerCase(Locale.ROOT));
        if (coeffs == null) {
            System.out.println("Nation not found: " + nation);
            System.out.println("Available nations: " + nationCoefficients.keySet());
        } else {
            System.out.println(nation + " coefficients: " + coeffs);
            System.out.println("Rolling 5-year (2022): " + getNationCoefficientRolling5Year(nation, 2022, 1.0));
        }
    }

    /**
     * Apply clamping to a value between min and max.
     */
    public static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    // ============== CONVENIENCE GETTERS FOR COMMON PARAMETERS ==============

    // Scheduling parameters
    public static int getDomesticLeagueWeekStart() {
        return (int) getGlobalConfig("scheduling.domestic_league_weeks_start", 2);
    }

    public static int getDomesticLeagueWeekEnd() {
        return (int) getGlobalConfig("scheduling.domestic_league_weeks_end", 39);
    }

    public static double getSlotStep() {
        return getGlobalConfig("scheduling.slot_step", 0.5);
    }

    public static double getRolloverThreshold() {
        return getGlobalConfig("scheduling.rollover_threshold", 52.0);
    }

    public static int getMinLeagueSize() {
        return (int) getGlobalConfig("scheduling.min_league_size", 10);
    }

    // Feature flags
    public static boolean isEnabledDomesticCups() {
        return getGlobalConfig("feature_flags.enable_domestic_cups", 1.0) > 0.5;
    }

    public static boolean isEnabledContinental() {
        return getGlobalConfig("feature_flags.enable_continental", 1.0) > 0.5;
    }

    public static boolean isEnabledGlobal() {
        return getGlobalConfig("feature_flags.enable_global", 0.0) > 0.5;
    }

    public static boolean isEnabledParityTuning() {
        return getGlobalConfig("feature_flags.enable_parity_tuning", 1.0) > 0.5;
    }

    // Financial parameters
    public static double getWageCap(int tier) {
        String key = "financial.wage_cap_tier" + Math.min(tier, 5);
        if (tier >= 5) key = "financial.wage_cap_tier5plus";
        return getGlobalConfig(key, tier == 1 ? 0.70 : (tier == 2 ? 0.60 : (tier == 3 ? 0.55 : (tier == 4 ? 0.50 : 0.50))));
    }

    public static double getFinancialDropThreshold() {
        return getGlobalConfig("financial.financial_drop_threshold", 22.0);
    }

    public static double getMinimumWageMultiplier(int tier) {
        String key = "financial.minimum_wage_multiplier_tier" + Math.min(tier, 5);
        if (tier >= 5) key = "financial.minimum_wage_multiplier_tier5plus";
        return getGlobalConfig(key, tier == 1 ? 1.2 : (tier == 2 ? 1.0 : (tier == 3 ? 0.8 : (tier == 4 ? 0.6 : 0.5))));
    }

    // Strength mechanics parameters
    public static double getSquadStrengthCurrentWeight() {
        return getGlobalConfig("strength_mechanics.squad_strength_current_weight", 0.60);
    }

    public static double getSquadStrengthInertiaWeight() {
        return getGlobalConfig("strength_mechanics.squad_strength_inertia_weight", 0.25);
    }

    public static double getSquadStrengthEloWeight() {
        return getGlobalConfig("strength_mechanics.squad_strength_elo_weight", 0.15);
    }

    public static double getTierSkillFloor(int tier) {
        String key = "strength_mechanics.tier" + Math.min(tier, 5) + "_skill_floor";
        if (tier >= 5) key = "strength_mechanics.tier5plus_skill_floor";
        double defaults[] = {0, 55, 35, 25, 18, 14};
        return getGlobalConfig(key, defaults[Math.min(tier, 5)]);
    }

    public static double getRelegationPenaltyCohesionThreshold() {
        return getGlobalConfig("strength_mechanics.relegation_penalty_cohesion_threshold", 50.0);
    }

    public static double getRelegationPenaltyExponentialFactor() {
        return getGlobalConfig("strength_mechanics.relegation_penalty_exponential_factor", 1.5);
    }

    // Rating parameters
    public static double getEloKFactorHigh() {
        return getGlobalConfig("rating.elo_k_factor_high", 32.0);
    }

    public static double getEloKFactorLow() {
        return getGlobalConfig("rating.elo_k_factor_low", 24.0);
    }

    public static double getEloHomeAdvantage() {
        return getGlobalConfig("rating.elo_home_advantage", 0.4);
    }

    public static double getEloUpsetBonus() {
        return getGlobalConfig("rating.elo_upset_bonus", 0.5);
    }

    // Parity tuning parameters
    public static double getParityPpgGapThreshold() {
        return getGlobalConfig("parity_tuning.parity_ppg_gap_threshold", 0.28);
    }

    public static int getParityTopClubsCount() {
        return (int) getGlobalConfig("parity_tuning.parity_top_clubs_count", 4.0);
    }

    public static int getParityBottomClubsCount() {
        return (int) getGlobalConfig("parity_tuning.parity_bottom_clubs_count", 4.0);
    }

    public static double getParityStrengthShiftMin() {
        return getGlobalConfig("parity_tuning.parity_strength_shift_min", 0.6);
    }

    public static double getParityStrengthShiftMax() {
        return getGlobalConfig("parity_tuning.parity_strength_shift_max", 2.8);
    }

    public static double getParityFinanceShiftMin() {
        return getGlobalConfig("parity_tuning.parity_finance_shift_min", 0.8);
    }

    public static double getParityFinanceShiftMax() {
        return getGlobalConfig("parity_tuning.parity_finance_shift_max", 3.0);
    }

    public static double getParityMoraleShiftMin() {
        return getGlobalConfig("parity_tuning.parity_morale_shift_min", 1.2);
    }

    public static double getParityMoraleShiftMax() {
        return getGlobalConfig("parity_tuning.parity_morale_shift_max", 4.0);
    }

    public static double getParityPpgGapMultiplier() {
        return getGlobalConfig("parity_tuning.parity_ppg_gap_multiplier", 3.4);
    }

    public static double getParityFinanceGapMultiplier() {
        return getGlobalConfig("parity_tuning.parity_finance_gap_multiplier", 3.0);
    }

    public static double getParityMoraleGapMultiplier() {
        return getGlobalConfig("parity_tuning.parity_morale_gap_multiplier", 4.0);
    }

    // Revenue parameters
    public static double getBaseRevenue(int tier) {
        String key = "revenue.base_revenue_tier" + Math.min(tier, 5);
        if (tier >= 5) key = "revenue.base_revenue_tier5plus";
        double defaults[] = {0, 10.0, 6.0, 3.5, 2.0, 1.0};
        return getGlobalConfig(key, defaults[Math.min(tier, 5)]);
    }

    public static double getPerformanceBonusMultiplier() {
        return getGlobalConfig("revenue.performance_bonus_multiplier", 0.15);
    }

    public static double getFacilityQualityMultiplier() {
        return getGlobalConfig("revenue.facility_quality_multiplier", 1.2);
    }

    public static double getDomesticCupBonus() {
        return getGlobalConfig("revenue.domestic_cup_bonus", 0.5);
    }

    public static double getContinentalBonusMultiplier(int tier) {
        if (tier == 1) return getGlobalConfig("revenue.continental_bonus_tier1", 1.5);
        if (tier == 2) return getGlobalConfig("revenue.continental_bonus_tier2", 1.0);
        if (tier == 3) return getGlobalConfig("revenue.continental_bonus_tier3", 0.7);
        return 0.0;  // No continental bonus for tiers 4+
    }

    // Match engine parameters
    public static double getUpsetProbability() {
        return getGlobalConfig("match_engine.upset_probability", 0.15);
    }

    public static double getDrawProbability() {
        return getGlobalConfig("match_engine.draw_probability", 0.25);
    }

    public static double getHomeAdvantageGoals() {
        return getGlobalConfig("match_engine.home_advantage_goals", 0.3);
    }

    public static double getInjuryRate() {
        return getGlobalConfig("match_engine.injury_rate", 0.05);
    }

    public static double getRedCardRate() {
        return getGlobalConfig("match_engine.red_card_rate", 0.02);
    }

    // Transfer parameters
    public static double getSummerBudgetMultiplier() {
        return getGlobalConfig("transfer.summer_budget_multiplier", 1.0);
    }

    public static double getWinterBudgetMultiplier() {
        return getGlobalConfig("transfer.winter_budget_multiplier", 0.4);
    }

    public static int getYoungPlayerPoolSize() {
        return (int) getGlobalConfig("transfer.young_player_pool_size", 50.0);
    }

    public static double getDevelopmentRate() {
        return getGlobalConfig("transfer.development_rate", 0.08);
    }

    public static int getInjuryRecoveryDays() {
        return (int) getGlobalConfig("transfer.injury_recovery_days", 14.0);
    }

    public static int getPlayerRetirementAge() {
        return (int) getGlobalConfig("transfer.player_retirement_age", 36.0);
    }

    public static int getPeakPerformanceAge() {
        return (int) getGlobalConfig("transfer.peak_performance_age", 27.0);
    }

    public static double getTransferFeeMultiplier() {
        return getGlobalConfig("transfer.transfer_fee_multiplier", 1.0);
    }

    public static double getPlayerWageGrowthRate() {
        return getGlobalConfig("transfer.player_wage_growth_rate", 0.05);
    }

    public static double getYoungPlayerWageMultiplier() {
        return getGlobalConfig("transfer.young_player_wage_multiplier", 0.5);
    }

    // Infrastructure parameters
    public static double getFacilityImprovementRate() {
        return getGlobalConfig("infrastructure.facility_improvement_rate", 0.02);
    }

    public static int getStadiumCapacityMin() {
        return (int) getGlobalConfig("infrastructure.stadium_capacity_min", 5000.0);
    }

    public static int getStadiumCapacityMax() {
        return (int) getGlobalConfig("infrastructure.stadium_capacity_max", 100000.0);
    }

    // Match engine parameters (additional)
    public static double getHomeAdvantageMultiplier() {
        return getGlobalConfig("match_engine.home_advantage_multiplier", 1.045);
    }

    public static double getContinentalEloK() {
        return getGlobalConfig("match_engine.continental_elo_k", 18.0);
    }

    public static double getLeagueEloK() {
        return getGlobalConfig("match_engine.league_elo_k", 12.0);
    }

    public static double getCupEloK() {
        return getGlobalConfig("match_engine.cup_elo_k", 10.0);
    }

    public static double getMatchOddsScale() {
        return getGlobalConfig("match_engine.match_odds_scale", 0.55);
    }

    public static double getHomeExpectedGoalsFloor() {
        return getGlobalConfig("match_engine.home_expected_goals_floor", 0.35);
    }

    public static double getHomeExpectedGoalsCeiling() {
        return getGlobalConfig("match_engine.home_expected_goals_ceiling", 3.0);
    }

    public static double getAwayExpectedGoalsFloor() {
        return getGlobalConfig("match_engine.away_expected_goals_floor", 0.35);
    }

    public static double getAwayExpectedGoalsCeiling() {
        return getGlobalConfig("match_engine.away_expected_goals_ceiling", 3.0);
    }

    public static int getMaxGoalsSample() {
        return (int) getGlobalConfig("match_engine.max_goals_sample", 8.0);
    }

    public static double getGoalDampingFactor() {
        return getGlobalConfig("match_engine.goal_damping_factor", 0.78);
    }

    public static double getRareGoalBoostChance() {
        return getGlobalConfig("match_engine.rare_goal_boost_chance", 0.015);
    }

    // Club evolution parameters
    public static int getBaseGenerationCycle() {
        return (int) getGlobalConfig("club_evolution.base_generation_cycle", 8.0);
    }

    public static int getGenerationCycleVariance() {
        return (int) getGlobalConfig("club_evolution.generation_cycle_variance", 10.0);
    }

    public static int getMinSquadDepthPlayers() {
        return (int) getGlobalConfig("club_evolution.min_squad_depth_players", 14.0);
    }

    public static int getMaxSquadDepthPlayers() {
        return (int) getGlobalConfig("club_evolution.max_squad_depth_players", 36.0);
    }

    // Coefficient parameters
    public static int getCoefficientHistorySize() {
        return (int) getGlobalConfig("coefficient.coefficient_history_size", 5.0);
    }

    public static double getCoefficientMin() {
        return getGlobalConfig("coefficient.coefficient_min", 3.0);
    }

    public static double getCoefficientMax() {
        return getGlobalConfig("coefficient.coefficient_max", 40.0);
    }

    public static double getCoefficientEloBase() {
        return getGlobalConfig("coefficient.coefficient_elo_base", 800.0);
    }

    public static double getCoefficientEloDivisor() {
        return getGlobalConfig("coefficient.coefficient_elo_divisor", 25.0);
    }

    // Calendar parameters
    public static int getDomesticSupercupWeek() {
        return (int) getGlobalConfig("calendar.domestic_supercup_week", 1.0);
    }

    public static int getConfedSupercupWeek() {
        return (int) getGlobalConfig("calendar.confed_supercup_week", 47.0);
    }

    public static int getMidSeasonTransferWeek() {
        return (int) getGlobalConfig("calendar.mid_season_transfer_week", 20.0);
    }

    public static int getWinterTransferStart() {
        return (int) getGlobalConfig("calendar.winter_transfer_start", 25.0);
    }

    public static int getWinterTransferEnd() {
        return (int) getGlobalConfig("calendar.winter_transfer_end", 26.0);
    }

    // Injury parameters
    public static double getBaseInjuryChance() {
        return getGlobalConfig("injury.base_injury_chance", 0.02);
    }

    public static int getInjuryRecoveryWeeks() {
        return (int) getGlobalConfig("injury.injury_recovery_weeks", 3.0);
    }

    public static double getMaxConcurrentInjuryRate() {
        return getGlobalConfig("injury.max_concurrent_injury_rate", 0.15);
    }

    // FFP parameters
    public static double getWageCapPercent() {
        return getGlobalConfig("ffp.wage_cap_percent", 0.65);
    }

    public static double getFfpRepeatOffenseMultiplier() {
        return getGlobalConfig("ffp.ffp_repeat_offense_multiplier", 1.5);
    }

    // Wage parameters
    public static double getWageInflationRate() {
        return getGlobalConfig("wage.wage_inflation_rate", 0.045);
    }

    /**
     * Parse a value that may be numeric, boolean, or in other formats.
     */
    private static double parseValue(String s) {
        if (s == null || s.trim().isEmpty()) {
            throw new NumberFormatException("Empty value");
        }

        String lower = s.trim().toLowerCase(Locale.ROOT);
        if ("true".equals(lower)) return 1.0;
        if ("false".equals(lower)) return 0.0;

        return Double.parseDouble(s.trim());
    }
}
