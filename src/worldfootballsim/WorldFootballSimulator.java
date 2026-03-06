package worldfootballsim;

import java.util.*;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

public class WorldFootballSimulator {
    @SuppressWarnings("unused")
    private Set<Integer> DOMESTIC_LEAGUE_WEEKS;
    @SuppressWarnings("unused")
    private int[] DOMESTIC_CUP_WEEKS;
    @SuppressWarnings("unused")
    private int[] CONTINENTAL_PHASE_WEEKS;
    @SuppressWarnings("unused")
    private int[] CONTINENTAL_KO_WEEKS;
    @SuppressWarnings("unused")
    private int[] GLOBAL_CUP_WEEKS;
    @SuppressWarnings("unused")
    private int DOMESTIC_SUPERCUP_WEEK;
    @SuppressWarnings("unused")
    private int CONFED_SUPERCUP_WEEK;
    private boolean ENABLE_DOMESTIC_CUPS;
    private boolean ENABLE_CONTINENTAL;
    private boolean ENABLE_GLOBAL;
    private double SLOT_STEP;
    private double ROLLOVER_T;
    private int MIN_LEAGUE_SIZE;
    private boolean ENABLE_PARITY_TUNING;
    private double FINANCIAL_DROP_THRESHOLD;

    private int seasonYear;
    private final boolean verbose;
    private boolean suppressSeasonSummary;
    private boolean seasonCompleted;
    private boolean seasonInitialized;
    private final List<Double> leagueMatchSlots = new ArrayList<>();
    private final Set<Double> domesticCupSlots = new HashSet<>();
    private final Map<Double, String> slotLabels = new HashMap<>();
    private List<Double> seasonSlots = new ArrayList<>();
    private int nextSlotIndex;
    private String currentTransferWindow = "";
    private final Map<Club, Double> exportableStrengthPool = new HashMap<>();
    private int transfersThisWindow;
    private int transfersThisSlot;

    // Export directory configuration: run-scoped to prevent file mixing
    // IMPORTANT: simulationSeed must be set via setRandomSeed() or
    // configureExportFolder()
    // before running simulation, otherwise it will default to current time
    private String exportRunFolder = "export";
    private long simulationSeed = System.currentTimeMillis(); // DEFAULT: replaced by configureExportFolder()
    private int simulationStartYear = 2026;

    private Random rng = new Random();
    private final Set<String> excludedCountries = new HashSet<>();
    private Match lastBiggestUpset;

    final Map<String, Club> clubIndex = new HashMap<>();
    final Map<String, List<Club>> clubIndexByName = new HashMap<>();
    private final Map<String, Map<String, Club>> clubsByCountryAndName = new HashMap<>();
    private final Set<Club> clubsWithContinentalHistory = new HashSet<>();
    private final Map<String, List<Club>> unassignedClubsByCountry = new HashMap<>();
    private final Map<String, List<Club>> pendingFinancialDropoutsByCountry = new HashMap<>();

    final Map<String, CountryAssociation> countries = new HashMap<>();
    final Map<Confederation, List<CountryAssociation>> countriesByConfed = new HashMap<>();

    // domestic comps
    final List<League> allLeagues = new ArrayList<>();
    final Map<String, KnockoutCup> domesticCups = new HashMap<>();
    final Map<String, Match> domesticSuperCupResults = new LinkedHashMap<>();

    // continental comps (multi-tier)
    final Map<Confederation, List<ContinentalCompetitionDef>> continentalDefs = new LinkedHashMap<>();
    final Map<String, ContinentalTournament> continentalTournaments = new LinkedHashMap<>(); // keyed by competitionKey
    private final Map<String, LinkedHashMap<String, List<Match>>> continentalQualifyingMatches = new LinkedHashMap<>();
    private final Set<Double> continentalSlots = new HashSet<>();

    // titleholder tracking: competition_key -> previous season's champion
    private final Map<String, Club> previousContinentalWinners = new HashMap<>();

    // global
    KnockoutCup globalClubCup;

    private SeasonWinnersExporter winnersExporter;
    private CupWinnersExporter cupWinnersExporter;

    public WorldFootballSimulator(int seasonYear, boolean verbose) {
        this.seasonYear = seasonYear;
        this.verbose = verbose;
        this.seasonCompleted = false;
        this.seasonInitialized = false;
        this.suppressSeasonSummary = false;
        initializeConfigurationDefaults();
    }

    /**
     * Initialize configuration parameters from CSV files.
     * Call this before starting simulation if you want custom config.
     */
    public void loadConfiguration(String globalConfigPath, String nationCoefficientsPath) throws IOException {
        SimulatorConfigReader.readGlobalConfig(globalConfigPath);
        SimulatorConfigReader.readNationCoefficients(nationCoefficientsPath);
        initializeConfigurationDefaults();
    }

    /**
     * Initialize all configuration parameters from SimulatorConfigReader
     */
    private void initializeConfigurationDefaults() {
        SLOT_STEP = SimulatorConfigReader.getSlotStep();
        ROLLOVER_T = SimulatorConfigReader.getRolloverThreshold();
        MIN_LEAGUE_SIZE = SimulatorConfigReader.getMinLeagueSize();
        ENABLE_DOMESTIC_CUPS = SimulatorConfigReader.isEnabledDomesticCups();
        ENABLE_CONTINENTAL = SimulatorConfigReader.isEnabledContinental();
        ENABLE_GLOBAL = SimulatorConfigReader.isEnabledGlobal();
        ENABLE_PARITY_TUNING = SimulatorConfigReader.isEnabledParityTuning();
        FINANCIAL_DROP_THRESHOLD = SimulatorConfigReader.getFinancialDropThreshold();
        DOMESTIC_SUPERCUP_WEEK = 1;
        CONFED_SUPERCUP_WEEK = 47;

        // Build domestic league weeks from config
        int start = SimulatorConfigReader.getDomesticLeagueWeekStart();
        int end = SimulatorConfigReader.getDomesticLeagueWeekEnd();
        DOMESTIC_LEAGUE_WEEKS = rangeSet(start, end);

        // Cup scheduling (these are relative; could be made configurable)
        DOMESTIC_CUP_WEEKS = new int[] { 6, 11, 16, 21, 26, 31, 36, 44 };
        CONTINENTAL_PHASE_WEEKS = new int[] { 5, 6, 8, 10, 12, 14, 16, 18, 20, 22 };
        CONTINENTAL_KO_WEEKS = new int[] { 26, 28, 30, 32, 34, 36, 38, 40, 42, 46 };
        GLOBAL_CUP_WEEKS = new int[] { 50, 51, 52 };
    }

    public void setSeasonYear(int seasonYear) {
        this.seasonYear = seasonYear;
    }

    public void setSuppressSeasonSummary(boolean suppressSeasonSummary) {
        this.suppressSeasonSummary = suppressSeasonSummary;
    }

    /**
     * Configure run-scoped export folder to prevent file mixing across simulation
     * runs.
     * Format: export/run_<seed>_<startYear>_<endYear>/
     * If that folder already exists, a _saveN suffix is appended.
     */
    public void configureExportFolder(long seed, int startYear, int endYear) {
        this.simulationSeed = seed;
        this.simulationStartYear = startYear;

        String baseFolder = String.format("export/run_%d_%d_%d", seed, startYear, endYear);
        this.exportRunFolder = resolveUniqueRunFolder(baseFolder);
        try {
            Files.createDirectories(Paths.get(exportRunFolder));
            initializeWinnerExporters();
            if (verbose) {
                System.out.println("[EXPORT] Configured run folder: " + exportRunFolder);
            }
        } catch (IOException e) {
            System.err.println("[EXPORT] Failed to create export folder: " + e.getMessage());
            exportRunFolder = resolveUniqueRunFolder(String.format("export/run_%d_%d_%d_fallback", seed, startYear, endYear));
            try {
                Files.createDirectories(Paths.get(exportRunFolder));
                initializeWinnerExporters();
            } catch (IOException fallbackError) {
                System.err.println("[EXPORT] Failed to initialize fallback export folder: " + fallbackError.getMessage());
            }
        }
    }

    private String resolveUniqueRunFolder(String baseFolder) {
        Path basePath = Paths.get(baseFolder);
        if (!Files.exists(basePath)) {
            return baseFolder;
        }

        int suffix = 2;
        while (true) {
            String candidate = baseFolder + "_save" + suffix;
            if (!Files.exists(Paths.get(candidate))) {
                return candidate;
            }
            suffix++;
        }
    }

    private void initializeWinnerExporters() {
        winnersExporter = new SeasonWinnersExporter(Path.of(exportRunFolder, "season_winners.csv"));
        cupWinnersExporter = new CupWinnersExporter(Path.of(exportRunFolder, "cup_winners.csv"));
    }

    public String getExportFolder() {
        return exportRunFolder;
    }

    public void setRandomSeed(long seed) {
        long scramble = seed ^ System.nanoTime() ^ (long) (Math.random() * Long.MAX_VALUE);
        Random local = new Random(scramble);
        for (Club club : clubIndex.values()) {
            club.resetRandom(scramble ^ local.nextLong());
        }
        MatchEngine.setSeed(scramble ^ 0x9E3779B97F4A7C15L);
        rng = new Random(scramble ^ 0xDEADBEEF);
    }

    public int getSeasonYear() {
        return seasonYear;
    }

    public boolean isSeasonCompleted() {
        return seasonCompleted;
    }

    public boolean isSeasonInitialized() {
        return seasonInitialized;
    }

    public double getCurrentSlot() {
        if (!seasonInitialized || seasonSlots.isEmpty())
            return 0;
        if (nextSlotIndex <= 0)
            return seasonSlots.get(0);
        if (nextSlotIndex >= seasonSlots.size())
            return seasonSlots.get(seasonSlots.size() - 1);
        return seasonSlots.get(nextSlotIndex - 1);
    }

    public String getSlotLabel(double t) {
        return slotLabels.getOrDefault(t, "");
    }

    public Match getLastBiggestUpset() {
        return lastBiggestUpset;
    }

    public void excludeCountry(String country) {
        if (country != null && !country.trim().isEmpty()) {
            excludedCountries.add(country.trim());
        }
    }

    public boolean isCountryExcluded(String country) {
        return excludedCountries.contains(country);
    }

    public Set<String> getExcludedCountries() {
        return Collections.unmodifiableSet(excludedCountries);
    }

    public void loadFromOpta(String csvPath) throws IOException {
        loadFromOpta(csvPath, null);
    }

    public void loadFromOpta(String csvPath, String leaguesCsvPath) throws IOException {
        List<Club> clubs = OptaReader.readClubs(csvPath);
        unassignedClubsByCountry.clear();
        clubsByCountryAndName.clear();
        clubsWithContinentalHistory.clear();
        initializeWinnerExporters();

        Map<String, Map<Integer, LeagueConfigReader.LeagueConfig>> leagueConfig = new HashMap<>();
        if (leaguesCsvPath != null && !leaguesCsvPath.trim().isEmpty()) {
            leagueConfig = LeagueConfigReader.readLeagueConfig(leaguesCsvPath);
        }

        Map<String, Map<String, Integer>> levelByLeagueName = new HashMap<>();
        for (Map.Entry<String, Map<Integer, LeagueConfigReader.LeagueConfig>> entry : leagueConfig.entrySet()) {
            Map<String, Integer> byName = new HashMap<>();
            for (Map.Entry<Integer, LeagueConfigReader.LeagueConfig> cfg : entry.getValue().entrySet()) {
                String leagueName = cfg.getValue().getLeagueName();
                if (leagueName != null && !leagueName.trim().isEmpty()) {
                    byName.put(leagueName.trim().toLowerCase(), cfg.getKey());
                }
            }
            levelByLeagueName.put(entry.getKey(), byName);
        }

        // Build country -> leagues
        Map<String, Map<Integer, List<Club>>> byCountryLevel = new HashMap<>();
        Map<String, Map<Integer, String>> leagueNameByCountryLevel = new HashMap<>();
        Map<String, Confederation> countryConfed = new HashMap<>();

        for (Club c : clubs) {
            Map<String, Integer> lookup = levelByLeagueName.get(c.getCountry());
            if (lookup != null) {
                Integer levelFromConfig = lookup.get(c.getDomesticLeagueName().trim().toLowerCase());
                if (levelFromConfig != null && levelFromConfig > 0
                        && (c.getDomesticLevel() <= 0 || c.getDomesticLevel() != levelFromConfig)) {
                    c.setDomesticLevel(levelFromConfig);
                }
            }
            String key = c.getName().toLowerCase();
            clubIndex.putIfAbsent(key, c);
            clubIndexByName.computeIfAbsent(key, k -> new ArrayList<>()).add(c);

            String countryKey = c.getCountry().toLowerCase(Locale.ROOT);
            Map<String, Club> byName = clubsByCountryAndName.computeIfAbsent(countryKey, k -> new HashMap<>());
            Club existing = byName.get(key);
            if (existing == null || c.getEloRating() > existing.getEloRating()) {
                byName.put(key, c);
            }

            int level = c.getDomesticLevel();
            byCountryLevel.computeIfAbsent(c.getCountry(), k -> new HashMap<>())
                    .computeIfAbsent(level, k -> new ArrayList<>())
                    .add(c);
            leagueNameByCountryLevel
                    .computeIfAbsent(c.getCountry(), k -> new HashMap<>())
                    .putIfAbsent(level, c.getDomesticLeagueName());

            countryConfed.put(c.getCountry(), c.getConfederation());
        }

        // Create associations and leagues
        for (Map.Entry<String, Map<Integer, List<Club>>> e : byCountryLevel.entrySet()) {
            String country = e.getKey();
            Confederation conf = countryConfed.getOrDefault(country, Confederation.UNKNOWN);

            CountryAssociation assoc = new CountryAssociation(country, conf);
            countries.put(country, assoc);

            // Use provided domestic levels; if missing, sort by ELO strength.
            Map<Integer, LeagueConfigReader.LeagueConfig> configLevels = leagueConfig.getOrDefault(country,
                    new HashMap<>());
            for (Map.Entry<Integer, LeagueConfigReader.LeagueConfig> cfg : configLevels.entrySet()) {
                leagueNameByCountryLevel
                        .computeIfAbsent(country, k -> new HashMap<>())
                        .putIfAbsent(cfg.getKey(), cfg.getValue().getLeagueName());
            }

            List<Map.Entry<Integer, List<Club>>> leagues = new ArrayList<>(e.getValue().entrySet());
            leagues.sort(Comparator.comparingInt(Map.Entry::getKey));
            int maxLevel = 0;
            for (Map.Entry<Integer, List<Club>> le : leagues) {
                if (le.getKey() > maxLevel) {
                    maxLevel = le.getKey();
                }
            }

            int maxConfigLevel = maxLevel;
            for (Integer levelKey : configLevels.keySet()) {
                if (levelKey != null && levelKey > maxConfigLevel) {
                    maxConfigLevel = levelKey;
                }
            }
            maxLevel = Math.max(maxLevel, maxConfigLevel);

            if (leagues.size() == 1 && leagues.get(0).getKey() == 0) {
                leagues.sort((a, b) -> Double.compare(avgElo(b.getValue()), avgElo(a.getValue())));
            }

            int fallbackLevel = 1;
            for (Map.Entry<Integer, List<Club>> le : leagues) {
                int level = le.getKey() > 0 ? le.getKey() : (maxLevel > 0 ? maxLevel : fallbackLevel++);
                // FIX: Guard against empty club list
                String fallbackName = !le.getValue().isEmpty() ? le.getValue().get(0).getDomesticLeagueName()
                        : "Unknown";
                String rawName = leagueNameByCountryLevel
                        .getOrDefault(country, new HashMap<>())
                        .getOrDefault(le.getKey(), fallbackName);
                String normalizedLeagueName = assoc.normalizeLeagueName(rawName);
                LeagueConfigReader.LeagueConfig cfg = configLevels.get(level);
                if (cfg != null && cfg.getDivisionsCount() > 1
                        && (cfg.isMergedRegional() || !cfg.getDivisionNames().isEmpty())) {
                    List<League> splitLeagues = buildDivisionalLeagues(assoc, cfg, level, le.getValue());
                    for (League split : splitLeagues) {
                        // Set target size at league creation for divisional leagues too
                        split.initializeTargetSize();
                        split.generateFixtures();
                        allLeagues.add(split);
                    }
                } else {
                    League league = assoc.getOrCreateLeague(normalizedLeagueName, level);
                    if (cfg != null) {
                        league.setLeagueConfig(cfg);
                        int configuredTarget = resolveConfiguredLeagueTarget(cfg, 0, le.getValue().size());
                        league.setTargetSize(configuredTarget);
                    }
                    for (Club c : le.getValue()) {
                        c.setDomesticLeagueName(normalizedLeagueName);
                        c.setDomesticLevel(level);
                        c.setCountryAssociation(assoc); // Link club to its country's coefficient
                        league.addClub(c);
                    }
                    // CRITICAL: Set target size at league creation, based on initial population
                    // This prevents the "target set after damage" problem
                    league.initializeTargetSize();
                    league.generateFixtures();
                    allLeagues.add(league);
                }
            }

            assignUnassignedClubs(countries.get(country));

            assoc.finalizeTopLeague();

            // Load historical coefficients from CSV config if available
            // Pre-populate the 5-year rolling window with CSV data (2021-2025)
            Map<String, Map<Integer, Double>> allCoeffs = SimulatorConfigReader.getAllNationCoefficients();
            Map<Integer, Double> nationCoeffs = allCoeffs.get(country.toLowerCase(Locale.ROOT));

            if (nationCoeffs != null && !nationCoeffs.isEmpty()) {
                int[] years = { 2021, 2022, 2023, 2024, 2025 };
                assoc.loadHistoricalCoefficients(years, nationCoeffs);
            } else {
                // Fallback to ELO-based seeding if no CSV data
                assoc.seedInitialCoefficient();
            }

            countriesByConfed.computeIfAbsent(conf, k -> new ArrayList<>()).add(assoc);
        }

        // Prepare domestic cups (one per country) using all clubs in that country
        for (CountryAssociation assoc : countries.values()) {
            List<Club> entrants = new ArrayList<>();
            for (League league : assoc.getAllLeagues()) {
                entrants.addAll(league.getClubs());
            }

            KnockoutCup cup = new KnockoutCup(assoc.getName() + " Cup", entrants, true);
            domesticCups.put(assoc.getName(), cup);
        }

        // Prepare continental competitions from CSV
        if (ENABLE_CONTINENTAL) {
            loadContinentalDefs();
        }
    }

    public void startSeason() {
        seasonCompleted = false;
        domesticSuperCupResults.clear();
        seasonInitialized = true;
        currentTransferWindow = "";
        exportableStrengthPool.clear();
        transfersThisWindow = 0;
        transfersThisSlot = 0;
        clearTransfers();
        for (Club club : clubIndex.values()) {
            club.startNewSeason(seasonYear);
        }
        // Reset leagues for new season
        // Note: target sizes are already initialized at league creation time
        for (League l : allLeagues) {
            l.startNewSeason();
        }

        // start domestic cups
        if (ENABLE_DOMESTIC_CUPS) {
            rebuildDomesticCupsForSeason();
            for (KnockoutCup cup : domesticCups.values()) {
                cup.startNewSeason(Integer.MAX_VALUE);
            }
        }

        if (ENABLE_CONTINENTAL) {
            allocateContinentalEntrants();
            // Set region split for AFC tournaments before starting season
            java.util.function.Function<Club, String> afcRegionMapper = c -> RegionMapper.isAfcWest(c.getCountry())
                    ? "West"
                    : "East";
            for (Map.Entry<String, ContinentalTournament> entry : continentalTournaments.entrySet()) {
                if (entry.getKey().startsWith("afc_")) {
                    entry.getValue().setRegionSplit(afcRegionMapper);
                }
            }
            for (ContinentalTournament t : continentalTournaments.values())
                t.startNewSeason();
            applyAllQualifyingMatchResults();
        }

        // global cup will be created after continental champions are known
        globalClubCup = null;

        buildSeasonCalendar();
        seasonSlots = buildSeasonSlots();
        nextSlotIndex = 0;
    }

    public boolean simulateNextSlot() {
        if (!seasonInitialized || seasonCompleted) {
            return false;
        }
        if (nextSlotIndex >= seasonSlots.size()) {
            return false;
        }

        double slot = seasonSlots.get(nextSlotIndex++);
        if (slot >= ROLLOVER_T) {
            endSeason();
            return false;
        }

        simulateSlot(slot);
        if (allLeaguesComplete() && allCupsComplete()) {
            endSeason();
            return false;
        }

        return true;
    }

    private void loadContinentalDefs() {
        continentalDefs.clear();
        continentalTournaments.clear();

        java.io.File cupsConfigFile = new java.io.File("continental_cups_config.csv");
        java.io.File mergedFile = new java.io.File("continental_config.csv");
        java.io.File compFile = new java.io.File("continental_competitions.csv");
        java.io.File stagesFile = new java.io.File("stages.csv");

        Map<Confederation, List<ContinentalCompetitionDef>> defs;
        if (cupsConfigFile.exists()) {
            defs = ContinentalCompetitionDef.loadFromMergedCsv(cupsConfigFile.getPath());
        } else if (mergedFile.exists()) {
            defs = ContinentalCompetitionDef.loadFromMergedCsv(mergedFile.getPath());
        } else if (compFile.exists() && stagesFile.exists()) {
            defs = ContinentalCompetitionDef.loadFromCsv(compFile.getPath(), stagesFile.getPath());
        } else {
            defs = ContinentalCompetitionDef.buildAllDefs();
        }

        if (defs.isEmpty()) {
            System.err.println("Warning: No continental competitions loaded from CSV; using hardcoded defaults.");
            defs = ContinentalCompetitionDef.buildAllDefs();
        }

        for (Map.Entry<Confederation, List<ContinentalCompetitionDef>> entry : defs.entrySet()) {
            continentalDefs.put(entry.getKey(), entry.getValue());
            for (ContinentalCompetitionDef def : entry.getValue()) {
                ContinentalTournament tournament = def.createTournament();
                continentalTournaments.put(def.getCompetitionKey(), tournament);
            }
        }
    }

