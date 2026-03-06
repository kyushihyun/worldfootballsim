package worldfootballsim;

public enum Confederation {
    EUROPE("Europe"),
    ASIA("Asia"),
    AFRICA("Africa"),
    NORTH_CENTRAL_AMERICA("N/C America"),
    SOUTH_AMERICA("South America"),
    OCEANIA("Oceania"),
    UNKNOWN("Unknown");

    private final String optaName;

    Confederation(String optaName) {
        this.optaName = optaName;
    }

    public String getOptaName() { return optaName; }

    public static Confederation fromOptaName(String s) {
        if (s == null) return UNKNOWN;
        String t = s.trim();
        for (Confederation c : values()) {
            if (c.optaName.equalsIgnoreCase(t)) return c;
        }
        return UNKNOWN;
    }

    public static Confederation fromCsvName(String s) {
        if (s == null) return UNKNOWN;
        String t = s.trim();
        if (t.isEmpty()) return UNKNOWN;

        // Support abbreviation codes
        switch (t.toUpperCase()) {
            case "UEFA":     return EUROPE;
            case "AFC":      return ASIA;
            case "CAF":      return AFRICA;
            case "CONMEBOL": return SOUTH_AMERICA;
            case "CONCACAF": return NORTH_CENTRAL_AMERICA;
            case "OFC":      return OCEANIA;
        }

        // Support display names (as used in continental_cups_config.csv)
        String norm = t.toLowerCase().replace("-", "/").replaceAll("\\s+", " ");
        switch (norm) {
            case "europe":        return EUROPE;
            case "asia":          return ASIA;
            case "africa":        return AFRICA;
            case "south america": return SOUTH_AMERICA;
            case "oceania":       return OCEANIA;
            case "n/c america":
            case "north/central america":
            case "north central america":
                return NORTH_CENTRAL_AMERICA;
        }

        // Fallback: try opta name match
        Confederation fromOpta = fromOptaName(t);
        return fromOpta != UNKNOWN ? fromOpta : UNKNOWN;
    }
}
