package worldfootballsim;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Error-only logging system that appears only when errors occur.
 * Collects errors and can output them at the end of processing or on demand.
 */
public class ErrorLogger {
    private static final ErrorLogger INSTANCE = new ErrorLogger();
    private final List<ErrorEntry> errors = new ArrayList<>();
    private final DateTimeFormatter timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private boolean writeToFile = true;
    private java.nio.file.Path errorLogPath = null;

    private static class ErrorEntry {
        final long timestamp;
        final String category;
        final String message;
        final StackTraceElement[] stackTrace;

        ErrorEntry(String category, String message, Throwable throwable) {
            this.timestamp = System.currentTimeMillis();
            this.category = category;
            this.message = message;
            this.stackTrace = throwable != null ? throwable.getStackTrace() : null;
        }
    }

    public static ErrorLogger getInstance() {
        return INSTANCE;
    }

    /**
     * Log an error with category
     */
    public static void error(String category, String message) {
        INSTANCE.logError(category, message, null);
    }

    /**
     * Log an error with exception details
     */
    public static void error(String category, String message, Throwable t) {
        INSTANCE.logError(category, message, t);
    }

    /**
     * Log error without category
     */
    public static void error(String message) {
        INSTANCE.logError("GENERAL", message, null);
    }

    private void logError(String category, String message, Throwable throwable) {
        ErrorEntry entry = new ErrorEntry(category, message, throwable);
        errors.add(entry);

        // Print to stderr immediately for visibility
        System.err.printf("[ERROR] %s: %s%n", category, message);
        if (throwable != null) {
            throwable.printStackTrace(System.err);
        }

        // Write to file if configured
        if (writeToFile && errorLogPath != null) {
            writeToErrorFile(entry);
        }
    }

    /**
     * Get count of errors logged
     */
    public static int getErrorCount() {
        return INSTANCE.errors.size();
    }

    /**
     * Check if any errors were logged
     */
    public static boolean hasErrors() {
        return !INSTANCE.errors.isEmpty();
    }

    /**
     * Clear error log
     */
    public static void clearErrors() {
        INSTANCE.errors.clear();
    }

    /**
     * Get all errors as formatted strings
     */
    public static List<String> getErrorMessages() {
        List<String> messages = new ArrayList<>();
        for (ErrorEntry e : INSTANCE.errors) {
            messages.add(String.format("[%s] %s: %s", 
                INSTANCE.timeFormat.format(LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(e.timestamp),
                    ZoneId.systemDefault())),
                e.category, e.message));
        }
        return messages;
    }

    /**
     * Print summary of all errors logged
     */
    public static void printErrorSummary() {
        if (!hasErrors()) {
            return;
        }

        System.err.println("\n" + "=".repeat(70));
        System.err.printf("ERROR SUMMARY: %d error(s) logged%n", getErrorCount());
        System.err.println("=".repeat(70));

        for (String msg : getErrorMessages()) {
            System.err.println(msg);
        }
    }

    /**
     * Configure error file output
     */
    public static void configureErrorFile(String filePath) {
        try {
            INSTANCE.errorLogPath = Paths.get(filePath);
            Files.createDirectories(INSTANCE.errorLogPath.getParent());
            INSTANCE.writeToFile = true;
        } catch (Exception e) {
            System.err.println("[LOGGER ERROR] Failed to configure error file: " + e.getMessage());
            INSTANCE.writeToFile = false;
        }
    }

    private void writeToErrorFile(ErrorEntry entry) {
        if (errorLogPath == null) return;

        try {
            String logLine = String.format("[%s] %s: %s%n",
                timeFormat.format(LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(entry.timestamp),
                    ZoneId.systemDefault())),
                entry.category, entry.message);

            Files.write(
                errorLogPath,
                logLine.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
        } catch (IOException e) {
            // Silently fail to avoid infinite loops
        }
    }

    private ErrorLogger() {
        // Singleton
    }
}
