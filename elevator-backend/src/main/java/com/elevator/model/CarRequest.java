package com.elevator.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * CarRequest — Represents a passenger pressing a floor button INSIDE an elevator.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * CONCEPT: WHY CAR REQUESTS ARE SIMPLER THAN HALL REQUESTS
 * ─────────────────────────────────────────────────────────────────────────────
 * Once a passenger boards an elevator, the complexity of "which elevator?"
 * disappears. The request goes directly to that elevator's local scheduler.
 *
 * The elevator's LOOK algorithm then handles it:
 *   - If the target floor is ahead in current direction → add to current sweep
 *   - If the target floor is behind current direction → queue for return sweep
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * EXAMPLE SCENARIOS:
 * ─────────────────────────────────────────────────────────────────────────────
 * Elevator 1 is at floor 3, moving UP, stops queued at [5, 7].
 * Passenger boards at 3 and presses floor 6:
 *   → floor 6 inserted between 5 and 7 → upQueue = [5, 6, 7] ✓
 *
 * Same elevator. Another passenger presses floor 2:
 *   → floor 2 is BELOW current direction (going up)
 *   → add to downQueue = [2] (served after completing the upward sweep)
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * REAL-WORLD RELEVANCE:
 * ─────────────────────────────────────────────────────────────────────────────
 * In Otis and KONE systems, car calls are processed by the elevator's onboard
 * microcontroller independently of the group dispatch computer.
 * The dispatch computer only gets involved for HALL calls.
 * Our architecture mirrors this separation perfectly.
 * ─────────────────────────────────────────────────────────────────────────────
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CarRequest {

    /**
     * Which elevator received this request.
     * Values: 1, 2, or 3 (matching Elevator.elevatorId).
     *
     * This is set by the system — the passenger presses a button inside
     * a specific elevator car, so the car identity is implicit.
     */
    private int elevatorId;

    /**
     * The destination floor the passenger wants to reach.
     * Must be within [BuildingConfig.MIN_FLOOR, BuildingConfig.MAX_FLOOR].
     *
     * Note: the target floor CAN be any valid floor, even below the current
     * floor of an upward-moving elevator. LOOK handles this correctly.
     */
    private int targetFloor;

    /**
     * Timestamp when the passenger pressed the button.
     * Used for logging and wait-time analytics.
     */
    @Builder.Default
    private Instant timestamp = Instant.now();

    /**
     * Unique ID for traceability — useful when multiple passengers
     * press the same floor button (deduplication logic can check this).
     */
    private String requestId;
}