    private void allocateContinentalEntrants() {
        continentalQualifyingMatches.clear();
        for (Map.Entry<Confederation, List<ContinentalCompetitionDef>> entry : continentalDefs.entrySet()) {
            Confederation conf = entry.getKey();
            List<ContinentalCompetitionDef> defs = entry.getValue();
            List<CountryAssociation> confCountries = countriesByConfed.getOrDefault(conf, new ArrayList<>());
            confCountries = new ArrayList<>(confCountries);
            confCountries.removeIf(ca -> excludedCountries.contains(ca.getName()));
            if (confCountries.isEmpty())
                continue;

            switch (conf) {
                case EUROPE:
                    allocateUefaEntrants(confCountries, defs);
                    break;
                case ASIA:
                    allocateAfcEntrants(confCountries, defs);
                    break;
                case AFRICA:
                    allocateCafEntrants(confCountries, defs);
                    break;
                case NORTH_CENTRAL_AMERICA:
                    allocateConcacafEntrants(confCountries, defs);
                    break;
                case OCEANIA:
                    allocateOfcEntrants(confCountries, defs);
                    break;
                default:
                    allocateNonUefaEntrants(conf, confCountries, defs);
                    break;
            }
        }
    }

    private List<Club> getRankedClubsForCountry(CountryAssociation ca) {
        List<Club> ranked = ca.getLastTopLeagueFinalTable();
        if (ranked == null || ranked.isEmpty()) {
            ranked = new ArrayList<>();
            for (League league : ca.getAllLeagues()) {
                ranked.addAll(league.getClubs());
            }
            ranked.sort((a, b) -> Double.compare(b.getEloRating(), a.getEloRating()));
        }
        return ranked;
    }

    private Club pickNextUelEligible(List<CountryAssociation> ranked,
            List<Club> uclLeaguePhase,
            List<Club> uelLeaguePhase) {
        Set<Club> excluded = new HashSet<>(uclLeaguePhase);
        excluded.addAll(uelLeaguePhase);
        for (CountryAssociation ca : ranked) {
            List<Club> clubs = getRankedClubsForCountry(ca);
            for (Club club : clubs) {
                if (!excluded.contains(club)) {
                    return club;
                }
            }
        }
        return null;
    }

    private void trimToTarget(List<Club> clubs, int target, Club protectedClub) {
        if (clubs == null)
            return;
        while (clubs.size() > target) {
            Club lowest = null;
            for (Club club : clubs) {
                if (protectedClub != null && club.equals(protectedClub))
                    continue;
                if (lowest == null || club.getEloRating() < lowest.getEloRating()) {
                    lowest = club;
                }
            }
            if (lowest == null)
                break;
            clubs.remove(lowest);
        }
    }

    private void addQualifyingRound(String competitionKey, String roundName, List<Match> matches) {
        if (competitionKey == null || roundName == null || matches == null || matches.isEmpty())
            return;
        LinkedHashMap<String, List<Match>> rounds = continentalQualifyingMatches.computeIfAbsent(competitionKey,
                k -> new LinkedHashMap<>());
        rounds.computeIfAbsent(roundName, k -> new ArrayList<>()).addAll(matches);
    }

    private void addQualifyingRounds(String competitionKey, Map<String, List<Match>> byRound) {
        if (competitionKey == null || byRound == null)
            return;
        for (Map.Entry<String, List<Match>> entry : byRound.entrySet()) {
            addQualifyingRound(competitionKey, entry.getKey(), entry.getValue());
        }
    }

    private void applyQualifyingMatchResults(String competitionKey, boolean singleLeg) {
        if (competitionKey == null)
            return;
        LinkedHashMap<String, List<Match>> rounds = continentalQualifyingMatches.get(competitionKey);
        if (rounds == null || rounds.isEmpty())
            return;

        List<Match> matches = new ArrayList<>();
        for (List<Match> roundMatches : rounds.values()) {
            if (roundMatches != null) {
                matches.addAll(roundMatches);
            }
        }

        ContinentalTournament tournament = continentalTournaments.get(competitionKey);
        if (tournament != null && !matches.isEmpty()) {
            tournament.recordQualifyingMatches(matches, singleLeg);
        }
    }

    private void applyAllQualifyingMatchResults() {
        for (String competitionKey : continentalQualifyingMatches.keySet()) {
            applyQualifyingMatchResults(competitionKey, false);
        }
    }

    private void allocateUefaEntrants(List<CountryAssociation> confCountries,
            List<ContinentalCompetitionDef> defs) {
        // Sort by coefficient
        List<CountryAssociation> ranked = new ArrayList<>(confCountries);
        ranked.sort((a, b) -> Double.compare(b.getRollingCoefficient(), a.getRollingCoefficient()));

        boolean russiaExcluded = excludedCountries.contains("Russia");
        boolean israelExcluded = excludedCountries.contains("Israel");
        boolean bothExcluded = russiaExcluded && israelExcluded;
        boolean oneExcluded = (russiaExcluded || israelExcluded) && !bothExcluded;

        // Run the UEFA qualifying engine
        UefaQualifyingEngine.UefaSeasonAllocation allocation = UefaQualifyingEngine.allocateAndQualify(
                ranked, this::getRankedClubsForCountry, bothExcluded, oneExcluded);

        // Titleholder rules: UEL winner -> UCL league phase, UECL winner -> UEL league
        // phase
        Club prevUelWinner = previousContinentalWinners.get("uefa_uel");
        Club prevUeclWinner = previousContinentalWinners.get("uefa_uecl");

        if (prevUelWinner != null) {
            if (!allocation.uclLeaguePhase.contains(prevUelWinner)) {
                allocation.uclLeaguePhase.add(prevUelWinner);
            }
            allocation.uelLeaguePhase.remove(prevUelWinner);
        }

        if (prevUeclWinner != null) {
            if (allocation.uclLeaguePhase.contains(prevUeclWinner)) {
                Club passDown = pickNextUelEligible(ranked, allocation.uclLeaguePhase, allocation.uelLeaguePhase);
                if (passDown != null) {
                    allocation.uelLeaguePhase.add(passDown);
                }
            } else if (!allocation.uelLeaguePhase.contains(prevUeclWinner)) {
                allocation.uelLeaguePhase.add(prevUeclWinner);
            }
        }

        trimToTarget(allocation.uclLeaguePhase, 36, prevUelWinner);
        trimToTarget(allocation.uelLeaguePhase, 36, prevUeclWinner);

        for (Map.Entry<String, Map<String, List<Match>>> entry : allocation.qualifyingMatchesByCompetition.entrySet()) {
            addQualifyingRounds(entry.getKey(), entry.getValue());
        }
        continentalQualifyingMatches.putIfAbsent("uefa_ucl", new LinkedHashMap<>());
        continentalQualifyingMatches.putIfAbsent("uefa_uel", new LinkedHashMap<>());
        continentalQualifyingMatches.putIfAbsent("uefa_uecl", new LinkedHashMap<>());

        // Map competition keys to entrant lists
        for (ContinentalCompetitionDef def : defs) {
            ContinentalTournament tournament = continentalTournaments.get(def.getCompetitionKey());
            if (tournament == null)
                continue;

            List<Club> entrants;
            switch (def.getCompetitionKey()) {
                case "uefa_ucl":
                    entrants = allocation.uclLeaguePhase;
                    break;
                case "uefa_uel":
                    entrants = allocation.uelLeaguePhase;
                    break;
                case "uefa_uecl":
                    entrants = allocation.ueclLeaguePhase;
                    break;
                default:
                    entrants = new ArrayList<>();
                    break;
            }
            tournament.setEntrants(entrants);
        }
    }

    private void allocateAfcEntrants(List<CountryAssociation> confCountries,
            List<ContinentalCompetitionDef> defs) {
        List<CountryAssociation> ranked = new ArrayList<>(confCountries);
        ranked.sort((a, b) -> Double.compare(b.getRollingCoefficient(), a.getRollingCoefficient()));

        Club prevElite = previousContinentalWinners.get("afc_acl_elite");
        Club prevTwo = previousContinentalWinners.get("afc_acl_two");
        Club prevChallenge = previousContinentalWinners.get("afc_challenge");

        AfcQualifyingEngine.AfcSeasonAllocation allocation = AfcQualifyingEngine.allocateAndQualify(ranked,
                this::getRankedClubsForCountry,
                prevElite, prevTwo, prevChallenge);

        for (Map.Entry<String, Map<String, List<Match>>> entry : allocation.qualifyingMatchesByCompetition.entrySet()) {
            addQualifyingRounds(entry.getKey(), entry.getValue());
        }
        continentalQualifyingMatches.putIfAbsent("afc_acl_elite", new LinkedHashMap<>());
        continentalQualifyingMatches.putIfAbsent("afc_acl_two", new LinkedHashMap<>());
        continentalQualifyingMatches.putIfAbsent("afc_challenge", new LinkedHashMap<>());

        for (ContinentalCompetitionDef def : defs) {
            ContinentalTournament tournament = continentalTournaments.get(def.getCompetitionKey());
            if (tournament == null)
                continue;
            List<Club> entrants;
            switch (def.getCompetitionKey()) {
                case "afc_acl_elite":
                    entrants = allocation.aclEliteEntrants;
                    break;
                case "afc_acl_two":
                    entrants = allocation.aclTwoEntrants;
                    break;
                case "afc_challenge":
                    entrants = allocation.challengeEntrants;
                    break;
                default:
                    entrants = new ArrayList<>();
                    break;
            }
            tournament.setEntrants(entrants);
        }
    }

    private void allocateCafEntrants(List<CountryAssociation> confCountries,
            List<ContinentalCompetitionDef> defs) {
        List<CountryAssociation> ranked = new ArrayList<>(confCountries);
        ranked.sort((a, b) -> Double.compare(b.getRollingCoefficient(), a.getRollingCoefficient()));

        Club prevClWinner = previousContinentalWinners.get("caf_cl");

        CafQualifyingEngine.CafSeasonAllocation clAllocation = CafQualifyingEngine.allocateAndQualify(ranked,
                this::getRankedClubsForCountry,
                prevClWinner);

        addQualifyingRounds("caf_cl", clAllocation.qualifyingMatchesByRound);
        continentalQualifyingMatches.putIfAbsent("caf_cl", new LinkedHashMap<>());

        // Track all CL participants (including qualifying round entrants) so CC doesn't
        // overlap
        Set<Club> clParticipants = new HashSet<>(clAllocation.clGroupStageEntrants);
        // Also add clubs from qualifying matches
        for (Match m : clAllocation.allQualifyingMatches) {
            clParticipants.add(m.getHome());
            clParticipants.add(m.getAway());
        }

        for (ContinentalCompetitionDef def : defs) {
            ContinentalTournament tournament = continentalTournaments.get(def.getCompetitionKey());
            if (tournament == null)
                continue;
            if ("caf_cl".equals(def.getCompetitionKey())) {
                tournament.setEntrants(clAllocation.clGroupStageEntrants);
            } else if ("caf_cc".equals(def.getCompetitionKey())) {
                // CAF Confederation Cup: same structure as CL but next-highest-ranked clubs
                Club prevCcWinner = previousContinentalWinners.get("caf_cc");
                CafQualifyingEngine.CafSeasonAllocation ccAllocation = CafQualifyingEngine.allocateAndQualifyExcluding(
                        ranked,
                        this::getRankedClubsForCountry, prevCcWinner, clParticipants);
                addQualifyingRounds("caf_cc", ccAllocation.qualifyingMatchesByRound);
                continentalQualifyingMatches.putIfAbsent("caf_cc", new LinkedHashMap<>());
                tournament.setEntrants(ccAllocation.clGroupStageEntrants);
            }
        }
    }

    private void allocateConcacafEntrants(List<CountryAssociation> confCountries,
            List<ContinentalCompetitionDef> defs) {
        List<CountryAssociation> ranked = new ArrayList<>(confCountries);
        ranked.sort((a, b) -> Double.compare(b.getRollingCoefficient(), a.getRollingCoefficient()));

        Club prevWinner = previousContinentalWinners.get("concacaf_cc");

        ConcacafQualifyingEngine.ConcacafSeasonAllocation allocation = ConcacafQualifyingEngine.allocateAndQualify(
                ranked, this::getRankedClubsForCountry,
                prevWinner);

        addQualifyingRounds("concacaf_cc", allocation.qualifyingMatchesByRound);
        continentalQualifyingMatches.putIfAbsent("concacaf_cc", new LinkedHashMap<>());

        for (ContinentalCompetitionDef def : defs) {
            ContinentalTournament tournament = continentalTournaments.get(def.getCompetitionKey());
            if (tournament == null)
                continue;
            if ("concacaf_cc".equals(def.getCompetitionKey())) {
                tournament.setEntrants(allocation.ccR16Entrants);
            }
        }
    }

    private void allocateOfcEntrants(List<CountryAssociation> confCountries,
            List<ContinentalCompetitionDef> defs) {
        List<CountryAssociation> ranked = new ArrayList<>(confCountries);
        ranked.sort((a, b) -> Double.compare(b.getRollingCoefficient(), a.getRollingCoefficient()));

        OfcQualifyingEngine.OfcSeasonAllocation allocation = OfcQualifyingEngine.allocateAndQualify(ranked,
                this::getRankedClubsForCountry);

        addQualifyingRounds("ofc_cl", allocation.qualifyingMatchesByRound);
        continentalQualifyingMatches.putIfAbsent("ofc_cl", new LinkedHashMap<>());

        for (ContinentalCompetitionDef def : defs) {
            ContinentalTournament tournament = continentalTournaments.get(def.getCompetitionKey());
            if (tournament == null)
                continue;
            if ("ofc_cl".equals(def.getCompetitionKey())) {
                tournament.setEntrants(allocation.ofcClEntrants);
            }
        }
    }

    private void allocateNonUefaEntrants(Confederation conf,
            List<CountryAssociation> confCountries,
            List<ContinentalCompetitionDef> defs) {
        SlotAllocator.TieredSlots tiered = SlotAllocator.allocateTiered(
                conf, confCountries, defs, null);

        Set<Club> alreadyQualified = new HashSet<>();

        for (ContinentalCompetitionDef def : defs) {
            Map<String, Integer> compSlots = tiered.forCompetition(def.getCompetitionKey());
            List<Club> entrants = new ArrayList<>();

            for (CountryAssociation ca : confCountries) {
                int slotsForCountry = compSlots.getOrDefault(ca.getName(), 0);
                if (slotsForCountry <= 0)
                    continue;

                League top = ca.getTopLeague();
                if (top == null)
                    continue;

                List<Club> ranked = getRankedClubsForCountry(ca);

                int added = 0;
                Club cupWinner = ca.getLastCupWinner();
                if (def.getTier() >= 2 && cupWinner != null && cupWinner.getConfederation() == conf
                        && !alreadyQualified.contains(cupWinner)) {
                    entrants.add(cupWinner);
                    alreadyQualified.add(cupWinner);
                    added++;
                }

                for (int i = 0; i < ranked.size() && added < slotsForCountry; i++) {
                    Club q = ranked.get(i);
                    if (alreadyQualified.contains(q))
                        continue;
                    entrants.add(q);
                    alreadyQualified.add(q);
                    added++;
                }
            }

            ContinentalTournament tournament = continentalTournaments.get(def.getCompetitionKey());
            if (tournament != null) {
                tournament.setEntrants(entrants);
            }
        }
    }

    public void printContinentalEntrants(String competitionKey) {
        if (competitionKey == null || competitionKey.isEmpty()) {
            // Print all continental competitions
            for (Map.Entry<Confederation, List<ContinentalCompetitionDef>> entry : continentalDefs.entrySet()) {
                for (ContinentalCompetitionDef def : entry.getValue()) {
                    printEntrantsForCompetition(def);
                }
            }
            return;
        }
        // Find specific competition
        for (Map.Entry<Confederation, List<ContinentalCompetitionDef>> entry : continentalDefs.entrySet()) {
            for (ContinentalCompetitionDef def : entry.getValue()) {
                if (def.getCompetitionKey().equalsIgnoreCase(competitionKey)) {
                    printEntrantsForCompetition(def);
                    return;
                }
            }
        }
        System.out.println("Competition not found: " + competitionKey);
    }

    public void printContinentalQualifyingMatches(String competitionKey) {
        if (continentalQualifyingMatches.isEmpty()) {
            System.out.println("\nNo continental qualifying matches recorded.");
            return;
        }

        if (competitionKey == null || competitionKey.isEmpty()) {
            for (Map.Entry<String, LinkedHashMap<String, List<Match>>> entry : continentalQualifyingMatches
                    .entrySet()) {
                printQualifyingMatchesForKey(entry.getKey(), entry.getValue());
            }
            return;
        }

        LinkedHashMap<String, List<Match>> matchesByRound = continentalQualifyingMatches.get(competitionKey);
        if (matchesByRound == null) {
            System.out.println("Competition not found: " + competitionKey);
            return;
        }
        printQualifyingMatchesForKey(competitionKey, matchesByRound);
    }

    private void printQualifyingMatchesForKey(String competitionKey,
            LinkedHashMap<String, List<Match>> matchesByRound) {
        ContinentalTournament tournament = continentalTournaments.get(competitionKey);
        String title = tournament != null ? tournament.getName() : competitionKey;

        System.out.println("\n" + "=".repeat(80));
        System.out.println(title + " - Qualifying Matches");
        System.out.println("=".repeat(80));

        if (matchesByRound == null || matchesByRound.isEmpty()) {
            System.out.println("  No qualifying matches.");
            return;
        }
        for (Map.Entry<String, List<Match>> entry : matchesByRound.entrySet()) {
            System.out.println("  [" + entry.getKey() + "]");
            List<Match> matches = entry.getValue();
            if (matches == null || matches.isEmpty()) {
                System.out.println("    No matches.");
                continue;
            }
            for (Match m : matches) {
                System.out.println("    " + formatQualifyingMatch(m));
            }
        }
    }

    private String formatQualifyingMatch(Match m) {
        String h = m.getHome().getNameWithCountry();
        String a = m.getAway().getNameWithCountry();
        if (!m.hasResult())
            return h + " vs " + a;
        return h + " " + m.getHomeGoals() + " - " + m.getAwayGoals() + " " + a;
    }

    private void printEntrantsForCompetition(ContinentalCompetitionDef def) {
        ContinentalTournament tournament = continentalTournaments.get(def.getCompetitionKey());
        if (tournament == null)
            return;

        System.out.println("\n" + "=".repeat(80));
        System.out.println(def.getDisplayName() + " (Tier " + def.getTier() + ")");
        System.out.println("=".repeat(80));

        Confederation conf = def.getConfederation();
        List<CountryAssociation> confCountries = countriesByConfed.getOrDefault(conf, new ArrayList<>());
        confCountries = new ArrayList<>(confCountries);
        confCountries.removeIf(ca -> excludedCountries.contains(ca.getName()));

        // For UEFA: show the actual qualified clubs (post-qualifying)
        if (conf == Confederation.EUROPE) {
            printUefaEntrants(def, confCountries);
            return;
        }

        // Non-UEFA: show slot-based allocation
        SlotAllocator.TieredSlots tiered = SlotAllocator.allocateTiered(
                conf, confCountries, continentalDefs.getOrDefault(conf, new ArrayList<>()), null);
        Map<String, Integer> compSlots = tiered.forCompetition(def.getCompetitionKey());

        List<CountryAssociation> ranked = new ArrayList<>(confCountries);
        ranked.sort((a, b) -> Double.compare(b.getRollingCoefficient(), a.getRollingCoefficient()));

        int totalEntrants = 0;
        for (int ci = 0; ci < ranked.size(); ci++) {
            CountryAssociation ca = ranked.get(ci);
            int slotsForCountry = compSlots.getOrDefault(ca.getName(), 0);
            if (slotsForCountry <= 0)
                continue;

            League top = ca.getTopLeague();
            if (top == null)
                continue;

            List<Club> clubRanked = getRankedClubsForCountry(ca);
            boolean firstSeason = (ca.getLastTopLeagueFinalTable() == null
                    || ca.getLastTopLeagueFinalTable().isEmpty());

            System.out.printf("\n  [Rank %d] %s (Coefficient: %.2f) - %d slot(s)%n",
                    ci + 1, ca.getName(), ca.getRollingCoefficient(), slotsForCountry);

            int added = 0;
            for (int i = 0; i < clubRanked.size() && added < slotsForCountry; i++) {
                Club q = clubRanked.get(i);
                String qual = firstSeason ? "ELO-based (season 1)" : ("Pos " + (i + 1));
                System.out.printf("    %d. %-35s ELO: %6.0f  [%s]%n",
                        added + 1, q.getNameWithCountry(), q.getEloRating(), qual);
                added++;
            }
            totalEntrants += added;
        }

        System.out.printf("\n  Total entrants: %d (target: %d)%n", totalEntrants, def.getMainStageTeams());

        List<ContinentalCompetitionDef.StageDef> stages = def.getStages();
        if (!stages.isEmpty()) {
            System.out.println("\n  Stage Structure:");
            for (ContinentalCompetitionDef.StageDef stage : stages) {
                System.out.printf("    %d. %-45s [%s] %d teams%n",
                        stage.getStageOrder(), stage.getStageName(),
                        stage.getStageType(), stage.getTeams());
            }
        }
    }

