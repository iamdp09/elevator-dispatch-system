package com.elevator.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * CarRequestDTO — Incoming WebSocket message from the frontend
 * when a passenger presses a floor button INSIDE an elevator.
 *
 * The elevator ID comes from the UI panel the user interacted with.
 * The target floor is the button they pressed.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CarRequestDTO {

    /** Which elevator received this button press (1, 2, or 3). */
    private int elevatorId;

    /** The destination floor the passenger selected. */
    private int targetFloor;
}
