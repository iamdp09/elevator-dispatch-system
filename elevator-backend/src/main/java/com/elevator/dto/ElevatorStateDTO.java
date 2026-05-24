package com.elevator.dto;

import com.elevator.model.Direction;
import com.elevator.model.ElevatorState;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;
import java.util.TreeSet;

/**
 * ElevatorStateDTO — The data snapshot sent to the frontend via WebSocket
 * every simulation tick.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * WHY A SEPARATE DTO INSTEAD OF SENDING THE ELEVATOR ENTITY DIRECTLY?
 * ─────────────────────────────────────────────────────────────────────────────
 * 1. SECURITY: The Elevator entity has synchronized methods and internal
 *    state that should never be exposed over the wire.
 *
 * 2. DECOUPLING: Frontend doesn't need every field. The DTO is tailored
 *    exactly to what the React UI needs for rendering.
 *
 * 3. SERIALIZATION SAFETY: TreeSet with Comparators doesn't serialize well
 *    with Jackson. We convert to a plain Set<Integer> for clean JSON.
 *
 * 4. VERSIONING: If the backend domain model changes, we can update the DTO
 *    independently without breaking the frontend contract.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * JSON OUTPUT EXAMPLE:
 * ─────────────────────────────────────────────────────────────────────────────
 * {
 *   "elevatorId": 1,
 *   "currentFloor": 3,
 *   "direction": "UP",
 *   "state": "MOVING",
 *   "upQueue": [5, 7],
 *   "downQueue": [],
 *   "passengerLoad": 2,
 *   "capacity": 10,
 *   "doorOpen": false,
 *   "floorLabel": "3"
 * }
 * ─────────────────────────────────────────────────────────────────────────────
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ElevatorStateDTO {

    /** Elevator ID (1, 2, or 3). Matches the UI's elevator column index. */
    private int elevatorId;

    /** Current floor number (can be negative for basement). */
    private int currentFloor;

    /** Travel direction for the animated arrow indicator. */
    private Direction direction;

    /** Operational state drives door animation and status badge. */
    private ElevatorState state;

    /**
     * Floors queued for upward travel — displayed in the car panel UI
     * with lit-up buttons to show which floors will be visited.
     */
    private Set<Integer> upQueue;

    /**
     * Floors queued for downward travel.
     */
    private Set<Integer> downQueue;

    /** Passenger load for capacity bar in UI. */
    private int passengerLoad;

    /** Max capacity for capacity bar in UI. */
    private int capacity;

    /** Convenience boolean so frontend doesn't parse ElevatorState enum. */
    private boolean doorOpen;

    /**
     * Human-readable floor label (e.g., "B3", "G", "7").
     * Pre-computed to reduce frontend logic complexity.
     */
    private String floorLabel;
}
