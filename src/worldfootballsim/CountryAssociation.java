package worldfootballsim;

import java.util.*;

public class CountryAssociation {
    private final String name;
    private final String leaguePrefix;
    private final Confederation confederation;

    private final Map<String, League> leagues = new HashMap<>();
    private League topLeague;

    private final Deque<Double> lastFiveSeasonCoefficients = new ArrayDeque<>();
    private double rollingCoefficient = 0.0;

        private List<Club> lastTopLeagueFinalTable = null;

private Club lastLeagueChampion;
    private Club lastCupWinner;

    public CountryAssociation(String name, Confederation confederation) {
        this.name = name;
        this.confederation = confederation;
        this.leaguePrefix = name + " - ";
    }

    public String getName() { return name; }
    public Confederation getConfederation() { return confederation; }

    public League getOrCreateLeague(String leagueName, int level) {
        String normalized = normalizeLeagueName(leagueName);
        League l = leagues.get(normalized);
        if (l == null) {
            l = new League(normalized, name, level);
            leagues.put(normalized, l);
        }
        return l;
    }

    public Collection<League> getAllLeagues() { return leagues.values(); }

    public League getLeagueByLevel(int level) {
        for (League l : leagues.values()) {
            if (l.getLevel() == level) return l;
        }
        return null;
    }

    public void finalizeTopLeague() {
        List<League> list = new ArrayList<>(leagues.values());
        list.sort((a, b) -> Double.compare(avgElo(b), avgElo(a)));
        this.topLeague = list.isEmpty() ? null : list.get(0);
    }

    private double avgElo(League l) {
        if (l.getClubs().isEmpty()) return 0;
        double sum = 0;
        for (Club c : l.getClubs()) sum += c.getEloRating();
        return sum / l.getClubs().size();
    }

    public League getTopLeague() { return topLeague; }

    public String normalizeLeagueName(String leagueName) {
        if (leagueName == null) return leaguePrefix.trim();
        String trimmed = leagueName.trim();
        if (trimmed.isEmpty()) return leaguePrefix.trim();
        
        // Normalize group names: "Group 5" ↁE"Group 05" for proper numeric sorting
        trimmed = normalizeGroupNameForSorting(trimmed);
        
        if (trimmed.startsWith(leaguePrefix)) return trimmed;
        return leaguePrefix + trimmed;
    }
    
    private static String normalizeGroupNameForSorting(String name) {
        if (name == null || name.isEmpty()) {
            return name;
        }
        
        // Find the last number in the string and zero-pad it
        // Handles: "Group 5" ↁE"Group 05"
        //          "Regional Division 5" ↁE"Regional Division 05"
        //          "Autonómicas - Regional Division 10" ↁE"Autonómicas - Regional Division 10"
        
        StringBuilder result = new StringBuilder();
        StringBuilder lastNumber = new StringBuilder();
        int lastNumberStart = -1;
        
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isDigit(c)) {
                if (lastNumber.length() == 0) {
                    lastNumberStart = i;
                }
                lastNumber.append(c);
            } else {
                if (lastNumber.length() > 0) {
                    // Reset - we'll track the most recent number
                    lastNumber = new StringBuilder();
                }
            }
        }
        
        // If we found a number, zero-pad it
        if (lastNumber.length() > 0 && lastNumberStart >= 0) {
            try {
                int num = Integer.parseInt(lastNumber.toString());
                String paddedNum = String.format("%03d", num);  // Pad to 3 digits for up to 999
                
                // Replace the last number in the string with zero-padded version
                int endPos = lastNumberStart + lastNumber.length();
                String before = name.substring(0, lastNumberStart);
                String after = name.substring(endPos);
                return before + paddedNum + after;
            } catch (NumberFormatException e) {
                return name;
            }
        }
        
        return name;
    }

    public void seedInitialCoefficient() {
        if (topLeague == null) {
            addSeasonCoefficient(0);
            return;
        }
        List<Club> top = new ArrayList<>(topLeague.getClubs());
        top.sort((a, b) -> Double.compare(b.getEloRating(), a.getEloRating()));
        int take = Math.min(5, top.size());
        double sum = 0;
        for (int i = 0; i < take; i++) sum += top.get(i).getEloRating();
        double avg = take == 0 ? 1000 : sum / take;

        double base = Math.max(3.0, Math.min(40.0, (avg - 800.0) / 25.0));
        addSeasonCoefficient(base);
    }

    public void addSeasonCoefficient(double seasonCoefficient) {
        if (lastFiveSeasonCoefficients.size() == 5) lastFiveSeasonCoefficients.removeFirst();
        lastFiveSeasonCoefficients.addLast(Math.max(0.0, seasonCoefficient));
        recompute();
    }

    private void recompute() {
        double sum = 0;
        for (double v : lastFiveSeasonCoefficients) sum += v;
        // Rolling-5 is the five-season total used for country ranking/slot allocation.
        // Missing seasons count as zero by virtue of absent entries.
        rollingCoefficient = sum;
    }

    public double getRollingCoefficient() { return rollingCoefficient; }

    public double getLastSeasonCoefficient() {
        return lastFiveSeasonCoefficients.isEmpty() ? 0.0 : lastFiveSeasonCoefficients.peekLast();
    }

    public List<Double> getSeasonCoefficientHistory() {
        return new ArrayList<>(lastFiveSeasonCoefficients);
    }
    
    /**
     * Pre-populate coefficient history with historical values.
     * Useful for loading CSV data at startup.
     */
    public void loadHistoricalCoefficients(int[] years, java.util.Map<Integer, Double> coeffsByYear) {
        lastFiveSeasonCoefficients.clear();
        for (int year : years) {
            if (coeffsByYear.containsKey(year)) {
                lastFiveSeasonCoefficients.addLast(Math.max(0.0, coeffsByYear.get(year)));
            }
        }
        // Keep only the last 5
        while (lastFiveSeasonCoefficients.size() > 5) {
            lastFiveSeasonCoefficients.removeFirst();
        }
        recompute();
    }

    public void setLastLeagueChampion(Club c) { lastLeagueChampion = c; }
    public void setLastCupWinner(Club c) { lastCupWinner = c; }
    public Club getLastLeagueChampion() { return lastLeagueChampion; }
    public Club getLastCupWinner() { return lastCupWinner; }


    public void setLastTopLeagueFinalTable(List<Club> tableSnapshot) {
        if (tableSnapshot == null) { this.lastTopLeagueFinalTable = null; return; }
        this.lastTopLeagueFinalTable = new ArrayList<>(tableSnapshot);
    }

    public List<Club> getLastTopLeagueFinalTable() {
         return lastTopLeagueFinalTable == null ? null : new ArrayList<>(lastTopLeagueFinalTable);
     }

}
