package worldfootballsim;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Interactive menu-driven multi-season simulator
 * Allows simulation of 1, 5, 100+ seasons with dynamic promotion/relegation
 */
public class SimulationManager {

    private WorldFootballSimulator currentSim;
    private int currentSeason;
    private final String csvPath;
    private final String leaguesPath;
    private final Scanner scanner = new Scanner(System.in, StandardCharsets.UTF_8);
    private static final int MAX_SEASON_HISTORY = 50;
    private final Map<Integer, SeasonRecord> seasonHistory = new LinkedHashMap<>();
    private int lastRecordedSeason = 0;
    private boolean running = true;
    private final Map<LeagueKey, LeagueStatBoard> leagueStatBoards = new HashMap<>();
    
    public SimulationManager() {
        this("clubs_utf8.csv", "leagues_utf8.csv");
    }

    public SimulationManager(String csvPath, String leaguesPath) {
        this.csvPath = csvPath;
        this.leaguesPath = leaguesPath;
        this.currentSeason = resolveSeasonYear(csvPath);
    }

    public void start() {
        configureUtf8Output();
        loadInitialData();
        if (running) {
            mainMenu();
        }
    }

    private void loadInitialData() {
        System.out.println("\n" + "=".repeat(70));
        System.out.println("Loading world football database...");
        System.out.println("=".repeat(70));

        try {
            currentSim = new WorldFootballSimulator(currentSeason, false);
            // Load configuration files (global config and nation coefficients)
            currentSim.loadConfiguration("global_config.csv", "preset_coefficient.csv");
            currentSim.loadFromOpta(csvPath, leaguesPath);
            // CRITICAL FIX: Set seed before loading data, and configure export folder
            long initialSeed = System.currentTimeMillis();
            currentSim.setRandomSeed(initialSeed);
            currentSim.configureExportFolder(initialSeed, currentSeason, currentSeason);
            System.out.printf("Loaded %d clubs from %d countries\n",
                    currentSim.clubIndex.size(), currentSim.countries.size());
            System.out.printf("Configured %d leagues across 7 confederations\n",
                    currentSim.allLeagues.size());
            lastRecordedSeason = currentSeason - 1;

            // Ask about excluded countries
            System.out.println();
            System.out.print("Include Russia in the simulation? (y/n): ");
            String russiaChoice = scanner.nextLine().trim().toLowerCase();
            if (russiaChoice.equals("n") || russiaChoice.equals("no")) {
                currentSim.excludeCountry("Russia");
                System.out.println("  Russia will be excluded from continental competitions.");
            }

            System.out.print("Include Israel in the simulation? (y/n): ");
            String israelChoice = scanner.nextLine().trim().toLowerCase();
            if (israelChoice.equals("n") || israelChoice.equals("no")) {
                currentSim.excludeCountry("Israel");
                System.out.println("  Israel will be excluded from continental competitions.");
            }

            if (!currentSim.getExcludedCountries().isEmpty()) {
                System.out.println("Excluded countries: " + currentSim.getExcludedCountries());
            }
        } catch (IOException e) {
            System.err.println("Failed to load data: " + e.getMessage());
            running = false;
        }
    }

    private void mainMenu() {
        while (running) {
            System.out.println("\n" + "=".repeat(70));
            System.out.printf("MAIN MENU - Season %d%n", currentSeason);
            System.out.println("=".repeat(70));

            Map<Integer, Runnable> actions = new LinkedHashMap<>();
            int option = 1;

            System.out.println("  SIMULATION");
            option = addMenuItem(actions, option, "Simulate fixtures", this::simulateFixturesMenu);
            option = addMenuItem(actions, option, "Simulate seasons", this::simulateSeasonsMenu);
            String startSeasonLabel = currentSim.isSeasonCompleted()
                    ? "Start new season"
                    : "Start new season (complete current season first)";
            option = addMenuItem(actions, option, startSeasonLabel, this::startNewSeason);

            System.out.println("  CLUBS AND NATIONS");
            option = addMenuItem(actions, option, "Find club", this::findClub);
            option = addMenuItem(actions, option, "Find nations", this::viewCoefficients);
            option = addMenuItem(actions, option, "View transfer market", this::viewTransferMarket);

            System.out.println("  COMPETITIONS");
            option = addMenuItem(actions, option, "View domestic cups", this::viewDomesticCups);
            option = addMenuItem(actions, option, "View domestic leagues", this::displayStandings);
            option = addMenuItem(actions, option, "View continental cups", this::viewContinentalCompetitions);

            System.out.println("  HISTORY");
            option = addMenuItem(actions, option, "Winners by league", this::showWinnersByLeague);
            option = addMenuItem(actions, option, "Winners by cup", this::showWinnersByCup);
            option = addMenuItem(actions, option, "Statistics (league/cup)", this::displayLeagueCupStatistics);

            option = addMenuItem(actions, option, "Exit", () -> running = false);

            System.out.println("=".repeat(70));
            System.out.print("Choose an option: ");

            try {
                if (!scanner.hasNextLine()) {
                    break;
                }
                int choice = Integer.parseInt(scanner.nextLine().trim());
                Runnable action = actions.get(choice);
                if (action != null) {
                    action.run();
                } else {
                    System.out.println("Invalid option. Please try again.");
                }
            } catch (NumberFormatException e) {
                System.out.println("Invalid input. Please enter a number.");
            } catch (NoSuchElementException e) {
                break;
            }
        }
        System.out.println("\nThank you for using World Football Simulator!");
    }

    private void simulateFixturesMenu() {
        System.out.println("\n1. Simulate next fixture");
        System.out.println("2. Simulate multiple fixtures");
        System.out.print("Choice: ");
        String choice = scanner.nextLine().trim();
        if ("2".equals(choice)) {
            simulateMultipleFixtures();
        } else if ("1".equals(choice)) {
            simulateGameweek();
        } else {
            System.out.println("Invalid option.");
        }
    }

    private void simulateSeasonsMenu() {
        System.out.println("\n1. Simulate entire season");
        System.out.println("2. Simulate multiple seasons");
        System.out.print("Choice: ");
        String choice = scanner.nextLine().trim();
        if ("2".equals(choice)) {
            simulateMultipleSeasons();
        } else if ("1".equals(choice)) {
            simulateFullSeason();
        } else {
            System.out.println("Invalid option.");
        }
    }

    private void simulateGameweek() {
        ensureSeasonInitialized();
        double slotBefore = currentSim.getCurrentSlot();
        Match biggestUpset = currentSim.getLastBiggestUpset();
        boolean advanced = currentSim.simulateNextSlot();
        if (!advanced) {
            System.out.println("\nSeason complete.");
            recordSeasonHistory(currentSeason);
            displaySeasonSummary();
        } else {
            String label = currentSim.getSlotLabel(slotBefore);
            System.out.printf("\n[Week %.1f] %s%n", slotBefore, label.isEmpty() ? "No events" : label);
            Match upset = currentSim.getLastBiggestUpset();
            if (upset != null && upset != biggestUpset && upset.hasResult()) {
                String homeCountry = upset.getHome().getCountry();
                String awayCountry = upset.getAway().getCountry();
                System.out.printf("Biggest upset: %s (%s) %d - %d %s (%s)%n",
                        upset.getHome().getName(), homeCountry,
                        upset.getHomeGoals(), upset.getAwayGoals(),
                        upset.getAway().getName(), awayCountry);
            }
        }
    }

    private void simulateMultipleFixtures() {
        System.out.print("How many fixtures to simulate? ");
        Integer count = readNumber();
        if (count == null || count <= 0) {
            System.out.println("Please enter a positive number.");
            return;
        }
        for (int i = 0; i < count; i++) {
            if (currentSim.isSeasonCompleted()) {
                break;
            }
            simulateGameweek();
        }
    }

    private void simulateFullSeason() {
        if (currentSim.isSeasonCompleted()) {
            System.out.println("\n[Warning] Season already simulated. Simulating again will overwrite standings.");
            currentSim.clearSeasonState();
        }
        System.out.println("\n" + "=".repeat(70));
        System.out.printf("Simulating Season %d...%n", currentSeason);
        System.out.println("=".repeat(70));

        long start = System.currentTimeMillis();
        ensureSeasonInitialized();
        currentSim.simulateSeason();
        long elapsed = System.currentTimeMillis() - start;

        System.out.printf("\nSeason %d completed in %.2f seconds%n", currentSeason, elapsed / 1000.0);
        recordSeasonHistory(currentSeason);
        displaySeasonSummary();
    }

    private void simulateMultipleSeasons() {
         if (currentSim.isSeasonCompleted()) {
             System.out.println("\n[Warning] Current season already simulated. Multi-season run will overwrite it.");
         }
         System.out.print("How many seasons to simulate? ");
         try {
             int count = Integer.parseInt(scanner.nextLine().trim());
             if (count <= 0) {
                 System.out.println("Please enter a positive number.");
                 return;
             }

             System.out.printf("\nSimulating %d seasons (%d-%d)...%n",
                     count, currentSeason, currentSeason + count - 1);
             System.out.println("=".repeat(70));

             // CRITICAL FIX: Configure run-scoped export folder to prevent file mixing
             // Use a seed that identifies this run uniquely
             long runSeed = System.currentTimeMillis() ^ (long) (Math.random() * Long.MAX_VALUE);
             int endYear = currentSeason + count - 1;
             currentSim.configureExportFolder(runSeed, currentSeason, endYear);

             for (int i = 0; i < count; i++) {
                 currentSim.clearSeasonState();
                long start = System.currentTimeMillis();
                ensureSeasonInitialized();
                currentSim.simulateSeason();
                long elapsed = System.currentTimeMillis() - start;

                // Display brief summary (season number only for cleaner output)
                System.out.printf("Season %d (%.2fs)%n", currentSeason, elapsed / 1000.0);

                // Save season
                recordSeasonHistory(currentSeason);
                currentSeason++;

                // Prepare next season with promotion/relegation handled on rollover
                if (i < count - 1) {
                    currentSim.applyPromotionAndRelegationForNextSeason();
                    currentSim.setSeasonYear(currentSeason);
                }
            }

            System.out.println("=".repeat(70));
            System.out.printf("Completed %d seasons%n", count);

        } catch (NumberFormatException e) {
            System.out.println("Invalid input.");
        }
    }