    private void printUefaEntrants(ContinentalCompetitionDef def, List<CountryAssociation> confCountries) {
        List<CountryAssociation> ranked = new ArrayList<>(confCountries);
        ranked.sort((a, b) -> Double.compare(b.getRollingCoefficient(), a.getRollingCoefficient()));

        boolean russiaExcluded = excludedCountries.contains("Russia");
        boolean israelExcluded = excludedCountries.contains("Israel");
        boolean bothExcluded = russiaExcluded && israelExcluded;
        boolean oneExcluded = (russiaExcluded || israelExcluded) && !bothExcluded;

        // Re-run allocation (without simulation) to show the structure
        int totalNations = ranked.size();
        boolean firstSeason = ranked.isEmpty() || ranked.get(0).getLastTopLeagueFinalTable() == null;

        if ("uefa_ucl".equals(def.getCompetitionKey())) {
            System.out.println("\n  AUTOMATIC QUALIFICATION (League Phase):");
            int totalDirect = 0;
            for (int i = 0; i < Math.min(totalNations, 11); i++) {
                CountryAssociation ca = ranked.get(i);
                int r = i + 1;
                int slots;
                if (r == 1)
                    slots = 5;
                else if (r >= 2 && r <= 4)
                    slots = (r == 2 && (oneExcluded || bothExcluded)) ? 5 : 4;
                else if (r == 5)
                    slots = 3;
                else if (r == 6)
                    slots = 2;
                else
                    slots = (r == 7 && bothExcluded) ? 2 : 1;

                List<Club> clubs = getRankedClubsForCountry(ca);
                System.out.printf("\n    [Rank %d] %s (Coeff: %.2f) - %d slot(s)%n",
                        r, ca.getName(), ca.getRollingCoefficient(), slots);
                for (int j = 0; j < Math.min(slots, clubs.size()); j++) {
                    Club c = clubs.get(j);
                    String pos = firstSeason ? "ELO-based" : (j == 0 ? "Champion" : "Pos " + (j + 1));
                    System.out.printf("      %d. %-35s ELO: %6.0f  [%s]%n",
                            j + 1, c.getNameWithCountry(), c.getEloRating(), pos);
                }
                totalDirect += Math.min(slots, clubs.size());
            }
            System.out.printf("\n    Direct qualifiers: %d%n", totalDirect);

            System.out.println("\n  CHAMPIONS PATH QUALIFYING:");
            System.out.printf("    Q1: Rank %d-%d champions (%d teams)%n",
                    Math.min(27, totalNations), totalNations,
                    Math.max(0, totalNations - 26));
            System.out.printf("    Q2: Q1 winners + Rank 17-%d champions%n",
                    Math.min(26, totalNations));
            System.out.println("    Q3: Q2 winners (paired)");
            System.out.println("    PO: Q3 winners + Rank 12-15 champions -> 5 qualify for League Phase");

            System.out.println("\n  LEAGUE PATH QUALIFYING:");
            System.out.println("    Q2: Rank 10-15 runners-up");
            System.out.println("    Q3: Q2 winners + various non-champion qualifiers");
            System.out.println("    PO: Q3 winners -> 2 qualify for League Phase");

            System.out.printf("\n  Target: 36 teams (direct: %d + CP: 5 + LP: 2 = %d)%n",
                    totalDirect, totalDirect + 7);
        } else {
            // UEL/UECL: show the actual entrant list from the tournament
            ContinentalTournament tournament = continentalTournaments.get(def.getCompetitionKey());
            if (tournament != null) {
                // Group entrants by country
                Map<String, List<Club>> byCountry = new LinkedHashMap<>();
                for (Club c : tournament.getEntrants()) {
                    byCountry.computeIfAbsent(c.getCountry(), k -> new ArrayList<>()).add(c);
                }
                int total = 0;
                for (Map.Entry<String, List<Club>> e : byCountry.entrySet()) {
                    System.out.printf("\n    %s (%d club(s)):%n", e.getKey(), e.getValue().size());
                    for (Club c : e.getValue()) {
                        System.out.printf("      - %-35s ELO: %6.0f%n",
                                c.getName(), c.getEloRating());
                        total++;
                    }
                }
                System.out.printf("\n  Total: %d (target: %d)%n", total, def.getMainStageTeams());
            }
        }

        List<ContinentalCompetitionDef.StageDef> stages = def.getStages();
        if (!stages.isEmpty()) {
            System.out.println("\n  Stage Structure:");
            for (ContinentalCompetitionDef.StageDef stage : stages) {
                System.out.printf("    %d. %-45s [%s] %d teams%n",
                        stage.getStageOrder(), stage.getStageName(),
                        stage.getStageType(), stage.getTeams());
            }
        }
    }

    public void simulateSeason() {
        if (verbose) {
            System.out.println("\n" + "=".repeat(70));
            System.out.println("STARTING SEASON " + seasonYear);
            System.out.printf("Leagues: %d | Countries: %d | Confederations: %d%n",
                    allLeagues.size(), countries.size(), countriesByConfed.size());
            System.out.println("=".repeat(70));
        }

        // Clear old export files at start of season
        clearOldExportFiles();

        for (double slot : buildSeasonSlots()) {
            if (slot >= ROLLOVER_T) {
                break;
            }
            simulateSlot(slot);

            if (allLeaguesComplete() && allCupsComplete()) {
                break;
            }

            if (verbose && slot % 10 == 0) {
                System.out.printf("\n[PROGRESS] %.0f%% complete (t=%.1f)%n",
                        (slot * 100) / 52.0, slot);
            }
        }

        if (verbose)
            System.out.println("\n" + "=".repeat(70));
        endSeason();
    }

    public void printDomesticCupRound(String country) {
        if (!ENABLE_DOMESTIC_CUPS) {
            System.out.println("Domestic cups are disabled.");
            return;
        }
        if (country == null || country.trim().isEmpty()) {
            System.out.println("No country provided.");
            return;
        }
        String resolvedCountry = resolveCountryKey(country);
        KnockoutCup cup = domesticCups.get(resolvedCountry);
        if (cup == null) {
            System.out.println("Cup not found for " + country + ".");
            return;
        }
        List<KnockoutCup.RoundResult> rounds = cup.getRoundHistory();
        if (rounds.isEmpty()) {
            System.out.println("No cup matches recorded yet.");
            return;
        }
        KnockoutCup.RoundResult latest = rounds.get(rounds.size() - 1);
        System.out.println("\n" + "=".repeat(70));
        System.out.println(cup.getName() + " - " + latest.roundName);
        System.out.println("=".repeat(70));
        for (Match match : latest.matches) {
            System.out.println(match);
        }
    }

    public void printDomesticSuperCupResults(String country) {
        if (country == null || country.trim().isEmpty()) {
            System.out.println("No country provided.");
            return;
        }
        String resolvedCountry = resolveCountryKey(country);
        Match m = domesticSuperCupResults.get(resolvedCountry);
        if (m == null) {
            System.out.println(
                    "No Super Cup result found for " + country + ". (Same club may have won both league and cup.)");
            return;
        }
        System.out.println("\n" + "=".repeat(70));
        System.out.println(resolvedCountry + " Super Cup (League Champion vs Cup Winner)");
        System.out.println("=".repeat(70));
        System.out.println("  " + m);
    }

    private String resolveCountryKey(String input) {
        if (input == null) {
            return "";
        }
        String raw = input.trim();
        if (raw.isEmpty()) {
            return raw;
        }

        String normalized = raw.toLowerCase(Locale.ROOT)
                .replace("ü", "u")
                .replace("ı", "i")
                .replace("ł", "l")
                .replace("ā", "a")
                .replace("ç", "c")
                .replace("ö", "o")
                .replaceAll("[^a-z0-9 ]", "")
                .trim();

        Map<String, String> aliases = new HashMap<>();
        aliases.put("usa", "USA");
        aliases.put("us", "USA");
        aliases.put("united states", "USA");
        aliases.put("united states of america", "USA");
        aliases.put("turkey", "Türkiye");
        aliases.put("turkiye", "Türkiye");

        if (aliases.containsKey(normalized)) {
            return aliases.get(normalized);
        }

        for (String country : countries.keySet()) {
            String countryNormalized = country.toLowerCase(Locale.ROOT)
                    .replace("ü", "u")
                    .replace("ı", "i")
                    .replace("ł", "l")
                    .replace("ā", "a")
                    .replace("ç", "c")
                    .replace("ö", "o")
                    .replaceAll("[^a-z0-9 ]", "")
                    .trim();
            if (countryNormalized.equals(normalized)) {
                return country;
            }
        }

        return raw;
    }

    public void printAllDomesticSuperCupResults() {
        if (domesticSuperCupResults.isEmpty()) {
            System.out.println("No Super Cup results available.");
            return;
        }
        System.out.println("\n" + "=".repeat(70));
        System.out.println("DOMESTIC SUPER CUP RESULTS - Season " + seasonYear);
        System.out.println("=".repeat(70));
        for (Map.Entry<String, Match> entry : domesticSuperCupResults.entrySet()) {
            System.out.printf("  %-25s: %s%n", entry.getKey(), entry.getValue());
        }
    }

    private boolean isInTransferWindow(double t) {
        return (t >= 1.0 && t <= 4.0) || (t >= 24.0 && t <= 26.0);
    }

    private String getTransferWindowName(double t) {
        if (t >= 1.0 && t <= 4.0) {
            return "SUMMER";
        }
        if (t >= 24.0 && t <= 26.0) {
            return "WINTER";
        }
        return "";
    }

    private void simulateSlot(double t) {
        lastBiggestUpset = null;
        boolean isWholeWeek = Math.abs(t % 1.0) < 1e-6;
        boolean hasEvent = slotLabels.containsKey(t) || leagueMatchSlots.contains(t);
        String windowName = getTransferWindowName(t);
        boolean inTransferWindowSlot = !windowName.isEmpty();

        if (!isWholeWeek && !hasEvent && !inTransferWindowSlot) {
            return;
        }

        if (verbose && (hasEvent || inTransferWindowSlot)) {
            System.out.println("\n" + "=".repeat(70));
            System.out.printf("t=%.1f | %s", t, slotLabels.getOrDefault(t, ""));
            System.out.println("\n" + "=".repeat(70));
        }

        // Weekly economic tick for all clubs (whole weeks only)
        if (isWholeWeek) {
            boolean isTransferWindow = isInTransferWindow(t);
            for (Club club : clubIndex.values()) {
                club.weeklyTick(t, isTransferWindow);
            }
        }

        if (inTransferWindowSlot) {
            if (!windowName.equals(currentTransferWindow)) {
                currentTransferWindow = windowName;
                initializeTransferWindow(windowName);
            }
            simulateTransferWindowTick(t, "WINTER".equals(windowName));
            if (("SUMMER".equals(windowName) && Double.compare(t, 4.0) == 0)
                    || ("WINTER".equals(windowName) && Double.compare(t, 26.0) == 0)) {
                if (verbose) {
                    displayTransferMarketActivity(windowName, t);
                }
                currentTransferWindow = "";
                exportableStrengthPool.clear();
            }
        }

        // Mid-season and late-season FFP audits
        if (Double.compare(t, 20.0) == 0 || Double.compare(t, 40.0) == 0) {
            for (Club club : clubIndex.values()) {
                club.performAudit(false);
            }
        }

        // Mid-season rebalancing for multi-group tiers to prevent overflow
        if (Double.compare(t, 22.0) == 0) {
            rebalanceMultiGroupTiers();
        }

        if (t >= 2.0 && t <= 4.0 && Math.abs(t % 0.5) < 1e-6) {
            simulatePreseasonFriendlies(t);
            simulateUnassignedFriendlies(t);
        }

        if (Double.compare(t, 4.5) == 0) {
            simulateDomesticSuperCups();
        }

        if (ENABLE_DOMESTIC_CUPS && domesticCupSlots.contains(t)) {
            for (KnockoutCup cup : domesticCups.values()) {
                cup.simulateNextRound(verbose, t);
            }
        }

        // Continental match windows
        if (ENABLE_CONTINENTAL && continentalSlots.contains(t)) {
            for (ContinentalTournament ct : continentalTournaments.values()) {
                if (!ct.isComplete()) {
                    ct.simulateNextMatchWindow(verbose);
                }
            }
        }

        if (leagueMatchSlots.contains(t)) {
            int matchCount = 0;
            double biggestUpsetGap = 0;
            for (League l : allLeagues) {
                if (l.hasNextRound()) {
                    List<Match> ms = l.simulateNextRound(verbose, t);
                    matchCount += ms.size();
                    for (Match m : ms) {
                        if (!m.hasResult())
                            continue;
                        double homeElo = m.getHome().getEloRating();
                        double awayElo = m.getAway().getEloRating();
                        boolean homeWon = m.getHomeGoals() > m.getAwayGoals();
                        boolean awayWon = m.getAwayGoals() > m.getHomeGoals();
                        double gap = 0;
                        if (homeWon && awayElo > homeElo)
                            gap = awayElo - homeElo;
                        else if (awayWon && homeElo > awayElo)
                            gap = homeElo - awayElo;
                        if (gap > biggestUpsetGap) {
                            biggestUpsetGap = gap;
                            lastBiggestUpset = m;
                        }
                    }
                }
            }
            if (verbose && matchCount > 0) {
                System.out.printf("\nLeague Matches: %d matches played%n", matchCount);
            }
        }
    }

    public void printWorldSchedule() {
        buildSeasonCalendar();
        System.out.println("\n" + "=".repeat(70));
        System.out.println("WORLD SCHEDULE (t = 0.0..52.0)");
        System.out.println("=".repeat(70));

        for (double t : buildSeasonSlots()) {
            String label = slotLabels.getOrDefault(t, "No scheduled competitions");
            String slotType = (Math.abs(t % 1.0) < 1e-6) ? "Weekend" : "Midweek";
            if (Double.compare(t, ROLLOVER_T) == 0) {
                slotType = "Rollover";
                label = "Rollover / bookkeeping";
            }
            System.out.printf("t=%4.1f (%s): %s%n", t, slotType, label);
        }
    }

    private List<Double> buildSeasonSlots() {
        List<Double> slots = new ArrayList<>();
        for (double t = 0.0; t <= ROLLOVER_T + 1e-6; t += SLOT_STEP) {
            slots.add(roundSlot(t));
        }
        return slots;
    }

    private void buildSeasonCalendar() {
        leagueMatchSlots.clear();
        domesticCupSlots.clear();
        continentalSlots.clear();
        slotLabels.clear();
        Set<Double> blocked = new HashSet<>();

        markRange(blocked, 0.0, 2.0, "Summer break");
        markRange(blocked, 0.0, 4.0, "Summer transfer window");
        markRange(blocked, 2.0, 4.0, "Preseason friendlies");
        addLabel(4.5, "Domestic Super Cup");

        markSlot(blocked, 24.5, "Winter break");
        markSlot(blocked, 25.5, "Winter break");
        addLabel(24.0, "Winter transfer window");
        addLabel(25.0, "Winter transfer window");
        addLabel(26.0, "Winter transfer window");

        if (ENABLE_DOMESTIC_CUPS) {
            for (int week : DOMESTIC_CUP_WEEKS) {
                double slot = roundSlot(week + 0.5);
                domesticCupSlots.add(slot);
                markSlot(blocked, slot, "Domestic cup round");
            }
        }

        if (ENABLE_CONTINENTAL) {
            for (int week : CONTINENTAL_PHASE_WEEKS) {
                double slot = roundSlot(week + 0.5);
                continentalSlots.add(slot);
                addLabel(slot, "Continental matchday");
            }
            for (int week : CONTINENTAL_KO_WEEKS) {
                double slot = roundSlot(week + 0.5);
                continentalSlots.add(slot);
                addLabel(slot, "Continental knockout");
            }
        }

        if (ENABLE_DOMESTIC_CUPS) {
            markSlot(blocked, 40.0, "Domestic cup semifinals");
            markSlot(blocked, 42.0, "Domestic cup final");
        }
        markSlot(blocked, 43.0, "Continental final");
        markRange(blocked, 45.0, 47.0, "Intercontinental cup");
        markRange(blocked, 48.5, 52.0, "Offseason / awards");
        addLabel(20.0, "Mid-season FFP audit");
        addLabel(40.0, "Late-season FFP audit");

        List<Double> weekendSlots = new ArrayList<>();
        List<Double> midweekSlots = new ArrayList<>();
        for (double t : buildSeasonSlots()) {
            if (t < 5.0 || t > 48.0)
                continue;
            if (blocked.contains(t))
                continue;
            if (Math.abs(t % 1.0) < 1e-6) {
                weekendSlots.add(t);
            } else {
                midweekSlots.add(t);
            }
        }

        int maxRounds = 0;
        for (League league : allLeagues) {
            maxRounds = Math.max(maxRounds, league.getRoundsCount());
        }

        List<Double> scheduleSlots = new ArrayList<>(weekendSlots);

        for (int i = 0; i < Math.min(maxRounds, scheduleSlots.size()); i++) {
            double t = scheduleSlots.get(i);
            leagueMatchSlots.add(t);
            addLabel(t, "League matchday");
        }
    }

    private void markRange(Set<Double> blocked, double start, double end, String label) {
        for (double t = start; t <= end + 1e-6; t += SLOT_STEP) {
            double slot = roundSlot(t);
            blocked.add(slot);
            addLabel(slot, label);
        }
    }

    private void markSlot(Set<Double> blocked, double t, String label) {
        double slot = roundSlot(t);
        blocked.add(slot);
        addLabel(slot, label);
    }

    private void addLabel(double t, String label) {
        String existing = slotLabels.get(t);
        if (existing == null) {
            slotLabels.put(t, label);
            return;
        }
        if (!existing.contains(label)) {
            slotLabels.put(t, existing + " | " + label);
        }
    }

    private double roundSlot(double t) {
        return Math.round(t * 2.0) / 2.0;
    }

    private boolean allLeaguesComplete() {
        for (League league : allLeagues) {
            if (league.hasNextRound())
                return false;
        }
        return true;
    }

    public void clearSeasonState() {
        seasonCompleted = false;
        seasonInitialized = false;
        // CRITICAL FIX: Reset all per-season state variables for multi-season runs
        // Without these, the next season will use stale data (wrong year, wrong slots,
        // duplicate transfers)
        nextSlotIndex = 0;
        seasonSlots.clear();
        leagueMatchSlots.clear();
        domesticCupSlots.clear();
        continentalSlots.clear();
        currentTransferWindow = "";
        exportableStrengthPool.clear();
        transfersThisWindow = 0;
        transfersThisSlot = 0;
        lastBiggestUpset = null;
    }

    private void simulatePreseasonFriendlies(double t) {
        List<Club> allClubs = new ArrayList<>(clubIndex.values());
        if (allClubs.size() < 2)
            return;
        Collections.shuffle(allClubs, new Random(Double.doubleToLongBits(t) ^ seasonYear * 31L));

        for (int i = 0; i + 1 < allClubs.size(); i += 2) {
            Club a = allClubs.get(i);
            Club b = allClubs.get(i + 1);
            MatchEngine.play(a, b, false, false, t, 2.0);
        }
    }

    private void simulateDomesticSuperCups() {
        domesticSuperCupResults.clear();
        for (CountryAssociation ca : countries.values()) {
            Club lc = ca.getLastLeagueChampion();
            Club cw = ca.getLastCupWinner();
            if (lc == null || cw == null)
                continue;

            Club challenger = cw;
            if (lc.equals(cw)) {
                List<Club> table = ca.getLastTopLeagueFinalTable();
                if (table != null && table.size() >= 2) {
                    challenger = table.get(1);
                } else {
                    lc.addTrophy(ca.getName() + " Super Cup", seasonYear);
                    continue;
                }
            }

            Match m = new Match(lc, challenger, true);
            MatchEngine.Score s = MatchEngine.play(m.getHome(), m.getAway(), true, false, 4.5, 4.0);
            m.setResult(s.homeGoals, s.awayGoals);

            Club winner;
            if (m.getHomeGoals() > m.getAwayGoals())
                winner = m.getHome();
            else if (m.getAwayGoals() > m.getHomeGoals())
                winner = m.getAway();
            else
                winner = MatchEngine.resolveKnockoutWinner(m.getHome(), m.getAway());

            winner.addTrophy(ca.getName() + " Super Cup", seasonYear);
            domesticSuperCupResults.put(ca.getName(), m);
        }
    }

    private void simulateConfederationSuperCups() {
        for (Map.Entry<Confederation, List<ContinentalCompetitionDef>> entry : continentalDefs.entrySet()) {
            Confederation conf = entry.getKey();
            List<ContinentalCompetitionDef> defs = entry.getValue();
            if (defs.size() < 2)
                continue;

            ContinentalTournament tier1 = continentalTournaments.get(defs.get(0).getCompetitionKey());
            ContinentalTournament tier2 = continentalTournaments.get(defs.get(1).getCompetitionKey());
            if (tier1 == null || tier2 == null)
                continue;

            Club a = tier1.getChampion();
            Club b = tier2.getChampion();
            if (a == null || b == null)
                continue;

            Match m = new Match(a, b, true);
            MatchEngine.Score s = MatchEngine.play(m.getHome(), m.getAway(), false, false, 47.0, 10.0);
            m.setResult(s.homeGoals, s.awayGoals);

            Club winner;
            if (m.getHomeGoals() > m.getAwayGoals())
                winner = m.getHome();
            else if (m.getAwayGoals() > m.getHomeGoals())
                winner = m.getAway();
            else
                winner = MatchEngine.resolveKnockoutWinner(m.getHome(), m.getAway());

            winner.addTrophy(conf.getOptaName() + " Super Cup", seasonYear);
        }
    }

