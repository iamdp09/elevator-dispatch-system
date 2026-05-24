package com.elevator.service;

import com.elevator.model.BuildingConfig;
import com.elevator.model.Direction;
import com.elevator.model.Elevator;
import com.elevator.model.ElevatorState;
import com.elevator.websocket.ElevatorStatePublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * SimulationEngine — The heartbeat of the entire elevator system.
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * WHAT IS A SIMULATION TICK?
 * ═══════════════════════════════════════════════════════════════════════════
 * A "tick" is one unit of simulated time — in our system, 1 second.
 * Every tick, the simulation engine drives EVERY elevator through one
 * atomic step of its state machine:
 *
 *   DOOR_OPEN → countdown timer decrements. When 0: close doors, apply LOOK
 *   MOVING    → car moves ONE floor toward its next stop.
 *               If it arrives: open doors, remove floor from queues.
 *   IDLE      → nothing to do (elevator waits for new requests via addRequest)
 *
 * After all elevators are processed, the current state is published to
 * all frontend clients via WebSocket.
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * REAL-WORLD RELEVANCE
 * ═══════════════════════════════════════════════════════════════════════════
 * Real elevator controllers run on a fixed interrupt cycle (typically 50ms
 * to 200ms) driven by a hardware timer. Each interrupt:
 *   1. Reads sensor inputs (floor position, door sensors, call buttons)
 *   2. Updates internal state machine
 *   3. Writes output signals (motor direction, door relay, floor indicators)
 *
 * Our @Scheduled tick is the software equivalent of that hardware interrupt.
 * The key property of both: DETERMINISTIC — state only changes at tick
 * boundaries, never mid-tick.
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * CONCURRENCY MODEL
 * ═══════════════════════════════════════════════════════════════════════════
 * The simulation tick runs on Spring's task scheduler thread.
 * WebSocket handlers (adding new requests) run on separate threads.
 *
 * Thread safety is achieved through:
 *   1. Individual Elevator methods are synchronized (Phase 1 design)
 *   2. SimulationEngine reads queues via synchronized snapshot methods
 *   3. @Scheduled with fixedDelay (not fixedRate) prevents tick overlap —
 *      next tick only starts AFTER the previous one fully completes
 *
 * WHY fixedDelay OVER fixedRate?
 * ────────────────────────────────
 * fixedRate: starts next tick regardless of whether previous finished.
 *   Risk: if a tick takes >1s (e.g., slow WebSocket broadcast), ticks pile up.
 * fixedDelay: waits 1s AFTER previous tick completes before starting next.
 *   Guarantee: only ONE tick active at any time. Perfect for a state machine.
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * THE LOOK ALGORITHM WITHIN THE TICK
 * ═══════════════════════════════════════════════════════════════════════════
 * LOOK lives in the TRANSITION from DOOR_OPEN → next action:
 *
 *   After closing doors:
 *     Has more floors in current direction?
 *       YES → keep moving (state = MOVING, direction unchanged)
 *       NO  → look the other way (any floors in opposite direction?)
 *                YES → reverse direction, state = MOVING
 *                NO  → no more work (state = IDLE, direction = IDLE)
 *
 * LOOK's key property: "look in current direction first, then switch."
 * This guarantees no unnecessary directional reversals.
 * ═══════════════════════════════════════════════════════════════════════════
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SimulationEngine {

    private final ElevatorStatePublisher publisher;
    private final ElevatorMapper         elevatorMapper;
    private final ElevatorService        elevatorService;
    private final DispatcherService      dispatcherService;

    // ── Stuck-elevator watchdog ───────────────────────────────────────────────
    // Tracks how many consecutive ticks each elevator has been in MOVING state
    // without changing floor. If this exceeds STUCK_THRESHOLD the elevator is
    // reset to IDLE so it can be re-assigned by the dispatcher on the next tick.
    private final java.util.concurrent.ConcurrentHashMap<Integer, Integer> lastFloorByElevator  = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<Integer, Integer> stuckTicksByElevator = new java.util.concurrent.ConcurrentHashMap<>();
    private static final int STUCK_THRESHOLD = 20; // 20 seconds without moving = stuck

    /** Running tick counter for logging and debugging. */
    private long tickCount = 0;

    // ═══════════════════════════════════════════════════════════════════════
    // MAIN TICK LOOP
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * The main simulation tick — runs every TICK_INTERVAL_MS milliseconds.
     *
     * fixedDelay ensures the next tick starts TICK_INTERVAL_MS after the
     * PREVIOUS tick COMPLETES, never overlapping.
     *
     * initialDelay = 2000ms: give the Spring context time to fully initialize
     * all beans (ElevatorService, WebSocket config etc.) before starting.
     */
    @Scheduled(fixedDelay = BuildingConfig.TICK_INTERVAL_MS, initialDelay = 2000)
    public void tick() {
        tickCount++;
        log.debug("── Tick #{} ──────────────────────────", tickCount);

        // ── Phase 4 integration: Retry any capacity-blocked hall calls FIRST ──
        // Must run BEFORE movement so freed capacity is exploited in the same tick.
        dispatcherService.retryPendingRequests();

        List<Elevator> elevators = elevatorService.getAllElevators();

        // Process each elevator independently — no cross-elevator coupling in the tick.
        // Each car's state machine evolves on its own.
        for (Elevator elevator : elevators) {
            processElevator(elevator);
        }

        // After ALL elevators are updated, take a consistent snapshot and broadcast.
        publisher.broadcastState(elevatorMapper.toDTOList(elevators));

        // Also broadcast the current active hall calls so the frontend can
        // clear glowing buttons the moment a call is served.
        publisher.broadcastHallCalls(dispatcherService.getActiveHallCalls().keySet());
    }

    // ── Stuck-elevator watchdog ───────────────────────────────────────────────

    /**
     * Detects elevators that are in MOVING state but haven't changed floor in
     * STUCK_THRESHOLD ticks, then resets them to IDLE so they can recover.
     */
    private void checkStuckElevator(Elevator elevator) {
        int id = elevator.getElevatorId();
        int floor = elevator.getCurrentFloor();

        if (elevator.getState() != ElevatorState.MOVING) {
            // Not moving — reset counters
            lastFloorByElevator.put(id, floor);
            stuckTicksByElevator.put(id, 0);
            return;
        }

        int lastFloor  = lastFloorByElevator.getOrDefault(id, floor);
        int stuckTicks = stuckTicksByElevator.getOrDefault(id, 0);

        if (floor == lastFloor) {
            stuckTicks++;
            stuckTicksByElevator.put(id, stuckTicks);
            if (stuckTicks >= STUCK_THRESHOLD) {
                log.error("[WATCHDOG] Elevator {} STUCK at floor {} for {} ticks — resetting to IDLE!",
                        id, BuildingConfig.floorLabel(floor), stuckTicks);
                synchronized (elevator) {
                    elevator.setState(ElevatorState.IDLE);
                    elevator.setDirection(Direction.IDLE);
                }
                stuckTicksByElevator.put(id, 0);
            }
        } else {
            lastFloorByElevator.put(id, floor);
            stuckTicksByElevator.put(id, 0);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // PER-ELEVATOR PROCESSING — The State Machine Driver
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Drives one elevator through a single tick of its FSM.
     *
     * This method is the direct software analog of one PLC scan cycle
     * for a single elevator controller.
     *
     * The switch is exhaustive — every ElevatorState value is handled.
     * If a new state is ever added to the enum, the compiler will warn
     * that this switch is incomplete (a safety net!).
     *
     * @param elevator  the elevator to process this tick
     */
    void processElevator(Elevator elevator) {
        // Run the stuck watchdog before processing so a frozen elevator
        // gets reset before we try to advance it.
        checkStuckElevator(elevator);

        switch (elevator.getState()) {
            case DOOR_OPEN -> handleDoorOpenTick(elevator);
            case MOVING    -> handleMovingTick(elevator);
            case IDLE      -> { /* Waiting for new requests — addRequest() handles wakeup */ }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // STATE HANDLER: DOOR_OPEN
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Handles one tick while the elevator doors are open.
     *
     * DOOR TIMING MODEL:
     * ──────────────────
     * When elevator arrives at a floor, doorOpenTicksRemaining is set to
     * BuildingConfig.DOOR_OPEN_TICKS (default = 2).
     *
     * Each tick decrements this counter:
     *   Tick 1: doors open, doorOpenTicksRemaining = 2 → 1
     *   Tick 2: doors still open,              = 1 → 0
     *   Tick 3: doors close, decide next action
     *
     * This gives passengers ~2 seconds to board/alight in our simulation.
     * Real elevators use 3-5 seconds + infrared beam sensors to extend.
     *
     * AFTER DOORS CLOSE → applyLookDirectionLogic()
     * This is where LOOK's direction-reversal decision happens.
     *
     * @param elevator  the elevator whose doors are currently open
     */
    void handleDoorOpenTick(Elevator elevator) {
        // Thread-safe decrement via setter (volatile field)
        synchronized (elevator) {
            int remaining = elevator.getDoorOpenTicksRemaining();

            if (remaining > 0) {
                elevator.setDoorOpenTicksRemaining(remaining - 1);
                log.debug("Elevator {}: Door open, {} tick(s) remaining.",
                        elevator.getElevatorId(), remaining - 1);
            } else {
                // Door close time! Transition to next state.
                log.info("Elevator {}: Doors closing at floor {}.",
                        elevator.getElevatorId(),
                        BuildingConfig.floorLabel(elevator.getCurrentFloor()));
                applyLookDirectionLogic(elevator);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // STATE HANDLER: MOVING
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Handles one tick while the elevator is in motion.
     *
     * MOVEMENT MODEL:
     * ───────────────
     * The elevator moves exactly ONE floor per tick.
     * After moving, we check if the new position matches the next stop.
     * If yes → arrive at floor → open doors.
     * If no  → continue moving.
     *
     * ONE FLOOR PER TICK is crucial for two reasons:
     *   1. Real elevators have a maximum speed (typically 1m/s to 10m/s).
     *      One floor/second is a simplified but directionally accurate model.
     *   2. It allows the frontend to animate smooth floor-by-floor movement.
     *      If we teleported, animation would be impossible.
     *
     * ARRIVAL DETECTION:
     * ──────────────────
     * We get the nextStop BEFORE moving (so we know where we're headed).
     * After moving, we check: did we just land on nextStop?
     *
     * Edge case: nextStop is null (queue became empty while MOVING).
     * This can happen if a request was somehow removed externally.
     * We handle it by gracefully transitioning to IDLE.
     *
     * @param elevator  the elevator that is currently in motion
     */
    void handleMovingTick(Elevator elevator) {
        synchronized (elevator) {
            Integer nextStop = elevator.getNextStop();

            // Guard: if no next stop but state is MOVING, that's inconsistent.
            // Correct it gracefully rather than crashing.
            if (nextStop == null) {
                log.warn("Elevator {}: In MOVING state but no next stop. Resetting to IDLE.",
                        elevator.getElevatorId());
                elevator.setDirection(Direction.IDLE);
                elevator.setState(ElevatorState.IDLE);
                return;
            }

            // Guard: direction must be UP or DOWN — never IDLE while MOVING
            if (elevator.getDirection() == Direction.IDLE) {
                Direction recovered = (nextStop > elevator.getCurrentFloor()) ? Direction.UP : Direction.DOWN;
                log.warn("Elevator {}: MOVING but direction=IDLE — recovering to {}.",
                        elevator.getElevatorId(), recovered);
                elevator.setDirection(recovered);
            }

            // ── Move one floor in current direction ──────────────────────────
            int currentFloor = elevator.getCurrentFloor();
            int newFloor;

            if (elevator.getDirection() == Direction.UP) {
                newFloor = currentFloor + 1;
            } else {
                newFloor = currentFloor - 1;
            }

            // Safety boundary check — elevator should never go outside building
            if (!BuildingConfig.isValidFloor(newFloor)) {
                log.error("Elevator {}: Would move to invalid floor {}! Halting.",
                        elevator.getElevatorId(), newFloor);
                elevator.setState(ElevatorState.IDLE);
                elevator.setDirection(Direction.IDLE);
                return;
            }

            elevator.setCurrentFloor(newFloor);
            log.info("Elevator {} → Floor {} (heading to {})",
                    elevator.getElevatorId(),
                    BuildingConfig.floorLabel(newFloor),
                    BuildingConfig.floorLabel(nextStop));

            // ── Check if we've arrived at the next stop ──────────────────────
            if (newFloor == nextStop) {
                arriveAtFloor(elevator, newFloor);
            }
            // Else: still in transit, continue to next tick
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ARRIVAL AT FLOOR
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Called when an elevator physically arrives at a destination floor.
     *
     * This method performs the complete arrival sequence:
     *   1. Remove the floor from queues (it's been served)
     *   2. Open the doors
     *   3. Start the door-open countdown timer
     *
     * WHY REMOVE BEFORE OPENING DOORS?
     * ────────────────────────────────────
     * We remove the floor from queues FIRST so that if a new request for
     * the SAME floor arrives while doors are open (passenger re-enters
     * and presses the same button), it's treated as a fresh request
     * and goes back into the queue correctly.
     *
     * If we removed AFTER closing doors, we'd miss that re-entry scenario.
     *
     * LOG FORMAT: Matches what monitoring systems look for in production.
     * Real elevator companies log every arrival event for compliance audits.
     *
     * @param elevator  the elevator that has arrived
     * @param floor     the floor it arrived at
     */
    private void arriveAtFloor(Elevator elevator, int floor) {
        // Mark this floor as served — remove from all queues
        elevator.removeFloorFromQueues(floor);

        // Open doors and start countdown
        elevator.setState(ElevatorState.DOOR_OPEN);
        elevator.setDoorOpenTicksRemaining(BuildingConfig.DOOR_OPEN_TICKS);

        // ── Phase 4: Clear the hall call indicator for this floor ─────────
        // Clears only the direction matching the elevator's current travel.
        // e.g., UP-going elevator at floor 5 clears the UP hall button.
        // The DOWN hall button at floor 5 remains lit for a down-bound elevator.
        dispatcherService.clearHallCall(floor, elevator.getDirection());

        log.info("━━━ Elevator {} ARRIVED at floor {} [DOORS OPEN] ━━━",
                elevator.getElevatorId(),
                BuildingConfig.floorLabel(floor));
    }

    // ═══════════════════════════════════════════════════════════════════════
    // LOOK ALGORITHM — Direction Decision After Door Close
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Applies the LOOK algorithm to decide what the elevator does after
     * closing its doors.
     *
     * ─────────────────────────────────────────────────────────────────────
     * THE LOOK ALGORITHM — PLAIN ENGLISH
     * ─────────────────────────────────────────────────────────────────────
     * Imagine you're a delivery person on a street:
     *   - You're walking NORTH (UP).
     *   - You deliver a package. Now decide: what next?
     *   - LOOK north: are there more deliveries ahead? Yes → keep going north.
     *   - LOOK north: no more deliveries ahead?
     *       LOOK south: any deliveries behind you? Yes → turn around, go south.
     *       Nothing either way? → Go home (IDLE).
     *
     * The elevator does the same with floors:
     *   - After serving a floor, it LOOKS in its current direction first.
     *   - Only reverses if there's truly nothing more in the current direction.
     *
     * WHY THIS IS BETTER THAN SIMPLE ALTERNATION:
     * ─────────────────────────────────────────────
     * Simple alternation: go to top floor, come back down, go back up...
     *   → Wastes energy reversing at extremes, even when all requests are mid-rise.
     *
     * LOOK: reverses EXACTLY at the last request in each direction.
     *   → No unnecessary travel to floor extremes.
     *   → Minimizes average passenger wait time vs. naive approaches.
     *
     * LOOK vs. SCAN (C-SCAN, Elevator Algorithm):
     * ─────────────────────────────────────────────
     * SCAN (used in disk scheduling): always goes to the END (floor limit) then
     *   reverses, regardless of whether there are requests beyond current position.
     * LOOK: reverses at the LAST REQUEST in each direction. More efficient.
     * We use LOOK because in elevator systems, going to floor 10 when the
     * highest request is floor 7 is pure waste.
     *
     * ─────────────────────────────────────────────────────────────────────
     * DECISION TREE (executed after doors close):
     * ─────────────────────────────────────────────────────────────────────
     *
     *  Current Direction = UP?
     *  ├─ upQueue not empty?  → MOVING UP    (continue current sweep)
     *  └─ upQueue empty?
     *     ├─ downQueue not empty? → MOVING DOWN  (LOOK reversal)
     *     └─ downQueue empty?    → IDLE           (all done)
     *
     *  Current Direction = DOWN?
     *  ├─ downQueue not empty? → MOVING DOWN  (continue current sweep)
     *  └─ downQueue empty?
     *     ├─ upQueue not empty?  → MOVING UP   (LOOK reversal)
     *     └─ upQueue empty?      → IDLE          (all done)
     *
     * ─────────────────────────────────────────────────────────────────────
     *
     * @param elevator  elevator whose doors just closed
     */
    void applyLookDirectionLogic(Elevator elevator) {
        Direction currentDirection = elevator.getDirection();
        boolean hasUp   = elevator.hasUpRequests();
        boolean hasDown = elevator.hasDownRequests();

        if (currentDirection == Direction.UP) {
            if (hasUp) {
                // ─ Case 1: More floors above us → keep going UP ──────────────
                elevator.setState(ElevatorState.MOVING);
                // direction stays UP — no change needed
                log.info("Elevator {}: LOOK ↑ → Continuing UP. Next: {}",
                        elevator.getElevatorId(),
                        BuildingConfig.floorLabel(elevator.getNextStop()));

            } else if (hasDown) {
                // ─ Case 2: No more up, but floors below → REVERSE to DOWN ─────
                // This is the signature LOOK reversal point.
                elevator.setDirection(Direction.DOWN);
                elevator.setState(ElevatorState.MOVING);
                log.info("Elevator {}: LOOK ↓ → Reversing to DOWN. Next: {}",
                        elevator.getElevatorId(),
                        BuildingConfig.floorLabel(elevator.getNextStop()));

            } else {
                // ─ Case 3: Nothing in either direction → IDLE ────────────────
                elevator.setDirection(Direction.IDLE);
                elevator.setState(ElevatorState.IDLE);
                log.info("Elevator {}: All requests served. → IDLE at floor {}",
                        elevator.getElevatorId(),
                        BuildingConfig.floorLabel(elevator.getCurrentFloor()));
            }

        } else if (currentDirection == Direction.DOWN) {
            if (hasDown) {
                // ─ Case 4: More floors below us → keep going DOWN ────────────
                elevator.setState(ElevatorState.MOVING);
                // direction stays DOWN
                log.info("Elevator {}: LOOK ↓ → Continuing DOWN. Next: {}",
                        elevator.getElevatorId(),
                        BuildingConfig.floorLabel(elevator.getNextStop()));

            } else if (hasUp) {
                // ─ Case 5: No more down, but floors above → REVERSE to UP ─────
                elevator.setDirection(Direction.UP);
                elevator.setState(ElevatorState.MOVING);
                log.info("Elevator {}: LOOK ↑ → Reversing to UP. Next: {}",
                        elevator.getElevatorId(),
                        BuildingConfig.floorLabel(elevator.getNextStop()));

            } else {
                // ─ Case 6: Nothing in either direction → IDLE ────────────────
                elevator.setDirection(Direction.IDLE);
                elevator.setState(ElevatorState.IDLE);
                log.info("Elevator {}: All requests served. → IDLE at floor {}",
                        elevator.getElevatorId(),
                        BuildingConfig.floorLabel(elevator.getCurrentFloor()));
            }

        } else {
            // ─ Fallback: somehow IDLE-direction when called ──────────────────
            // This can happen if elevator was set to DOOR_OPEN manually (e.g., in tests)
            // or if direction was never set before doors opened.
            if (hasUp || hasDown) {
                Direction newDir = hasUp ? Direction.UP : Direction.DOWN;
                elevator.setDirection(newDir);
                elevator.setState(ElevatorState.MOVING);
                log.warn("Elevator {}: Recovery — IDLE but has requests. Resuming {}.",
                        elevator.getElevatorId(), newDir);
            } else {
                // No requests at all, no direction — go truly IDLE
                elevator.setDirection(Direction.IDLE);
                elevator.setState(ElevatorState.IDLE);
            }
        }
    }

    /** Returns the current tick count (used in tests to verify ticks ran). */
    public long getTickCount() {
        return tickCount;
    }
}