    private void displayStandings() {
        ensureLeagueStructureReadyForDisplay();
        System.out.println("\nSelect league to display:");
        System.out.println("1. Show all leagues (all tiers, full tables)");
        System.out.println("2. Browse leagues (Confederation -> Country -> League)");
        System.out.print("Choice: ");

        String choice = scanner.nextLine().trim();

        if ("1".equals(choice)) {
            List<League> all = new ArrayList<>(currentSim.allLeagues);
            all.sort(Comparator.comparing(League::getCountry)
                    .thenComparingInt(League::getLevel)
                    .thenComparing(League::getName));

            System.out.println("\n" + "=".repeat(70));
            System.out.println("ALL LEAGUE STANDINGS (ALL TIERS)");
            System.out.println("=".repeat(70));

            int rank = 1;
            for (League league : all) {
                System.out.printf("\n%d. %s (%s, Tier %d)%n",
                        rank++, league.getName(), league.getCountry(), league.getLevel());
                league.printTableSimple(league.getClubs().size());
            }
        } else if ("2".equals(choice)) {
            League selected = chooseLeagueByPyramid();
            if (selected == null) {
                return;
            }
            browseLeaguesWithinCountry(selected, "DOMESTIC LEAGUE TABLES",
                    league -> league.printTableSimple(league.getClubs().size()));
        }
    }

    private void findClub() {
        System.out.println("\n1. Search by name");
        System.out.println("2. Browse by power ranking");
        System.out.println("3. Browse club continental coefficients (Roll-5)");
        System.out.print("Choice: ");
        String mode = scanner.nextLine().trim();

        if ("2".equals(mode)) {
            browseClubsByPower();
            return;
        }
        if ("3".equals(mode)) {
            browseClubCoefficients();
            return;
        }

        System.out.print("\nEnter club name: ");
        String clubName = scanner.nextLine().trim();

        System.out.println("\n" + "=".repeat(70));
        System.out.println("CLUB STATS AND HISTORY");
        System.out.println("=".repeat(70));

        List<Club> matches = currentSim.clubIndexByName.getOrDefault(clubName.toLowerCase(), new ArrayList<>());
        if (matches.isEmpty()) {
            // Substring search fallback
            String query = clubName.toLowerCase();
            for (Map.Entry<String, List<Club>> entry : currentSim.clubIndexByName.entrySet()) {
                if (entry.getKey().contains(query)) {
                    matches.addAll(entry.getValue());
                }
            }
        }
        if (matches.isEmpty()) {
            System.out.println("Club not found: " + clubName);
            return;
        }

        Club club;
        if (matches.size() == 1) {
            club = matches.get(0);
        } else {
            club = chooseClubFromMatches(matches);
            if (club == null) {
                return;
            }
        }

        System.out.println(club.toString());
        System.out.printf("Base Rating: %.1f\n", club.getBaseRating());
        System.out.printf("Focus: %s (next change: %d)\n", club.getArchetype(), club.getNextFocusChangeYear());
        System.out.printf("Strength trend: %.2f\n", club.getStrengthTrend());
        System.out.printf("Raw strength: %.1f\n", club.getRawStrength());
        System.out.printf("Manager: %.1f | Tactical: %.1f | System fit: %.1f\n",
                club.getManagerRating(), club.getTacticalQuality(), club.getSystemFit());
        System.out.printf("Cohesion: %.1f | Availability: %.1f | Squad depth: %d\n",
                club.getCohesion(), club.getAvailability(), club.getSquadDepthPlayers());
        System.out.printf("Youth: %.1f | Development: %.1f | Recruitment: %.1f\n",
                club.getYouthRating(), club.getDevelopmentRating(), club.getRecruitmentQuality());
        System.out.printf("Financial power: %.1f | Dev quality: %.1f\n",
                club.getFinancialPower(), club.getDevelopmentQuality());
        System.out.printf("Reserves: %.1f | Revenue/yr: %.1f | Wage/wk: %.2f | Wage cap/wk: %.2f\n",
                club.getCash(), club.getRevenueSeason(), club.getWageBillWeekly(), club.getWageCapWeekly());
        System.out.printf("FFP severity: %.2f | Rolling loss (3y): %.2f | Repeat offenses: %d\n",
                club.getFfpSeverity(), club.getRollingLoss3y(), club.getRepeatOffenses());
        System.out.printf("Bad run streak: %d | Strength delta: %.2f\n",
                club.getBadRunStreak(), club.getSquadStrengthDelta());
        System.out.printf("Continental coeff Roll-5: %.3f | Last 5: %s%n",
                club.getRollingContinentalCoefficient(),
                formatCoefficientHistory(club.getContinentalCoefficientHistory()));

        // Show season-specific stats
        System.out.printf("\nSeason %d:%n", currentSeason);
        System.out.printf("  League: %s (Level %d)%n", club.getDomesticLeagueName(), club.getDomesticLevel());
        System.out.printf("  ELO: %.0f | W:%d D:%d L:%d | GF:%d GA:%d | Pts:%d%n",
                club.getEloRating(), club.getWins(), club.getDraws(), club.getLosses(),
                club.getGoalsFor(), club.getGoalsAgainst(), club.getPoints());

        printClubRecentStandings(club);
        printClubFocusHistory(club);
        printUpcomingFixtures(club);
        }

    private void printClubFocusHistory(Club club) {
        List<Club.FocusEntry> entries = club.getFocusHistory();
        if (entries.isEmpty()) {
            return;
        }
        System.out.println("\nFocus history:");
        for (int i = entries.size() - 1; i >= 0; i--) {
            Club.FocusEntry entry = entries.get(i);
            System.out.printf("  %d: %s%n", entry.getSeasonYear(), entry.getArchetype());
        }
    }

    private void printUpcomingFixtures(Club club) {
        // Get domestic league fixtures (next 10 matches)
        League league = null;
        for (League l : currentSim.allLeagues) {
            if (l.getClubs().contains(club) && l.getName().equals(club.getDomesticLeagueName())) {
                league = l;
                break;
            }
        }
        
        if (league == null) {
            System.out.println("\nUpcoming fixtures: None (league not found).");
            return;
        }
        
        System.out.println("\nNext 10 Fixtures:");
        System.out.println("-".repeat(70));
        
        // Get upcoming fixtures from the league's rounds
        List<Match> upcomingMatches = new ArrayList<>();
        int currentRound = league.getCurrentRound();
        
        // Collect fixtures from current round onwards
        for (int roundIdx = currentRound; roundIdx < Math.min(currentRound + 20, league.getRounds().size()); roundIdx++) {
            List<Match> roundMatches = league.getRounds().get(roundIdx);
            for (Match m : roundMatches) {
                if (m.getHome() == club || m.getAway() == club) {
                    upcomingMatches.add(m);
                    if (upcomingMatches.size() >= 10) {
                        break;
                    }
                }
            }
            if (upcomingMatches.size() >= 10) {
                break;
            }
        }
        
        if (upcomingMatches.isEmpty()) {
            System.out.println("  No upcoming fixtures.");
            return;
        }
        
        for (int i = 0; i < upcomingMatches.size(); i++) {
            Match m = upcomingMatches.get(i);
            String venue = m.getHome() == club ? "H" : "A";
            String opponent = m.getHome() == club ? m.getAway().getName() : m.getHome().getName();
            String oppCountry = m.getHome() == club ? m.getAway().getCountry() : m.getHome().getCountry();
            String competition = m.isCupMatch() ? "Cup" : "League";
            
            System.out.printf("  %2d. [%s] %-25s vs %-25s (%s)%n",
                    i + 1, venue, club.getName(), opponent, competition);
        }
    }

    private void browseClubsByPower() {
        System.out.println("\n1. Worldwide");
        System.out.println("2. By confederation");
        System.out.println("3. By country");
        System.out.print("Choice: ");
        String scope = scanner.nextLine().trim();

        List<Club> pool;
        String title;
        if ("2".equals(scope)) {
            Confederation conf = selectConfederation();
            if (conf == null)
                return;
            pool = new ArrayList<>();
            for (CountryAssociation ca : currentSim.countriesByConfed.getOrDefault(conf, new ArrayList<>())) {
                for (League l : ca.getAllLeagues())
                    pool.addAll(l.getClubs());
            }
            title = conf.name().replace("_", " ") + " POWER RANKINGS";
        } else if ("3".equals(scope)) {
            Confederation conf = selectConfederation();
            if (conf == null)
                return;
            CountryAssociation ca = selectCountry(conf);
            if (ca == null)
                return;
            pool = new ArrayList<>();
            for (League l : ca.getAllLeagues())
                pool.addAll(l.getClubs());
            title = ca.getName() + " POWER RANKINGS";
        } else {
            pool = new ArrayList<>(currentSim.clubIndex.values());
            title = "WORLDWIDE POWER RANKINGS";
        }

        // Display all clubs combined, sorted by strength
        pool.sort((a, b) -> Double.compare(b.getSquadStrength(), a.getSquadStrength()));
        
        int pageSize = 50;
        displayTierRankings(pool, title, pageSize);
    }