    private void endSeason() {
        // Phase 2 & 3: Initialize seasonal event logging
        SeasonalEventLog.getInstance().setSeason(seasonYear);

        for (League league : allLeagues) {
            league.sortTable();
        }

        if (ENABLE_PARITY_TUNING) {
            applyLeagueParityAdjustments();
        }

        // Determine champions BEFORE endSeasonUpdate so dynasty/success flags are
        // current
        for (CountryAssociation ca : countries.values()) {
            League top = ca.getTopLeague();
            if (top == null)
                continue;
            top.sortTable();
            ca.setLastTopLeagueFinalTable(new ArrayList<>(top.getClubs()));
            Club champion = top.getChampion();
            ca.setLastLeagueChampion(champion);
            if (champion != null) {
                champion.addTrophy(top.getName(), seasonYear);
                champion.markLeagueChampion(seasonYear);
            }
        }

        // Domestic cups
        for (Map.Entry<String, KnockoutCup> e : domesticCups.entrySet()) {
            Club w = e.getValue().getChampion();
            if (w != null) {
                countries.get(e.getKey()).setLastCupWinner(w);
                w.addTrophy(e.getValue().getName(), seasonYear);
            }
        }

        // Record standings for all clubs
        for (League league : allLeagues) {
            int position = 1;
            for (Club club : league.getClubs()) {
                club.recordSeasonStanding(seasonYear, league.getName(), league.getLevel(), position, club.getPoints());
                position++;
            }
        }

        // End-of-season economic updates and final FFP audit for all clubs
        for (Club club : clubIndex.values()) {
            club.endSeasonUpdate();
        }
        for (Club club : clubIndex.values()) {
            club.performAudit(true);
        }

        applySeasonEconomyRewards();

        // Continental trophies + titleholder tracking
        if (ENABLE_CONTINENTAL) {
            previousContinentalWinners.clear();
            for (Map.Entry<String, ContinentalTournament> entry : continentalTournaments.entrySet()) {
                Club w = entry.getValue().getChampion();
                if (w != null) {
                    w.addTrophy(entry.getValue().getName(), seasonYear);
                    previousContinentalWinners.put(entry.getKey(), w);
                }
            }
        }

        // Global
        if (ENABLE_GLOBAL && globalClubCup != null && globalClubCup.getChampion() != null) {
            globalClubCup.getChampion().addTrophy(globalClubCup.getName(), seasonYear);
        }

        // Update coefficients based on continental performance (UEFA-like average)
        if (ENABLE_CONTINENTAL) {
            simulateConfederationSuperCups();
            applyContinentalCoefficients();
        }

        seasonCompleted = true;
        seasonInitialized = false;

        // Print a compact summary
        if (!suppressSeasonSummary) {
            printSeasonSummary();
        }

        exportSeasonWinners();
        exportCupWinners();
        exportLeagueSnapshot(seasonYear);

        // Phase 2: Export seasonal events
        try {
            SeasonalEventLog.getInstance().exportFFPViolations(
                    Path.of(exportRunFolder, "ffp_violations.csv"));
            SeasonalEventLog.getInstance().exportFloorPenalties(
                    Path.of(exportRunFolder, "floor_penalties.csv"));
        } catch (IOException e) {
            System.err.println("Failed to export seasonal events: " + e.getMessage());
        }

        // Phase 3: Export validation diagnostics (FIX: now generates directly from
        // league state)
        exportIntegrityWarnings(seasonYear, null);

        // Phase 4: Export seasonal diagnostics
        exportSeasonDiagnostics(seasonYear);

        // Clear event log for next season
        SeasonalEventLog.getInstance().clearSeason();
    }

    private void applyLeagueParityAdjustments() {
        for (League league : allLeagues) {
            List<Club> clubs = league.getClubs();
            if (clubs.size() < 6) {
                continue;
            }

            league.sortTable();
            Club champion = league.getChampion();
            Club runnerUp = clubs.size() > 1 ? clubs.get(1) : null;
            if (champion == null || runnerUp == null) {
                continue;
            }

            double champPpg = champion.getPoints() / Math.max(1.0, champion.getGamesPlayed());
            double runnerPpg = runnerUp.getPoints() / Math.max(1.0, runnerUp.getGamesPlayed());
            double gap = champPpg - runnerPpg;

            // Load from config
            double ppgThreshold = SimulatorConfigReader.getParityPpgGapThreshold();
            if (gap <= ppgThreshold) {
                continue;
            }

            int topCount = Math.min(SimulatorConfigReader.getParityTopClubsCount(), clubs.size());
            int bottomCount = Math.min(SimulatorConfigReader.getParityBottomClubsCount(), clubs.size());

            // Calculate shifts using config parameters
            double strengthShiftMin = SimulatorConfigReader.getParityStrengthShiftMin();
            double strengthShiftMax = SimulatorConfigReader.getParityStrengthShiftMax();
            double strengthMultiplier = SimulatorConfigReader.getParityPpgGapMultiplier();
            double strengthShift = Math.min(strengthShiftMax, strengthShiftMin + gap * strengthMultiplier);

            double financeShiftMin = SimulatorConfigReader.getParityFinanceShiftMin();
            double financeShiftMax = SimulatorConfigReader.getParityFinanceShiftMax();
            double financeMultiplier = SimulatorConfigReader.getParityFinanceGapMultiplier();
            double financeShift = Math.min(financeShiftMax, financeShiftMin + gap * financeMultiplier);

            double moraleShiftMin = SimulatorConfigReader.getParityMoraleShiftMin();
            double moraleShiftMax = SimulatorConfigReader.getParityMoraleShiftMax();
            double moraleMultiplier = SimulatorConfigReader.getParityMoraleGapMultiplier();
            double moraleShift = Math.min(moraleShiftMax, moraleShiftMin + gap * moraleMultiplier);

            for (int i = 0; i < topCount; i++) {
                Club top = clubs.get(i);
                top.applyParityAdjustment(-strengthShift, -financeShift, -1.0, -moraleShift);
            }
            for (int i = clubs.size() - bottomCount; i < clubs.size(); i++) {
                if (i < 0 || i >= clubs.size())
                    continue;
                Club bottom = clubs.get(i);
                bottom.applyParityAdjustment(strengthShift * 0.7, financeShift * 0.75, 0.8, moraleShift * 0.6);
            }
        }
    }

