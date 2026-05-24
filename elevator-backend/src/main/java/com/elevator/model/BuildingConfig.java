package com.elevator.model;

/**
 * BuildingConfig — Immutable constants defining the physical building layout.
 *
 * WHY A SEPARATE CONFIG CLASS?
 * ─────────────────────────────
 * Hardcoding floor numbers like -3, 10, 14 scattered across the codebase
 * is a maintenance nightmare. If the building ever changes (e.g., adds a
 * rooftop floor), you'd need to hunt every reference. Centralizing here:
 *   - Single source of truth
 *   - Easy to extend (just change MIN_FLOOR / MAX_FLOOR)
 *   - Self-documenting via well-named constants
 *
 * FLOOR NUMBERING CONVENTION:
 * ────────────────────────────
 * Floors -3 to -1 = Underground parking levels (B3, B2, B1)
 * Floor   0       = Ground floor (G or L)
 * Floors  1 to 10 = Upper levels (1F to 10F)
 *
 * In array-based algorithms, we use floorIndex = floor - MIN_FLOOR
 * so index 0 maps to floor -3, index 13 maps to floor 10.
 * This avoids negative array indices.
 *
 * REAL-WORLD RELEVANCE:
 * ──────────────────────
 * Commercial elevator control systems (Otis, KONE, Schindler) store
 * building configuration in a commissioning database — exactly what this
 * class represents. The min/max floors, speed, and capacity are all
 * configurable parameters loaded at startup.
 */
public final class BuildingConfig {

    // ── Floor Boundaries ─────────────────────────────────────────────────────

    /** Lowest floor in the building (underground parking level 3). */
    public static final int MIN_FLOOR = -3;

    /** Highest floor in the building (top level). */
    public static final int MAX_FLOOR = 10;

    /** Total number of floors = MAX_FLOOR - MIN_FLOOR + 1 = 14. */
    public static final int TOTAL_FLOORS = MAX_FLOOR - MIN_FLOOR + 1; // 14

    // ── Elevator Defaults ─────────────────────────────────────────────────────

    /** Number of elevators in the system. */
    public static final int TOTAL_ELEVATORS = 3;

    /** Maximum passenger capacity per elevator car. */
    public static final int DEFAULT_CAPACITY = 10;

    /**
     * Starting floor for all elevators on system boot.
     * Ground floor (0) is conventional — it's where most morning traffic originates.
     */
    public static final int DEFAULT_START_FLOOR = 0;

    // ── Simulation Timing ─────────────────────────────────────────────────────

    /**
     * How many simulation ticks a door stays open.
     * 1 tick = 1 second in our simulation.
     * Real elevators hold doors ~3-5 seconds; we use 2 for demo speed.
     */
    public static final int DOOR_OPEN_TICKS = 2;

    /**
     * Simulation tick interval in milliseconds.
     * Each tick = 1 floor movement OR 1 door-open tick.
     */
    public static final long TICK_INTERVAL_MS = 1000L;

    // ── Utility Methods ───────────────────────────────────────────────────────

    /**
     * Converts a real floor number to a 0-based array index.
     *
     * Example: floor -3 → index 0, floor 0 → index 3, floor 10 → index 13
     *
     * WHY NEEDED?
     * Java arrays can't use negative indices. When we need array-based
     * structures (e.g., tracking hall calls per floor), we must normalize.
     *
     * @param floor  the real floor number (can be negative)
     * @return       zero-based array index
     */
    public static int floorToIndex(int floor) {
        return floor - MIN_FLOOR;
    }

    /**
     * Converts a 0-based array index back to a real floor number.
     *
     * @param index  zero-based array index
     * @return       real floor number (can be negative)
     */
    public static int indexToFloor(int index) {
        return index + MIN_FLOOR;
    }

    /**
     * Validates that a given floor number is within the building's range.
     *
     * @param floor  floor number to validate
     * @return       true if floor is between MIN_FLOOR and MAX_FLOOR (inclusive)
     */
    public static boolean isValidFloor(int floor) {
        return floor >= MIN_FLOOR && floor <= MAX_FLOOR;
    }

    /**
     * Returns a human-readable label for a floor number.
     * Negative floors display as "B3", "B2", "B1" (basement levels).
     * Floor 0 displays as "G" (ground).
     * Positive floors display as their number.
     *
     * @param floor  real floor number
     * @return       display label string
     */
    public static String floorLabel(int floor) {
        if (floor < 0) {
            return "B" + Math.abs(floor);   // -3 → "B3"
        } else if (floor == 0) {
            return "G";                      //  0 → "G"
        } else {
            return String.valueOf(floor);    //  7 → "7"
        }
    }

    /** Prevent instantiation — this is a static constants class. */
    private BuildingConfig() {}
}