    private void browseClubCoefficients() {
        System.out.println("\n1. Worldwide");
        System.out.println("2. By confederation");
        System.out.println("3. By country");
        System.out.println("4. By league");
        System.out.print("Choice: ");
        String scope = scanner.nextLine().trim();

        List<Club> pool;
        String title;
        if ("2".equals(scope)) {
            Confederation conf = selectConfederation();
            if (conf == null) {
                return;
            }
            pool = new ArrayList<>();
            for (CountryAssociation ca : currentSim.countriesByConfed.getOrDefault(conf, new ArrayList<>())) {
                for (League league : ca.getAllLeagues()) {
                    pool.addAll(league.getClubs());
                }
            }
            title = conf.name().replace("_", " ") + " CLUB COEFFICIENTS";
        } else if ("3".equals(scope)) {
            Confederation conf = selectConfederation();
            if (conf == null) {
                return;
            }
            CountryAssociation ca = selectCountry(conf);
            if (ca == null) {
                return;
            }
            pool = new ArrayList<>();
            for (League league : ca.getAllLeagues()) {
                pool.addAll(league.getClubs());
            }
            title = ca.getName() + " CLUB COEFFICIENTS";
        } else if ("4".equals(scope)) {
            League league = chooseLeagueByPyramid();
            if (league == null) {
                return;
            }
            pool = new ArrayList<>(league.getClubs());
            title = league.getName() + " CLUB COEFFICIENTS";
        } else {
            pool = new ArrayList<>(currentSim.clubIndex.values());
            title = "WORLDWIDE CLUB COEFFICIENTS";
        }

        pool.sort((a, b) -> {
            int cmp = Double.compare(b.getRollingContinentalCoefficient(), a.getRollingContinentalCoefficient());
            if (cmp != 0) {
                return cmp;
            }
            return Double.compare(b.getEloRating(), a.getEloRating());
        });

        int page = 0;
        int pageSize = 25;
        boolean paging = true;
        while (paging) {
            int start = page * pageSize;
            if (start >= pool.size()) {
                System.out.println("No more clubs.");
                break;
            }
            int end = Math.min(pool.size(), start + pageSize);

            System.out.println("\n" + "=".repeat(105));
            System.out.printf("%-4s %-25s %-15s %7s %7s%n",
                    "Rank", "Club", "Country", "Roll5", "Last5Avg");
            System.out.println("=".repeat(105));

            for (int i = start; i < end; i++) {
                Club club = pool.get(i);
                List<Double> history = club.getContinentalCoefficientHistory();
                double avg5 = history.isEmpty() ? 0.0 : history.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
                System.out.printf("%-4d %-25s %-15s %7.3f %7.3f%n",
                        i + 1,
                        truncate(club.getName(), 25),
                        truncate(club.getCountry(), 15),
                        club.getRollingContinentalCoefficient(),
                        avg5);
            }

            System.out.println("\nN = Next | P = Previous | Q = Quit | # = View club details");
            System.out.print("Choice: ");
            String choice = scanner.nextLine().trim().toLowerCase(Locale.ROOT);
            switch (choice) {
                case "n":
                    page++;
                    break;
                case "p":
                    page = Math.max(0, page - 1);
                    break;
                case "q":
                case "":
                    paging = false;
                    break;
                default:
                    try {
                        int idx = Integer.parseInt(choice) - 1;
                        if (idx >= start && idx < end) {
                            displayClubDetails(pool.get(idx));
                        } else {
                            System.out.println("Please enter a number visible on this page.");
                        }
                    } catch (NumberFormatException e) {
                        System.out.println("Invalid option.");
                    }
                    break;
            }
        }
    }
    
    private void displayTierRankings(List<Club> tierPool, String tierTitle, int pageSize) {
         if (tierPool.isEmpty()) return;
         
         int page = 0;
         boolean paging = true;
         while (paging) {
             int start = page * pageSize;
             if (start >= tierPool.size()) {
                 System.out.println("No more clubs in this tier.");
                 break;
             }
             int end = Math.min(tierPool.size(), start + pageSize);

             System.out.println("\n" + "=".repeat(85));
             System.out.printf("%-4s %-30s %-20s %6s %6s %7s %5s%n", "Rank", tierTitle, "Country", "ELO", "Str", "CoeffR5", "Lvl");
             System.out.println("=".repeat(85));

             for (int i = start; i < end; i++) {
                 Club c = tierPool.get(i);
                 System.out.printf("%-4d %-30s %-20s %6.0f %6.1f %7.3f %5d%n",
                         i + 1, truncate(c.getName(), 30), truncate(c.getCountry(), 20),
                         c.getEloRating(), c.getSquadStrength(), c.getRollingContinentalCoefficient(), c.getDomesticLevel());
             }

             System.out.println("\nN = Next | P = Previous | Q = Quit | # = View club details");
             System.out.print("Choice: ");
             String choice = scanner.nextLine().trim().toLowerCase(Locale.ROOT);
             switch (choice) {
                 case "n":
                     page++;
                     break;
                 case "p":
                     page = Math.max(0, page - 1);
                     break;
                 case "q":
                 case "":
                     paging = false;
                     break;
                 default:
                     try {
                         int idx = Integer.parseInt(choice) - 1;
                         if (idx >= start && idx < end) {
                             displayClubDetails(tierPool.get(idx));
                         } else {
                             System.out.println("Please enter a number visible on this page.");
                         }
                     } catch (NumberFormatException e) {
                         System.out.println("Invalid option.");
                     }
                     break;
             }
             }
             }

    private void displayClubDetails(Club club) {
        System.out.println("\n" + "=".repeat(70));
        System.out.println("CLUB STATS AND HISTORY");
        System.out.println("=".repeat(70));
        System.out.println(club.toString());
        System.out.printf("Focus: %s (next change: %d)\n", club.getArchetype(), club.getNextFocusChangeYear());
        System.out.printf("Strength trend: %.2f\n", club.getStrengthTrend());
        System.out.printf("Raw strength: %.1f\n", club.getRawStrength());
        System.out.printf("Manager: %.1f | Tactical: %.1f | System fit: %.1f\n",
                club.getManagerRating(), club.getTacticalQuality(), club.getSystemFit());
        System.out.printf("Cohesion: %.1f | Availability: %.1f | Squad depth: %d\n",
                club.getCohesion(), club.getAvailability(), club.getSquadDepthPlayers());
        System.out.printf("Financial power: %.1f | Dev quality: %.1f\n",
                club.getFinancialPower(), club.getDevelopmentQuality());
        System.out.printf("ELO: %.0f | Squad Strength: %.1f%n",
                club.getEloRating(), club.getSquadStrength());
        System.out.printf("Continental coeff Roll-5: %.3f | Last 5: %s%n",
                club.getRollingContinentalCoefficient(),
                formatCoefficientHistory(club.getContinentalCoefficientHistory()));
        printClubRecentStandings(club);
    }

    private Club chooseClubFromMatches(List<Club> matches) {
        System.out.println("\nMultiple clubs found:");
        for (int i = 0; i < matches.size(); i++) {
            Club c = matches.get(i);
            System.out.printf("%d. %s (%s | %s)%n", i + 1, c.getName(), c.getCountry(), c.getDomesticLeagueName());
        }
        System.out.print("Enter number: ");
        Integer choice = readNumber();
        if (choice == null || choice < 1 || choice > matches.size()) {
            System.out.println("Invalid selection.");
            return null;
        }
        return matches.get(choice - 1);
    }

    private void printClubRecentStandings(Club club) {
        List<Club.SeasonStanding> standings = club.getRecentStandings();
        if (standings.isEmpty()) {
            System.out.println("\nNo historical standings recorded yet. Simulate seasons to build history.");
            return;
        }
        System.out.println("\nLast 10 seasons:");
        for (int i = standings.size() - 1; i >= 0; i--) {
            Club.SeasonStanding s = standings.get(i);
            System.out.printf("  %d: %s (Level %d) - Position %d, %d pts%n",
                    s.getSeasonYear(), s.getLeagueName(), s.getLeagueLevel(), s.getPosition(), s.getPoints());
        }
    }

    private void startNewSeason() {
        if (!currentSim.isSeasonCompleted()) {
            System.out.println(
                    "\nCurrent season is still in progress. Finish or simulate the season before starting a new one.");
            return;
        }
        currentSeason++;
        System.out.printf("\nStarting Season %d...%n", currentSeason);

        currentSim.applyPromotionAndRelegationForNextSeason();
        currentSim.setSeasonYear(currentSeason);
        currentSim.clearSeasonState();
        System.out.println("Season ready for simulation.");
    }

