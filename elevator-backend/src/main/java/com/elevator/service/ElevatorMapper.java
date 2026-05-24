package com.elevator.service;

import com.elevator.dto.ElevatorStateDTO;
import com.elevator.model.BuildingConfig;
import com.elevator.model.Direction;
import com.elevator.model.Elevator;
import com.elevator.model.ElevatorState;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

/**
 * ElevatorMapper — Converts Elevator domain entities into ElevatorStateDTOs
 * for WebSocket broadcast to the frontend.
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * WHAT IS A MAPPER AND WHY DO WE NEED IT?
 * ═══════════════════════════════════════════════════════════════════════════
 * A Mapper is a class that transforms objects from one "layer" of the
 * application to another. In clean architecture:
 *
 *   Domain Layer  →  [Mapper]  →  DTO Layer  →  Transport Layer (WebSocket/REST)
 *
 * Without a mapper, you'd be tempted to put conversion logic inside the
 * service or controller — violating single responsibility principle.
 * Having a dedicated mapper means:
 *   ✓ Conversion logic is in exactly one place
 *   ✓ Unit testable in isolation
 *   ✓ Easy to update if DTO shape changes (frontend evolves)
 *   ✓ Domain model stays clean — no JSON annotations on business objects
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * WHAT DOES IT DO?
 * ═══════════════════════════════════════════════════════════════════════════
 * toDTO(Elevator)         → single Elevator → ElevatorStateDTO
 * toDTOList(List<Elevator>) → all elevators → List<ElevatorStateDTO>
 *
 * During conversion it:
 *   - Copies primitive fields directly
 *   - Converts TreeSet queues → plain HashSet (Jackson-serializable)
 *   - Computes the "doorOpen" boolean shortcut
 *   - Generates the human-readable floorLabel
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * THREAD SAFETY NOTE
 * ═══════════════════════════════════════════════════════════════════════════
 * toDTO() reads from an Elevator object. Because Elevator uses volatile
 * fields and synchronized queue methods, we call a snapshot method to
 * get consistent queue copies before building the DTO. This avoids reading
 * state mid-update from the simulation tick thread.
 * ═══════════════════════════════════════════════════════════════════════════
 */
@Component
public class ElevatorMapper {

    /**
     * Converts a single Elevator entity to a broadcast-safe DTO.
     *
     * The conversion is synchronized at the Elevator level — we call
     * getUpQueueSnapshot() and getDownQueueSnapshot() which are
     * synchronized methods, ensuring we read consistent queue state
     * even if the simulation thread is mid-update.
     *
     * @param elevator  the elevator entity to convert
     * @return          a serializable DTO safe to send over WebSocket
     */
    public ElevatorStateDTO toDTO(Elevator elevator) {
        // Read all volatile primitives atomically
        int currentFloor   = elevator.getCurrentFloor();
        Direction direction = elevator.getDirection();
        ElevatorState state = elevator.getState();
        int passengerLoad  = elevator.getPassengerLoad();
        int capacity       = elevator.getCapacity();

        // Get a safe copy of the queues (synchronized inside Elevator)
        // We copy into a plain HashSet so Jackson can serialize without issues.
        // TreeSet with custom Comparator can confuse Jackson's type system.
        HashSet<Integer> upQueueSnapshot   = elevator.getUpQueueSnapshot();
        HashSet<Integer> downQueueSnapshot = elevator.getDownQueueSnapshot();

        return ElevatorStateDTO.builder()
            .elevatorId(elevator.getElevatorId())
            .currentFloor(currentFloor)
            .direction(direction)
            .state(state)
            .upQueue(upQueueSnapshot)
            .downQueue(downQueueSnapshot)
            .passengerLoad(passengerLoad)
            .capacity(capacity)
            // Convenience boolean: frontend doesn't need to parse enum
            .doorOpen(state == ElevatorState.DOOR_OPEN)
            // Pre-computed display label: "B3", "G", "7", etc.
            .floorLabel(BuildingConfig.floorLabel(currentFloor))
            .build();
    }

    /**
     * Converts all elevators to a list of DTOs.
     * Called every simulation tick to produce the full system state snapshot.
     *
     * @param elevators  list of all elevator instances (from ElevatorService)
     * @return           ordered list of DTOs, one per elevator
     */
    public List<ElevatorStateDTO> toDTOList(List<Elevator> elevators) {
        return elevators.stream()
            .map(this::toDTO)
            .collect(Collectors.toList());
    }
}
