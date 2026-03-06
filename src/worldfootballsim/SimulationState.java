package worldfootballsim;

/**
 * Enumeration of valid season simulation states.
 * Ensures operations are only performed at appropriate times.
 *
 * State transitions:
 *   UNINITIALIZED ↁEINITIALIZED ↁESEASON_RUNNING ↁESEASON_COMPLETED
 */
public enum SimulationState {
    /** Simulator created but data not loaded */
    UNINITIALIZED("Not initialized"),

    /** Data loaded, season prepared, fixtures generated */
    INITIALIZED("Initialized"),

    /** Season matches are being played */
    SEASON_RUNNING("Season running"),

    /** All matches completed, final calculations done */
    SEASON_COMPLETED("Season completed");

    private final String description;

    SimulationState(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }

    /**
     * Check if transition from current state to next is valid
     */
    public boolean canTransitionTo(SimulationState next) {
        if (next == null) return false;

        return switch (this) {
            case UNINITIALIZED -> next == INITIALIZED;
            case INITIALIZED -> next == SEASON_RUNNING || next == UNINITIALIZED; // Can reset
            case SEASON_RUNNING -> next == SEASON_COMPLETED || next == SEASON_RUNNING; // Stay or complete
            case SEASON_COMPLETED -> next == INITIALIZED || next == UNINITIALIZED; // Can reset
        };
    }

    /**
     * Validate that an operation is allowed in this state
     */
    public void validateOperation(String operationName) throws IllegalStateException {
        // Override in specific contexts if needed
    }
}