    private void viewContinentalCompetitions() {
        if (currentSim.continentalTournaments.isEmpty()) {
            System.out.println("\nNo continental competitions configured. Enable ENABLE_CONTINENTAL.");
            return;
        }

        System.out.println("\n" + "=".repeat(70));
        System.out.println("CONTINENTAL COMPETITIONS - Season " + currentSeason);
        System.out.println("=".repeat(70));

        List<Map.Entry<String, ContinentalTournament>> entries = new ArrayList<>(
                currentSim.continentalTournaments.entrySet());

        for (int i = 0; i < entries.size(); i++) {
            ContinentalTournament t = entries.get(i).getValue();
            String status;
            if (t.isComplete()) {
                status = "Champion: " + t.getChampion().getName() + " (" + t.getChampion().getCountry() + ")";
            } else {
                status = t.getCurrentStageName();
            }
            System.out.printf("  %2d. %-40s %s%n", i + 1, t.getName(), status);
        }

        System.out.println("\nEnter competition number for details (0 to go back): ");
        Integer choice = readNumber();
        if (choice == null || choice < 1 || choice > entries.size())
            return;

        ContinentalTournament selected = entries.get(choice - 1).getValue();
        String competitionKey = entries.get(choice - 1).getKey();

        // Show phase/stage selection
        System.out.println("\n" + "=".repeat(70));
        System.out.println(selected.getName());
        System.out.println("=".repeat(70));
        System.out.println("1. Play-off/League/Group Phase Fixtures");
        System.out.println("2. Knockout Round Fixtures");
        System.out.println("3. Full Standings");
        System.out.print("Choose phase to view: ");
        Integer phaseChoice = readNumber();

        if (phaseChoice == null)
            return;

        System.out.println("\n" + "=".repeat(70));
        System.out.println(selected.getName() + " - " + selected.getCurrentStageName());
        System.out.println("=".repeat(70));

        List<String> lines = selected.getStandingsLines(Integer.MAX_VALUE);
        List<String> groupLines = new ArrayList<>();
        List<String> playoffLines = new ArrayList<>();
        List<String> knockoutLines = new ArrayList<>();
        boolean inKnockoutSection = false;
        boolean inPlayoffSection = false;
        for (String line : lines) {
            String trimmed = line.trim();
            if ("Knockout Playoffs:".equals(trimmed)) {
                inPlayoffSection = true;
            } else if (inPlayoffSection && trimmed.isEmpty()) {
                inPlayoffSection = false;
            }
            if (isKnockoutHeader(line)) {
                inKnockoutSection = true;
                inPlayoffSection = false;
            }
            if (inKnockoutSection) {
                knockoutLines.add(line);
            } else if (inPlayoffSection) {
                playoffLines.add(line);
            } else {
                groupLines.add(line);
            }
        }

        if (phaseChoice == 1) {
            // Qualifying/group phase - filter for league-like structure
            System.out.println("\n[Play-off / League / Group Phase]");
            System.out.println("1. Entrants");
            System.out.println("2. Qualifying matches");
            System.out.println("3. League/Group standings");
            System.out.println("4. Play-off fixtures");
            System.out.println("5. Play-off fixtures + League/Group standings");
            System.out.print("Choice: ");
            Integer groupChoice = readNumber();
            if (groupChoice == null) {
                return;
            }
            if (groupChoice == 1) {
                currentSim.printContinentalEntrants(competitionKey);
                return;
            }
            if (groupChoice == 2) {
                currentSim.printContinentalQualifyingMatches(competitionKey);
                return;
            }
            if (groupChoice == 4 || groupChoice == 5) {
                for (String line : playoffLines) {
                    System.out.println(line);
                }
            }
            if (groupChoice == 3 || groupChoice == 5) {
                if (!playoffLines.isEmpty() && groupChoice == 5) {
                    System.out.println();
                }
                for (String line : groupLines) {
                    System.out.println(line);
                }
            }
        } else if (phaseChoice == 2) {
            // Knockout phase - filter for knockout structure
            System.out.println("\n[Knockout Rounds]");
            for (String line : knockoutLines) {
                System.out.println(line);
            }
        } else {
            // Full standings
            for (String line : lines) {
                System.out.println(line);
            }
        }

        if (selected.isComplete()) {
            System.out.println("\nChampion: " + selected.getChampion().getName()
                    + " (" + selected.getChampion().getCountry() + ")");
        }

    }

    private boolean isKnockoutHeader(String line) {
        if (line == null) {
            return false;
        }
        String trimmed = line.trim();
        if (!trimmed.endsWith(":")) {
            return false;
        }
        if (trimmed.equals("Knockout Playoffs:")) {
            return false;
        }
        return trimmed.startsWith("Final")
                || trimmed.startsWith("Semi-Finals")
                || trimmed.startsWith("Quarter-Finals")
                || trimmed.startsWith("Round of")
                || trimmed.startsWith("Round ");
    }

    private void viewCoefficients() {
        System.out.println("\n1. Worldwide rankings");
        System.out.println("2. By confederation");
        System.out.print("Choice: ");
        String scopeChoice = scanner.nextLine().trim();

        List<CountryAssociation> pool;
        String title;
        if ("2".equals(scopeChoice)) {
            Confederation conf = selectConfederation();
            if (conf == null)
                return;
            pool = new ArrayList<>(currentSim.countriesByConfed.getOrDefault(conf, new ArrayList<>()));
            title = conf.name().replace("_", " ") + " COEFFICIENTS";
        } else {
            pool = new ArrayList<>(currentSim.countries.values());
            title = "WORLDWIDE COEFFICIENTS";
        }

        // Build display data
        List<String[]> rows = new ArrayList<>();
        for (CountryAssociation ca : pool) {
            List<Double> history = ca.getSeasonCoefficientHistory();
            double[] lastFive = new double[5];
            int copy = Math.min(5, history.size());
            for (int i = 0; i < copy; i++) {
                lastFive[5 - copy + i] = history.get(history.size() - copy + i);
            }
            double roll5 = lastFive[0] + lastFive[1] + lastFive[2] + lastFive[3] + lastFive[4];
            String confLabel = ca.getConfederation().name().replace("_", " ");
            rows.add(new String[] {
                    ca.getName(),
                    confLabel,
                    String.format("%.3f", lastFive[0]),
                    String.format("%.3f", lastFive[1]),
                    String.format("%.3f", lastFive[2]),
                    String.format("%.3f", lastFive[3]),
                    String.format("%.3f", lastFive[4]),
                    String.format("%.3f", roll5)
            });
        }

        rows.sort((a, b) -> {
            double va = Double.parseDouble(a[7]);
            double vb = Double.parseDouble(b[7]);
            return Double.compare(vb, va);
        });

        int page = 0;
        int pageSize = 30;
        boolean paging = true;
        while (paging) {
            int start = page * pageSize;
            if (start >= rows.size()) {
                System.out.println("No more countries.");
                break;
            }
            int end = Math.min(rows.size(), start + pageSize);

            int latestCoefficientYear = currentSim.getSeasonYear() - (currentSim.isSeasonCompleted() ? 0 : 1);
            int y1 = latestCoefficientYear - 4;
            int y2 = latestCoefficientYear - 3;
            int y3 = latestCoefficientYear - 2;
            int y4 = latestCoefficientYear - 1;
            int y5 = latestCoefficientYear;

            System.out.println("\n" + "=".repeat(70));
            System.out.println(title + " (Last 5 Seasons + Rolling-5 Total)");
            System.out.printf("%-4s %-22s %-15s %8d %8d %8d %8d %8d %10s%n",
                    "Rank", "Country", "Confed", y1, y2, y3, y4, y5, "Roll5");
            System.out.println("=".repeat(70));

            for (int i = start; i < end; i++) {
                String[] row = rows.get(i);
                System.out.printf("%-4d %-22s %-15s %8s %8s %8s %8s %8s %10s%n",
                        i + 1,
                        truncate(row[0], 22),
                        truncate(row[1], 15),
                        row[2], row[3], row[4], row[5], row[6], row[7]);
            }

            System.out.println("\nN = Next | P = Previous | Q = Quit");
            System.out.print("Choice: ");
            String choice = scanner.nextLine().trim().toLowerCase(Locale.ROOT);
            switch (choice) {
                case "n":
                    page++;
                    break;
                case "p":
                    page = Math.max(0, page - 1);
                    break;
                case "q":
                case "":
                    paging = false;
                    break;
                default:
                    System.out.println("Invalid option.");
                    break;
            }
        }

        System.out.println("\nCoefficients determine continental cup slot allocation.");
    }

    private void showWinnersByLeague() {
        System.out.println("\nChoose league:");
        League selected = chooseLeagueByPyramid();
        if (selected == null) {
            return;
        }
        displayLeagueWinnersFromCsv(selected);
    }

    private void showWinnersByCup() {
        System.out.println("\n1. Domestic cup winners");
        System.out.println("2. Continental competition winners");
        System.out.print("Choice: ");
        String choice = scanner.nextLine().trim();

        if ("2".equals(choice)) {
            showContinentalWinners();
        } else if ("1".equals(choice)) {
            showDomesticCupWinners();
        } else {
            System.out.println("Invalid option.");
        }
    }

