package worldfootballsim;

public class Match {
    private final Club home;
    private final Club away;
    private Integer homeGoals;
    private Integer awayGoals;
    private final boolean cupMatch;

    public Match(Club home, Club away) {
        this(home, away, false);
    }

    public Match(Club home, Club away, boolean cupMatch) {
        this.home = home;
        this.away = away;
        this.cupMatch = cupMatch;
        this.homeGoals = null;
        this.awayGoals = null;
    }

    public Club getHome() { return home; }
    public Club getAway() { return away; }
    public boolean isCupMatch() { return cupMatch; }

    public void setResult(int hg, int ag) {
        this.homeGoals = hg;
        this.awayGoals = ag;
    }

    public boolean hasResult() {
        return homeGoals != null && awayGoals != null;
    }

    public int getHomeGoals() { return homeGoals == null ? 0 : homeGoals; }
    public int getAwayGoals() { return awayGoals == null ? 0 : awayGoals; }

    @Override
    public String toString() {
        if (!hasResult()) return home.getName() + " vs " + away.getName();
        return home.getName() + " " + homeGoals + " - " + awayGoals + " " + away.getName();
    }
}
