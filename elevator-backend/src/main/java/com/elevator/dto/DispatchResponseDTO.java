package com.elevator.dto;

import com.elevator.model.DispatchResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DispatchResponseDTO — Sent back to the client after a hall or car call.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * RESPONSE FLOW:
 * ─────────────────────────────────────────────────────────────────────────────
 * Client sends:  /app/hall-call  { floor: 5, direction: "UP" }
 * Server handler calls scheduleHallCall()
 * Server responds to: /topic/dispatch-result
 *   {
 *     "result": "ASSIGNED",
 *     "message": "Elevator 2 is on its way to floor 5",
 *     "floor": 5,
 *     "direction": "UP",
 *     "assignedElevatorId": 2
 *   }
 *
 * The frontend uses this to:
 *   - Light up the correct hall call button (ASSIGNED)
 *   - Show a "waiting" indicator (QUEUED)
 *   - Flash an error (REJECTED)
 *   - Do nothing (DUPLICATE — already handled)
 * ─────────────────────────────────────────────────────────────────────────────
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DispatchResponseDTO {

    /** The outcome of the dispatch attempt. */
    private DispatchResult result;

    /** Human-readable message for the UI to display. */
    private String message;

    /** The floor that was requested (echo for client-side correlation). */
    private int floor;

    /** The direction of the request (echo). */
    private String direction;

    /**
     * ID of the elevator assigned to this request.
     * -1 if not assigned (QUEUED, REJECTED, DUPLICATE).
     */
    private int assignedElevatorId;
}
