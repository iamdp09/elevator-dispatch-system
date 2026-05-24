package com.elevator.service;

import com.elevator.model.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * SchedulerService — The unified public API for all incoming requests.
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * WHY A SEPARATE SCHEDULER SERVICE?
 * ═══════════════════════════════════════════════════════════════════════════
 * Without this layer, the WebSocket controller (Phase 6) would need to know
 * about BOTH DispatcherService (hall calls) and ElevatorService (car calls).
 * That's two dependencies for presentation-layer code — a violation of the
 * Single Responsibility Principle.
 *
 * SchedulerService is the FAÇADE that hides this complexity:
 *
 *   WebSocket Handler ──▶ SchedulerService ──▶ DispatcherService (hall calls)
 *                                          └──▶ ElevatorService  (car calls)
 *
 * This means Phase 6's WebSocket controller has exactly ONE dependency,
 * and the entire scheduling strategy can change without touching the
 * controller layer.
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * THE LOOK ALGORITHM — HOW REQUESTS FLOW THROUGH THIS LAYER
 * ═══════════════════════════════════════════════════════════════════════════
 *
 *   HALL CALL (floor panel button pressed):
 *   ─────────────────────────────────────────
 *   scheduleHallCall(floor, direction)
 *     └─▶ DispatcherService.dispatch()
 *           ├─ Validates input (floor range, direction)
 *           ├─ Deduplicates (ConcurrentHashMap.putIfAbsent)
 *           ├─ Nearest Car: evaluates estimateCost() for all 3 elevators
 *           ├─ Winner: elevator.addRequest(floor)
 *           │     └─ LOOK routing: goes to upQueue or downQueue
 *           │         based on elevator direction vs floor position
 *           └─ Returns: ASSIGNED / DUPLICATE / QUEUED / REJECTED
 *
 *   CAR CALL (passenger presses floor button inside elevator):
 *   ─────────────────────────────────────────────────────────
 *   scheduleCarCall(elevatorId, targetFloor)
 *     └─▶ ElevatorService.addCarRequest()
 *           └─ elevator.addRequest(floor)
 *                 └─ LOOK routing: upQueue or downQueue
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * LOOK QUEUE ROUTING — direction-aware insertion (inside Elevator.addRequest)
 * ═══════════════════════════════════════════════════════════════════════════
 *
 *   Elevator going UP, new request at floor F:
 *     F > currentFloor  →  upQueue   (serve this sweep)
 *     F < currentFloor  →  downQueue (serve return sweep)
 *     F = currentFloor  →  already here, ignored
 *
 *   Elevator going DOWN, new request at floor F:
 *     F < currentFloor  →  downQueue (serve this sweep)
 *     F > currentFloor  →  upQueue   (serve return sweep)
 *
 *   Elevator IDLE:
 *     F > currentFloor  →  upQueue   + direction set to UP
 *     F < currentFloor  →  downQueue + direction set to DOWN
 *     F = currentFloor  →  open doors immediately (not via queue)
 *
 * This routing is the heart of the LOOK algorithm: it guarantees that
 * the elevator always serves the furthest stop in the current direction
 * before reversing, with no unnecessary extra travel.
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * SYSTEM STATUS
 * ═══════════════════════════════════════════════════════════════════════════
 * getSystemStatus() produces a complete snapshot used for:
 *   - Initial state load when a browser connects (Phase 6 REST endpoint)
 *   - Admin monitoring dashboards
 *   - Debug logging
 * ═══════════════════════════════════════════════════════════════════════════
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SchedulerService {

    private final DispatcherService dispatcherService;
    private final ElevatorService   elevatorService;

    // ═══════════════════════════════════════════════════════════════════════
    // HALL CALL — Passenger presses UP/DOWN on a floor landing
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Schedules a hall call: floor + intended travel direction.
     *
     * Called when a passenger standing on floor F presses the UP or DOWN
     * button on the hall panel. The system must choose the best elevator
     * and route it to floor F.
     *
     * LOOK routing happens inside Elevator.addRequest():
     *   - If the assigned elevator is going UP and F is above it → upQueue
     *   - If the assigned elevator is going UP and F is below it → downQueue
     *   The elevator will naturally pick up this passenger without detour.
     *
     * @param floor      floor where the button was pressed
     * @param direction  UP or DOWN (passenger's intended travel direction)
     * @return           dispatch outcome (ASSIGNED / DUPLICATE / QUEUED / REJECTED)
     */
    public DispatchResult scheduleHallCall(int floor, Direction direction) {
        log.info("→ Hall call: floor {} {}", BuildingConfig.floorLabel(floor), direction);
        DispatchResult result = dispatcherService.dispatch(floor, direction);
        log.info("← Hall call result: {} (floor {} {})", result, BuildingConfig.floorLabel(floor), direction);
        return result;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // CAR CALL — Passenger inside elevator presses a floor button
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Schedules a car call: a specific elevator must go to a specific floor.
     *
     * Called when a passenger already INSIDE elevator N presses button for
     * floor F. Unlike hall calls, we already know WHICH elevator — no dispatch
     * needed. The request goes straight into that elevator's LOOK queue.
     *
     * LOOK routing inside Elevator.addRequest():
     *   If elevator is going UP   and F > currentFloor → upQueue
     *   If elevator is going UP   and F < currentFloor → downQueue (return sweep)
     *   If elevator is going DOWN and F < currentFloor → downQueue
     *   If elevator is going DOWN and F > currentFloor → upQueue   (return sweep)
     *
     * @param elevatorId  which elevator the passenger is in
     * @param targetFloor which floor button they pressed
     * @return            true if queued, false if rejected (out of range / bad id)
     */
    public boolean scheduleCarCall(int elevatorId, int targetFloor) {
        log.info("→ Car call: Elevator {} → floor {}", elevatorId, BuildingConfig.floorLabel(targetFloor));
        boolean accepted = elevatorService.addCarRequest(elevatorId, targetFloor);
        if (accepted) {
            log.info("← Car call accepted: Elevator {} will stop at floor {}",
                    elevatorId, BuildingConfig.floorLabel(targetFloor));
        } else {
            log.warn("← Car call rejected: Elevator {} / floor {}",
                    elevatorId, BuildingConfig.floorLabel(targetFloor));
        }
        return accepted;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // SYSTEM STATUS — Complete snapshot for REST/WebSocket initial load
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Returns a complete snapshot of the current system state.
     *
     * Structure:
     * {
     *   "elevators": [ { id, floor, direction, state, load, upQueue, downQueue }, ... ],
     *   "activeHallCalls": { "5_UP": {...}, "-2_DOWN": {...} },
     *   "pendingQueueSize": 0,
     *   "dispatchStats": { "dispatched": 12, "duplicates": 3, ... },
     *   "building": { "minFloor": -3, "maxFloor": 10, "totalFloors": 14 }
     * }
     *
     * WHY NOT RETURN A TYPED DTO HERE?
     * ─────────────────────────────────
     * A Map gives flexibility for Phase 6: the WebSocket can serialize it
     * directly with Jackson without needing a separate SystemStatusDTO class.
     * We can refine to a typed DTO in Phase 7 when the frontend contract solidifies.
     *
     * @return system status snapshot
     */
    public Map<String, Object> getSystemStatus() {
        List<Elevator> elevators = elevatorService.getAllElevators();

        List<Map<String, Object>> elevatorSnapshots = new ArrayList<>();
        for (Elevator e : elevators) {
            Map<String, Object> snap = new LinkedHashMap<>();
            snap.put("id",            e.getElevatorId());
            snap.put("floor",         e.getCurrentFloor());
            snap.put("floorLabel",    BuildingConfig.floorLabel(e.getCurrentFloor()));
            snap.put("direction",     e.getDirection().name());
            snap.put("state",         e.getState().name());
            snap.put("passengerLoad", e.getPassengerLoad());
            snap.put("capacity",      e.getCapacity());
            snap.put("atCapacity",    e.isAtCapacity());
            snap.put("upQueue",       e.getUpQueueSnapshot());
            snap.put("downQueue",     e.getDownQueueSnapshot());
            snap.put("doorTicks",     e.getDoorOpenTicksRemaining());
            elevatorSnapshots.add(snap);
        }

        Map<String, Object> status = new LinkedHashMap<>();
        status.put("elevators",       elevatorSnapshots);
        status.put("activeHallCalls", dispatcherService.getActiveHallCalls());
        status.put("pendingQueue",    dispatcherService.getPendingQueueSize());
        status.put("dispatchStats",   dispatcherService.getStats());
        status.put("building", Map.of(
            "minFloor",    BuildingConfig.MIN_FLOOR,
            "maxFloor",    BuildingConfig.MAX_FLOOR,
            "totalFloors", BuildingConfig.TOTAL_FLOORS,
            "elevators",   BuildingConfig.TOTAL_ELEVATORS
        ));

        return status;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // CONVENIENCE QUERY METHODS
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Checks whether a hall call is currently active (button lit) for a floor.
     * Used by the frontend to show correct button state on reconnect.
     */
    public boolean isHallCallActive(int floor, Direction direction) {
        return dispatcherService.isHallCallActive(floor, direction);
    }

    /**
     * Returns all active hall calls. Used for initial state sync when
     * a new browser client connects via WebSocket.
     */
    public Map<String, HallRequest> getActiveHallCalls() {
        return dispatcherService.getActiveHallCalls();
    }
}
