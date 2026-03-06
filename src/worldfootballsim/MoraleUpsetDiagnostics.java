package worldfootballsim;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Diagnostic tool for tracking morale and upset pressure behavior across a single season.
 * 
 * For each club, logs:
 * - Morale average, max, min
 * - Number of times upset pressure triggered
 * - Biggest single morale delta in season
 * - Longest win streak
 * - Longest unbeaten streak
 * - Points gained above expected
 * 
 * Useful for verifying that:
 * - Promoted clubs have hot streaks but not permanent aura
 * - Elite clubs can lose but don't spiral for months
 * - Morale changes are realistic (not wild swings)
 */
public class MoraleUpsetDiagnostics {
    
    private Map<Club, ClubDiagnostics> tracking = new HashMap<>();
    private int currentSeason;
    private PrintWriter logWriter;
    
    public MoraleUpsetDiagnostics(int season) throws IOException {
        this.currentSeason = season;
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String filename = String.format("morale_upset_diagnostics_s%d_%s.csv", season, timestamp);
        this.logWriter = new PrintWriter(new FileWriter(filename));
        
        logWriter.println("club,country,tier,morale_avg,morale_max,morale_min," +
                "upset_pressure_triggers,biggest_morale_delta,win_streak_max,unbeaten_streak_max," +
                "points_vs_expected,promotion_status");
        logWriter.flush();
    }
    
    public void trackClub(Club club) {
        tracking.put(club, new ClubDiagnostics(club.getName(), club.getCountry()));
    }
    
    /**
     * Called after each match in the season.
     * Records morale value and whether upset pressure triggered.
     */
    public void recordMatchResult(Club club, int pointsEarned, double expectedPoints,
                                  double beforeMorale, double afterMorale,
                                  boolean upsetPressureTriggered) {
        ClubDiagnostics diag = tracking.get(club);
        if (diag == null) return;
        
        diag.moraleHistory.add(afterMorale);
        diag.moraleDeltaHistory.add(Math.abs(afterMorale - beforeMorale));
        
        if (upsetPressureTriggered) {
            diag.upsetPressureTriggerCount++;
        }
        
        diag.pointsVsExpected += (pointsEarned - expectedPoints);
        
        // Update streaks
        if (pointsEarned >= 3) {
            diag.currentWinStreak++;
            diag.currentUnbeatStreak++;
            diag.longestWinStreak = Math.max(diag.longestWinStreak, diag.currentWinStreak);
            diag.longestUnbeatStreak = Math.max(diag.longestUnbeatStreak, diag.currentUnbeatStreak);
        } else if (pointsEarned == 1) {
            diag.currentWinStreak = 0;
            diag.currentUnbeatStreak++;
            diag.longestUnbeatStreak = Math.max(diag.longestUnbeatStreak, diag.currentUnbeatStreak);
        } else {
            diag.currentWinStreak = 0;
            diag.currentUnbeatStreak = 0;
        }
    }
    
    /**
     * Finalize season and write diagnostics to file.
     */
    public void finalizeAndExport(Map<Club, String> promotionStatus) throws IOException {
        for (Map.Entry<Club, ClubDiagnostics> entry : tracking.entrySet()) {
            Club club = entry.getKey();
            ClubDiagnostics diag = entry.getValue();
            
            double moralAvg = 0, moralMax = 0, moralMin = 0;
            if (!diag.moraleHistory.isEmpty()) {
                moralAvg = diag.moraleHistory.stream().mapToDouble(d -> d).average().orElse(0);
                moralMax = diag.moraleHistory.stream().mapToDouble(d -> d).max().orElse(0);
                moralMin = diag.moraleHistory.stream().mapToDouble(d -> d).min().orElse(0);
            }
            
            double biggestMoraleDelta = diag.moraleDeltaHistory.isEmpty() ? 0 :
                    diag.moraleDeltaHistory.stream().mapToDouble(d -> d).max().orElse(0);
            
            String promoStatus = promotionStatus.getOrDefault(club, "NONE");
            
            logWriter.printf("%s,%s,%d,%.2f,%.2f,%.2f,%d,%.2f,%d,%d,%.1f,%s\n",
                    club.getName(),
                    club.getCountry(),
                    club.getDomesticLevel(),
                    moralAvg,
                    moralMax,
                    moralMin,
                    diag.upsetPressureTriggerCount,
                    biggestMoraleDelta,
                    diag.longestWinStreak,
                    diag.longestUnbeatStreak,
                    diag.pointsVsExpected,
                    promoStatus);
        }
        
        logWriter.flush();
        logWriter.close();
    }
    
    static class ClubDiagnostics {
        String name;
        String country;
        
        List<Double> moraleHistory = new ArrayList<>();
        List<Double> moraleDeltaHistory = new ArrayList<>();
        int upsetPressureTriggerCount = 0;
        
        int longestWinStreak = 0;
        int currentWinStreak = 0;
        
        int longestUnbeatStreak = 0;
        int currentUnbeatStreak = 0;
        
        double pointsVsExpected = 0;
        
        ClubDiagnostics(String name, String country) {
            this.name = name;
            this.country = country;
        }
    }
}