    private void showDomesticCupWinners() {
        System.out.print("\nEnter country for cup winners (e.g., England): ");
        String country = scanner.nextLine().trim();
        if (country.isEmpty()) {
            System.out.println("No country entered.");
            return;
        }

        Path path = Paths.get(currentSim.getExportFolder(), "cup_winners.csv");
        if (!Files.exists(path)) {
            System.out.println("No cup winners recorded yet. Simulate seasons to populate history.");
            return;
        }

        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String scopeRow = reader.readLine();
            String countryRow = reader.readLine();
            String cupRow = reader.readLine();
            if (scopeRow == null || countryRow == null || cupRow == null) {
                System.out.println("Cup winners file is missing headers.");
                return;
            }

            List<String> scopeCols = OptaReader.parseCsvLinePublic(scopeRow);
            List<String> countryCols = OptaReader.parseCsvLinePublic(countryRow);
            List<String> cupCols = OptaReader.parseCsvLinePublic(cupRow);

            List<Integer> indices = new ArrayList<>();
            List<String> cupNames = new ArrayList<>();
            for (int i = 1; i < countryCols.size(); i++) {
                if (countryCols.get(i).trim().equalsIgnoreCase(country)) {
                    indices.add(i);
                    cupNames.add(i < cupCols.size() ? cupCols.get(i).trim() : "Cup");
                }
            }

            if (indices.isEmpty()) {
                System.out.println("No cups found for " + country + ".");
                return;
            }

            Map<String, List<Integer>> winYears = new HashMap<>();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty())
                    continue;
                List<String> cols = OptaReader.parseCsvLinePublic(line);
                int year = parseYear(cols.get(0));
                if (year <= 0)
                    continue;
                for (int i = 0; i < indices.size(); i++) {
                    int idx = indices.get(i);
                    if (idx >= cols.size())
                        continue;
                    String winner = cols.get(idx).trim();
                    if (winner.isEmpty())
                        continue;
                    String cup = cupNames.get(i);
                    winYears.computeIfAbsent(cup + "|" + winner, k -> new ArrayList<>()).add(year);
                }
            }

            if (winYears.isEmpty()) {
                System.out.println("No cup winners recorded yet. Simulate seasons to populate history.");
                return;
            }

            System.out.println("\n" + "=".repeat(70));
            System.out.printf("Domestic Cup Winners for %s%n", country);
            System.out.println("=".repeat(70));

            Map<String, Map<String, List<Integer>>> byCup = new HashMap<>();
            for (Map.Entry<String, List<Integer>> entry : winYears.entrySet()) {
                String[] parts = entry.getKey().split("\\|", 2);
                String cup = parts.length > 0 ? parts[0] : "Cup";
                String club = parts.length > 1 ? parts[1] : "";
                byCup.computeIfAbsent(cup, k -> new HashMap<>()).put(club, entry.getValue());
            }

            for (Map.Entry<String, Map<String, List<Integer>>> cupEntry : byCup.entrySet()) {
                System.out.println("\n" + cupEntry.getKey());
                System.out.println("-".repeat(cupEntry.getKey().length()));
                cupEntry.getValue().entrySet().stream()
                        .sorted((a, b) -> b.getValue().size() - a.getValue().size())
                        .forEach(e -> {
                            List<Integer> yearsList = new ArrayList<>(e.getValue());
                            yearsList.sort(Integer::compareTo);
                            String years = yearsList.stream()
                                    .map(Object::toString)
                                    .reduce((a, b) -> a + ", " + b)
                                    .orElse("");
                            System.out.printf("%d - %s (%s)%n", yearsList.size(), e.getKey(), years);
                        });
            }
        } catch (IOException e) {
            System.out.println("Failed to read cup winners export: " + e.getMessage());
        }
    }

    private void showContinentalWinners() {
        if (currentSim.continentalTournaments.isEmpty()) {
            System.out.println("\nNo continental competitions configured.");
            return;
        }

        // List all continental competitions
        List<Map.Entry<String, ContinentalTournament>> comps = new ArrayList<>(
                currentSim.continentalTournaments.entrySet());

        System.out.println("\nSelect competition:");
        for (int i = 0; i < comps.size(); i++) {
            System.out.printf("%d. %s%n", i + 1, comps.get(i).getValue().getName());
        }
        System.out.print("Enter number: ");
        Integer idx = readNumber();
        if (idx == null || idx < 1 || idx > comps.size()) {
            System.out.println("Invalid selection.");
            return;
        }

        ContinentalTournament selected = comps.get(idx - 1).getValue();
        String compName = selected.getName();

        // Read from run-scoped club winners export (fallback: legacy season_winners.csv)
        Path path = resolveClubWinnersPath();
        if (!Files.exists(path)) {
            System.out.println("No winners recorded yet. Simulate seasons to populate history.");
            return;
        }

        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String levelRow = reader.readLine();
            String countryRow = reader.readLine();
            String leagueRow = reader.readLine();
            if (levelRow == null || countryRow == null || leagueRow == null) {
                System.out.println("Winners file is missing headers.");
                return;
            }

            List<String> leagueCols = OptaReader.parseCsvLinePublic(leagueRow);

            int targetIndex = -1;
            // First try exact match
            for (int i = 1; i < leagueCols.size(); i++) {
                if (leagueCols.get(i).trim().equalsIgnoreCase(compName)) {
                    targetIndex = i;
                    break;
                }
            }
            
            // If not found, try case-insensitive partial match (for format variations)
            if (targetIndex < 0) {
                for (int i = 1; i < leagueCols.size(); i++) {
                    String colName = leagueCols.get(i).trim();
                    // Check if it's the same competition with slight variations
                    if (colName.toLowerCase().contains(compName.toLowerCase()) || 
                        compName.toLowerCase().contains(colName.toLowerCase())) {
                        targetIndex = i;
                        break;
                    }
                }
            }

            if (targetIndex < 0) {
                System.out.println("Competition not found in winners export: " + compName);
                System.out.println("Available competitions: ");
                for (int i = 1; i < Math.min(leagueCols.size(), 11); i++) {
                    System.out.println("  - " + leagueCols.get(i).trim());
                }
                return;
            }

            Map<String, List<Integer>> winYears = new HashMap<>();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty())
                    continue;
                List<String> cols = OptaReader.parseCsvLinePublic(line);
                if (cols.size() <= targetIndex)
                    continue;
                int year = parseYear(cols.get(0));
                if (year <= 0)
                    continue;
                String winner = cols.get(targetIndex).trim();
                if (winner.isEmpty())
                    continue;
                winYears.computeIfAbsent(winner, k -> new ArrayList<>()).add(year);
            }

            if (winYears.isEmpty()) {
                System.out.println("No winners recorded yet. Simulate seasons to populate history.");
                return;
            }

            System.out.println("\n" + "=".repeat(70));
            System.out.printf("Winners of %s%n", compName);
            System.out.println("=".repeat(70));

            winYears.entrySet().stream()
                    .sorted((a, b) -> b.getValue().size() - a.getValue().size())
                    .forEach(e -> {
                        List<Integer> yearsList = new ArrayList<>(e.getValue());
                        yearsList.sort(Integer::compareTo);
                        String years = yearsList.stream()
                                .map(Object::toString)
                                .reduce((a2, b2) -> a2 + ", " + b2)
                                .orElse("");
                        ClubToken token = parseClubToken(e.getKey());
                        Club found = resolveClubByName(token.name, selected.getConfederation(), token.country);
                        String country = token.country != null ? token.country
                                : (found != null ? found.getCountry() : null);
                        String countryLabel = country != null ? " (" + country + ")" : "";
                        System.out.printf("%d - %s%s (%s)%n", yearsList.size(), token.name, countryLabel, years);
                    });
        } catch (IOException e) {
            System.out.println("Failed to read winners export: " + e.getMessage());
        }
    }

    private void viewDomesticCupMatches() {
        ensureLeagueStructureReadyForDisplay();
        System.out.println("\nChoose a starting league for domestic cup browsing:");
        League selected = chooseLeagueByPyramid();
        if (selected == null) {
            return;
        }
        browseLeaguesWithinCountry(selected, "DOMESTIC CUP MATCHES",
                league -> currentSim.printDomesticCupRound(league.getCountry()));
    }

    private void viewDomesticCups() {
        System.out.println("\n1. View domestic cup matches (browse by league)");
        System.out.println("2. View super cup results");
        System.out.print("Choice: ");
        String choice = scanner.nextLine().trim();
        if ("2".equals(choice)) {
            viewSuperCupResults();
        } else if ("1".equals(choice)) {
            viewDomesticCupMatches();
        } else {
            System.out.println("Invalid option.");
        }
    }

    private void viewSuperCupResults() {
        System.out.println("\n1. View all super cup results");
        System.out.println("2. View by country");
        System.out.print("Choice: ");
        String choice = scanner.nextLine().trim();
        if ("1".equals(choice)) {
            currentSim.printAllDomesticSuperCupResults();
        } else if ("2".equals(choice)) {
            System.out.print("Enter country (e.g., England): ");
            String country = scanner.nextLine().trim();
            if (!country.isEmpty()) {
                currentSim.printDomesticSuperCupResults(country);
            }
        } else {
            System.out.println("Invalid option.");
        }
    }

    private void ensureSeasonInitialized() {
        if (currentSim.isSeasonInitialized()) {
            return;
        }

        List<String> warnings = currentSim.validateLeagueIntegrity();
        if (!warnings.isEmpty()) {
            System.err.println("\n=== LEAGUE INTEGRITY WARNINGS ===");
            for (String w : warnings) {
                System.err.println(w);
            }
            System.err.println("=================================\n");
            currentSim.exportIntegrityWarnings(currentSeason, warnings);
        }

        currentSim.forceAssignAllRemainingClubs();
        currentSim.rebalanceLeagueSizesToTargets();
        currentSim.startSeason();
    }

    private void ensureLeagueStructureReadyForDisplay() {
        if (currentSim.isSeasonInitialized() || currentSim.isSeasonCompleted()) {
            return;
        }

        currentSim.forceAssignAllRemainingClubs();
        currentSim.rebalanceLeagueSizesToTargets();
    }

    private void displayLeagueWinnersFromCsv(League league) {
        System.out.println("\n" + "=".repeat(70));
        System.out.printf("Winners for %s (%s)%n", league.getName(), league.getCountry());
        System.out.println("=".repeat(70));

        Path path = resolveClubWinnersPath();
        if (!Files.exists(path)) {
            System.out.println("No winners recorded yet. Simulate seasons to populate history.");
            return;
        }

        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String levelRow = reader.readLine();
            String countryRow = reader.readLine();
            String leagueRow = reader.readLine();
            if (levelRow == null || countryRow == null || leagueRow == null) {
                System.out.println("Winners file is missing headers.");
                return;
            }

            List<String> levelCols = OptaReader.parseCsvLinePublic(levelRow);
            List<String> countryCols = OptaReader.parseCsvLinePublic(countryRow);
            List<String> leagueCols = OptaReader.parseCsvLinePublic(leagueRow);
            int targetIndex = findLeagueColumn(countryCols, leagueCols, league.getCountry(), league.getName());
            if (targetIndex < 0) {
                System.out.println("League not found in winners export.");
                return;
            }

            Map<String, List<Integer>> winYears = new HashMap<>();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty())
                    continue;
                List<String> cols = OptaReader.parseCsvLinePublic(line);
                if (cols.size() <= targetIndex)
                    continue;
                int year = parseYear(cols.get(0));
                if (year <= 0)
                    continue;
                String winner = cols.get(targetIndex).trim();
                if (winner.isEmpty())
                    continue;
                winYears.computeIfAbsent(winner, k -> new ArrayList<>()).add(year);
            }

            if (winYears.isEmpty()) {
                System.out.println("No winners recorded yet. Simulate seasons to populate history.");
                return;
            }

            winYears.entrySet().stream()
                    .sorted((a, b) -> b.getValue().size() - a.getValue().size())
                    .forEach(e -> {
                        List<Integer> yearsList = new ArrayList<>(e.getValue());
                        yearsList.sort(Integer::compareTo);
                        String years = yearsList.stream()
                                .map(Object::toString)
                                .reduce((a, b) -> a + ", " + b)
                                .orElse("");
                        System.out.printf("%d - %s (%s)%n", yearsList.size(), e.getKey(), years);
                    });
        } catch (IOException e) {
            System.out.println("Failed to read winners export: " + e.getMessage());
        }
    }

    private int findLeagueColumn(List<String> countryCols, List<String> leagueCols, String country, String leagueName) {
        // First try exact match
        for (int i = 1; i < leagueCols.size(); i++) {
            String league = leagueCols.get(i).trim();
            String c = (i < countryCols.size()) ? countryCols.get(i).trim() : "";
            if (league.equalsIgnoreCase(leagueName) && c.equalsIgnoreCase(country)) {
                return i;
            }
        }
        // If exact match fails, try matching without the country prefix
        // (in case league name includes country like "England - Premier League")
        String leagueWithoutCountry = leagueName.replaceFirst("^" + country + "\\s*-\\s*", "").trim();
        for (int i = 1; i < leagueCols.size(); i++) {
            String league = leagueCols.get(i).trim();
            String c = (i < countryCols.size()) ? countryCols.get(i).trim() : "";
            if (c.equalsIgnoreCase(country) && 
                (league.equalsIgnoreCase(leagueWithoutCountry) || 
                 (country + " - " + league).equalsIgnoreCase(leagueName))) {
                return i;
            }
        }
        return -1;
    }

    private int parseYear(String value) {
        if (value == null || value.trim().isEmpty())
            return -1;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private Path resolveClubWinnersPath() {
        Path clubWinners = Paths.get(currentSim.getExportFolder(), "club_winners.csv");
        if (Files.exists(clubWinners)) {
            return clubWinners;
        }
        return Paths.get(currentSim.getExportFolder(), "season_winners.csv");
    }

    private void displaySeasonSummary() {
        System.out.println("\n" + "=".repeat(70));
        System.out.printf("SEASON %d SUMMARY%n", currentSeason);
        System.out.println("=".repeat(70));
        List<CountryAssociation> ranked = new ArrayList<>(currentSim.countries.values());
        ranked.sort((a, b) -> Double.compare(b.getRollingCoefficient(), a.getRollingCoefficient()));
        int shown = 0;

        System.out.println("\n[TOP LEAGUE STANDINGS]");
        for (CountryAssociation ca : ranked) {
            if (shown >= 3)
                break;
            League league = ca.getTopLeague();
            if (league == null)
                continue;
            shown++;
            System.out.printf("\n%s - %s (Coeff %.3f)%n", league.getName(), ca.getName(), ca.getRollingCoefficient());
            league.printTableSimple(5);
        }
    }

    private void recordSeasonHistory(int seasonYear) {
        if (seasonHistory.containsKey(seasonYear)) {
            return;
        }
        SeasonRecord record = new SeasonRecord(seasonYear);

        for (League league : currentSim.allLeagues) {
            league.sortTable();
            LeagueKey key = new LeagueKey(league.getName(), league.getCountry());
            List<String> participants = new ArrayList<>();
            for (Club club : league.getClubs()) {
                participants.add(club.getName());
            }
            record.leagueParticipants.put(key, participants);

            Club champ = league.getChampion();
            if (champ != null) {
                record.leagueChampions.put(key, champ.getName());
            }
        }

        for (Map.Entry<String, KnockoutCup> entry : currentSim.domesticCups.entrySet()) {
            KnockoutCup cup = entry.getValue();
            Club champ = cup.getChampion();
            if (champ != null) {
                record.domesticCupChampions.put(cup.getName(), champ.getName());
            }

            Set<String> finalists = new LinkedHashSet<>();
            List<KnockoutCup.RoundResult> rounds = cup.getRoundHistory();
            if (!rounds.isEmpty()) {
                KnockoutCup.RoundResult finalRound = rounds.get(rounds.size() - 1);
                for (Match match : finalRound.matches) {
                    finalists.add(match.getHome().getName());
                    finalists.add(match.getAway().getName());
                }
            }
            if (!finalists.isEmpty()) {
                record.domesticCupFinalists.put(cup.getName(), new ArrayList<>(finalists));
            }
        }

        for (ContinentalTournament ct : currentSim.continentalTournaments.values()) {
            Club champ = ct.getChampion();
            if (champ != null) {
                record.continentalChampions.put(ct.getName(), champ.getName() + " (" + champ.getCountry() + ")");
            }
        }

        seasonHistory.put(seasonYear, record);
        updateLeagueStatRecords(seasonYear);
        if (seasonHistory.size() > MAX_SEASON_HISTORY) {
            int oldestYear = seasonHistory.keySet().iterator().next();
            seasonHistory.remove(oldestYear);
        }
        lastRecordedSeason = Math.max(lastRecordedSeason, seasonYear);
    }

    private void updateLeagueStatRecords(int seasonYear) {
        for (League league : currentSim.allLeagues) {
            league.sortTable();
            LeagueKey key = new LeagueKey(league.getName(), league.getCountry());
            LeagueStatBoard board = leagueStatBoards.computeIfAbsent(key, k -> new LeagueStatBoard());
            for (Club club : league.getClubs()) {
                updateStatLeaderboard(board.mostPoints, club, league, seasonYear, club.getPoints(), true);
                updateStatLeaderboard(board.leastPoints, club, league, seasonYear, club.getPoints(), false);
                updateStatLeaderboard(board.mostWins, club, league, seasonYear, club.getWins(), true);
                updateStatLeaderboard(board.leastWins, club, league, seasonYear, club.getWins(), false);
                updateStatLeaderboard(board.mostDraws, club, league, seasonYear, club.getDraws(), true);
                updateStatLeaderboard(board.leastDraws, club, league, seasonYear, club.getDraws(), false);
                updateStatLeaderboard(board.mostLosses, club, league, seasonYear, club.getLosses(), true);
                updateStatLeaderboard(board.leastLosses, club, league, seasonYear, club.getLosses(), false);
            }
        }
    }

    private void updateStatLeaderboard(StatLeaderboard leaderboard, Club club, League league, int seasonYear,
            int value, boolean preferHigh) {
        StatRecord record = new StatRecord(club.getName(), league.getName(), league.getCountry(), seasonYear, value);
        leaderboard.update(record, preferHigh);
    }

    private void displayLeagueCupStatistics() {
        if (seasonHistory.isEmpty()) {
            System.out.println("\nNo seasons recorded yet.");
            return;
        }

        System.out.println("\n1. Browse by league");
        System.out.println("2. Browse by domestic cup");
        System.out.println("3. Browse by continental cup");
        System.out.println("4. Browse combined league + cup");
        System.out.print("Choose category: ");
        String choice = scanner.nextLine().trim();

        if ("1".equals(choice)) {
            displayLeagueStatistics();
        } else if ("2".equals(choice)) {
            displayDomesticCupStatistics();
        } else if ("3".equals(choice)) {
            displayContinentalCupStatistics();
        } else if ("4".equals(choice)) {
            displayCombinedLeagueCupStatistics();
        } else {
            System.out.println("Invalid option.");
        }
    }

    private void displayLeagueStatistics() {
        League league = chooseLeagueByPyramid();
        if (league == null) {
            return;
        }

        LeagueKey target = new LeagueKey(league.getName(), league.getCountry());

        Map<String, Integer> leagueTitles = new LinkedHashMap<>();
        Map<String, Integer> longestLeagueAppearanceStreak = new LinkedHashMap<>();
        Map<String, Integer> activeStreak = new HashMap<>();
        Map<String, Integer> lastSeenSeason = new HashMap<>();

        List<Integer> years = new ArrayList<>(seasonHistory.keySet());
        years.sort(Integer::compareTo);

        for (int year : years) {
            SeasonRecord record = seasonHistory.get(year);

            String leagueChampion = record.leagueChampions.get(target);
            if (leagueChampion != null && !leagueChampion.isBlank()) {
                leagueTitles.merge(leagueChampion, 1, Integer::sum);
            }

            List<String> participants = record.leagueParticipants.getOrDefault(target, List.of());
            Set<String> participantSet = new HashSet<>(participants);
            for (String club : participants) {
                int previousYear = lastSeenSeason.getOrDefault(club, Integer.MIN_VALUE);
                int streak = (previousYear == year - 1) ? activeStreak.getOrDefault(club, 0) + 1 : 1;
                activeStreak.put(club, streak);
                lastSeenSeason.put(club, year);
                longestLeagueAppearanceStreak.merge(club, streak, Math::max);
            }

            for (String trackedClub : new ArrayList<>(activeStreak.keySet())) {
                if (!participantSet.contains(trackedClub)) {
                    activeStreak.remove(trackedClub);
                }
            }
        }

        LeagueStatBoard board = leagueStatBoards.get(target);

        System.out.println("\n" + "=".repeat(70));
        System.out.printf("LEAGUE STATISTICS - %s (%s)%n", league.getName(), league.getCountry());
        System.out.println("=".repeat(70));

        printCountLeaders("Most league titles won (League)", leagueTitles);
        printCountLeaders("Most season consecutive appearances (League)", longestLeagueAppearanceStreak);

        if (board == null) {
            System.out.println("\nNo historical league stat records available yet.");
            return;
        }

        printStatLeaders("Most wins (League)", board.mostWins);
        printStatLeaders("Most draws (League)", board.mostDraws);
        printStatLeaders("Most losses (League)", board.mostLosses);
        printStatLeaders("Least wins (League)", board.leastWins);
        printStatLeaders("Least draws (League)", board.leastDraws);
        printStatLeaders("Least losses (League)", board.leastLosses);
        printStatLeaders("Most points in a season (League)", board.mostPoints);
        printStatLeaders("Least points in a season (League)", board.leastPoints);
    }

    private void displayDomesticCupStatistics() {
        Confederation conf = selectConfederation();
        if (conf == null) {
            return;
        }
        CountryAssociation country = selectCountry(conf);
        if (country == null) {
            return;
        }

        KnockoutCup cup = currentSim.domesticCups.get(country.getName());
        if (cup == null) {
            System.out.println("\nNo domestic cup available for " + country.getName() + ".");
            return;
        }

        System.out.println("\n" + "=".repeat(70));
        System.out.printf("DOMESTIC CUP STATISTICS - %s%n", cup.getName());
        System.out.println("=".repeat(70));

        Map<String, Integer> cupWinnersByClub = new HashMap<>();
        Map<String, Integer> finalAppearancesByClub = new HashMap<>();
        for (SeasonRecord record : seasonHistory.values()) {
            String champ = record.domesticCupChampions.get(cup.getName());
            if (champ != null && !champ.isBlank()) {
                cupWinnersByClub.merge(champ, 1, Integer::sum);
            }
            for (String finalist : record.domesticCupFinalists.getOrDefault(cup.getName(), List.of())) {
                if (finalist != null && !finalist.isBlank()) {
                    finalAppearancesByClub.merge(finalist, 1, Integer::sum);
                }
            }
        }

        printCountLeaders("Most tournament won (Cup)", cupWinnersByClub);
        printCountLeaders("Most final appearances (Cup)", finalAppearancesByClub);
    }

    private void displayCombinedLeagueCupStatistics() {
        League league = chooseLeagueByPyramid();
        if (league == null) {
            return;
        }

        String domesticCupName = league.getCountry() + " Cup";
        LeagueKey target = new LeagueKey(league.getName(), league.getCountry());
        Map<String, Integer> tournamentTitles = new LinkedHashMap<>();

        for (SeasonRecord record : seasonHistory.values()) {
            String leagueChampion = record.leagueChampions.get(target);
            if (leagueChampion != null && !leagueChampion.isBlank()) {
                tournamentTitles.merge(leagueChampion, 1, Integer::sum);
            }

            String domesticCupChampion = record.domesticCupChampions.get(domesticCupName);
            if (domesticCupChampion != null && !domesticCupChampion.isBlank()) {
                tournamentTitles.merge(domesticCupChampion, 1, Integer::sum);
            }
        }

        System.out.println("\n" + "=".repeat(70));
        System.out.printf("COMBINED STATISTICS - %s (%s)%n", league.getName(), league.getCountry());
        System.out.println("=".repeat(70));

        printCountLeaders("Most tournament won (League + Cup)", tournamentTitles);
    }

    private void displayContinentalCupStatistics() {
        Confederation conf = selectConfederation();
        if (conf == null) {
            return;
        }

        List<ContinentalTournament> comps = new ArrayList<>();
        for (ContinentalTournament ct : currentSim.continentalTournaments.values()) {
            if (ct.getConfederation() == conf) {
                comps.add(ct);
            }
        }
        if (comps.isEmpty()) {
            System.out.println("\nNo continental competitions available.");
            return;
        }

        System.out.println("\nSelect competition:");
        for (int i = 0; i < comps.size(); i++) {
            System.out.printf("%d. %s%n", i + 1, comps.get(i).getName());
        }
        System.out.print("Enter number: ");
        Integer idx = readNumber();
        if (idx == null || idx < 1 || idx > comps.size()) {
            System.out.println("Invalid selection.");
            return;
        }

        ContinentalTournament selected = comps.get(idx - 1);
        System.out.println("\n" + "=".repeat(70));
        System.out.printf("CONTINENTAL CUP STATISTICS - %s%n", selected.getName());
        System.out.println("=".repeat(70));

        Map<String, Integer> cupWinnersByClub = new HashMap<>();
        for (SeasonRecord record : seasonHistory.values()) {
            String champ = record.continentalChampions.get(selected.getName());
            if (champ == null || champ.isBlank()) {
                continue;
            }
            ClubToken token = parseClubToken(champ);
            cupWinnersByClub.merge(token.name, 1, Integer::sum);
        }

        printCountLeaders("Most tournament won (Cup)", cupWinnersByClub);
    }

    private Club resolveClubByName(String clubName, Confederation confederation, String country) {
        if (clubName == null) {
            return null;
        }
        List<Club> matches = currentSim.clubIndexByName.getOrDefault(clubName.toLowerCase(), List.of());
        if (matches.isEmpty()) {
            return null;
        }
        if (country != null && !country.isBlank()) {
            for (Club match : matches) {
                if (country.equalsIgnoreCase(match.getCountry())) {
                    return match;
                }
            }
        }
        List<Club> filtered = new ArrayList<>();
        if (confederation != null) {
            for (Club match : matches) {
                if (match.getConfederation() == confederation) {
                    filtered.add(match);
                }
            }
        }
        List<Club> pool = filtered.isEmpty() ? matches : filtered;
        return pool.stream()
                .max(Comparator.comparingDouble(Club::getEloRating))
                .orElse(pool.get(0));
    }

    private ClubToken parseClubToken(String raw) {
        if (raw == null) {
            return new ClubToken("", null);
        }
        String trimmed = raw.trim();
        int bracketStart = trimmed.lastIndexOf("[");
        int bracketEnd = trimmed.lastIndexOf("]");
        if (bracketStart >= 0 && bracketEnd > bracketStart) {
            String name = trimmed.substring(0, bracketStart).trim();
            String country = trimmed.substring(bracketStart + 1, bracketEnd).trim();
            return new ClubToken(name, country.isEmpty() ? null : country);
        }
        String name = trimmed.replaceAll("\\s*\\([^)]*\\)\\s*$", "").trim();
        return new ClubToken(name, null);
    }

    private static final class ClubToken {
        private final String name;
        private final String country;

        private ClubToken(String name, String country) {
            this.name = name;
            this.country = country;
        }
    }

    private void printCountLeaders(String label, Map<String, Integer> counts) {
        System.out.print("\n[" + label + "] ");
        if (counts.isEmpty()) {
            System.out.println("No data.");
            return;
        }

        int best = counts.values().stream().max(Integer::compareTo).orElse(0);
        List<String> clubs = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() == best) {
                clubs.add(entry.getKey());
            }
        }
        clubs.sort(String::compareTo);
        System.out.println(String.join(", ", clubs) + " - " + best);
    }

    private void printStatLeaders(String label, StatLeaderboard leaderboard) {
        System.out.print("\n[" + label + "] ");
        if (leaderboard == null || leaderboard.getLeaders().isEmpty()) {
            System.out.println("No data.");
            return;
        }

        List<String> entries = new ArrayList<>();
        for (StatRecord record : leaderboard.getLeaders()) {
            entries.add(String.format("%s (%d)", record.club, record.seasonYear));
        }
        entries.sort(String::compareTo);
        System.out.println(String.join(", ", entries) + " - " + leaderboard.getBestValue());
    }

    private double avgElo(List<Club> clubs) {
        if (clubs.isEmpty())
            return 0;
        return clubs.stream().mapToDouble(Club::getEloRating).average().orElse(0);
    }

    private String truncate(String s, int n) {
        if (s.length() <= n)
            return s;
        return s.substring(0, n - 3) + "...";
    }

    private String formatCoefficientHistory(List<Double> history) {
        double[] lastFive = new double[5];
        int copy = Math.min(5, history.size());
        for (int i = 0; i < copy; i++) {
            lastFive[5 - copy + i] = history.get(history.size() - copy + i);
        }
        return String.format("%.2f %.2f %.2f %.2f %.2f",
                lastFive[0], lastFive[1], lastFive[2], lastFive[3], lastFive[4]);
    }

    public static void configureUtf8Output() {
        try {
            if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")) {
                try {
                    new ProcessBuilder("cmd", "/c", "chcp 65001 >NUL")
                            .inheritIO()
                            .start()
                            .waitFor();
                } catch (IOException | InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8));
        } catch (Exception e) {
            // Best-effort only; fall back to default encoding.
        }
    }

    private League chooseLeagueByPyramid() {
        Confederation conf = selectConfederation();
        if (conf == null) {
            return null;
        }

        CountryAssociation country = selectCountry(conf);
        if (country == null) {
            return null;
        }

        return selectLeague(country);
    }

    private void browseLeaguesWithinCountry(League startingLeague, String title, Consumer<League> renderer) {
        List<League> leagues = new ArrayList<>();
        for (League league : currentSim.allLeagues) {
            if (league.getCountry().equalsIgnoreCase(startingLeague.getCountry())) {
                leagues.add(league);
            }
        }
        leagues.sort(Comparator.comparingInt(League::getLevel).thenComparing(League::getName));
        if (leagues.isEmpty()) {
            System.out.println("No leagues found for " + startingLeague.getCountry() + ".");
            return;
        }

        int index = 0;
        for (int i = 0; i < leagues.size(); i++) {
            League league = leagues.get(i);
            if (league.getName().equals(startingLeague.getName())
                    && league.getLevel() == startingLeague.getLevel()) {
                index = i;
                break;
            }
        }

        boolean browsing = true;
        while (browsing) {
            League current = leagues.get(index);
            System.out.println("\n" + "=".repeat(70));
            System.out.printf("%s - %s | %s (Tier %d)%n",
                    title,
                    current.getCountry(),
                    stripCountryPrefix(current.getName(), current.getCountry()),
                    current.getLevel());
            System.out.println("=".repeat(70));

            renderer.accept(current);

            System.out.println("\nN = Next league | P = Previous league | Q = Quit");
            System.out.print("Choice: ");

            String choice = scanner.nextLine().trim().toLowerCase(Locale.ROOT);
            switch (choice) {
                case "n":
                    if (index < leagues.size() - 1) {
                        index++;
                    } else {
                        System.out.println("Already at the last league in this country.");
                    }
                    break;
                case "p":
                    if (index > 0) {
                        index--;
                    } else {
                        System.out.println("Already at the first league in this country.");
                    }
                    break;
                case "q":
                case "":
                    browsing = false;
                    break;
                default:
                    System.out.println("Invalid option.");
                    break;
            }
        }
    }

    private Confederation selectConfederation() {
        List<Confederation> confeds = new ArrayList<>();
        for (Confederation conf : Confederation.values()) {
            if (conf != Confederation.UNKNOWN) {
                confeds.add(conf);
            }
        }
        confeds.sort(Comparator.comparing(Confederation::name));

        System.out.println("\nSelect confederation:");
        for (int i = 0; i < confeds.size(); i++) {
            System.out.printf("%d. %s%n", i + 1, confeds.get(i).name().replace("_", " "));
        }

        System.out.print("Enter number: ");
        Integer choice = readNumber();
        if (choice == null || choice < 1 || choice > confeds.size()) {
            System.out.println("Invalid selection.");
            return null;
        }
        return confeds.get(choice - 1);
    }

    private CountryAssociation selectCountry(Confederation conf) {
        List<CountryAssociation> countries = new ArrayList<>(
                currentSim.countriesByConfed.getOrDefault(conf, new ArrayList<>()));
        if (countries.isEmpty()) {
            System.out.println("No countries available.");
            return null;
        }
        countries.sort(Comparator.comparing(CountryAssociation::getName));

        System.out.println("\nSelect country:");
        for (int i = 0; i < countries.size(); i++) {
            System.out.printf("%d. %s%n", i + 1, countries.get(i).getName());
        }

        System.out.print("Enter number: ");
        Integer choice = readNumber();
        if (choice == null || choice < 1 || choice > countries.size()) {
            System.out.println("Invalid selection.");
            return null;
        }
        return countries.get(choice - 1);
    }

    private League selectLeague(CountryAssociation country) {
        List<League> leagues = new ArrayList<>(country.getAllLeagues());
        if (leagues.isEmpty()) {
            System.out.println("No leagues available.");
            return null;
        }
        leagues.sort(Comparator.comparingInt(League::getLevel).thenComparing(League::getName));

        System.out.println("\nSelect league:");
        for (int i = 0; i < leagues.size(); i++) {
            League league = leagues.get(i);
            String displayName = stripCountryPrefix(league.getName(), country.getName());
            System.out.printf("%d. %s (Level %d)%n", i + 1, displayName, league.getLevel());
        }

        System.out.print("Enter number: ");
        Integer choice = readNumber();
        if (choice == null || choice < 1 || choice > leagues.size()) {
            System.out.println("Invalid selection.");
            return null;
        }
        return leagues.get(choice - 1);
    }

    private int addMenuItem(Map<Integer, Runnable> actions, int option, String label, Runnable action) {
        actions.put(option, action);
        System.out.printf("    %d. %s%n", option, label);
        return option + 1;
    }

    private Integer readNumber() {
        try {
            return Integer.parseInt(scanner.nextLine().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String stripCountryPrefix(String leagueName, String countryName) {
        String prefix = countryName + " - ";
        if (leagueName.startsWith(prefix)) {
            return leagueName.substring(prefix.length());
        }
        return leagueName;
    }

    private static int resolveSeasonYear(String csvPath) {
        int yearFromName = extractYearFromText(csvPath);
        if (yearFromName != -1) {
            return yearFromName;
        }

        int yearFromCsv = extractYearFromCsv(csvPath);
        if (yearFromCsv != -1) {
            return yearFromCsv;
        }

        return LocalDate.now().getYear();
    }

    private static int extractYearFromCsv(String csvPath) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(csvPath), StandardCharsets.UTF_8))) {
            String header = reader.readLine();
            if (header == null) {
                return -1;
            }

            List<String> columns = OptaReader.parseCsvLinePublic(header);
            int seasonIndex = -1;
            for (int i = 0; i < columns.size(); i++) {
                String column = columns.get(i).trim().toLowerCase(Locale.ROOT);
                if (column.equals("season") || column.equals("seasonyear") || column.equals("season_year")) {
                    seasonIndex = i;
                    break;
                }
            }

            if (seasonIndex == -1) {
                return -1;
            }

            String line = reader.readLine();
            if (line == null) {
                return -1;
            }

            List<String> values = OptaReader.parseCsvLinePublic(line);
            if (seasonIndex >= values.size()) {
                return -1;
            }

            return extractYearFromText(values.get(seasonIndex));
        } catch (IOException e) {
            return -1;
        }
    }

    private static int extractYearFromText(String text) {
        if (text == null) {
            return -1;
        }
        Matcher matcher = Pattern.compile("\\b(19|20)\\d{2}\\b").matcher(text);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group());
        }
        return -1;
    }

    private static final class LeagueKey {
        private final String name;
        private final String country;

        private LeagueKey(String name, String country) {
            this.name = name;
            this.country = country;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (!(obj instanceof LeagueKey))
                return false;
            LeagueKey other = (LeagueKey) obj;
            return Objects.equals(name, other.name) && Objects.equals(country, other.country);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, country);
        }
    }

    private static final class SeasonRecord {
        private final Map<LeagueKey, String> leagueChampions = new HashMap<>();
        private final Map<LeagueKey, List<String>> leagueParticipants = new HashMap<>();
        private final Map<String, String> domesticCupChampions = new LinkedHashMap<>();
        private final Map<String, List<String>> domesticCupFinalists = new LinkedHashMap<>();
        private final Map<String, String> continentalChampions = new LinkedHashMap<>();

        private SeasonRecord(int seasonYear) {
        }
    }

    private static final class StatRecord {
        private final String club;
        private final String league;
        private final String country;
        private final int seasonYear;
        private final int value;

        private StatRecord(String club, String league, String country, int seasonYear, int value) {
            this.club = club;
            this.league = league;
            this.country = country;
            this.seasonYear = seasonYear;
            this.value = value;
        }

        private String format(String label) {
            return String.format("%s: %s (%s, %s) - %d in %d", label, club, league, country, value, seasonYear);
        }
    }

    private static final class StatLeaderboard {
        private final List<StatRecord> leaders = new ArrayList<>();
        private Integer bestValue;

        private void update(StatRecord record, boolean preferHigh) {
            if (bestValue == null) {
                bestValue = record.value;
                leaders.clear();
                leaders.add(record);
                return;
            }

            boolean better = preferHigh ? record.value > bestValue : record.value < bestValue;
            if (better) {
                bestValue = record.value;
                leaders.clear();
                leaders.add(record);
                return;
            }

            if (record.value == bestValue) {
                leaders.add(record);
            }
        }

        private List<StatRecord> getLeaders() {
            return new ArrayList<>(leaders);
        }

        private int getBestValue() {
            return bestValue == null ? 0 : bestValue;
        }
    }

    private static final class LeagueStatBoard {
        private final StatLeaderboard mostPoints = new StatLeaderboard();
        private final StatLeaderboard leastPoints = new StatLeaderboard();
        private final StatLeaderboard mostWins = new StatLeaderboard();
        private final StatLeaderboard leastWins = new StatLeaderboard();
        private final StatLeaderboard mostDraws = new StatLeaderboard();
        private final StatLeaderboard leastDraws = new StatLeaderboard();
        private final StatLeaderboard mostLosses = new StatLeaderboard();
        private final StatLeaderboard leastLosses = new StatLeaderboard();
    }

    private void viewTransferMarket() {
        if (currentSim == null) {
            System.out.println("\nNo simulator loaded. Start a season first.");
            return;
        }

        System.out.println("\n1. Worldwide");
        System.out.println("2. By confederation");
        System.out.println("3. By nation");
        System.out.println("4. By league");
        System.out.print("Choice: ");
        String choice = scanner.nextLine().trim();

        if ("2".equals(choice)) {
            Confederation conf = selectConfederation();
            if (conf == null) {
                return;
            }
            currentSim.debugDisplayTransferMarket(conf, null, null);
            return;
        }

        if ("3".equals(choice)) {
            Confederation conf = selectConfederation();
            if (conf == null) {
                return;
            }
            CountryAssociation country = selectCountry(conf);
            if (country == null) {
                return;
            }
            currentSim.debugDisplayTransferMarket(null, country.getName(), null);
            return;
        }

        if ("4".equals(choice)) {
            League league = chooseLeagueByPyramid();
            if (league == null) {
                return;
            }
            currentSim.debugDisplayTransferMarket(null, null, league.getName());
            return;
        }

        currentSim.debugDisplayTransferMarket();
    }

    public static void main(String[] args) {
        SimulationManager manager = new SimulationManager();
        manager.start();
    }
    }
