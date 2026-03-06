package worldfootballsim;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public class SeasonalEventLog {
    private static final SeasonalEventLog INSTANCE = new SeasonalEventLog();

    private final List<String> eventLog = new ArrayList<>();
    private final List<FFPViolationRecord> ffpViolations = new ArrayList<>();
    private final List<FloorPenaltyRecord> floorPenalties = new ArrayList<>();
    private final List<relegationMoraleImpactRecord> relegationImpacts = new ArrayList<>();

    // Season-level counters for diagnostics
    private int ffpViolationsRecorded = 0;
    private int floorPenaltiesRecorded = 0;
    private int relegationImpactsRecorded = 0;
    
    // FFP/Floor audit counters: track whether audits ran and hit thresholds
    private int ffpAuditsRun = 0;
    private int ffpThresholdBreaches = 0;
    private int ffpPenaltiesApplied = 0;
    private int floorChecksRun = 0;
    private int floorThresholdBreaches = 0;
    private int floorPenaltiesApplied = 0;

    private int currentSeason = 0;

    public static SeasonalEventLog getInstance() {
        return INSTANCE;
    }

    public void setSeason(int season) {
        this.currentSeason = season;
    }

    public void logrelegationCrisis(Club club, double relegationValue, double strengthReduction, double penaltyPercent) {
        String msg = String.format("[RELEGATION CRISIS] %s (%s) relegated with low relegation (%.1f): " +
                "strength reduced by %.1f, penalty %.1f%%",
                club.getName(), club.getCountry(), relegationValue, strengthReduction, penaltyPercent);
        eventLog.add(msg);
        System.out.println(msg);

        relegationImpacts.add(new relegationMoraleImpactRecord(
                currentSeason, club.getName(), club.getCountry(),
                "RELEGATION_relegation", relegationValue, club.getMorale(),
                strengthReduction, penaltyPercent));
    }

    public void logFFPViolation(Club club, double severity, double morale, int pointsDeducted) {
        String msg = String.format("[FFP VIOLATION] %s deducted %d points (severity: %.2f, morale: %.1f)",
                club.getName(), pointsDeducted, severity, morale);
        eventLog.add(msg);
        System.out.println(msg);

        ffpViolations.add(new FFPViolationRecord(
                currentSeason, club.getName(), club.getCountry(),
                severity, morale, pointsDeducted, club.getRollingLoss3y()));
    }

    public void logFloorPenalty(Club club, double morale, int pointsDeducted) {
        String msg = String.format("[FLOOR PENALTY] %s deducted %d points (morale: %.1f)",
                club.getName(), pointsDeducted, morale);
        eventLog.add(msg);
        System.out.println(msg);

        floorPenalties.add(new FloorPenaltyRecord(
                currentSeason, club.getName(), club.getCountry(),
                morale, pointsDeducted));
        floorPenaltiesRecorded++;
    }

    public void logFinancialDropout(Club club, double financialPower) {
        String msg = String.format("[FINANCIAL DROPOUT] %s dropped from %s (power: %.1f)",
                club.getName(), club.getDomesticLeagueName(), financialPower);
        eventLog.add(msg);
        System.out.println(msg);
    }

    public void logHighSpendStreak(Club club, int streak) {
        String msg = String.format("[HIGH SPEND STREAK] %s has spent heavily for %d consecutive seasons",
                club.getName(), streak);
        eventLog.add(msg);
        System.out.println(msg);
    }

    public void logMoraleCollapse(Club club, int lossStreak, double morale) {
        String msg = String.format("[MORALE COLLAPSE] %s after %d consecutive losses (morale: %.1f)",
                club.getName(), lossStreak, morale);
        eventLog.add(msg);
        System.out.println(msg);
    }

    public void logLatePayments(Club club) {
        String msg = String.format("[LATE PAYMENTS] %s failed to meet weekly wage obligations",
                club.getName());
        eventLog.add(msg);
        System.out.println(msg);
    }

    public void clearSeason() {
        eventLog.clear();
        ffpViolations.clear();
        floorPenalties.clear();
        relegationImpacts.clear();
        ffpViolationsRecorded = 0;
        floorPenaltiesRecorded = 0;
        relegationImpactsRecorded = 0;
        ffpAuditsRun = 0;
        ffpThresholdBreaches = 0;
        ffpPenaltiesApplied = 0;
        floorChecksRun = 0;
        floorThresholdBreaches = 0;
        floorPenaltiesApplied = 0;
    }

    /**
     * Get diagnostic counters for this season
     */
    public int getFFPViolationsRecorded() {
        return ffpViolationsRecorded;
    }

    public int getFloorPenaltiesRecorded() {
        return floorPenaltiesRecorded;
    }

    public int getrelegationImpactsRecorded() {
        return relegationImpactsRecorded;
    }
    
    public int getFFPAuditsRun() {
        return ffpAuditsRun;
    }
    
    public int getFFPThresholdBreaches() {
        return ffpThresholdBreaches;
    }
    
    public int getFFPPenaltiesApplied() {
        return ffpPenaltiesApplied;
    }
    
    public int getFloorChecksRun() {
        return floorChecksRun;
    }
    
    public int getFloorThresholdBreaches() {
        return floorThresholdBreaches;
    }
    
    public int getFloorPenaltiesApplied() {
        return floorPenaltiesApplied;
    }
    
    /**
     * Increment audit counters - called by Club.performAudit
     */
    public void incrementFFPAudit() {
        ffpAuditsRun++;
    }
    
    public void incrementFFPThresholdBreach() {
        ffpThresholdBreaches++;
    }
    
    public void incrementFFPPenalty() {
        ffpPenaltiesApplied++;
    }
    
    public void incrementFloorCheck() {
        floorChecksRun++;
    }
    
    public void incrementFloorThresholdBreach() {
        floorThresholdBreaches++;
    }
    
    public void incrementFloorPenalty() {
        floorPenaltiesApplied++;
    }

    /**
     * Escape CSV field content: convert double quotes to double-double quotes
     */
    private static String escapeCSV(String field) {
        if (field == null)
            return "";
        return field.replace("\"", "\"\"");
    }

    public List<String> getEventLog() {
        return new ArrayList<>(eventLog);
    }

    public List<FFPViolationRecord> getFFPViolations() {
        return new ArrayList<>(ffpViolations);
    }

    public List<FloorPenaltyRecord> getFloorPenalties() {
        return new ArrayList<>(floorPenalties);
    }

    public List<relegationMoraleImpactRecord> getrelegationImpacts() {
        return new ArrayList<>(relegationImpacts);
    }

    public void exportFFPViolations(Path outputPath) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append('\uFEFF');
        sb.append("season,club_name,country,severity,morale,points_deducted,rolling_loss_3y,penalty_reason\n");

        // Only export records where a club actually was punished (points_deducted > 0)
        for (FFPViolationRecord record : ffpViolations) {
            if (record.pointsDeducted > 0) { // Only log actual violations, not audits
                sb.append(String.format("%d,\"%s\",\"%s\",%.4f,%.1f,%d,%.2f,\"%s\"\n",
                        record.season,
                        escapeCSV(record.clubName),
                        escapeCSV(record.country),
                        record.severity,
                        record.morale,
                        record.pointsDeducted,
                        record.rollingLoss3y,
                        "FFP"));
            }
        }

        Files.write(outputPath, sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    public void exportFloorPenalties(Path outputPath) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append('\uFEFF');
        sb.append("season,club_name,country,morale,points_deducted,penalty_reason\n");

        for (FloorPenaltyRecord record : floorPenalties) {
            if (record.pointsDeducted > 0) {
                sb.append(String.format("%d,\"%s\",\"%s\",%.1f,%d,\"%s\"\n",
                        record.season,
                        escapeCSV(record.clubName),
                        escapeCSV(record.country),
                        record.morale,
                        record.pointsDeducted,
                        "FLOOR"));
            }
        }

        Files.write(outputPath, sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    public void exportrelegationMoraleImpacts(Path outputPath) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append('\uFEFF');
        sb.append(
                "season,club_name,country,event_type,relegation_value,morale_value,strength_reduction,penalty_percent\n");

        // Export non-relegation crises
        for (relegationMoraleImpactRecord record : relegationImpacts) {
            if (!"RELEGATION_relegation".equals(record.eventType)) {
                sb.append(String.format("%d,\"%s\",\"%s\",\"%s\",%.1f,%.1f,%.2f,%.2f\n",
                        record.season,
                        escapeCSV(record.clubName),
                        escapeCSV(record.country),
                        escapeCSV(record.eventType),
                        record.relegationValue,
                        record.moraleValue,
                        record.strengthReduction,
                        record.penaltyPercent));
            }
        }

        Files.write(outputPath, sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    public void exportRelegationCrises(Path outputPath) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append('\uFEFF');
        sb.append("season,club_name,country,relegation_value,morale_value,strength_reduction,penalty_percent\n");

        for (relegationMoraleImpactRecord record : relegationImpacts) {
            if ("RELEGATION_relegation".equals(record.eventType)) {
                sb.append(String.format("%d,\"%s\",\"%s\",%.1f,%.1f,%.2f,%.2f\n",
                        record.season,
                        escapeCSV(record.clubName),
                        escapeCSV(record.country),
                        record.relegationValue,
                        record.moraleValue,
                        record.strengthReduction,
                        record.penaltyPercent));
            }
        }

        Files.write(outputPath, sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    public static class FFPViolationRecord {
        public int season;
        public String clubName;
        public String country;
        public double severity;
        public double morale;
        public int pointsDeducted;
        public double rollingLoss3y;

        public FFPViolationRecord(int season, String clubName, String country,
                double severity, double morale, int pointsDeducted, double rollingLoss3y) {
            this.season = season;
            this.clubName = clubName;
            this.country = country;
            this.severity = severity;
            this.morale = morale;
            this.pointsDeducted = pointsDeducted;
            this.rollingLoss3y = rollingLoss3y;
        }
    }

    public static class FloorPenaltyRecord {
        public int season;
        public String clubName;
        public String country;
        public double morale;
        public int pointsDeducted;

        public FloorPenaltyRecord(int season, String clubName, String country,
                double morale, int pointsDeducted) {
            this.season = season;
            this.clubName = clubName;
            this.country = country;
            this.morale = morale;
            this.pointsDeducted = pointsDeducted;
        }
    }

    public static class relegationMoraleImpactRecord {
        public int season;
        public String clubName;
        public String country;
        public String eventType;
        public double relegationValue;
        public double moraleValue;
        public double strengthReduction;
        public double penaltyPercent;

        public relegationMoraleImpactRecord(int season, String clubName, String country,
                String eventType, double relegationValue, double moraleValue,
                double strengthReduction, double penaltyPercent) {
            this.season = season;
            this.clubName = clubName;
            this.country = country;
            this.eventType = eventType;
            this.relegationValue = relegationValue;
            this.moraleValue = moraleValue;
            this.strengthReduction = strengthReduction;
            this.penaltyPercent = penaltyPercent;
        }
    }
}
