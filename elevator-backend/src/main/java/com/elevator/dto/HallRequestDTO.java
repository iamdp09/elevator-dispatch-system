package com.elevator.dto;

import com.elevator.model.Direction;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * HallRequestDTO — Incoming WebSocket message from the frontend
 * when a user presses an UP or DOWN button on a floor panel.
 *
 * Simple payload: just the floor and direction.
 * The backend dispatcher will handle the rest.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class HallRequestDTO {

    /** Floor where the hall call button was pressed. */
    private int floor;

    /** Direction the passenger wants to travel (UP or DOWN). */
    private Direction direction;
}
