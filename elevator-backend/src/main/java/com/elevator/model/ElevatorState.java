package com.elevator.model;

/**
 * ElevatorState — Enum representing the operational state of an elevator car.
 *
 * WHY A STATE MACHINE?
 * ─────────────────────
 * Elevator behavior is a classic finite state machine (FSM). At any moment
 * the car is in exactly ONE of these three states, and transitions between
 * states follow strict rules:
 *
 *         ┌─────────────────────────────────────────────┐
 *         │                                             │
 *         ▼                                             │
 *   ┌──────────┐   has request    ┌─────────┐           │
 *   │  IDLE    │ ───────────────► │ MOVING  │           │
 *   └──────────┘                  └────┬────┘           │
 *         ▲                           │ reached floor   │
 *         │                           ▼                 │
 *         │                    ┌───────────┐            │
 *         │ door closed        │ DOOR_OPEN │            │
 *         └────────────────────└───────────┘ ──────────►┘
 *                                            doors close, requests remain
 *
 * TRANSITION RULES:
 * ──────────────────
 *   IDLE      → MOVING     when a new request is assigned
 *   MOVING    → DOOR_OPEN  when currentFloor matches a stop in the queue
 *   DOOR_OPEN → MOVING     when door-close timer expires AND more stops exist
 *   DOOR_OPEN → IDLE       when door-close timer expires AND no more stops
 *
 * REAL-WORLD RELEVANCE:
 * ──────────────────────
 * Real elevator PLCs (Programmable Logic Controllers) use exactly this FSM.
 * The state determines which hardware signals are active:
 *   IDLE      → motor off, brake engaged, door closed
 *   MOVING    → motor on, brake released, door locked
 *   DOOR_OPEN → motor off, brake engaged, door unlocked, buzzer/timer active
 */
public enum ElevatorState {

    /**
     * Elevator is stationary with no pending destinations.
     * Doors are closed. Motor is off.
     * This elevator is the preferred candidate for new assignments when nearest.
     */
    IDLE,

    /**
     * Elevator is actively traveling between floors.
     * Doors are locked (safety requirement in real systems).
     * Floors are added/removed from queue dynamically during this state.
     */
    MOVING,

    /**
     * Elevator has arrived at a destination floor.
     * Doors are open for passenger boarding/alighting.
     * A countdown timer controls when doors close automatically.
     * In our simulation: door stays open for 2 simulation ticks.
     */
    DOOR_OPEN
}