    private void applySeasonEconomyRewards() {
        Map<String, KnockoutCup> cupByCountry = new HashMap<>(domesticCups);
        for (CountryAssociation ca : countries.values()) {
            League top = ca.getTopLeague();
            if (top == null)
                continue;
            top.sortTable();

            double coeffFactor = coefficientEconomyBoost(ca.getRollingCoefficient());
            double underdogBoost = coefficientUnderdogBoost(ca.getRollingCoefficient());
            Map<Club, Double> clubRewards = new HashMap<>();

            List<Club> clubs = top.getClubs();
            int totalClubs = clubs.size();
            for (int i = 0; i < totalClubs; i++) {
                Club club = clubs.get(i);
                double placementScore = (totalClubs - i) / (double) totalClubs;
                double placementBoost = 0.25 + 1.05 * placementScore;
                placementBoost = clamp(Math.pow(placementBoost, 1.12), 0.0, 1.75);
                clubRewards.merge(club, placementBoost, Double::sum);
            }

            KnockoutCup cup = cupByCountry.get(ca.getName());
            if (cup != null) {
                Map<Club, Integer> roundsWon = cup.getRoundsWonByClub();
                for (Map.Entry<Club, Integer> entry : roundsWon.entrySet()) {
                    if (!ca.getName().equalsIgnoreCase(entry.getKey().getCountry()))
                        continue;
                    double cupBoost = 0.35 * Math.pow(1.12, entry.getValue());
                    cupBoost = Math.min(cupBoost, 1.6);
                    clubRewards.merge(entry.getKey(), cupBoost, Double::sum);
                }
            }

            for (Map.Entry<Club, Double> entry : clubRewards.entrySet()) {
                double reward = entry.getValue() * coeffFactor * underdogBoost;
                reward = clamp(reward, 0.0, 3.5);
                entry.getKey().applyEconomyPoints(reward);
            }
        }

        // Continental competition rewards (higher than domestic)
        if (ENABLE_CONTINENTAL) {
            for (Map.Entry<Confederation, List<ContinentalCompetitionDef>> confEntry : continentalDefs.entrySet()) {
                for (ContinentalCompetitionDef def : confEntry.getValue()) {
                    ContinentalTournament ct = continentalTournaments.get(def.getCompetitionKey());
                    if (ct == null)
                        continue;
                    double tierMult = def.getTier() == 1 ? 2.5 : (def.getTier() == 2 ? 1.8 : 1.2);
                    // Reward champion
                    Club champ = ct.getChampion();
                    if (champ != null) {
                        champ.applyEconomyPoints(clamp(tierMult * 1.5, 0.0, 5.0));
                    }
                    // Reward all participants Elook up clubs by country
                    Map<String, ContinentalTournament.CountryCoeffData> coeffData = ct.getCountryCoefficients();
                    for (Map.Entry<String, ContinentalTournament.CountryCoeffData> cde : coeffData.entrySet()) {
                        ContinentalTournament.CountryCoeffData data = cde.getValue();
                        if (data.teamCount > 0 && data.totalPoints() > 0) {
                            double perTeam = clamp(data.totalPoints() / data.teamCount * tierMult * 0.3, 0.0, 3.0);
                            CountryAssociation ca = countries.get(cde.getKey());
                            if (ca != null && ca.getTopLeague() != null) {
                                List<Club> topClubs = ca.getTopLeague().getTopN(data.teamCount);
                                for (Club tc : topClubs) {
                                    tc.applyEconomyPoints(perTeam);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private double coefficientEconomyBoost(double coefficient) {
        double normalized = clamp(coefficient / 35.0, 0.0, 1.2);
        return 1.0 + Math.pow(normalized, 1.4) * 0.55;
    }

    private double coefficientUnderdogBoost(double coefficient) {
        double deficit = clamp((8.0 - coefficient) / 40.0, 0.0, 0.15);
        return 1.0 + deficit;
    }

    private void exportSeasonWinners() {
        if (winnersExporter == null) {
            return;
        }

        Map<String, String> intercontinental = new LinkedHashMap<>();
        if (ENABLE_GLOBAL && globalClubCup != null) {
            intercontinental.put(globalClubCup.getName(),
                    globalClubCup.getChampion() != null ? formatClubToken(globalClubCup.getChampion()) : "");
        }
        if (intercontinental.isEmpty()) {
            intercontinental.put("Global Club Cup", "");
        }

        Map<String, String> continental = new LinkedHashMap<>();
        for (ContinentalTournament t : continentalTournaments.values()) {
            continental.put(t.getName(), t.getChampion() != null ? formatClubToken(t.getChampion()) : "");
        }
        if (continental.isEmpty()) {
            for (Confederation conf : Confederation.values()) {
                if (conf == Confederation.UNKNOWN)
                    continue;
                continental.put(conf.getOptaName() + " Champions Cup", "");
            }
        }

        List<SeasonWinnersExporter.LeagueEntry> domesticEntries = new ArrayList<>();
        Map<String, String> domesticWinners = new LinkedHashMap<>();
        for (League league : allLeagues) {
            Club champ = league.getChampion();
            domesticEntries.add(new SeasonWinnersExporter.LeagueEntry(
                    league.getLevel(), "Domestic", league.getCountry(), league.getName()));
            domesticWinners.put(buildExportKey("Domestic", league.getCountry(), league.getName(), league.getLevel()),
                    champ != null ? champ.getName() : "");
        }

        try {
            winnersExporter.exportSeason(seasonYear, intercontinental, continental, domesticEntries, domesticWinners);

            // Backward-compatible alias for users expecting this filename.
            Path source = Path.of(exportRunFolder, "season_winners.csv");
            Path alias = Path.of(exportRunFolder, "club_winners.csv");
            Files.copy(source, alias, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.err.println("Failed to export season winners: " + e.getMessage());
        }
    }

    private void exportCupWinners() {
        if (cupWinnersExporter == null) {
            return;
        }

        List<CupWinnersExporter.CupEntry> entries = new ArrayList<>();
        Map<String, String> winners = new LinkedHashMap<>();

        // Domestic Cup winners
        for (Map.Entry<String, KnockoutCup> entry : domesticCups.entrySet()) {
            KnockoutCup cup = entry.getValue();
            String country = entry.getKey();
            entries.add(new CupWinnersExporter.CupEntry("Domestic Cup", country, cup.getName()));
            winners.put(CupWinnersExporter.buildKey("Domestic Cup", country, cup.getName()),
                    cup.getChampion() != null ? cup.getChampion().getName() : "");
        }

        // Domestic Super Cup winners
        for (Map.Entry<String, Match> entry : domesticSuperCupResults.entrySet()) {
            String country = entry.getKey();
            Match m = entry.getValue();
            Club winner = null;
            if (m.getHomeGoals() > m.getAwayGoals()) {
                winner = m.getHome();
            } else if (m.getAwayGoals() > m.getHomeGoals()) {
                winner = m.getAway();
            }
            if (winner != null) {
                entries.add(new CupWinnersExporter.CupEntry("Domestic Super Cup", country, country + " Super Cup"));
                winners.put(CupWinnersExporter.buildKey("Domestic Super Cup", country, country + " Super Cup"),
                        winner.getName());
            }
        }

        // Continental Cup winners
        for (Map.Entry<String, ContinentalTournament> entry : continentalTournaments.entrySet()) {
            ContinentalTournament t = entry.getValue();
            entries.add(new CupWinnersExporter.CupEntry("Continental Cup", "Global", t.getName()));
            winners.put(CupWinnersExporter.buildKey("Continental Cup", "Global", t.getName()),
                    t.getChampion() != null ? t.getChampion().getName() : "");
        }

        try {
            cupWinnersExporter.exportSeason(seasonYear, entries, winners);
        } catch (IOException e) {
            System.err.println("Failed to export cup winners: " + e.getMessage());
        }
    }

    private String buildExportKey(String scope, String country, String leagueName, int level) {
        return scope + "|" + country + "|" + leagueName + "|" + level;
    }

    private String formatClubToken(Club club) {
        if (club == null) {
            return "";
        }
        return club.getName() + " [" + club.getCountry() + "]";
    }

    public void applyPromotionAndRelegationForNextSeason() {
        if (!seasonCompleted) {
            return;
        }
        pendingFinancialDropoutsByCountry.clear();
        applyPromotionAndRelegation();
        flushFinancialDropouts();

        // Promote top unassigned clubs back to bottom tier
        for (CountryAssociation ca : countries.values()) {
            promoteTopUnassignedClubs(ca);
        }

        if (!unassignedClubsByCountry.isEmpty()) {
            for (CountryAssociation assoc : countries.values()) {
                assignUnassignedClubs(assoc);
            }
        }
    }

    private void applyPromotionAndRelegation() {
        for (CountryAssociation ca : countries.values()) {
            Map<League, FinancialDropInfo> financialDrops = processFinancialDropouts(ca);
            List<League> leagues = new ArrayList<>(ca.getAllLeagues());
            if (leagues.isEmpty())
                continue;
            if (leagues.size() < 2) {
                recordFinancialDropouts(ca, financialDrops);
                normalizeLeagueSizes(leagues);
                continue;
            }

            leagues.sort(Comparator.comparingInt(League::getLevel).thenComparing(League::getName));
            List<LeagueMove> moves = new ArrayList<>();
            Map<League, Set<Club>> reserved = new HashMap<>();

            for (int i = 0; i < leagues.size() - 1; i++) {
                League upper = leagues.get(i);
                League lower = leagues.get(i + 1);
                if (upper.getLevel() == lower.getLevel()) {
                    continue;
                }

                // Only skip if one side is completely empty
                if (upper.getClubs().isEmpty() || lower.getClubs().isEmpty()) {
                    continue;
                }

                League.MovementRules upperRules = League.calculateMovementRules(upper.getLevel(),
                        upper.getClubs().size());
                League.MovementRules lowerRules = League.calculateMovementRules(lower.getLevel(),
                        lower.getClubs().size());
                int autoPromotion = lowerRules.autoPromotionSlots;
                int promotionPlayoff = lowerRules.promotionPlayoffSlots;
                int relegationSlots = upperRules.relegationSlots;
                int relegationPlayoffSlots = upperRules.relegationPlayoffSlots;

                Set<Club> upperReserved = reserved.getOrDefault(upper, new HashSet<>());
                Set<Club> lowerReserved = reserved.getOrDefault(lower, new HashSet<>());

                int relegationDirectSlots = Math.max(0, relegationSlots - relegationPlayoffSlots);
                List<Club> forcedRelegations = findYouthParentConflicts(upper);
                FinancialDropInfo dropInfo = financialDrops.get(upper);
                int sparedCount = dropInfo != null ? dropInfo.countSpared(forcedRelegations) : 0;
                int adjustedRelegationPlayoffSlots = relegationPlayoffSlots;
                if (sparedCount > 0 && adjustedRelegationPlayoffSlots > 0) {
                    int sparedPlayoff = Math.min(sparedCount, adjustedRelegationPlayoffSlots);
                    adjustedRelegationPlayoffSlots -= sparedPlayoff;
                    sparedCount -= sparedPlayoff;
                }
                int adjustedRelegationSlots = Math.max(0, relegationDirectSlots - sparedCount);
                List<Club> relegated = pickRelegations(upper, forcedRelegations, adjustedRelegationSlots,
                        upperReserved);
                List<Club> promoted = pickAutomaticPromotions(lower, upper, autoPromotion, ca.getName(), lowerReserved);

                Club upperPlayoff = adjustedRelegationPlayoffSlots > 0
                        ? pickRelegationPlayoffClub(upper, relegated, forcedRelegations, upperReserved)
                        : null;
                Club lowerPlayoffWinner = promotionPlayoff > 0
                        ? runPromotionPlayoffs(lower, upper, autoPromotion, promotionPlayoff, ca.getName(),
                                lowerReserved)
                        : null;
                List<Club> playoffPromoted = new ArrayList<>();
                List<Club> playoffRelegated = new ArrayList<>();

                if (upperPlayoff != null && lowerPlayoffWinner != null) {
                    MatchEngine.Score s = MatchEngine.play(upperPlayoff, lowerPlayoffWinner, true, false);
                    boolean upperStays = s.homeGoals > s.awayGoals || (s.homeGoals == s.awayGoals
                            && MatchEngine.resolveKnockoutWinner(upperPlayoff, lowerPlayoffWinner)
                                    .equals(upperPlayoff));
                    if (upperStays) {
                        playoffPromoted.clear();
                    } else {
                        playoffPromoted.add(lowerPlayoffWinner);
                        playoffRelegated.add(upperPlayoff);
                    }
                } else if (lowerPlayoffWinner != null && upperPlayoff == null) {
                    playoffPromoted.add(lowerPlayoffWinner);
                }

                int dropoutsTotal = dropInfo != null ? dropInfo.dropouts.size() : 0;
                int desiredPromotions = relegated.size() + playoffRelegated.size() + dropoutsTotal;
                int actualPromotions = promoted.size() + playoffPromoted.size();
                if (actualPromotions > desiredPromotions) {
                    trimPromotions(promoted, playoffPromoted, actualPromotions - desiredPromotions);
                } else if (actualPromotions < desiredPromotions) {
                    int needed = desiredPromotions - actualPromotions;
                    List<Club> extra = pickAdditionalPromotions(lower, upper, ca.getName(), lowerReserved, needed);
                    promoted.addAll(extra);
                }

                reserveMoves(reserved, upper, relegated, upperPlayoff, playoffRelegated);
                reserveMoves(reserved, lower, promoted, lowerPlayoffWinner, playoffPromoted);
                moves.add(new LeagueMove(upper, lower, relegated, promoted, playoffPromoted, playoffRelegated));
            }

            for (LeagueMove move : moves) {
                for (Club club : move.relegated) {
                    move.upper.removeClub(club);
                    move.lower.addClub(club);
                    club.setDomesticLeagueName(move.lower.getName());
                    // Apply exponential cohesion penalty if team morale is broken
                    club.applyRelegationCohesionPenalty();
                }
                for (Club club : move.promoted) {
                    move.lower.removeClub(club);
                    move.upper.addClub(club);
                    club.setDomesticLeagueName(move.upper.getName());
                }

                for (Club club : move.playoffRelegated) {
                    move.upper.removeClub(club);
                    move.lower.addClub(club);
                    club.setDomesticLeagueName(move.lower.getName());
                    // Apply exponential cohesion penalty if team morale is broken
                    club.applyRelegationCohesionPenalty();
                }
                for (Club club : move.playoffPromoted) {
                    move.lower.removeClub(club);
                    move.upper.addClub(club);
                    club.setDomesticLeagueName(move.upper.getName());
                }

                move.upper.generateFixtures();
                move.lower.generateFixtures();
            }

            recordFinancialDropouts(ca, financialDrops);

            normalizeLeagueSizes(leagues);
        }
    }

    private void recordFinancialDropouts(CountryAssociation ca, Map<League, FinancialDropInfo> financialDrops) {
        if (financialDrops == null || financialDrops.isEmpty() || ca == null)
            return;
        List<Club> pending = pendingFinancialDropoutsByCountry
                .computeIfAbsent(ca.getName(), k -> new ArrayList<>());
        for (FinancialDropInfo info : financialDrops.values()) {
            pending.addAll(info.dropouts);
        }
    }

    private void flushFinancialDropouts() {
        if (pendingFinancialDropoutsByCountry.isEmpty())
            return;
        for (Map.Entry<String, List<Club>> entry : pendingFinancialDropoutsByCountry.entrySet()) {
            if (entry.getValue().isEmpty())
                continue;
            unassignedClubsByCountry
                    .computeIfAbsent(entry.getKey(), k -> new ArrayList<>())
                    .addAll(entry.getValue());
        }
        pendingFinancialDropoutsByCountry.clear();
    }

    /**
     * Check if a league is the bottom tier in its country.
     * Bottom tier is the one with the highest level number.
     */
    private boolean isBottomTier(CountryAssociation ca, int leagueLevel) {
        if (ca == null)
            return false;

        // Find the max level (bottom tier)
        int maxLevel = ca.getAllLeagues().stream()
                .mapToInt(League::getLevel)
                .max()
                .orElse(-1);

        return leagueLevel == maxLevel;
    }

    /**
     * Check if a league tier is in the top 50% of tiers in its country.
     * Example: If country has 4 tiers (levels 1-4), only levels 1-2 are announced.
     */
    private boolean isInTopTierHalf(CountryAssociation ca, int leagueLevel) {
        if (ca == null)
            return false;

        // Get all unique levels in this country
        java.util.Set<Integer> levels = new java.util.TreeSet<>();
        for (League league : ca.getAllLeagues()) {
            levels.add(league.getLevel());
        }

        if (levels.isEmpty())
            return false;

        // Find the max level (highest tier number)
        int maxLevel = levels.stream().max(Integer::compareTo).orElse(1);

        // Top 50% means level <= maxLevel / 2
        return leagueLevel <= (maxLevel + 1) / 2; // +1 for rounding up
    }

    private Map<League, FinancialDropInfo> processFinancialDropouts(CountryAssociation ca) {
        // Financial underperformance now applies point deductions only.
        // Clubs are no longer removed from league structures due to finances.
        boolean pointPenaltyOnly = true;
        if (ca != null && pointPenaltyOnly) {
            for (League league : ca.getAllLeagues()) {
                if (league.getClubs().isEmpty()) {
                    continue;
                }
                for (Club club : league.getClubs()) {
                    if (club.getFinancialPower() > FINANCIAL_DROP_THRESHOLD) {
                        continue;
                    }
                    double shortfall = FINANCIAL_DROP_THRESHOLD - club.getFinancialPower();
                    int severityBand = (int) Math.floor(shortfall / 4.0);
                    int tierBias = league.getLevel() <= 2 ? 1 : 0;
                    int deduction = Math.max(1, Math.min(8, 2 + severityBand + tierBias));
                    club.applyAdministrativePointDeduction(deduction);
                }
            }
            return Collections.emptyMap();
        }

        Map<League, FinancialDropInfo> dropouts = new HashMap<>();
        if (ca == null)
            return dropouts;

        for (League league : ca.getAllLeagues()) {
            if (league.getClubs().isEmpty())
                continue;
            league.sortTable();

            League.MovementRules rules = League.calculateMovementRules(league.getLevel(), league.getClubs().size());
            int relegationCandidates = Math.min(league.getClubs().size(), rules.relegationSlots);
            Set<Club> relegationSet = new HashSet<>(league.getBottomN(relegationCandidates));
            Set<Club> forcedSet = new HashSet<>(findYouthParentConflicts(league));

            List<Club> removed = new ArrayList<>();
            Set<Club> spared = new HashSet<>();
            for (Club club : new ArrayList<>(league.getClubs())) {
                if (club.getFinancialPower() > FINANCIAL_DROP_THRESHOLD)
                    continue;
                removed.add(club);
                if (!relegationSet.contains(club) && !forcedSet.contains(club)) {
                    spared.add(club);
                }
            }

            if (removed.isEmpty()) {
                continue;
            }

            // CAPACITY CHECK: Only drop from bottom tier if we have unassigned clubs to
            // replace them
            if (isBottomTier(ca, league.getLevel())) {
                List<Club> unassignedPool = unassignedClubsByCountry.getOrDefault(ca.getName(), new ArrayList<>());
                if (unassignedPool.size() < removed.size()) {
                    // Not enough unassigned clubs to replace them
                    // Keep all clubs in the league (don't drop)
                    if (verbose) {
                        System.err.println("[CAPACITY] " + ca.getName() + " cannot drop " + removed.size()
                                + " clubs from " + league.getName() + " (only " + unassignedPool.size()
                                + " unassigned available). Keeping all clubs.");
                    }
                    continue; // Skip this league's dropouts
                }
            }

            for (Club club : removed) {
                // Skip logging if already unassigned (dropping from Unassigned ↁEUnassigned is
                // meaningless)
                if (!"Unassigned".equals(club.getDomesticLeagueName())) {
                    // Only announce if club is in top 50% of tiers (by level rank in country)
                    if (isInTopTierHalf(ca, league.getLevel())) {
                        // Log BEFORE changing league name (so we know what they dropped FROM)
                        SeasonalEventLog.getInstance().logFinancialDropout(club, club.getFinancialPower());
                    }
                }
                league.removeClub(club);
                club.setDomesticLeagueName("Unassigned");
                club.setDomesticLevel(0);
            }
            dropouts.put(league, new FinancialDropInfo(removed, spared));
        }

        return dropouts;
    }

    private void normalizeLeagueSizes(List<League> leagues) {
        if (leagues == null || leagues.isEmpty())
            return;
        leagues.sort(Comparator.comparingInt(League::getLevel).thenComparing(League::getName));

        // First pass: Supply clubs upward to fix shortages
        // Iterate from bottom to top, filling gaps in upper leagues
        for (int i = leagues.size() - 1; i >= 0; i--) {
            League currentLeague = leagues.get(i);
            int currentTarget = Math.max(MIN_LEAGUE_SIZE, currentLeague.getTargetSize());

            // If this league is below target, try to get clubs from below
            if (currentLeague.getClubs().size() < currentTarget) {
                for (int j = i + 1; j < leagues.size(); j++) {
                    League donorLeague = leagues.get(j);
                    int donorTarget = Math.max(MIN_LEAGUE_SIZE, donorLeague.getTargetSize());

                    // Take clubs from donor if it has excess
                    while (currentLeague.getClubs().size() < currentTarget
                            && donorLeague.getClubs().size() > donorTarget) {
                        Club candidate = pickRebalanceCandidate(donorLeague, currentLeague, false);
                        if (candidate == null)
                            break;
                        donorLeague.removeClub(candidate);
                        currentLeague.addClub(candidate);
                        candidate.setDomesticLeagueName(currentLeague.getName());
                    }

                    if (currentLeague.getClubs().size() >= currentTarget)
                        break;
                }
            }
        }

        // Second pass: Redistribute excess clubs downward (adjacent tiers only)
        for (int i = 0; i < leagues.size() - 1; i++) {
            League upper = leagues.get(i);
            League lower = leagues.get(i + 1);
            int lowerTarget = Math.max(MIN_LEAGUE_SIZE, lower.getTargetSize());
            int upperTarget = Math.max(MIN_LEAGUE_SIZE, upper.getTargetSize());

            // Move excess from upper to lower
            while (lower.getClubs().size() < lowerTarget && upper.getClubs().size() > upperTarget) {
                Club candidate = pickRebalanceCandidate(upper, lower, false);
                if (candidate == null)
                    break;
                upper.removeClub(candidate);
                lower.addClub(candidate);
                candidate.setDomesticLeagueName(lower.getName());
            }
        }

        // Generate fixtures for all leagues
        for (League league : leagues) {
            league.generateFixtures();
        }
    }

    private Club pickRebalanceCandidate(League source, League target, boolean preferTop) {
        List<Club> candidates = preferTop
                ? source.getTopN(source.getClubs().size())
                : source.getBottomN(source.getClubs().size());
        for (Club club : candidates) {
            if (!target.getClubs().contains(club)) {
                return club;
            }
        }
        return null;
    }

    private void trimPromotions(List<Club> promoted, List<Club> playoffPromoted, int excess) {
        while (excess > 0 && !promoted.isEmpty()) {
            promoted.remove(promoted.size() - 1);
            excess--;
        }
        while (excess > 0 && !playoffPromoted.isEmpty()) {
            playoffPromoted.remove(playoffPromoted.size() - 1);
            excess--;
        }
    }

    private List<Club> pickAdditionalPromotions(League lower, League upper, String country,
            Set<Club> excluded, int needed) {
        List<Club> extra = new ArrayList<>();
        if (needed <= 0)
            return extra;
        List<Club> ranked = lower.getTopN(lower.getClubs().size());
        Set<String> upperNames = new HashSet<>();
        for (Club club : upper.getClubs()) {
            upperNames.add(club.getName().toLowerCase());
        }
        for (Club club : ranked) {
            if (extra.size() >= needed)
                break;
            if (excluded.contains(club))
                continue;
            if (!isPromotionEligible(club, upper, country))
                continue;
            String parent = parentName(club);
            if (parent != null && upperNames.contains(parent.toLowerCase()))
                continue;
            extra.add(club);
        }
        return extra;
    }

    private List<Club> findYouthParentConflicts(League league) {
        List<Club> conflicts = new ArrayList<>();
        Map<String, Club> byName = new HashMap<>();
        for (Club club : league.getClubs()) {
            byName.put(club.getName().toLowerCase(), club);
        }

        for (Club club : league.getClubs()) {
            if (!isYouthTeam(club))
                continue;
            String parent = parentName(club);
            if (parent == null)
                continue;
            if (byName.containsKey(parent.toLowerCase())) {
                conflicts.add(club);
            }
        }
        return conflicts;
    }

    private List<Club> pickRelegations(League league, List<Club> forced, int movers, Set<Club> excluded) {
        List<Club> relegated = new ArrayList<>();
        for (Club club : forced) {
            if (relegated.size() >= movers)
                break;
            if (excluded.contains(club))
                continue;
            relegated.add(club);
        }
        if (relegated.size() >= movers)
            return relegated;

        for (Club club : league.getBottomN(movers + forced.size())) {
            if (relegated.size() >= movers)
                break;
            if (relegated.contains(club))
                continue;
            if (excluded.contains(club))
                continue;
            relegated.add(club);
        }
        return relegated;
    }

    private List<Club> pickAutomaticPromotions(League lower, League upper, int autoSlots, String country,
            Set<Club> excluded) {
        List<Club> promoted = new ArrayList<>();
        if (autoSlots <= 0)
            return promoted;
        Set<String> upperNames = new HashSet<>();
        for (Club club : upper.getClubs()) {
            upperNames.add(club.getName().toLowerCase());
        }

        List<Club> ranked = lower.getTopN(lower.getClubs().size());
        for (Club club : ranked) {
            if (promoted.size() >= autoSlots)
                break;
            if (excluded.contains(club))
                continue;
            if (!isPromotionEligible(club, upper, country))
                continue;
            String parent = parentName(club);
            if (parent != null && upperNames.contains(parent.toLowerCase())) {
                continue;
            }
            promoted.add(club);
        }
        return promoted;
    }

    private Club pickRelegationPlayoffClub(League upper, List<Club> relegated, List<Club> forced, Set<Club> reserved) {
        Set<Club> excluded = new HashSet<>(relegated);
        excluded.addAll(forced);
        excluded.addAll(reserved);
        List<Club> ranked = upper.getBottomN(upper.getClubs().size());
        for (Club club : ranked) {
            if (!excluded.contains(club)) {
                return club;
            }
        }
        return null;
    }

    private Club runPromotionPlayoffs(League lower, League upper, int autoSlots, int playoffSlots, String country,
            Set<Club> excluded) {
        if (playoffSlots <= 1)
            return null;
        List<Club> ranked = lower.getTopN(lower.getClubs().size());
        List<Club> candidates = new ArrayList<>();
        for (int i = autoSlots; i < ranked.size() && candidates.size() < playoffSlots; i++) {
            Club club = ranked.get(i);
            if (excluded.contains(club))
                continue;
            if (!isPromotionEligible(club, upper, country))
                continue;
            String parent = parentName(club);
            if (parent != null) {
                boolean parentInUpper = false;
                for (Club upperClub : upper.getClubs()) {
                    if (upperClub.getName().equalsIgnoreCase(parent)) {
                        parentInUpper = true;
                        break;
                    }
                }
                if (parentInUpper) {
                    continue;
                }
            }
            candidates.add(club);
        }
        if (candidates.size() < 2)
            return null;
        while (candidates.size() > 1) {
            List<Club> nextRound = new ArrayList<>();
            int size = candidates.size();
            for (int i = 0; i < size / 2; i++) {
                Club high = candidates.get(i);
                Club low = candidates.get(size - 1 - i);
                MatchEngine.Score s = MatchEngine.play(high, low, true, false);
                Club winner = s.homeGoals > s.awayGoals ? high
                        : (s.awayGoals > s.homeGoals ? low
                                : MatchEngine.resolveKnockoutWinner(high, low));
                nextRound.add(winner);
            }
            candidates = nextRound;
        }
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    private void reserveMoves(Map<League, Set<Club>> reserved, League league, List<Club> list, Club single,
            List<Club> extra) {
        if (league == null)
            return;
        Set<Club> set = reserved.computeIfAbsent(league, k -> new HashSet<>());
        if (list != null)
            set.addAll(list);
        if (single != null)
            set.add(single);
        if (extra != null)
            set.addAll(extra);
    }

    private boolean isPromotionEligible(Club club, League upper, String country) {
        if (!isYouthTeam(club))
            return true;
        int targetLevel = upper.getLevel();
        if ("Germany".equalsIgnoreCase(country)) {
            return targetLevel > 2;
        }
        return targetLevel > 1;
    }

    private boolean isYouthTeam(Club club) {
        String name = club.getName().toLowerCase();
        return name.matches(
                ".*(\\bii\\b|\\bu23\\b|\\bu21\\b|\\bu20\\b|\\bu19\\b|\\bb\\b|reserves|reserve|youth|ii/|ii-).*")
                || name.contains(" b ") || name.endsWith(" b") || name.contains(" ii ") || name.endsWith(" ii");
    }

    private String parentName(Club club) {
        String name = club.getName();
        String lower = name.toLowerCase();
        String[] suffixes = new String[] { " II", " B", " U23", " U21", " U20", " U19", " Reserves", " Reserve",
                " Youth" };
        for (String suffix : suffixes) {
            if (lower.endsWith(suffix.toLowerCase())) {
                return name.substring(0, name.length() - suffix.length()).trim();
            }
        }
        if (lower.contains(" ii")) {
            return name.substring(0, lower.indexOf(" ii")).trim();
        }
        if (lower.contains(" b")) {
            return name.substring(0, lower.indexOf(" b")).trim();
        }
        return null;
    }

    private static final class LeagueMove {
        private final League upper;
        private final League lower;
        private final List<Club> relegated;
        private final List<Club> promoted;
        private final List<Club> playoffPromoted;
        private final List<Club> playoffRelegated;

        private LeagueMove(League upper, League lower, List<Club> relegated, List<Club> promoted,
                List<Club> playoffPromoted, List<Club> playoffRelegated) {
            this.upper = upper;
            this.lower = lower;
            this.relegated = relegated;
            this.promoted = promoted;
            this.playoffPromoted = playoffPromoted;
            this.playoffRelegated = playoffRelegated;
        }
    }

    private static final class FinancialDropInfo {
        private final List<Club> dropouts;
        private final Set<Club> sparedCandidates;

        private FinancialDropInfo(List<Club> dropouts, Set<Club> sparedCandidates) {
            this.dropouts = dropouts;
            this.sparedCandidates = sparedCandidates;
        }

        private int countSpared(List<Club> forcedRelegations) {
            if (sparedCandidates.isEmpty())
                return 0;
            if (forcedRelegations == null || forcedRelegations.isEmpty())
                return sparedCandidates.size();
            int count = 0;
            for (Club club : sparedCandidates) {
                if (!forcedRelegations.contains(club)) {
                    count++;
                }
            }
            return count;
        }
    }

    private void initializeTransferWindow(String windowName) {
        transfersThisWindow = 0;
        transfersThisSlot = 0;
        exportableStrengthPool.clear();

        boolean winterWindow = "WINTER".equals(windowName);
        for (Club club : clubIndex.values()) {
            exportableStrengthPool.put(club, computeExportableStrengthPool(club, winterWindow));
        }
    }

    private double computeExportableStrengthPool(Club seller, boolean winterWindow) {
        double baseExport = 0.05 + clamp((6.0 - seller.getDomesticLevel()) * 0.04, 0.0, 0.25);
        double youthBonus = clamp((seller.getYouthRating() + seller.getDevelopmentRating()) / 220.0, 0.10, 0.85);
        double distressBonus = clamp((32.0 - seller.getFinancialPower()) / 40.0, 0.0, 0.70);
        double randomFactor = 0.65 + rng.nextDouble() * 0.70;

        // Better nations and higher tiers maintain deeper sellable quality pools.
        double sellerCoeffSignal = clamp(getClubCoefficient(seller) / 120.0, 0.30, 1.55);
        double sellerTierSignal = clamp((7.0 - seller.getDomesticLevel()) / 6.0, 0.20, 1.05);
        double qualitySupplyMultiplier = 0.75 + sellerTierSignal * 0.50 + sellerCoeffSignal * 0.35;

        // Lower tiers can occasionally produce exportable talent spikes.
        double talentSpike = 0.0;
        if (seller.getDomesticLevel() >= 3 && rng.nextDouble() < 0.035) {
            talentSpike = 0.25 + rng.nextDouble() * 0.45;
        }

        double pool = (baseExport + youthBonus + distressBonus + talentSpike) * randomFactor * qualitySupplyMultiplier;
        if (winterWindow) {
            pool *= 0.70;
        }
        return clamp(pool, 0.05, 3.40);
    }

    private void simulateTransferWindowTick(double slot, boolean winterWindow) {
        if (clubIndex.isEmpty()) {
            return;
        }

        transfersThisSlot = 0;
        List<Club> marketClubs = new ArrayList<>(clubIndex.values());
        Collections.shuffle(marketClubs, rng);

        for (Club buying : marketClubs) {
            int maxTransfers = getMaxTransfersPerSlot(buying);
            int guaranteedAttempts = Math.min(maxTransfers, getGuaranteedAttemptsPerSlot(buying, winterWindow));
            for (int attempt = 0; attempt < maxTransfers; attempt++) {
                boolean forcedAttempt = attempt < guaranteedAttempts;
                double needFactor = computeSquadNeed(buying);
                double attemptChance = forcedAttempt ? 1.0 : computeTransferAttemptChance(buying, winterWindow, attempt, needFactor);
                if (!forcedAttempt && rng.nextDouble() > attemptChance) {
                    continue;
                }
                int retries = forcedAttempt ? 1 : 0;
                if (attemptTransferWithRetries(buying, marketClubs, winterWindow, retries, needFactor)) {
                    transfersThisSlot++;
                    transfersThisWindow++;
                }
            }
        }

        if (verbose && transfersThisSlot > 0) {
            System.out.printf("[TRANSFER] %s window t=%.1f: %d transfer(s) this slot (%d total in window)%n",
                    winterWindow ? "WINTER" : "SUMMER", slot, transfersThisSlot, transfersThisWindow);
        }
    }

    private int getMaxTransfersPerSlot(Club club) {
        int maxTransfers;
        switch (club.getArchetype()) {
            case PURE_STRENGTH:
            case SQUAD_DEPTH:
                maxTransfers = 5;
                break;
            case GOOD_MANAGER:
            case BALANCED:
                maxTransfers = 4;
                break;
            default:
                maxTransfers = 3;
                break;
        }

        if (club.getDomesticLevel() >= 4) {
            maxTransfers = Math.max(2, maxTransfers - 1);
        } else if (club.getDomesticLevel() <= 1) {
            maxTransfers = Math.min(5, maxTransfers + 1);
        }
        return maxTransfers;
    }

    private int getGuaranteedAttemptsPerSlot(Club club, boolean winterWindow) {
        double need = computeSquadNeed(club);
        if (club.getDomesticLevel() <= 1 && need >= 0.22) {
            return 1;
        }
        if (!winterWindow && club.getDomesticLevel() <= 2 && need >= 0.55
                && club.getCurrentWeights()[Club.W_STRENGTH_NOW] >= 0.20) {
            return 1;
        }
        return 0;
    }

    private boolean attemptTransferWithRetries(Club buying, List<Club> marketClubs, boolean winterWindow, int retries,
            double needFactor) {
        for (int i = 0; i <= retries; i++) {
            if (executeTransferAttempt(buying, marketClubs, winterWindow, needFactor)) {
                return true;
            }
        }
        return false;
    }

    private double computeTransferAttemptChance(Club buying, boolean winterWindow, int attemptIndex, double needFactor) {
        double[] weights = buying.getCurrentWeights();
        double focusAggression = 0.52
                + weights[Club.W_STRENGTH_NOW] * 0.95
                + weights[Club.W_SURVIVAL] * 0.55
                + weights[Club.W_TALENT_TRADING] * 1.10;
        double tierFactor = clamp((7.0 - buying.getDomesticLevel()) / 6.0, 0.20, 1.18);
        double financialFactor = clamp(buying.getFinancialPower() / 100.0, 0.24, 1.25);
        double decay = clamp(1.0 - attemptIndex * 0.20, 0.35, 1.0);

        // Winter transfer market volume is about 35% of summer volume.
        // Because winter has fewer slots (5 vs 7), per-slot scaling uses ~0.49.
        double seasonalVolume = winterWindow ? 0.49 : 1.0;

        double chance = (0.04 + 0.14 * focusAggression * tierFactor + 0.10 * needFactor + 0.07 * financialFactor)
                * seasonalVolume * decay;
        if (buying.getDomesticLevel() <= 2) {
            chance += winterWindow ? 0.01 : 0.03;
        }
        chance += buying.getMismanagementRisk() * 0.08;
        return clamp(chance, 0.01, 0.80);
    }

    private double computeSquadNeed(Club club) {
        double strengthGap = clamp((70.0 - club.getSquadStrength()) / 60.0, 0.0, 1.0);
        double cohesionGap = clamp((55.0 - club.getCohesion()) / 35.0, 0.0, 1.0);
        double moraleGap = clamp((52.0 - club.getMorale()) / 32.0, 0.0, 1.0);
        double depthGap = clamp((24.0 - club.getSquadDepthPlayers()) / 14.0, 0.0, 1.0);
        return clamp((strengthGap * 0.42) + (cohesionGap * 0.20) + (moraleGap * 0.18) + (depthGap * 0.20), 0.0, 1.2);
    }

    private boolean executeTransferAttempt(Club buying, List<Club> marketClubs, boolean winterWindow, double needFactor) {
        SellerCandidate candidate = pickSellerCandidate(buying, marketClubs);
        if (candidate == null) {
            return false;
        }

        TransferType type = pickTransferType(
                winterWindow ? 0.58 : 0.70,
                winterWindow ? 0.22 : 0.18,
                winterWindow ? 0.20 : 0.12);

        Club selling = candidate.club;
        double sellerPool = exportableStrengthPool.getOrDefault(selling, 0.0);
        if (sellerPool < 0.01) {
            return false;
        }

        double talentQuality = clamp((selling.getYouthRating() + selling.getDevelopmentRating()) / 200.0, 0.30, 1.35);
        double buyerTierSignal = clamp((7.0 - buying.getDomesticLevel()) / 6.0, 0.20, 1.1);
        double buyerCoeffSignal = clamp(getClubCoefficient(buying) / 120.0, 0.28, 1.575);
        double marketAmbition = 0.70 + buyerTierSignal * 0.62 + buyerCoeffSignal * 0.42;

        double baseChunk = (0.07 + rng.nextDouble() * 0.68) * (0.75 + needFactor) * talentQuality * marketAmbition;
        if (type == TransferType.FREE) {
            baseChunk *= 0.78;
        } else if (type == TransferType.LOAN) {
            baseChunk *= 0.62;
        }

        // Elite markets are more likely to generate blockbuster-sized strength swings.
        if (buying.getDomesticLevel() <= 2 && rng.nextDouble() < 0.16) {
            baseChunk *= 1.12 + rng.nextDouble() * 0.42;
        }

        double exportedStrength = clamp(Math.min(sellerPool, baseChunk), 0.02, 5);
        if (exportedStrength <= 0.0) {
            return false;
        }

        double crossBorderAcceptance = computeCrossBorderAcceptance(buying, candidate.crossBorder, selling.getConfederation());
        double topMarketFit = 0.80 + buyerTierSignal * 0.30 + buyerCoeffSignal * 0.22;
        double adaptation = clamp((0.82 + rng.nextDouble() * 0.45) * crossBorderAcceptance
                * topMarketFit
                * (candidate.outsideCoefficientBand ? 0.90 : 1.0), 0.34, 1.55);

        double minimumGain = clamp(0.02 + buyerTierSignal * 0.012 + buyerCoeffSignal * 0.008, 0.02, 0.055);
        double strengthGain = Math.max(minimumGain, exportedStrength * adaptation);
        double sellerLoss = Math.max(0.01, exportedStrength * (type == TransferType.LOAN ? 0.40 : 0.82));

        double spend = calculateTransferCost(buying, selling, strengthGain, candidate, type, winterWindow, needFactor);
        double maxSpend = calculateMaxSpendForAttempt(buying, winterWindow, type);
        if (spend > maxSpend) {
            return false;
        }

        if (spend > 0.0) {
            buying.adjustTransferBudget(-spend);
            buying.spendSeasonPoints(spend);

            double sellerIncomeShare = type == TransferType.NORMAL ? 0.78 : (type == TransferType.LOAN ? 0.55 : 0.22);
            selling.earnSeasonPoints(spend * sellerIncomeShare);
        }

        double buyingCohesionDelta = type == TransferType.LOAN ? -0.55 : -0.38;
        if (candidate.crossBorder && buying.getDomesticLevel() >= 3) {
            buyingCohesionDelta -= 0.18;
        }

        buying.applyTransferImpact(strengthGain, buyingCohesionDelta);
        selling.applyTransferImpact(-sellerLoss, type == TransferType.LOAN ? -0.08 : -0.20);

        double poolReduction = type == TransferType.LOAN ? exportedStrength * 0.55 : exportedStrength;
        exportableStrengthPool.put(selling, Math.max(0.0, sellerPool - poolReduction));

        recordTransfer(
                selling.getName(), selling.getCountry(), selling.getDomesticLeagueName(),
                buying.getName(), buying.getCountry(), buying.getDomesticLeagueName(),
                spend, strengthGain);

        return true;
    }

    private double calculateTransferCost(Club buying, Club selling, double strengthGain,
            SellerCandidate candidate, TransferType type, boolean winterWindow, double needFactor) {
        double buyerFinancial = clamp(buying.getFinancialPower() / 100.0, 0.25, 1.40);
        double sellerStrength = clamp(selling.getSquadStrength() / 100.0, 0.25, 1.40);
        double qualityPrice = 2.0 + buyerFinancial * 1.1 + sellerStrength * 1.0;
        double sellerTierDiscount = clamp(1.0 - Math.max(0, selling.getDomesticLevel() - 1) * 0.07, 0.62, 1.0);
        double breakoutPremium = 1.0 + Math.max(0.0, strengthGain - 0.45) * 0.70;
        double coeffPremium = candidate.outsideCoefficientBand ? 1.18 + (candidate.coefficientGap - 9.0) * 0.02 : 1.0;
        double borderPremium = candidate.crossBorder
                ? 1.0 + Math.max(0, buying.getDomesticLevel() - 1) * 0.08
                : 1.0;
        double winterPriceAdjustment = winterWindow ? 0.92 : 1.0;

        // High-tier, high-coefficient buyers push transfer pricing up exponentially.
        double tierSignal = clamp((6.0 - buying.getDomesticLevel()) / 5.0, 0.0, 1.3);
        double coefficientSignal = clamp(getClubCoefficient(buying) / 45.0, 0.2, 3.5);
        double tierExponent = Math.exp(tierSignal * 0.55);
        double coefficientExponent = Math.exp(Math.max(0.0, coefficientSignal - 1.0) * 0.35);
        double arroganceOverpay = 1.0 + buying.getArrogance() / 220.0 + buying.getMismanagementRisk() * 0.55;

        double marketPressure = 0.85 + buyerFinancial * 0.9 + (buying.getDomesticLevel() <= 2 ? 0.35 : 0.10);
        double urgencyPremium = 1.0 + needFactor * 0.35;

        double raw = strengthGain * qualityPrice * coeffPremium * borderPremium
                * winterPriceAdjustment * marketPressure * urgencyPremium
                * sellerTierDiscount * breakoutPremium
                * tierExponent * coefficientExponent * arroganceOverpay;
        raw *= (0.82 + rng.nextDouble() * 0.46);

        if (type == TransferType.FREE) {
            raw *= 0.16;
        } else if (type == TransferType.LOAN) {
            raw *= 0.42;
        } else {
            raw *= 1.15;
        }

        return Math.max(0.0, raw * SimulatorConfigReader.getTransferFeeMultiplier());
    }

    private double calculateMaxSpendForAttempt(Club buying, boolean winterWindow, TransferType type) {
        double budget = buying.getTransferBudgetSeason();
        double cash = buying.getCash();
        double revenue = buying.getRevenueSeason();

        // Blend budget, cash, and seasonal means so top-tier clubs can act as market makers.
        double budgetSlice = budget * (winterWindow ? 0.55 : 0.85);
        double cashSlice = cash * (buying.getDomesticLevel() <= 2 ? 0.50 : 0.28);
        double revenueSlice = revenue * (winterWindow ? 0.10 : 0.16);
        double marketMakerBoost = clamp((3.5 - buying.getDomesticLevel()) * 0.18, 0.0, 0.45);
        double available = (budgetSlice + cashSlice + revenueSlice) * (1.0 + marketMakerBoost);

        if (type == TransferType.FREE) {
            available *= 0.45;
        } else if (type == TransferType.LOAN) {
            available *= 0.65;
        }
        available *= 1.0 + buying.getMismanagementRisk() * 0.40;
        return Math.max(type == TransferType.NORMAL ? 0.35 : 0.20, available);
    }

    private SellerCandidate pickSellerCandidate(Club buying, List<Club> marketClubs) {
        if (marketClubs.isEmpty()) {
            return null;
        }

        double buyerCoefficient = getClubCoefficient(buying);
        SellerCandidate best = null;

        int samples = Math.min(Math.max(36, marketClubs.size() / 220), Math.min(72, marketClubs.size()));
        for (int i = 0; i < samples; i++) {
            Club seller = marketClubs.get(rng.nextInt(marketClubs.size()));
            if (seller == buying) {
                continue;
            }

            double sellerPool = exportableStrengthPool.getOrDefault(seller, 0.0);
            if (sellerPool < 0.02) {
                continue;
            }

            double coefficientGap = Math.abs(buyerCoefficient - getClubCoefficient(seller));
            boolean withinBand = coefficientGap <= 9.0;
            boolean topTierPull = buying.getDomesticLevel() <= 1 && coefficientGap <= 18.0;
            if (!withinBand && !topTierPull) {
                continue;
            }

            boolean crossBorder = !Objects.equals(buying.getCountry(), seller.getCountry());
            double borderAcceptance = computeCrossBorderAcceptance(buying, crossBorder, seller.getConfederation());
            if (rng.nextDouble() > borderAcceptance) {
                continue;
            }

            double sellerWillingness = computeSellerWillingness(seller, buying, sellerPool, crossBorder);
            if (!withinBand) {
                sellerWillingness *= 0.45;
            }

            double domesticBias = crossBorder
                    ? clamp(1.0 - Math.max(0, buying.getDomesticLevel() - 1) * 0.12, 0.45, 1.0)
                    : 1.12;
            double score = sellerWillingness
                    * (0.8 + sellerPool)
                    * (0.85 + clamp((seller.getYouthRating() + seller.getDevelopmentRating()) / 200.0, 0.2, 1.2))
                    * domesticBias;

            if (best == null || score > best.score) {
                best = new SellerCandidate(seller, sellerPool, coefficientGap, crossBorder, !withinBand, score);
            }
        }

        return best;
    }

    private double computeSellerWillingness(Club seller, Club buyer, double sellerPool, boolean crossBorder) {
        double distress = clamp((30.0 - seller.getFinancialPower()) / 35.0, 0.0, 0.9);
        double prestigePull = clamp((buyer.getSquadStrength() - seller.getSquadStrength()) / 55.0, -0.2, 0.8);
        double talentFocus = clamp(seller.getCurrentWeights()[Club.W_TALENT_TRADING] * 1.5, 0.0, 1.2);

        double willingness = 0.18 + sellerPool * 0.35 + distress * 0.45 + Math.max(0.0, prestigePull) * 0.25
                + talentFocus * 0.15;
        if (crossBorder) {
            willingness *= 0.88;
        }
        return clamp(willingness, 0.04, 0.95);
    }

    private double computeCrossBorderAcceptance(Club buyer, boolean crossBorder, Confederation sellerConfederation) {
        if (!crossBorder) {
            return 1.0;
        }

        // Lower-tier buyers face progressively higher friction for international deals.
        double tierPenalty = clamp(1.0 - Math.max(0, buyer.getDomesticLevel() - 1) * 0.14, 0.18, 0.95);
        double confederationPenalty = buyer.getConfederation() == sellerConfederation ? 1.0 : 0.76;
        return clamp(tierPenalty * confederationPenalty, 0.12, 1.0);
    }

    private double getClubCoefficient(Club club) {
        CountryAssociation association = club.getCountryAssociation();
        if (association != null) {
            return association.getRollingCoefficient();
        }
        return 40.0;
    }

    private TransferType pickTransferType(double normalRate, double freeRate, double loanRate) {
        double total = Math.max(0.01, normalRate + freeRate + loanRate);
        double roll = rng.nextDouble() * total;
        if (roll < loanRate) {
            return TransferType.LOAN;
        }
        if (roll < loanRate + freeRate) {
            return TransferType.FREE;
        }
        return TransferType.NORMAL;
    }

    private double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private enum TransferType {
        NORMAL,
        FREE,
        LOAN
    }

    private static final class SellerCandidate {
        private final Club club;
        @SuppressWarnings("unused")
        private final double sellerPool;
        private final double coefficientGap;
        private final boolean crossBorder;
        private final boolean outsideCoefficientBand;
        private final double score;

        private SellerCandidate(Club club, double sellerPool, double coefficientGap,
                boolean crossBorder, boolean outsideCoefficientBand, double score) {
            this.club = club;
            this.sellerPool = sellerPool;
            this.coefficientGap = coefficientGap;
            this.crossBorder = crossBorder;
            this.outsideCoefficientBand = outsideCoefficientBand;
            this.score = score;
        }
    }

    private final List<TransferRecord> Transfers = new ArrayList<>();

    private static class TransferRecord {
        final String fromClub;
        final String fromCountry;
        final String fromLeague;
        final String toClub;
        final String toCountry;
        final String toLeague;
        final double economicPointsSpent;
        final double strengthAdded;

        TransferRecord(String fromClub, String fromCountry, String fromLeague,
                String toClub, String toCountry, String toLeague,
                double economicPointsSpent, double strengthAdded) {
            this.fromClub = fromClub;
            this.fromCountry = fromCountry;
            this.fromLeague = fromLeague;
            this.toClub = toClub;
            this.toCountry = toCountry;
            this.toLeague = toLeague;
            this.economicPointsSpent = economicPointsSpent;
            this.strengthAdded = strengthAdded;
        }
    }

    private void recordTransfer(String fromClub, String fromCountry, String fromLeague,
            String toClub, String toCountry, String toLeague,
            double economicPointsSpent, double strengthAdded) {
        Transfers.add(new TransferRecord(fromClub, fromCountry, fromLeague,
                toClub, toCountry, toLeague,
                economicPointsSpent, strengthAdded));
    }

    private void debugDisplayTransferMarketLegacy() {
        System.out.println("\n" + "=".repeat(70));
        System.out.println("[DEBUG] TRANSFER MARKET ANALYSIS - SEASON " + seasonYear);
        System.out.println("=".repeat(70));

        if (Transfers.isEmpty()) {
            System.out.println("\nNo transfers recorded in current season.");
            return;
        }

        System.out.printf("\nTotal Transfers: %d%n", Transfers.size());
        System.out.println("-".repeat(70));

        System.out.println("\nTOP 10 BIGGEST TRANSFERS:");
        Transfers.stream()
            .filter(t -> t.toCountry != null && !t.toCountry.isEmpty())
            .sorted((a, b) -> Double.compare(b.economicPointsSpent, a.economicPointsSpent))
            .limit(10)
            .forEach(t -> System.out.printf("  %s ↁE%s (%s) | Cost: %.2f pts, Strength: %.2f%n",
                t.fromClub, t.toClub, t.toCountry, t.economicPointsSpent, t.strengthAdded));

        System.out.println("\nTOP 10 BEST TRANSFERS:");
        Transfers.stream()
            .filter(t -> t.economicPointsSpent > 0 && t.toCountry != null && !t.toCountry.isEmpty())
            .sorted((a, b) -> Double.compare(
                b.strengthAdded / b.economicPointsSpent,
                a.strengthAdded / a.economicPointsSpent))
            .limit(10)
            .forEach(t -> System.out.printf("  %s ↁE%s | Ratio: %.3f (%.2f pts gained / %.2f spent)%n",
                t.fromClub, t.toClub, t.strengthAdded / t.economicPointsSpent,
                t.strengthAdded, t.economicPointsSpent));

        System.out.println("\nTOP 10 WORST TRANSFERS:");
        Transfers.stream()
            .filter(t -> t.economicPointsSpent > 0 && t.toCountry != null && !t.toCountry.isEmpty())
            .sorted((a, b) -> Double.compare(
                a.strengthAdded / a.economicPointsSpent,
                b.strengthAdded / b.economicPointsSpent))
            .limit(10)
            .forEach(t -> System.out.printf("  %s ↁE%s | Ratio: %.3f (%.2f pts gained / %.2f spent)%n",
                t.fromClub, t.toClub, t.strengthAdded / t.economicPointsSpent,
                t.strengthAdded, t.economicPointsSpent));

        System.out.println("\n" + "=".repeat(70));
    }

    private void clearTransfers() {
        Transfers.clear();
    }

    public void debugDisplayTransferMarket() {
        debugDisplayTransferMarket(null, null, null);
    }

    public void debugDisplayTransferMarket(Confederation confFilter, String countryFilter, String leagueFilter) {
        List<TransferRecord> filtered = new ArrayList<>();
        for (TransferRecord record : Transfers) {
            if (matchesTransferMarketFilter(record, confFilter, countryFilter, leagueFilter)) {
                filtered.add(record);
            }
        }

        System.out.println("\n" + "=".repeat(70));
        System.out.println("TRANSFER MARKET ANALYSIS - SEASON " + seasonYear);
        System.out.println("=".repeat(70));

        if (filtered.isEmpty()) {
            System.out.println("\nNo transfers recorded in current season.");
            return;
        }

        System.out.printf("\nTotal Transfers: %d%n", filtered.size());
        System.out.println("-".repeat(70));

        System.out.println("\nTOP 10 COSTLIEST TRANSFERS (by economic points spent):");
        filtered.stream()
                .filter(t -> t.fromCountry != null && !t.fromCountry.isEmpty()
                        && t.toCountry != null && !t.toCountry.isEmpty())
                .sorted((a, b) -> Double.compare(b.economicPointsSpent, a.economicPointsSpent))
                .limit(10)
                .forEach(t -> System.out.printf("  %s (%s) -> %s (%s) | Cost: %.2f pts, Strength: %.2f%n",
                        t.fromClub, t.fromCountry, t.toClub, t.toCountry, t.economicPointsSpent, t.strengthAdded));

        System.out.println("\nTOP 10 BEST TRANSFERS (strength gained per economic point):");
        filtered.stream()
                .filter(t -> t.economicPointsSpent > 0
                        && t.fromCountry != null && !t.fromCountry.isEmpty()
                        && t.toCountry != null && !t.toCountry.isEmpty())
                .sorted((a, b) -> Double.compare(
                        b.strengthAdded / b.economicPointsSpent,
                        a.strengthAdded / a.economicPointsSpent))
                .limit(10)
                .forEach(t -> System.out.printf("  %s (%s) -> %s (%s) | Ratio: %.3f (%.2f pts gained / %.2f spent)%n",
                        t.fromClub, t.fromCountry, t.toClub, t.toCountry,
                        t.strengthAdded / t.economicPointsSpent,
                        t.strengthAdded, t.economicPointsSpent));

        System.out.println("\nTOP 10 WORST TRANSFERS (strength gained per economic point):");
        filtered.stream()
                .filter(t -> t.economicPointsSpent > 0
                        && t.fromCountry != null && !t.fromCountry.isEmpty()
                        && t.toCountry != null && !t.toCountry.isEmpty())
                .sorted((a, b) -> Double.compare(
                        a.strengthAdded / a.economicPointsSpent,
                        b.strengthAdded / b.economicPointsSpent))
                .limit(10)
                .forEach(t -> System.out.printf("  %s (%s) -> %s (%s) | Ratio: %.3f (%.2f pts gained / %.2f spent)%n",
                        t.fromClub, t.fromCountry, t.toClub, t.toCountry,
                        t.strengthAdded / t.economicPointsSpent,
                        t.strengthAdded, t.economicPointsSpent));

        System.out.println("\n" + "=".repeat(70));
    }

    private boolean matchesTransferMarketFilter(TransferRecord record, Confederation confFilter,
            String countryFilter, String leagueFilter) {
        if (confFilter != null) {
            Confederation fromConf = getCountryConfederation(record.fromCountry);
            Confederation toConf = getCountryConfederation(record.toCountry);
            if (fromConf != confFilter && toConf != confFilter) {
                return false;
            }
        }

        if (countryFilter != null && !countryFilter.isBlank()) {
            boolean countryMatch = countryFilter.equalsIgnoreCase(record.fromCountry)
                    || countryFilter.equalsIgnoreCase(record.toCountry);
            if (!countryMatch) {
                return false;
            }
        }

        if (leagueFilter != null && !leagueFilter.isBlank()) {
            boolean leagueMatch = leagueFilter.equalsIgnoreCase(record.fromLeague)
                    || leagueFilter.equalsIgnoreCase(record.toLeague);
            if (!leagueMatch) {
                return false;
            }
        }

        return true;
    }

    private Confederation getCountryConfederation(String country) {
        CountryAssociation association = countries.get(country);
        return association == null ? Confederation.UNKNOWN : association.getConfederation();
    }

    // ============================================================
    // [DEBUG] NATION COEFFICIENT CALCULATION
    // ============================================================

    /**
     * [DEBUG] Display nation coefficient breakdown showing how coefficients
     * are calculated from continental tournament performance.
     */
    private void debugDisplayNationCoefficientsLegacy() {
        System.out.println("\n" + "=".repeat(70));
        System.out.println("[DEBUG] NATION COEFFICIENT BREAKDOWN - SEASON " + seasonYear);
        System.out.println("=".repeat(70));

        System.out.println("\nNOTE: Coefficients are calculated ONLY from continental tournament performance.");
        System.out.println("Clubs earn points by competing and performing in continental cups.");
        System.out.println("Points are tallied by nation from all participating clubs' results.");
        System.out.println("A rolling 5-season total determines slot allocation for the following year.\n");

        System.out.println("-".repeat(70));
        System.out.println("PER-CONFEDERATION BREAKDOWNS:");
        System.out.println("-".repeat(70));

        for (Map.Entry<Confederation, List<ContinentalCompetitionDef>> entry : continentalDefs.entrySet()) {
            Confederation conf = entry.getKey();
            System.out.printf("\n%s:%n", conf);

            // Track per-country club-level data
            Map<String, List<Double>> countryClubPoints = new HashMap<>();
            Map<String, Double> countryTotals = new HashMap<>();

            for (ContinentalCompetitionDef def : entry.getValue()) {
                ContinentalTournament t = continentalTournaments.get(def.getCompetitionKey());
                if (t == null)
                    continue;

                Map<String, ContinentalTournament.CountryCoeffData> coeffData = t.getCountryCoefficients();
                for (Map.Entry<String, ContinentalTournament.CountryCoeffData> ce : coeffData.entrySet()) {
                    String country = ce.getKey();
                    ContinentalTournament.CountryCoeffData data = ce.getValue();
                    double performancePoints = data.qualifierPoints + data.mainStagePoints + data.knockoutPoints;
                    
                    // Track individual club points
                    countryClubPoints.computeIfAbsent(country, k -> new ArrayList<>()).add(performancePoints);
                    countryTotals.merge(country, performancePoints, Double::sum);
                }
            }

            // Display per-country breakdown with individual club points
            countryTotals.entrySet().stream()
                    .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                    .forEach(e -> {
                        String country = e.getKey();
                        List<Double> clubPoints = countryClubPoints.getOrDefault(country, new ArrayList<>());
                        double total = e.getValue();
                        int teamCount = clubPoints.size();
                        double avg = teamCount > 0 ? total / teamCount : 0;
                        
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < clubPoints.size(); i++) {
                            if (i > 0) sb.append(" + ");
                            sb.append(String.format("%.3f", clubPoints.get(i)));
                        }
                        System.out.printf("  %s: %s = %.3f; Average: %.3f (%d clubs)%n",
                                country, sb.toString(), total, avg, teamCount);
                    });
        }

        System.out.println("\n" + "-".repeat(70));
        System.out.println("CURRENT ROLLING 5-SEASON TOTALS (used for slot allocation):");
        System.out.println("-".repeat(70));

        countries.values().stream()
                .sorted((a, b) -> Double.compare(b.getRollingCoefficient(), a.getRollingCoefficient()))
                .forEach(ca -> System.out.printf("  %s (%s): %.3f%n",
                        ca.getName(), ca.getConfederation(), ca.getRollingCoefficient()));

        System.out.println("\n" + "=".repeat(70));
    }

    private void applyContinentalCoefficientsLegacy() {
        Map<Confederation, Map<String, Double>> avgByConf = new HashMap<>();

        for (Map.Entry<Confederation, List<ContinentalCompetitionDef>> entry : continentalDefs.entrySet()) {
            Confederation conf = entry.getKey();
            List<ContinentalCompetitionDef> defs = entry.getValue();

            Map<String, Double> countryTotals = new HashMap<>();
            Map<String, Integer> countryTeamsParticipating = new HashMap<>();

            for (ContinentalCompetitionDef def : defs) {
                ContinentalTournament t = continentalTournaments.get(def.getCompetitionKey());
                if (t == null)
                    continue;

                // FIX: Track clubs that earned ANY points (qualifiers + main stage + knockout + bonuses)
                Map<String, ContinentalTournament.CountryCoeffData> coeffData = t.getCountryCoefficients();
                for (Map.Entry<String, ContinentalTournament.CountryCoeffData> ce : coeffData.entrySet()) {
                    String country = ce.getKey();
                    ContinentalTournament.CountryCoeffData data = ce.getValue();
                    // Include ALL points: performance + participation bonuses
                    double allPoints = data.qualifierPoints + data.mainStagePoints + data.knockoutPoints + data.bonusPoints;
                    countryTotals.merge(country, allPoints, Double::sum);
                    // Use actual participant count (not just main stage)
                    int uniqueParticipants = data.teamCount;
                    countryTeamsParticipating.merge(country, uniqueParticipants, Integer::max);
                }
            }

            // Compute per-country average (PURE AVERAGE of performance points)
            Map<String, Double> avgs = new HashMap<>();
            for (Map.Entry<String, Double> e : countryTotals.entrySet()) {
                int teams = Math.max(1, countryTeamsParticipating.getOrDefault(e.getKey(), 1));
                double avg = e.getValue() / teams;
                avg = Math.floor(avg * 1000.0) / 1000.0;
                avgs.put(e.getKey(), avg);
            }
            avgByConf.put(conf, avgs);
        }

        // FIX: Do NOT apply normalization factor; use pure average semantics
        for (Map.Entry<Confederation, List<CountryAssociation>> entry : countriesByConfed.entrySet()) {
            Confederation conf = entry.getKey();
            Map<String, Double> avgs = avgByConf.getOrDefault(conf, new HashMap<>());

            for (CountryAssociation ca : entry.getValue()) {
                // Apply pure average without distorting multiplicative factor
                double seasonCoeff = avgs.getOrDefault(ca.getName(), 0.0);
                ca.addSeasonCoefficient(seasonCoeff);
            }
        }
    }

    private static final class CountryCoefficientAggregate {
        private final Map<String, Double> participantPoints = new LinkedHashMap<>();
        private final List<Double> fallbackContributions = new ArrayList<>();
        private double fallbackTotalPoints;
        private int fallbackParticipants;

        private void addParticipantPoints(Map<String, Double> pointsByParticipant) {
            for (Map.Entry<String, Double> entry : pointsByParticipant.entrySet()) {
                String participantKey = entry.getKey();
                if (participantKey == null || participantKey.isEmpty()) {
                    continue;
                }
                double safePoints = Math.max(0.0, entry.getValue());
                participantPoints.merge(participantKey, safePoints, Double::sum);
            }
        }

        private void addFallback(double points, int participantCount) {
            double safePoints = Math.max(0.0, points);
            int safeParticipants = Math.max(0, participantCount);
            fallbackContributions.add(safePoints);
            fallbackTotalPoints += safePoints;
            fallbackParticipants += safeParticipants;
        }

        private List<Double> contributions() {
            if (!participantPoints.isEmpty()) {
                return new ArrayList<>(participantPoints.values());
            }
            return new ArrayList<>(fallbackContributions);
        }

        private double totalPoints() {
            if (!participantPoints.isEmpty()) {
                double total = 0.0;
                for (double points : participantPoints.values()) {
                    total += points;
                }
                return total;
            }
            return fallbackTotalPoints;
        }

        private int participants() {
            if (!participantPoints.isEmpty()) {
                return participantPoints.size();
            }
            return fallbackParticipants;
        }

        private double average() {
            int participantCount = participants();
            return participantCount > 0 ? totalPoints() / participantCount : 0.0;
        }
    }

    private Map<Confederation, Map<String, CountryCoefficientAggregate>> buildCountryCoefficientAggregates() {
        Map<Confederation, Map<String, CountryCoefficientAggregate>> byConf = new HashMap<>();

        for (Map.Entry<Confederation, List<ContinentalCompetitionDef>> entry : continentalDefs.entrySet()) {
            Confederation conf = entry.getKey();
            Map<String, CountryCoefficientAggregate> perCountry = new HashMap<>();

            for (ContinentalCompetitionDef def : entry.getValue()) {
                ContinentalTournament tournament = continentalTournaments.get(def.getCompetitionKey());
                if (tournament == null) {
                    continue;
                }

                Map<String, ContinentalTournament.CountryCoeffData> coeffData = tournament.getCountryCoefficients();
                Map<String, Map<String, Double>> clubPointBreakdown = tournament.getCountryClubPointBreakdown();
                for (Map.Entry<String, ContinentalTournament.CountryCoeffData> ce : coeffData.entrySet()) {
                    ContinentalTournament.CountryCoeffData data = ce.getValue();
                    double allPoints = data.qualifierPoints + data.mainStagePoints + data.knockoutPoints + data.bonusPoints;

                    CountryCoefficientAggregate aggregate = perCountry.computeIfAbsent(ce.getKey(),
                            k -> new CountryCoefficientAggregate());
                    Map<String, Double> participantPoints = clubPointBreakdown.getOrDefault(ce.getKey(), Collections.emptyMap());
                    if (!participantPoints.isEmpty()) {
                        aggregate.addParticipantPoints(participantPoints);
                    } else {
                        aggregate.addFallback(allPoints, data.teamCount);
                    }
                }
            }

            byConf.put(conf, perCountry);
        }

        return byConf;
    }

    private Map<Club, Double> buildClubContinentalPointTotals() {
        Map<Club, Double> totals = new HashMap<>();

        for (ContinentalTournament tournament : continentalTournaments.values()) {
            Map<String, Map<String, Double>> byCountry = tournament.getCountryClubPointBreakdown();
            for (Map.Entry<String, Map<String, Double>> countryEntry : byCountry.entrySet()) {
                String country = countryEntry.getKey();
                for (Map.Entry<String, Double> clubEntry : countryEntry.getValue().entrySet()) {
                    Club club = resolveClubByCountryAndName(clubEntry.getKey(), country);
                    if (club == null) {
                        continue;
                    }
                    totals.merge(club, clubEntry.getValue(), Double::sum);
                }
            }
        }

        return totals;
    }

    private Club resolveClubByCountryAndName(String clubName, String country) {
        if (clubName == null || clubName.isBlank()) {
            return null;
        }
        if (country == null || country.isBlank()) {
            return null;
        }

        Map<String, Club> byName = clubsByCountryAndName.get(country.toLowerCase(Locale.ROOT));
        if (byName == null) {
            return null;
        }
        return byName.get(clubName.toLowerCase(Locale.ROOT));
    }

    private void updateClubContinentalCoefficientHistory() {
        Map<Club, Double> seasonPoints = buildClubContinentalPointTotals();
        for (Map.Entry<Club, Double> entry : seasonPoints.entrySet()) {
            Club club = entry.getKey();
            double points = Math.floor(entry.getValue() * 1000.0) / 1000.0;
            club.addSeasonContinentalCoefficient(points);
            clubsWithContinentalHistory.add(club);
        }

        for (Club club : new ArrayList<>(clubsWithContinentalHistory)) {
            if (seasonPoints.containsKey(club)) {
                continue;
            }
            club.addSeasonContinentalCoefficient(0.0);
        }
    }

    public void debugDisplayNationCoefficients() {
        System.out.println("\n" + "=".repeat(70));
        System.out.println("[DEBUG] NATION COEFFICIENT BREAKDOWN - SEASON " + seasonYear);
        System.out.println("=".repeat(70));

        System.out.println("\nNOTE: Coefficients are calculated ONLY from continental tournament performance.");
        System.out.println("Formula per country: (qualifier + main stage + knockout + bonus points) / participants.");
        System.out.println("A rolling 5-season total determines slot allocation for the following year.\n");

        System.out.println("-".repeat(70));
        System.out.println("PER-CONFEDERATION BREAKDOWNS:");
        System.out.println("-".repeat(70));

        Map<Confederation, Map<String, CountryCoefficientAggregate>> byConf = buildCountryCoefficientAggregates();
        for (Map.Entry<Confederation, Map<String, CountryCoefficientAggregate>> entry : byConf.entrySet()) {
            System.out.printf("\n%s:%n", entry.getKey());

            entry.getValue().entrySet().stream()
                    .sorted((a, b) -> Double.compare(b.getValue().average(), a.getValue().average()))
                    .forEach(countryEntry -> {
                        CountryCoefficientAggregate aggregate = countryEntry.getValue();
                        StringBuilder formula = new StringBuilder();
                        List<Double> contributions = aggregate.contributions();
                        for (int i = 0; i < contributions.size(); i++) {
                            if (i > 0) {
                                formula.append(" + ");
                            }
                            formula.append(String.format("%.3f", contributions.get(i)));
                        }
                        if (formula.length() == 0) {
                            formula.append("0.000");
                        }

                        System.out.printf("  %s: %s = %.3f; Average: %.3f (%d participants)%n",
                                countryEntry.getKey(),
                                formula,
                                aggregate.totalPoints(),
                                aggregate.average(),
                                aggregate.participants());
                    });
        }

        System.out.println("\n" + "-".repeat(70));
        System.out.println("CURRENT ROLLING 5-SEASON TOTALS (used for slot allocation):");
        System.out.println("-".repeat(70));

        countries.values().stream()
                .sorted((a, b) -> Double.compare(b.getRollingCoefficient(), a.getRollingCoefficient()))
                .forEach(ca -> System.out.printf("  %s (%s): %.3f%n",
                        ca.getName(), ca.getConfederation(), ca.getRollingCoefficient()));

        System.out.println("\n" + "=".repeat(70));
    }

    private void applyContinentalCoefficients() {
        Map<Confederation, Map<String, CountryCoefficientAggregate>> byConf = buildCountryCoefficientAggregates();
        updateClubContinentalCoefficientHistory();

        for (Map.Entry<Confederation, List<CountryAssociation>> entry : countriesByConfed.entrySet()) {
            Confederation conf = entry.getKey();
            Map<String, CountryCoefficientAggregate> perCountry = byConf.getOrDefault(conf, Collections.emptyMap());

            for (CountryAssociation ca : entry.getValue()) {
                CountryCoefficientAggregate aggregate = perCountry.get(ca.getName());
                double seasonCoeff = aggregate == null ? 0.0 : aggregate.average();
                seasonCoeff = Math.floor(seasonCoeff * 1000.0) / 1000.0;
                ca.addSeasonCoefficient(seasonCoeff);
            }
        }
    }

    private boolean allCupsComplete() {
        if (ENABLE_DOMESTIC_CUPS) {
            for (KnockoutCup cup : domesticCups.values()) {
                if (!cup.isComplete())
                    return false;
            }
        }
        if (ENABLE_CONTINENTAL) {
            for (ContinentalTournament t : continentalTournaments.values()) {
                if (!t.isComplete())
                    return false;
            }
        }
        if (ENABLE_GLOBAL) {
            return globalClubCup == null || globalClubCup.isComplete();
        }
        return true;
    }

    private int resolveConfiguredLeagueTarget(LeagueConfigReader.LeagueConfig cfg, int divisionIndex, int fallback) {
        if (cfg == null) {
            return Math.max(2, fallback);
        }

        if (cfg.getDivisionsCount() <= 1) {
            if (cfg.getClubsFixedTotal() > 0) {
                return Math.max(2, cfg.getClubsFixedTotal());
            }
            List<Integer> sizes = cfg.getDivisionSizes();
            if (!sizes.isEmpty() && sizes.get(0) > 0) {
                return Math.max(2, sizes.get(0));
            }
            return Math.max(2, fallback);
        }

        List<Integer> sizes = cfg.getDivisionSizes();
        if (divisionIndex >= 0 && divisionIndex < sizes.size() && sizes.get(divisionIndex) > 0) {
            return Math.max(2, sizes.get(divisionIndex));
        }
        return Math.max(2, fallback);
    }

    private List<League> buildDivisionalLeagues(CountryAssociation assoc, LeagueConfigReader.LeagueConfig cfg,
            int level, List<Club> clubs) {
        List<Integer> sizes = cfg.getDivisionSizes();
        List<String> names = cfg.getDivisionNames();
        int divisions = cfg.getDivisionsCount();
        List<Club> sorted = new ArrayList<>(clubs);
        sorted.sort((a, b) -> Double.compare(b.getEloRating(), a.getEloRating()));

        if (sizes.isEmpty() && divisions > 0) {
            int perDivision = Math.max(1, sorted.size() / divisions);
            for (int i = 0; i < divisions; i++) {
                sizes.add(perDivision);
            }
        }

        List<League> result = new ArrayList<>();
        int cursor = 0;
        for (int i = 0; i < divisions; i++) {
            int size = (i < sizes.size()) ? sizes.get(i) : (sizes.isEmpty() ? 0 : sizes.get(sizes.size() - 1));
            if (size <= 0) {
                size = Math.max(1, (int) Math.ceil((sorted.size() - cursor) / (double) (divisions - i)));
            }

            // Use league name straight from file (no smart appending)
            String baseName = names.size() > i ? names.get(i) : cfg.getLeagueName();
            String normalized = assoc.normalizeLeagueName(baseName);
            League league = assoc.getOrCreateLeague(normalized, level);
            league.setLeagueConfig(cfg);
            league.setTargetSize(resolveConfiguredLeagueTarget(cfg, i, size));

            for (int k = 0; k < size && cursor < sorted.size(); k++) {
                Club club = sorted.get(cursor++);
                club.setDomesticLeagueName(normalized);
                club.setDomesticLevel(level);
                club.setCountryAssociation(assoc); // Link club to its country's coefficient
                league.addClub(club);
            }
            result.add(league);
        }

        if (cursor < sorted.size()) {
            List<Club> leftovers = sorted.subList(cursor, sorted.size());
            unassignedClubsByCountry.computeIfAbsent(assoc.getName(), k -> new ArrayList<>()).addAll(leftovers);
        }

        return result;
    }

    private void assignUnassignedClubs(CountryAssociation assoc) {
        if (assoc == null)
            return;
        List<Club> pool = unassignedClubsByCountry.getOrDefault(assoc.getName(), new ArrayList<>());
        if (pool.isEmpty())
            return;

        List<League> leagues = new ArrayList<>(assoc.getAllLeagues());
        leagues.sort(Comparator.comparingInt(League::getLevel).thenComparing(League::getName));
        if (leagues.isEmpty())
            return;

        List<Club> remaining = new ArrayList<>();
        for (Club club : pool) {
            League target = findLeagueOpening(leagues);
            if (target == null) {
                remaining.add(club);
                continue;
            }
            club.setDomesticLeagueName(target.getName());
            club.setDomesticLevel(target.getLevel());
            club.setCountryAssociation(assoc); // Link club to its country's coefficient
            target.addClub(club);

            // Apply financial recovery when club returns from unassigned status
            // This breaks the dropout spiral
            double recoveryStrength = 0.7; // Moderate recovery boost
            club.applyRecoveryBonusOnReassignment(recoveryStrength);
        }

        if (!remaining.isEmpty()) {
            League lowest = leagues.get(leagues.size() - 1);
            for (Club club : remaining) {
                club.setDomesticLeagueName(lowest.getName());
                club.setDomesticLevel(lowest.getLevel());
                club.setCountryAssociation(assoc); // Link club to its country's coefficient
                lowest.addClub(club);

                // Apply financial recovery
                double recoveryStrength = 0.7;
                club.applyRecoveryBonusOnReassignment(recoveryStrength);
            }
            remaining.clear();
        }

        pool.clear();
        pool.addAll(remaining);

        for (League league : leagues) {
            league.generateFixtures();
        }
    }

    private League findLeagueOpening(List<League> leagues) {
        for (int i = leagues.size() - 1; i >= 0; i--) {
            League league = leagues.get(i);
            int target = Math.max(MIN_LEAGUE_SIZE, league.getTargetSize());
            if (league.getClubs().size() < target) {
                return league;
            }
        }
        return null;
    }

    /**
     * Drop a club to unassigned state with rating penalty
     */
    private void dropClubToUnassigned(Club club, CountryAssociation ca) {
        if (club == null || ca == null)
            return;

        club.setUnassigned(true);
        club.setSeasonsUnassigned(0);

        // Apply rating penalty: -7.5 base penalty (can be -5 to -10)
        double penalty = 7.5 + rng.nextGaussian() * 1.5;
        penalty = Math.max(5.0, Math.min(10.0, penalty));
        club.applyRatingPenaltyForDropdown(penalty);

        if (verbose) {
            System.out.println("[UNASSIGNED] " + club.getName() + " dropped with rating penalty -"
                    + String.format("%.1f", penalty));
        }

        unassignedClubsByCountry
                .computeIfAbsent(ca.getName(), k -> new ArrayList<>())
                .add(club);
    }

    /**
     * Simulate friendlies for unassigned clubs at the start of pre-season
     */
    private void simulateUnassignedFriendlies(double t) {
        for (List<Club> pool : unassignedClubsByCountry.values()) {
            for (Club unassignedClub : pool) {
                if (unassignedClub.getFriendliesPlayedThisSeason() >= 5) {
                    continue; // Already played 5 friendlies
                }

                // Find opponents within ±10 rating range
                List<Club> opponents = new ArrayList<>();
                double targetRating = unassignedClub.getRawStrength();
                double ratingMin = targetRating - 10.0;
                double ratingMax = targetRating + 10.0;

                for (Club candidate : clubIndex.values()) {
                    if (candidate.equals(unassignedClub))
                        continue;
                    if (candidate.isUnassigned())
                        continue; // For now, only play against assigned clubs

                    double candRating = candidate.getRawStrength();
                    if (candRating >= ratingMin && candRating <= ratingMax) {
                        opponents.add(candidate);
                    }
                }

                // Play friendly matches (up to 5)
                Collections.shuffle(opponents,
                        new Random(Double.doubleToLongBits(t) ^ unassignedClub.getName().hashCode()));
                for (int i = 0; i < Math.min(5 - unassignedClub.getFriendliesPlayedThisSeason(),
                        opponents.size()); i++) {
                    Club opponent = opponents.get(i);
                    MatchEngine.Score score = MatchEngine.play(unassignedClub, opponent, false, false, t + i * 0.5,
                            2.0);

                    // Apply rating changes: +2 for win, +0.5 for draw, -0.5/-1 for loss
                    if (score.homeGoals > score.awayGoals) {
                        unassignedClub.recordFriendlyWin();
                        unassignedClub.applyRatingChangeFromFriendly(2);
                    } else if (score.homeGoals == score.awayGoals) {
                        unassignedClub.recordFriendlyDraw();
                        unassignedClub.applyRatingChangeFromFriendly(0); // Small bonus could be +0.5
                    } else {
                        unassignedClub.recordFriendlyLoss();
                        unassignedClub.applyRatingChangeFromFriendly(-1);
                    }
                }
            }
        }
    }

    /**
     * Promote top-performing unassigned clubs back to the bottom tier
     */
    private void promoteTopUnassignedClubs(CountryAssociation ca) {
        if (ca == null)
            return;

        List<Club> unassignedPool = unassignedClubsByCountry.getOrDefault(ca.getName(), new ArrayList<>());
        if (unassignedPool.isEmpty())
            return;

        List<League> leagues = new ArrayList<>(ca.getAllLeagues());
        if (leagues.isEmpty())
            return;

        // Find bottom tier
        int maxLevel = leagues.stream().mapToInt(League::getLevel).max().orElse(-1);
        League bottomTier = leagues.stream()
                .filter(l -> l.getLevel() == maxLevel)
                .findFirst()
                .orElse(null);

        if (bottomTier == null)
            return;

        // Sort unassigned by rating (best first)
        unassignedPool.sort((a, b) -> Double.compare(b.getRawStrength(), a.getRawStrength()));

        // Promote clubs that have recovered sufficiently
        // Criteria: been unassigned for 2+ seasons AND rating >= original rating - 3
        List<Club> toPromote = new ArrayList<>();
        for (Club club : unassignedPool) {
            if (club.getSeasonsUnassigned() >= 2
                    && club.getRawStrength() >= club.getRatingAtDropdown() - 3.0) {
                toPromote.add(club);
                if (toPromote.size() >= 3)
                    break; // Promote max 3 per season
            }
        }

        // Move promoted clubs from unassigned to bottom tier
        for (Club club : toPromote) {
            unassignedPool.remove(club);
            club.setUnassigned(false);
            club.setSeasonsUnassigned(0);
            club.setDomesticLeagueName(bottomTier.getName());
            club.setDomesticLevel(bottomTier.getLevel());
            bottomTier.addClub(club);

            if (verbose) {
                System.out.println(
                        "[PROMOTION] " + club.getName() + " promoted from Unassigned to " + bottomTier.getName());
            }
        }

        bottomTier.generateFixtures();
    }

    /**
     * Calculate 2^x cup preliminary round formula for a nation
     * Returns the number of clubs that should play in the preliminary round
     */
    private int calculate2PowerXCupPreliminary(int totalClubs) {
        if (totalClubs <= 0)
            return 0;

        // Find the largest power of 2 that is <= totalClubs
        int powerOf2 = 1;
        while (powerOf2 * 2 <= totalClubs) {
            powerOf2 *= 2;
        }

        // gap = clubs beyond the power of 2
        int gap = totalClubs - powerOf2;

        // preliminaryClubs = gap * 2
        // This ensures the bracket works out to a proper power of 2
        return gap * 2;
    }

    /**
     * Rebuild domestic cups each season so entrants always reflect the latest league
     * assignments and financial drop/recovery changes.
     */
    private void rebuildDomesticCupsForSeason() {
        domesticCups.clear();

        for (CountryAssociation assoc : countries.values()) {
            Set<Club> entrants = new LinkedHashSet<>();
            for (League league : assoc.getAllLeagues()) {
                entrants.addAll(league.getClubs());
            }

            List<Club> unassigned = unassignedClubsByCountry.getOrDefault(assoc.getName(), Collections.emptyList());
            entrants.addAll(unassigned);

            List<Club> entrantList = new ArrayList<>(entrants);
            KnockoutCup cup = new KnockoutCup(assoc.getName() + " Cup", entrantList, true);
            domesticCups.put(assoc.getName(), cup);

            int preliminaryClubsCount = calculate2PowerXCupPreliminary(entrantList.size());
            if (verbose && preliminaryClubsCount > 0) {
                System.out.println("[CUP 2^x] " + assoc.getName() + ": " + entrantList.size() + " total clubs, "
                        + preliminaryClubsCount + " in preliminary round");
            }
        }
    }

    /**
     * Calculate unassigned club budget (80% of bottom tier average)
     */
    private double calculateUnassignedBudget(CountryAssociation ca) {
        if (ca == null)
            return 15.0; // Default fallback

        List<League> leagues = new ArrayList<>(ca.getAllLeagues());
        int maxLevel = leagues.stream().mapToInt(League::getLevel).max().orElse(-1);
        League bottomTier = leagues.stream()
                .filter(l -> l.getLevel() == maxLevel)
                .findFirst()
                .orElse(null);

        if (bottomTier == null || bottomTier.getClubs().isEmpty()) {
            return 15.0; // Default
        }

        double totalBudget = 0;
        for (Club club : bottomTier.getClubs()) {
            totalBudget += club.getTransferBudgetSeason() + club.getWageBillWeekly() * 52;
        }

        double averageBudget = totalBudget / bottomTier.getClubs().size();
        return averageBudget * 0.8; // 80% of average
    }

    private void printSeasonSummary() {
        System.out.println("\n" + "=".repeat(70));
        System.out.println("SEASON " + seasonYear + " - FINAL SUMMARY");
        System.out.println("=".repeat(70));
        System.out.println("\n[TOP LEAGUE STANDINGS]");
        List<CountryAssociation> ranked = new ArrayList<>(countries.values());
        ranked.sort((a, b) -> Double.compare(b.getRollingCoefficient(), a.getRollingCoefficient()));
        int shown = 0;
        for (CountryAssociation ca : ranked) {
            if (shown >= 3)
                break;
            League league = ca.getTopLeague();
            if (league == null)
                continue;
            shown++;
            System.out.printf("\n  %s (%s) - Coeff %.3f%n", league.getName(), ca.getName(), ca.getRollingCoefficient());
            league.printTableSimple(5);
        }

        System.out.println("\n" + "=".repeat(70));
    }

    public String lookupClub(String name) {
        if (name == null)
            return "No name.";
        Club c = clubIndex.get(name.toLowerCase());
        if (c == null)
            return "Not found: " + name;

        StringBuilder sb = new StringBuilder();
        sb.append(c.toString()).append("\n");
        sb.append("Avg rating: ").append(String.format("%.1f", c.getAverageRating())).append("\n");
        sb.append("Trophies: ").append(c.trophySummary(8)).append("\n");
        return sb.toString();
    }

    private static double avgElo(List<Club> clubs) {
        if (clubs.isEmpty())
            return 0;
        double sum = 0;
        for (Club c : clubs)
            sum += c.getEloRating();
        return sum / clubs.size();
    }

    private static Set<Integer> rangeSet(int start, int endInclusive) {
        Set<Integer> s = new HashSet<>();
        for (int i = start; i <= endInclusive; i++)
            s.add(i);
        return s;
    }

    /**
     * Validate that all leagues meet minimum size requirements (>= 2 clubs).
     */
    /**
     * Validation Upgrade: Check league completeness (actual == target), not just
     * viability.
     * Uses per-league target sizes stored on League object (initialized from club
     * list or config).
     */
    public List<String> validateLeagueIntegrity() {
        List<String> warnings = new ArrayList<>();

        for (CountryAssociation ca : countries.values()) {
            for (League league : ca.getAllLeagues()) {
                int actual = league.getClubs().size();
                int target = league.getTargetSize(); // Per-league target, not hardcoded 20

                // Check viability (minimum 2 clubs) - always enforce
                if (actual < 2) {
                    warnings.add(String.format(
                            "VIABILITY_BREACH: %s (%s tier %d): has %d clubs, needs minimum 2",
                            league.getName(), ca.getName(), league.getLevel(), actual));
                }

                // Check completeness (actual vs. league's configured target)
                // Only warn if deficit >= 2 to avoid spam for natural variation
                int deficit = Math.max(0, target - actual);
                if (deficit >= 2) {
                    warnings.add(String.format(
                            "COMPLETENESS_DEFICIT: %s (%s tier %d): has %d clubs, target %d (deficit %d)",
                            league.getName(), ca.getName(), league.getLevel(), actual, target, deficit));
                }
            }
        }
        return warnings;
    }

    /**
     * Export integrity warnings to CSV file with structured fields.
     * FIX: Now generates warnings directly from league snapshots instead of relying
     * on separate validation.
     * This ensures warnings_count in diagnostics matches actual warnings in CSV.
     */
    public void exportIntegrityWarnings(int year, List<String> warnings) {
        try {
            File csvFile = new File(exportRunFolder + "/integrity_warnings.csv");
            csvFile.getParentFile().mkdirs();
            boolean isNewFile = !csvFile.exists();
            try (PrintWriter pw = new PrintWriter(
                    new OutputStreamWriter(new FileOutputStream(csvFile, true), java.nio.charset.StandardCharsets.UTF_8))) {
                if (isNewFile) {
                    pw.write('\uFEFF');
                    pw.println("season_year,warning_type,league_name,country,tier,club_count,target_size,deficit");
                }

                // FIX: Generate warnings directly from current league state, not from
                // parsedinput string
                // This ensures 100% consistency between diagnostics counter and CSV rows
                int writtenCount = 0;
                for (CountryAssociation ca : countries.values()) {
                    for (League league : ca.getAllLeagues()) {
                        int actual = league.getClubs().size();
                        int target = league.getTargetSize();

                        // Check viability
                        if (actual < 2) {
                            pw.printf("%d,\"VIABILITY\",\"%s\",\"%s\",%d,%d,%d,%d\n",
                                    year,
                                    league.getName().replace("\"", "\"\""),
                                    ca.getName().replace("\"", "\"\""),
                                    league.getLevel(),
                                    actual, target, 0);
                            writtenCount++;
                        } else {
                            // Check completeness
                            int deficit = Math.max(0, target - actual);
                            if (deficit >= 2) {
                                pw.printf("%d,\"COMPLETENESS\",\"%s\",\"%s\",%d,%d,%d,%d\n",
                                        year,
                                        league.getName().replace("\"", "\"\""),
                                        ca.getName().replace("\"", "\"\""),
                                        league.getLevel(),
                                        actual, target, deficit);
                                writtenCount++;
                            } else if (actual > target) {
                                // Also log overflows for completeness
                                pw.printf("%d,\"OVERFLOW\",\"%s\",\"%s\",%d,%d,%d,%d\n",
                                        year,
                                        league.getName().replace("\"", "\"\""),
                                        ca.getName().replace("\"", "\"\""),
                                        league.getLevel(),
                                        actual, target, 0);
                                writtenCount++;
                            }
                        }
                    }
                }

                if (verbose && writtenCount > 0) {
                    System.out.println("[INTEGRITY] Exported " + writtenCount + " warnings for season " + year);
                }
                pw.flush();
            }
        } catch (IOException e) {
            System.err.println("ERROR: Could not write integrity warnings: " + e.getMessage());
        }
    }

    /**
     * Sanitize club/league names for Excel compatibility.
     * Removes or replaces special characters that cause issues.
     */
    private String sanitizeForExcel(String input) {
        if (input == null)
            return "";

        return input
                .replace("\"", "'") // Replace quotes with apostrophes
                .replace("\n", " ") // Replace newlines with spaces
                .replace("\r", "") // Remove carriage returns
                .replace("\t", " ") // Replace tabs with spaces
                .replaceAll("[^\\x20-\\x7E]", ""); // Remove non-ASCII characters
    }

    /**
     * Display transfer market activity - top 5 clubs by net points / rating ratio
     */
    private void displayTransferMarketActivity(String season, double slot) {
        if (!verbose)
            return;

        // Calculate net points per rating for each club
        List<TransferRatio> ratios = new ArrayList<>();
        for (Club club : clubIndex.values()) {
            if (club.getRawStrength() <= 0)
                continue;
            // Net points = revenue - spent this season
            double netPoints = club.getRevenueSeason() - club.getPointsSpentSeason();
            double ratio = netPoints / club.getRawStrength();
            ratios.add(new TransferRatio(club, ratio, netPoints));
        }

        // Sort by ratio descending
        ratios.sort((a, b) -> Double.compare(b.ratio, a.ratio));

        // Display top 5
        System.out.println("\n" + "=".repeat(100));
        System.out.println(
                "[TRANSFER MARKET] " + season + " TRANSFER WINDOW - Top 5 Clubs by Net Economy Points/Rating Ratio");
        System.out.println("=".repeat(100));

        for (int i = 0; i < Math.min(5, ratios.size()); i++) {
            TransferRatio tr = ratios.get(i);
            Club c = tr.club;
            System.out.printf("  %d. %s [%s] - Net Points: %.1f | Rating: %.1f | Ratio: %.3f%n",
                    i + 1, c.getName(), c.getCountry(), tr.netPoints, c.getRawStrength(), tr.ratio);
        }

        System.out.println("=".repeat(100) + "\n");
    }

    /**
     * Helper class for transfer market activity display
     */
    private static class TransferRatio {
        Club club;
        double ratio;
        double netPoints;

        TransferRatio(Club club, double ratio, double netPoints) {
            this.club = club;
            this.ratio = ratio;
            this.netPoints = netPoints;
        }
    }

    /**
     * Rebalance multi-group tier systems to prevent overflow (e.g., Spain Regional
     * Preferente with 56 clubs).
     * Called periodically to redistribute clubs from overfilled leagues to empty
     * ones at same tier/group.
     * This prevents schedules and fatigue from exploding due to unbalanced group
     * sizes.
     */
    public void rebalanceMultiGroupTiers() {
        int rebalancedCount = 0;

        for (CountryAssociation ca : countries.values()) {
            // Group leagues by tier
            Map<Integer, List<League>> leaguesByTier = new HashMap<>();
            for (League league : ca.getAllLeagues()) {
                leaguesByTier.computeIfAbsent(league.getLevel(), k -> new ArrayList<>()).add(league);
            }

            // Check each tier for multi-group overflow
            for (Map.Entry<Integer, List<League>> tierEntry : leaguesByTier.entrySet()) {
                int tier = tierEntry.getKey();
                List<League> tierLeagues = tierEntry.getValue();

                // If more than 1 league at tier, it's a multi-group system
                if (tierLeagues.size() <= 1)
                    continue;

                // Calculate average and max sizes
                int totalClubs = 0;
                int maxSize = 0;
                int minSize = Integer.MAX_VALUE;
                for (League league : tierLeagues) {
                    int size = league.getClubs().size();
                    totalClubs += size;
                    maxSize = Math.max(maxSize, size);
                    minSize = Math.min(minSize, size);
                }

                // If spread is too large (max > 1.5x average), rebalance
                int avgSize = totalClubs / tierLeagues.size();
                if (maxSize > avgSize * 1.5 && minSize * 2 < avgSize) {
                    System.out.println("[REBALANCE] " + ca.getName() + " tier " + tier +
                            ": sizes " + minSize + "-" + maxSize + " (avg " + avgSize + "), rebalancing...");

                    // Find overfilled and underfilled leagues
                    List<League> overfilled = new ArrayList<>();
                    List<League> underfilled = new ArrayList<>();
                    for (League league : tierLeagues) {
                        int size = league.getClubs().size();
                        if (size > avgSize)
                            overfilled.add(league);
                        if (size < avgSize - 2)
                            underfilled.add(league);
                    }

                    // Move clubs from overfilled to underfilled
                    for (League source : overfilled) {
                        while (source.getClubs().size() > avgSize && !underfilled.isEmpty()) {
                            League target = underfilled.get(0);
                            if (target.getClubs().size() >= avgSize) {
                                underfilled.remove(0);
                                continue;
                            }

                            // Move weakest club from source to target
                            List<Club> sourceClubs = new ArrayList<>(source.getClubs());
                            sourceClubs.sort(Comparator.comparingDouble(Club::getRawStrength));
                            Club toMove = sourceClubs.get(0);

                            source.removeClub(toMove);
                            target.addClub(toMove);
                            toMove.setDomesticLeagueName(target.getName());
                            rebalancedCount++;
                        }
                    }
                }
            }
        }

        if (rebalancedCount > 0) {
            System.out.println("[REBALANCE] Total clubs moved: " + rebalancedCount);
        }
    }

    /**
     * Refined Force-Assignment: Distribute orphaned clubs across multiple
     * bottom-tier groups
     * based on target sizes rather than dumping all into a single league.
     * Handles multi-group tier systems (e.g., Italy's multi-group bottom tiers).
     */
    public void forceAssignAllRemainingClubs() {
        List<Club> allUnassigned = new ArrayList<>();
        Map<String, List<Club>> unassignedByCountry = new HashMap<>(unassignedClubsByCountry);

        for (List<Club> pool : unassignedByCountry.values()) {
            allUnassigned.addAll(pool);
        }
        if (allUnassigned.isEmpty())
            return;

        System.err.println("[GUARDRAIL] Force-assigning " + allUnassigned.size() + " orphaned clubs...");

        for (Club club : allUnassigned) {
            String country = club.getCountry();
            CountryAssociation ca = countries.get(country);
            if (ca == null) {
                System.err.println("  ERROR: Country not found for " + club.getName());
                continue;
            }

            List<League> allLeagues = new ArrayList<>(ca.getAllLeagues());
            if (allLeagues.isEmpty()) {
                System.err.println("  ERROR: " + country + " has no leagues");
                continue;
            }

            // Find the lowest tier(s)
            int lowestTier = allLeagues.stream()
                    .mapToInt(League::getLevel)
                    .max()
                    .orElse(Integer.MAX_VALUE);

            // Get all leagues at the lowest tier, sorted by deficit (how far below target)
            List<League> lowestTierLeagues = allLeagues.stream()
                    .filter(l -> l.getLevel() == lowestTier)
                    .sorted(Comparator
                            .comparingInt((League l) -> l.getTargetSize() - l.getClubs().size()) // Biggest deficit
                                                                                                 // first
                            .reversed())
                    .collect(java.util.stream.Collectors.toList());

            if (lowestTierLeagues.isEmpty()) {
                System.err.println("  ERROR: No leagues found at tier " + lowestTier);
                continue;
            }

            // Assign to the league with the biggest deficit (or smallest size if all at
            // target)
            League targetLeague = lowestTierLeagues.get(0);
            targetLeague.addClub(club);
            club.setDomesticLeagueName(targetLeague.getName());
            club.setDomesticLevel(targetLeague.getLevel());
            System.out.println("  Assigned " + club.getName() + " to " + targetLeague.getName()
                    + " (size: " + targetLeague.getClubs().size() + ", target: " + targetLeague.getTargetSize() + ")");
        }
        unassignedClubsByCountry.clear();
    }

    public void rebalanceLeagueSizesToTargets() {
        for (CountryAssociation ca : countries.values()) {
            List<League> leagues = new ArrayList<>(ca.getAllLeagues());
            if (leagues.isEmpty()) {
                continue;
            }
            normalizeLeagueSizes(leagues);
        }
    }

    /**
     * Export per-league diagnostics for long-horizon analysis.
     * Uses per-league target sizes and appends one row per season.
     */
    private void exportLeagueSnapshot(int year) {
        try {
            File csvFile = new File(exportRunFolder + "/league_snapshots.csv");
            csvFile.getParentFile().mkdirs();
            boolean isNewFile = !csvFile.exists();
            try (PrintWriter pw = new PrintWriter(
                    new OutputStreamWriter(new FileOutputStream(csvFile, true), java.nio.charset.StandardCharsets.UTF_8))) {
                if (isNewFile) {
                    pw.write('\uFEFF');
                    pw.println("season_year,country,league,tier,club_count,target_count,deficit," +
                            "avg_financial_power,min_financial_power,max_financial_power,warning_class");
                }
                for (CountryAssociation ca : countries.values()) {
                    for (League league : ca.getAllLeagues()) {
                        int actual = league.getClubs().size();
                        int target = league.getTargetSize(); // Use per-league target, not hardcoded 20
                        int deficit = Math.max(0, target - actual);

                        // Determine warning class
                        String warningClass = "OK";
                        if (actual < 2) {
                            warningClass = "VIABILITY";
                        } else if (deficit >= 2) {
                            warningClass = "DEFICIT";
                        } else if (actual > target) {
                            warningClass = "OVERFLOW";
                        }

                        double avgPower = 50.0, minPower = 50.0, maxPower = 50.0;
                        if (!league.getClubs().isEmpty()) {
                            double sumPower = 0.0;
                            minPower = Double.POSITIVE_INFINITY;
                            maxPower = Double.NEGATIVE_INFINITY;
                            for (Club club : league.getClubs()) {
                                double power = club.getFinancialPower();
                                sumPower += power;
                                if (power < minPower) {
                                    minPower = power;
                                }
                                if (power > maxPower) {
                                    maxPower = power;
                                }
                            }
                            avgPower = sumPower / league.getClubs().size();
                        }
                        pw.printf("%d,\"%s\",\"%s\",%d,%d,%d,%d,%.1f,%.1f,%.1f,\"%s\"\n",
                                year, ca.getName().replace("\"", "\"\""),
                                league.getName().replace("\"", "\"\""), league.getLevel(),
                                actual, target, deficit, avgPower, minPower, maxPower, warningClass);
                    }
                }
                pw.flush();
            }
        } catch (IOException e) {
            System.err.println("ERROR: Could not write league snapshot: " + e.getMessage());
        }
    }

    /**
     * Export seasonal diagnostic summary: counts of penalty types recorded this
     * season.
     * Used to validate that exporters are working and penalties are being applied
     * correctly.
     * NOW INCLUDES: FFP/Floor audit counters to prove whether audits ran at all.
     */
    private void exportSeasonDiagnostics(int year) {
        try {
            File csvFile = new File(exportRunFolder + "/season_diagnostics.csv");
            csvFile.getParentFile().mkdirs();
            boolean isNewFile = !csvFile.exists();
            try (PrintWriter pw = new PrintWriter(
                    new OutputStreamWriter(new FileOutputStream(csvFile, true), java.nio.charset.StandardCharsets.UTF_8))) {
                if (isNewFile) {
                    pw.write('\uFEFF');
                    pw.println("season_year,ffp_violations_recorded,floor_penalties_recorded," +
                            "integrity_warnings_count," +
                            "ffp_audits_run,ffp_threshold_breaches,ffp_penalties_applied," +
                            "floor_checks_run,floor_threshold_breaches,floor_penalties_applied");
                }

                SeasonalEventLog log = SeasonalEventLog.getInstance();
                int warningCount = validateLeagueIntegrity().size();

                pw.printf("%d,%d,%d,%d,%d,%d,%d,%d,%d,%d\n",
                        year,
                        log.getFFPViolationsRecorded(),
                        log.getFloorPenaltiesRecorded(),
                        warningCount,
                        log.getFFPAuditsRun(),
                        log.getFFPThresholdBreaches(),
                        log.getFFPPenaltiesApplied(),
                        log.getFloorChecksRun(),
                        log.getFloorThresholdBreaches(),
                        log.getFloorPenaltiesApplied());
                pw.flush();
            }
        } catch (IOException e) {
            System.err.println("ERROR: Could not write season diagnostics: " + e.getMessage());
        }
    }

    /**
     * Ensure export folder exists for current run.
     * Files are run-scoped and append season rows; they are no longer deleted
     * each season.
     */
    private void clearOldExportFiles() {
        try {
            // Create export run folder if it doesn't exist
            java.nio.file.Files.createDirectories(java.nio.file.Paths.get(exportRunFolder));

            if (verbose) {
                System.out.println("[EXPORT] Configured export folder: " + exportRunFolder);
            }
        } catch (IOException e) {
            System.err.println("WARNING: Could not initialize export files: " + e.getMessage());
        }
    }

    // Main entry point - launches interactive simulator
    public static void main(String[] args) throws Exception {
        SimulationManager manager = new SimulationManager();
        manager.start();
    }
}
