package com.elevator.rest;

import com.elevator.model.DispatchResult;
import com.elevator.model.Direction;
import com.elevator.service.SchedulerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * ElevatorRestController — HTTP REST endpoints for initial state and admin use.
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * WHY REST AND WEBSOCKET TOGETHER?
 * ═══════════════════════════════════════════════════════════════════════════
 * WebSocket is a persistent connection — perfect for real-time streaming.
 * But WebSocket has a limitation: when a new browser connects, it gets no
 * history. It will only see updates from the NEXT tick onward.
 *
 * REST solves this: when the React app boots, it calls GET /api/state to
 * get the CURRENT system snapshot immediately. Then it subscribes to WebSocket
 * for subsequent real-time updates.
 *
 * Pattern:
 *   1. Page loads → GET /api/state → render initial building layout
 *   2. WebSocket connects → subscribe /topic/elevator-state
 *   3. Each tick → WebSocket update → update UI (floor positions, queues)
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * ENDPOINTS
 * ═══════════════════════════════════════════════════════════════════════════
 *   GET  /api/state             → Full system snapshot (initial load)
 *   GET  /api/elevators         → All elevator states only
 *   POST /api/hall-call         → Dispatch a hall call via HTTP (testing/admin)
 *   POST /api/car-call          → Dispatch a car call via HTTP (testing/admin)
 *   GET  /api/hall-calls/active → All currently active hall calls
 *   GET  /api/stats             → Dispatcher statistics
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * CORS CONFIG
 * ═══════════════════════════════════════════════════════════════════════════
 * @CrossOrigin allows the Vite dev server (port 5173) to call these endpoints
 * during development. In production, configure via Spring Security or a
 * gateway-level CORS policy instead.
 * ═══════════════════════════════════════════════════════════════════════════
 */
@Slf4j
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class ElevatorRestController {

    private final SchedulerService schedulerService;

    // ═══════════════════════════════════════════════════════════════════════
    // INITIAL STATE — Called once on browser page load
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Returns the full system state snapshot.
     *
     * Called by the React app on mount to immediately render all elevator
     * positions before the WebSocket stream delivers its first update.
     *
     * Response includes: all elevator states, active hall calls,
     * pending queue size, dispatch stats, and building config.
     *
     * GET /api/state
     */
    @GetMapping("/state")
    public ResponseEntity<Map<String, Object>> getSystemState() {
        log.debug("REST → GET /api/state");
        return ResponseEntity.ok(schedulerService.getSystemStatus());
    }

    /**
     * Returns only the elevator list (lighter payload than full state).
     * Useful for monitoring dashboards that only need car positions.
     *
     * GET /api/elevators
     */
    @GetMapping("/elevators")
    public ResponseEntity<Object> getElevators() {
        Map<String, Object> status = schedulerService.getSystemStatus();
        return ResponseEntity.ok(status.get("elevators"));
    }

    // ═══════════════════════════════════════════════════════════════════════
    // HALL CALL — HTTP fallback for testing / admin tooling
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Dispatches a hall call via HTTP POST.
     *
     * Primarily used for:
     *   - Integration testing (curl / Postman without WebSocket client)
     *   - Admin tooling
     *   - Phase 6 E2E tests that run before the React app is built
     *
     * POST /api/hall-call
     * Body: { "floor": 5, "direction": "UP" }
     *
     * Returns 200 with dispatch result, or 400 if REJECTED.
     */
    @PostMapping("/hall-call")
    public ResponseEntity<Map<String, Object>> postHallCall(
            @RequestParam int floor,
            @RequestParam Direction direction) {

        log.info("REST → POST /api/hall-call?floor={}&direction={}", floor, direction);
        DispatchResult result = schedulerService.scheduleHallCall(floor, direction);

        Map<String, Object> response = Map.of(
            "result",    result.name(),
            "floor",     floor,
            "direction", direction.name()
        );

        return result == DispatchResult.REJECTED
                ? ResponseEntity.badRequest().body(response)
                : ResponseEntity.ok(response);
    }

    /**
     * Dispatches a car call via HTTP POST.
     *
     * POST /api/car-call
     * Params: elevatorId, targetFloor
     */
    @PostMapping("/car-call")
    public ResponseEntity<Map<String, Object>> postCarCall(
            @RequestParam int elevatorId,
            @RequestParam int targetFloor) {

        log.info("REST → POST /api/car-call?elevatorId={}&targetFloor={}", elevatorId, targetFloor);
        boolean accepted = schedulerService.scheduleCarCall(elevatorId, targetFloor);

        Map<String, Object> response = Map.of(
            "accepted",    accepted,
            "elevatorId",  elevatorId,
            "targetFloor", targetFloor
        );

        return accepted
                ? ResponseEntity.ok(response)
                : ResponseEntity.badRequest().body(response);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // MONITORING ENDPOINTS
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Returns all currently active (unserved) hall calls.
     * Used by admin dashboards to see which floors are waiting.
     *
     * GET /api/hall-calls/active
     */
    @GetMapping("/hall-calls/active")
    public ResponseEntity<Object> getActiveHallCalls() {
        return ResponseEntity.ok(schedulerService.getActiveHallCalls());
    }

    /**
     * Returns cumulative dispatch statistics.
     * Useful for monitoring: how many calls were assigned vs queued vs rejected.
     *
     * GET /api/stats
     */
    @GetMapping("/stats")
    public ResponseEntity<Object> getStats() {
        Map<String, Object> status = schedulerService.getSystemStatus();
        return ResponseEntity.ok(status.get("dispatchStats"));
    }
}
