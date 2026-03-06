package worldfootballsim;

import java.util.*;
import java.util.stream.Collectors;

public final class RegionMapper {

    private static final Map<String, String> COUNTRY_TO_REGION = new LinkedHashMap<>();

    static {
        // ── AFC WEST ──────────────────────────────────────────────
        // Middle East
        for (String c : new String[]{
                "Bahrain", "Iran", "Iraq", "Jordan", "Kuwait", "Lebanon",
                "Oman", "Qatar", "Saudi Arabia", "Syria", "United Arab Emirates"
        }) COUNTRY_TO_REGION.put(c, "AFC West");

        // Central Asia
        for (String c : new String[]{
                "Kyrgyz Republic", "Tajikistan", "Turkmenistan", "Uzbekistan"
        }) COUNTRY_TO_REGION.put(c, "AFC West");

        // South Asia
        for (String c : new String[]{
                "Bangladesh", "Bhutan", "India", "Maldives", "Nepal"
        }) COUNTRY_TO_REGION.put(c, "AFC West");

        // ── AFC EAST ──────────────────────────────────────────────
        // East Asia
        for (String c : new String[]{
                "China PR", "Chinese Taipei", "Hong Kong, China", "Japan",
                "Korea Republic", "Macao", "Mongolia"
        }) COUNTRY_TO_REGION.put(c, "AFC East");

        // Southeast Asia
        for (String c : new String[]{
                "Brunei Darussalam", "Cambodia", "Indonesia", "Laos",
                "Malaysia", "Myanmar", "Philippines", "Singapore",
                "Thailand", "Vietnam"
        }) COUNTRY_TO_REGION.put(c, "AFC East");

        // Special (play in Asia)
        for (String c : new String[]{"Australia", "Guam"})
            COUNTRY_TO_REGION.put(c, "AFC East");

        // ── CAF (Africa) ─────────────────────────────────────────
        for (String c : new String[]{
                "Algeria", "Egypt", "Libya", "Morocco", "Tunisia", "Mauritania"
        }) COUNTRY_TO_REGION.put(c, "North Africa");

        for (String c : new String[]{
                "Benin", "Burkina Faso", "Cabo Verde", "Cameroon", "Congo",
                "Congo DR", "C\u00f4te d'Ivoire", "Gabon", "Gambia", "Ghana",
                "Guinea", "Liberia", "Mali", "Nigeria", "Senegal",
                "Sierra Leone", "Togo", "S\u00e3o Tom\u00e9 e Pr\u00edncipe"
        }) COUNTRY_TO_REGION.put(c, "West Africa");

        for (String c : new String[]{
                "Burundi", "Djibouti", "Ethiopia", "Kenya", "Madagascar",
                "Mauritius", "R\u00e9union", "Rwanda", "Somalia", "Sudan",
                "Tanzania", "Uganda"
        }) COUNTRY_TO_REGION.put(c, "East Africa");

        for (String c : new String[]{
                "Angola", "Botswana", "Eswatini", "Lesotho", "Malawi",
                "Mozambique", "South Africa", "Zambia", "Zimbabwe"
        }) COUNTRY_TO_REGION.put(c, "Southern Africa");
    }

    private RegionMapper() {}

    public static String getRegion(String country) {
        return COUNTRY_TO_REGION.getOrDefault(country, "Unknown");
    }

    public static boolean isAfcWest(String country) {
        return "AFC West".equals(COUNTRY_TO_REGION.get(country));
    }

    public static boolean isAfcEast(String country) {
        return "AFC East".equals(COUNTRY_TO_REGION.get(country));
    }

    public static List<String> getCountriesInRegion(String region) {
        return COUNTRY_TO_REGION.entrySet().stream()
                .filter(e -> e.getValue().equals(region))
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }
}
