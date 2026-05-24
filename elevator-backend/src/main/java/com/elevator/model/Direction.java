package com.elevator.model;

/**
 * Direction — Enum representing the current travel direction of an elevator
 * or the intended direction of a hall call.
 *
 * WHY AN ENUM?
 * ─────────────
 * In real elevator systems, direction is a finite, well-defined state.
 * Using an enum instead of strings or integers:
 *   - Prevents invalid values at compile time
 *   - Makes switch statements exhaustive (IDE warns if a case is missing)
 *   - Self-documents intent in method signatures
 *
 * USAGE CONTEXT:
 * ─────────────
 *   - Elevator.direction         → which way the car is currently moving
 *   - HallRequest.direction      → which way the passenger wants to go
 *   - LOOK algorithm comparisons → "is this request in my current sweep?"
 *
 * REAL-WORLD RELEVANCE:
 * ──────────────────────
 * Real elevator controllers maintain a direction flag in firmware registers.
 * The LOOK algorithm reads this flag to decide whether to stop at a floor
 * or continue past it. Our enum mirrors that exact concept.
 */
public enum Direction {

    /**
     * Elevator (or passenger) is going UP.
     * In LOOK algorithm: serves floors higher than currentFloor first.
     */
    UP,

    /**
     * Elevator (or passenger) is going DOWN.
     * In LOOK algorithm: serves floors lower than currentFloor first.
     */
    DOWN,

    /**
     * No direction — elevator is stationary with no pending requests.
     * An IDLE elevator is the top candidate for new hall call assignments
     * when it is closest to the requesting floor.
     */
    IDLE
}
