package com.elevator.websocket;

import com.elevator.model.HallRequest;
import com.elevator.dto.ElevatorStateDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ElevatorStatePublisher — Broadcasts elevator state to all connected WebSocket clients.
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * PHASE 6 UPGRADE: STUB → REAL STOMP BROADCAST
 * ═══════════════════════════════════════════════════════════════════════════
 * Phase 3 stub: logged the state to console only.
 * Phase 6 now: calls SimpMessagingTemplate.convertAndSend() to push the
 * live elevator state to every connected browser client via STOMP/WebSocket.
 *
 * Every time the SimulationEngine completes one tick (1 second), it calls
 * broadcastState() — which sends a JSON array of all 3 elevator snapshots
 * to the /topic/elevator-state topic.
 *
 * Every client subscribed to that topic (React UI, monitoring dashboards,
 * etc.) instantly receives the update and re-renders.
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * BROADCAST TOPIC: /topic/elevator-state
 * ═══════════════════════════════════════════════════════════════════════════
 * Client-side subscription (JavaScript/STOMP):
 *
 *   stompClient.subscribe('/topic/elevator-state', (message) => {
 *     const elevators = JSON.parse(message.body);
 *     updateUI(elevators);  // re-render all 3 elevator shafts
 *   });
 *
 * Message body (JSON):
 *   [
 *     { "elevatorId": 1, "currentFloor": 3, "direction": "UP",
 *       "state": "MOVING", "upQueue": [5,7], "downQueue": [], ... },
 *     { "elevatorId": 2, ... },
 *     { "elevatorId": 3, ... }
 *   ]
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * WHY @Autowired (NOT @RequiredArgsConstructor)?
 * ═══════════════════════════════════════════════════════════════════════════
 * SimpMessagingTemplate is created AFTER the WebSocket broker starts, which
 * happens AFTER Spring context initialization. Using field injection with
 * @Autowired avoids any circular dependency or initialization-order issues
 * that can occur with constructor injection when @EnableWebSocketMessageBroker
 * is involved.
 *
 * This is one of the few legitimate cases where field injection is preferred
 * over constructor injection.
 * ═══════════════════════════════════════════════════════════════════════════
 */
@Slf4j
@Component
public class ElevatorStatePublisher {

    /** The STOMP messaging template — sends messages to broker topics. */
    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    /**
     * The topic all clients subscribe to for real-time elevator state updates.
     *
     * Naming convention: /topic/{resource}
     *   /topic/ → broker-managed broadcast (all subscribers receive it)
     *   elevator-state → the resource being published
     */
    private static final String ELEVATOR_STATE_TOPIC = "/topic/elevator-state";

    /**
     * Broadcasts the full system state to all connected WebSocket clients.
     *
     * Called by SimulationEngine after every tick — approximately once per second.
     * The stateDTOs list contains one snapshot per elevator (3 total).
     *
     * Jackson automatically serializes the List<ElevatorStateDTO> to JSON.
     * SimpMessagingTemplate delivers it to every active STOMP subscriber.
     *
     * PERFORMANCE NOTE:
     * convertAndSend() is non-blocking — it queues the message for delivery
     * and returns immediately, so it does not slow down the simulation tick.
     *
     * @param stateDTOs  list of all elevator state snapshots for this tick
     */
    public void broadcastState(List<ElevatorStateDTO> stateDTOs) {
        messagingTemplate.convertAndSend(ELEVATOR_STATE_TOPIC, stateDTOs);
        stateDTOs.forEach(dto ->
            log.debug("[BROADCAST] Elevator {} | Floor:{} | Dir:{} | State:{} | upQ:{} | downQ:{}",
                dto.getElevatorId(), dto.getFloorLabel(), dto.getDirection(),
                dto.getState(), dto.getUpQueue(), dto.getDownQueue()
            )
        );
    }

    /**
     * Broadcasts the set of currently ACTIVE hall call keys to /topic/hall-calls.
     * Called every tick so the frontend can clear glowing buttons as soon as
     * a call is served (dispatcherService.clearHallCall removes it from the map).
     *
     * Key format matches frontend: "floor_DIRECTION" e.g. "5_UP", "-1_DOWN"
     */
    public void broadcastHallCalls(Set<String> activeKeys) {
        messagingTemplate.convertAndSend("/topic/hall-calls", activeKeys);
    }
}
