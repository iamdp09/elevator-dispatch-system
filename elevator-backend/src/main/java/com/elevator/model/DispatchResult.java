package com.elevator.model;

/**
 * DispatchResult — The outcome of a hall call dispatch attempt.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * WHY AN ENUM INSTEAD OF BOOLEAN?
 * ─────────────────────────────────────────────────────────────────────────────
 * A boolean "success/failure" is too coarse. There are meaningfully different
 * outcomes that the WebSocket handler needs to communicate back to the client:
 *
 *   ASSIGNED  → Elevator is on its way. Button should light up green.
 *   DUPLICATE → Request already being handled. Don't double-register.
 *   QUEUED    → All elevators full. Button stays lit; retry will happen.
 *   REJECTED  → Invalid input (bad floor, invalid direction). Show error.
 *
 * This is the "Result Object" pattern — returning rich status information
 * instead of ambiguous primitives. Used extensively in real elevator group
 * control software (Schindler PORT, KONE eLink, etc.).
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * REAL-WORLD PARALLEL
 * ─────────────────────────────────────────────────────────────────────────────
 * In modern destination dispatch systems (like Schindler PORT), the keypad
 * can display "Elevator B — Level 12 in 45 seconds" or "Please wait, all cars
 * are busy". These different UI states map directly to ASSIGNED vs QUEUED.
 * ─────────────────────────────────────────────────────────────────────────────
 */
public enum DispatchResult {

    /**
     * Hall call successfully assigned to an elevator.
     * An elevator is now committed to serving this floor.
     * The hall button indicator should light up and the client
     * should receive an elevator ETA.
     */
    ASSIGNED,

    /**
     * A hall call for this floor+direction is already active.
     * The system is already handling it — no new assignment needed.
     * This prevents passengers from triggering duplicate assignments
     * by pressing the button repeatedly.
     *
     * Real systems: button press while lit → ignored, stays lit.
     */
    DUPLICATE,

    /**
     * Request registered, but could not be assigned immediately.
     * All elevators are at capacity (passenger load = max).
     * The request is placed in the retry queue and will be assigned
     * as soon as an elevator becomes available (capacity decreases).
     *
     * Real systems: this rarely happens but is handled via a
     * "pending call register" that the group controller polls.
     */
    QUEUED,

    /**
     * Request rejected due to invalid input.
     * Reasons: floor out of range, direction = IDLE, or floor already
     * at max allowed floor for direction (e.g., floor 10 pressing UP).
     *
     * The client should display an error and not light the button.
     */
    REJECTED
}
