package com.elevator.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * HallRequest — Represents a passenger pressing the UP or DOWN button
 * on a specific floor's call panel (external request).
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * CONCEPT: HALL CALLS vs CAR CALLS
 * ─────────────────────────────────────────────────────────────────────────────
 * There are two fundamentally different types of requests in an elevator system:
 *
 *   1. HALL CALL (this class):
 *      - Originates OUTSIDE the elevator, on a floor landing
 *      - The passenger does NOT yet know which elevator will pick them up
 *      - The dispatcher decides the best elevator to serve this request
 *      - Contains: which floor called, and which direction they want to go
 *
 *   2. CAR CALL (CarRequest.java):
 *      - Originates INSIDE a specific elevator car
 *      - The passenger is already in the elevator and presses a floor button
 *      - Handled locally by that elevator's scheduler
 *      - Contains: which elevator, which destination floor
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * WHY DIRECTION MATTERS IN A HALL CALL?
 * ─────────────────────────────────────────────────────────────────────────────
 * If someone on floor 5 presses UP, they want to go higher.
 * The Nearest Car algorithm will prefer an elevator that is:
 *   a) Below floor 5 and moving UP (will naturally pass floor 5 going up)
 *   b) Already idle on floor 5
 *
 * Sending a downward-moving elevator to floor 5 for an UP request would be
 * inefficient — that passenger would end up going DOWN before going UP.
 * Direction in hall calls prevents such mismatches.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * REAL-WORLD RELEVANCE:
 * ─────────────────────────────────────────────────────────────────────────────
 * Modern elevator systems (duplex, triplex, group control) maintain a
 * "hall call register" — a data structure that tracks unserviced hall calls.
 * Each entry stores exactly: floor + direction + timestamp.
 * Our HallRequest maps directly to this concept.
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * @Data         → Generates getters, setters, equals, hashCode, toString
 * @Builder      → Enables fluent construction: HallRequest.builder().floor(5)...
 * @NoArgsConstructor → Required for JSON deserialization (Jackson)
 * @AllArgsConstructor → Required by @Builder internally
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HallRequest {

    /**
     * The floor where the hall call originated.
     * Must be within [BuildingConfig.MIN_FLOOR, BuildingConfig.MAX_FLOOR].
     *
     * Example: floor = 5 means someone on the 5th floor pressed the call button.
     */
    private int floor;

    /**
     * The direction the passenger intends to travel.
     * Only UP or DOWN are valid here (not IDLE — that makes no sense for a call).
     *
     * This is crucial for the Nearest Car cost function:
     * matching direction = lower cost = preferred elevator.
     */
    private Direction direction;

    /**
     * Wall-clock timestamp when this request was created.
     * Used for:
     *   - Tie-breaking when two elevators have equal cost
     *   - Monitoring average wait times (fairness metric)
     *   - Logging / analytics
     *
     * Using Instant (UTC) rather than System.currentTimeMillis() for
     * timezone-independence and better interoperability.
     */
    @Builder.Default
    private Instant timestamp = Instant.now();

    /**
     * Unique identifier for this request.
     * Allows the system to:
     *   - Track request lifecycle (created → assigned → served)
     *   - Avoid duplicate assignments
     *   - Cancel specific requests if needed
     */
    private String requestId;
}
