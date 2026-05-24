package com.elevator.websocket;

import com.elevator.dto.CarRequestDTO;
import com.elevator.dto.DispatchResponseDTO;
import com.elevator.dto.HallRequestDTO;
import com.elevator.model.DispatchResult;
import com.elevator.service.SchedulerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.stereotype.Controller;

/**
 * ElevatorWebSocketController — Handles all real-time messages from the frontend.
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * MESSAGE FLOW
 * ═══════════════════════════════════════════════════════════════════════════
 *
 *  ┌─────────────────────────────────────────────────────────────────────┐
 *  │  Browser (React)                                                    │
 *  │   stompClient.send('/app/hall-call', {}, JSON.stringify({           │
 *  │       floor: 5, direction: 'UP'                                     │
 *  │   }));                                                              │
 *  └──────────────────────┬──────────────────────────────────────────────┘
 *                         │  STOMP SEND → /app/hall-call
 *                         ▼
 *  ┌─────────────────────────────────────────────────────────────────────┐
 *  │  ElevatorWebSocketController                                        │
 *  │   @MessageMapping("/hall-call") handleHallCall()                    │
 *  │     └─▶ schedulerService.scheduleHallCall(floor, direction)         │
 *  │           └─▶ DispatcherService → Nearest Car algorithm             │
 *  │                 └─▶ Elevator.addRequest() → LOOK queue routing      │
 *  │   Returns DispatchResponseDTO                                       │
 *  └──────────────────────┬──────────────────────────────────────────────┘
 *                         │  @SendTo → /topic/dispatch-result
 *                         ▼
 *  ┌─────────────────────────────────────────────────────────────────────┐
 *  │  All subscribed browsers receive dispatch result:                   │
 *  │   { result: "ASSIGNED", message: "Elevator 2 coming to floor 5",   │
 *  │     floor: 5, direction: "UP", assignedElevatorId: 2 }             │
 *  └─────────────────────────────────────────────────────────────────────┘
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * @MessageMapping vs @RequestMapping
 * ═══════════════════════════════════════════════════════════════════════════
 * @RequestMapping  → handles HTTP requests (stateless, one-time)
 * @MessageMapping  → handles STOMP WebSocket messages (stateful session)
 *
 * The /app prefix is stripped automatically (configured in WebSocketConfig),
 * so @MessageMapping("/hall-call") handles messages sent to /app/hall-call.
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * @SendTo vs SimpMessagingTemplate
 * ═══════════════════════════════════════════════════════════════════════════
 * @SendTo("/topic/X"): broadcasts the return value of the handler to ALL
 * subscribers of /topic/X. Simple — good for responses all clients need.
 *
 * SimpMessagingTemplate.convertAndSendToUser(): sends to a SPECIFIC user.
 * Used for private responses (like "your request failed") in Phase 8.
 *
 * We use @SendTo here because dispatch results are relevant to ALL clients
 * (they all need to update button states based on who got assigned).
 * ═══════════════════════════════════════════════════════════════════════════
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class ElevatorWebSocketController {

    private final SchedulerService schedulerService;

    // ═══════════════════════════════════════════════════════════════════════
    // HALL CALL HANDLER — Passenger presses UP/DOWN on floor panel
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Handles a hall call request from any floor panel.
     *
     * Client sends to:  /app/hall-call
     * Payload:          { "floor": 5, "direction": "UP" }
     * Response sent to: /topic/dispatch-result (all subscribers)
     *
     * @param dto  deserialized hall call request from the client
     * @return     dispatch outcome wrapped in a DTO
     */
    @MessageMapping("/hall-call")
    @SendTo("/topic/dispatch-result")
    public DispatchResponseDTO handleHallCall(HallRequestDTO dto) {
        log.info("WebSocket ← HallCall: floor={} direction={}", dto.getFloor(), dto.getDirection());

        DispatchResult result = schedulerService.scheduleHallCall(dto.getFloor(), dto.getDirection());

        String message = buildHallCallMessage(result, dto.getFloor(), dto.getDirection().name());

        return DispatchResponseDTO.builder()
                .result(result)
                .message(message)
                .floor(dto.getFloor())
                .direction(dto.getDirection().name())
                .assignedElevatorId(-1) // Specific elevator ID will be in Phase 8 (user-targeted messages)
                .build();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // CAR CALL HANDLER — Passenger presses floor button inside elevator
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Handles a car call request from inside a specific elevator.
     *
     * Client sends to:  /app/car-call
     * Payload:          { "elevatorId": 2, "targetFloor": 7 }
     * Response sent to: /topic/dispatch-result (all subscribers)
     *
     * @param dto  deserialized car call request
     * @return     result DTO indicating acceptance or rejection
     */
    @MessageMapping("/car-call")
    @SendTo("/topic/dispatch-result")
    public DispatchResponseDTO handleCarCall(CarRequestDTO dto) {
        log.info("WebSocket ← CarCall: elevator={} floor={}", dto.getElevatorId(), dto.getTargetFloor());

        boolean accepted = schedulerService.scheduleCarCall(dto.getElevatorId(), dto.getTargetFloor());

        DispatchResult result = accepted ? DispatchResult.ASSIGNED : DispatchResult.REJECTED;
        String message = accepted
                ? String.format("Elevator %d will stop at floor %d", dto.getElevatorId(), dto.getTargetFloor())
                : String.format("Car call rejected: Elevator %d / floor %d (invalid)",
                                dto.getElevatorId(), dto.getTargetFloor());

        return DispatchResponseDTO.builder()
                .result(result)
                .message(message)
                .floor(dto.getTargetFloor())
                .direction("N/A")            // Car calls have no direction — destination known
                .assignedElevatorId(accepted ? dto.getElevatorId() : -1)
                .build();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // INTERNAL — Message builders
    // ═══════════════════════════════════════════════════════════════════════

    private String buildHallCallMessage(DispatchResult result, int floor, String direction) {
        return switch (result) {
            case ASSIGNED  -> String.format("Elevator assigned for floor %d %s", floor, direction);
            case DUPLICATE -> String.format("Floor %d %s is already being served", floor, direction);
            case QUEUED    -> String.format("Floor %d %s queued — all elevators busy", floor, direction);
            case REJECTED  -> String.format("Invalid request: floor %d %s", floor, direction);
        };
    }
}
