package com.elevator.service;

import com.elevator.model.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * DispatcherService — The brain of multi-elevator coordination.
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * RESPONSIBILITY
 * ═══════════════════════════════════════════════════════════════════════════
 * When a passenger presses a hall call button (UP or DOWN on a floor panel),
 * this service decides WHICH elevator should respond.
 *
 * It does NOT manage car calls (handled by ElevatorService directly).
 * It does NOT run the simulation loop (handled by SimulationEngine).
 * It ONLY answers: "Given this hall request, which elevator wins?"
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * THE NEAREST CAR ALGORITHM — EXPLAINED
 * ═══════════════════════════════════════════════════════════════════════════
 * "Nearest Car" is the industry-standard algorithm for small to mid-size
 * buildings (2–6 elevators, up to ~20 floors). It's used by Otis, KONE,
 * and ThyssenKrupp for standard installations.
 *
 * Core idea: For each elevator, compute an estimated COST to serve the
 * new hall call, then assign to the LOWEST COST elevator.
 *
 * Cost function (from Elevator.estimateCost()):
 * ─────────────────────────────────────────────
 *   IDLE elevator:     cost = |currentFloor - targetFloor|
 *   Going SAME direction AND target is ahead:
 *                      cost = |currentFloor - targetFloor| + directionBonus(0 or 2)
 *   Going OPPOSITE direction OR target is behind:
 *                      cost = full_sweep + return_distance + 4 (penalty)
 *
 * The direction bonus (0 for matching, 2 for mismatched) means:
 *   - An elevator going UP toward floor 7 is preferred over
 *     an IDLE elevator at the same distance — it's already heading there!
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * HALL CALL LIFECYCLE
 * ═══════════════════════════════════════════════════════════════════════════
 *
 *   1. RECEIVE:    Hall call arrives (floor + direction + timestamp)
 *   2. VALIDATE:   Floor in range? Direction valid? Not ground going down?
 *   3. DEDUPLICATE: Is this floor+direction already being served?
 *                   YES → return DUPLICATE (no double-assignment)
 *   4. FIND BEST:  Run Nearest Car across all elevators
 *                   If tie: prefer lighter load (fewer passengers)
 *   5. ASSIGN:     Add floor to winning elevator's LOOK queue
 *   6. TRACK:      Register in activeHallCalls map
 *   7. CLEAR:      When elevator arrives at floor, clearHallCall() called
 *                  (called from SimulationEngine.arriveAtFloor())
 *   8. RETRY:      If all elevators at capacity, queue request and retry
 *                  each tick until an elevator becomes available
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * CONCURRENCY MODEL
 * ═══════════════════════════════════════════════════════════════════════════
 * dispatch() is called from WebSocket handler threads (multiple, concurrent).
 * retryPendingRequests() and clearHallCall() are called from the simulation
 * tick thread.
 *
 * Data structures chosen for thread safety:
 *   - activeHallCalls: ConcurrentHashMap (lock-free reads, striped writes)
 *   - unassignedQueue: ConcurrentLinkedQueue (non-blocking, thread-safe)
 *   - AtomicLong counters: lock-free increment
 *
 * The critical section is dispatch(): we use putIfAbsent() to atomically
 * register a new call, preventing race conditions where two identical hall
 * calls arrive simultaneously from different threads.
 * ═══════════════════════════════════════════════════════════════════════════
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DispatcherService {

    private final ElevatorService elevatorService;

    // ═══════════════════════════════════════════════════════════════════════
    // HALL CALL REGISTRY — Active and Pending Requests
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Active hall calls: key = "floor_DIRECTION" → HallRequest
     *
     * WHY THIS KEY FORMAT?
     * ─────────────────────
     * Floor 5 pressing UP and floor 5 pressing DOWN are DIFFERENT calls.
     * The key must encode BOTH floor AND direction to distinguish them.
     * Example: "5_UP", "-2_DOWN", "0_UP"
     *
     * putIfAbsent() is the atomic deduplication guard — it either registers
     * the call (returns null) or tells us it's already there (returns existing).
     */
    private final ConcurrentHashMap<String, HallRequest> activeHallCalls = new ConcurrentHashMap<>();

    /**
     * Requests that could not be immediately assigned (all elevators at capacity).
     *
     * ConcurrentLinkedQueue: non-blocking, FIFO, thread-safe.
     * FIFO matters here: requests should be retried in arrival order to prevent
     * starvation (a late request jumping queue over an earlier one).
     */
    private final Queue<HallRequest> unassignedQueue = new ConcurrentLinkedQueue<>();

    // ═══════════════════════════════════════════════════════════════════════
    // STATISTICS COUNTERS — For monitoring and metrics
    // ═══════════════════════════════════════════════════════════════════════

    private final AtomicLong totalDispatched  = new AtomicLong(0);
    private final AtomicLong totalDuplicates  = new AtomicLong(0);
    private final AtomicLong totalQueued      = new AtomicLong(0);
    private final AtomicLong totalRejected    = new AtomicLong(0);

    // ═══════════════════════════════════════════════════════════════════════
    // PRIMARY ENTRY POINT — Handle incoming hall call
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Dispatches a hall call to the optimal elevator.
     *
     * This is the main entry point called by:
     *   - WebSocket handler when a passenger presses a hall button (Phase 6)
     *   - REST endpoint for initial state setup
     *   - Tests directly
     *
     * THREAD SAFETY:
     * The putIfAbsent() call is atomic. Even if two threads call dispatch()
     * simultaneously for the same floor+direction, only ONE will successfully
     * register it. The other gets DUPLICATE.
     *
     * @param floor      the floor where the button was pressed
     * @param direction  UP or DOWN (not IDLE)
     * @return           dispatch outcome (ASSIGNED, DUPLICATE, QUEUED, REJECTED)
     */
    public DispatchResult dispatch(int floor, Direction direction) {
        // ── Step 1: Validate input ───────────────────────────────────────
        DispatchResult validation = validate(floor, direction);
        if (validation == DispatchResult.REJECTED) {
            totalRejected.incrementAndGet();
            return DispatchResult.REJECTED;
        }

        // ── Step 2: Deduplicate — atomic check-and-register ──────────────
        String key = hallCallKey(floor, direction);
        HallRequest newRequest = HallRequest.builder()
                .floor(floor)
                .direction(direction)
                .requestId(UUID.randomUUID().toString())
                .timestamp(Instant.now())
                .build();

        // putIfAbsent is atomic: if key exists, returns existing value (non-null = duplicate)
        HallRequest existing = activeHallCalls.putIfAbsent(key, newRequest);
        if (existing != null) {
            log.debug("DUPLICATE hall call: floor {} {}. Already active since {}.",
                    floor, direction, existing.getTimestamp());
            totalDuplicates.incrementAndGet();
            return DispatchResult.DUPLICATE;
        }

        // ── Step 3: Try to assign to best elevator ─────────────────────
        return tryAssign(newRequest);
    }

    /**
     * Overload that accepts a fully-constructed HallRequest.
     * Used internally for retry logic and by tests that need to control timestamps.
     */
    public DispatchResult dispatch(HallRequest request) {
        return dispatch(request.getFloor(), request.getDirection());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // CORE DISPATCH LOGIC — Nearest Car Selection
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Attempts to assign a registered hall request to the optimal elevator.
     *
     * Called both for new requests and for retrying previously unassigned ones.
     * If no elevator can be assigned (all at capacity), the request is placed
     * (or remains) in the unassignedQueue for retry next tick.
     *
     * @param request  the hall request to assign (already registered in activeHallCalls)
     * @return         ASSIGNED if successful, QUEUED if deferred
     */
    private DispatchResult tryAssign(HallRequest request) {
        List<Elevator> elevators = elevatorService.getAllElevators();

        // Run the Nearest Car algorithm across all elevators
        Elevator winner = findBestElevator(elevators, request);

        if (winner == null) {
            // All elevators at capacity — park this request for retry next tick
            unassignedQueue.offer(request);
            totalQueued.incrementAndGet();
            log.warn("HallCall QUEUED: floor {} {} — all {} elevators at capacity.",
                    request.getFloor(), request.getDirection(), elevators.size());
            return DispatchResult.QUEUED;
        }

        // Assign the floor to the winning elevator's LOOK queue
        boolean accepted = elevatorService.assignHallCallToElevator(
                winner.getElevatorId(), request.getFloor());

        if (accepted) {
            int cost = winner.estimateCost(request.getFloor(), request.getDirection());
            totalDispatched.incrementAndGet();
            log.info("✓ HallCall ASSIGNED: floor {} {} → Elevator {} (cost={}, load={}/{})",
                    request.getFloor(),
                    request.getDirection(),
                    winner.getElevatorId(),
                    cost,
                    winner.getPassengerLoad(),
                    winner.getCapacity());
            return DispatchResult.ASSIGNED;
        } else {
            // addRequest() returned false — floor already in queue (edge case)
            log.debug("HallCall: floor {} already in Elevator {}'s queue.",
                    request.getFloor(), winner.getElevatorId());
            return DispatchResult.ASSIGNED; // Effectively handled
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // NEAREST CAR ALGORITHM — Find Optimal Elevator
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Selects the best elevator to serve a hall request using the Nearest Car algorithm.
     *
     * ─────────────────────────────────────────────────────────────────────
     * ALGORITHM STEPS:
     * ─────────────────────────────────────────────────────────────────────
     * 1. Filter out elevators at capacity (cannot accept more passengers).
     * 2. For each remaining elevator, compute estimateCost().
     * 3. Select the elevator with the MINIMUM cost.
     * 4. TIE-BREAK RULES (in priority order):
     *    a. Lower passenger load (lighter car serves more passengers efficiently)
     *    b. Lower elevator ID (deterministic, avoids non-determinism in tests)
     *
     * ─────────────────────────────────────────────────────────────────────
     * WHY NOT JUST "CLOSEST IN DISTANCE"?
     * ─────────────────────────────────────────────────────────────────────
     * Pure distance ignores direction. Example:
     *   - Elevator A: floor 3, going UP, target = floor 5
     *   - Elevator B: floor 4, going DOWN, target = floor 5
     *   B is "closer" by distance (1 floor) but it's moving AWAY from floor 5!
     *   It must complete its DOWN sweep before reversing UP to floor 5.
     *   A is 2 floors away but heading directly there — it arrives FIRST.
     *
     * estimateCost() captures this by adding penalty for opposite-direction travel.
     *
     * ─────────────────────────────────────────────────────────────────────
     *
     * @param elevators  all available elevator instances
     * @param request    the hall call to serve
     * @return           the optimal elevator, or null if all at capacity
     */
    Elevator findBestElevator(List<Elevator> elevators, HallRequest request) {
        Elevator best = null;
        int bestCost = Integer.MAX_VALUE;

        for (Elevator e : elevators) {
            // ── Filter: skip at-capacity elevators ───────────────────────
            if (e.isAtCapacity()) {
                log.debug("Elevator {}: SKIPPED (at capacity {}/{})",
                        e.getElevatorId(), e.getPassengerLoad(), e.getCapacity());
                continue;
            }

            // ── Compute Nearest Car cost ──────────────────────────────────
            int cost = e.estimateCost(request.getFloor(), request.getDirection());

            log.debug("Elevator {} cost={} [floor={}, dir={}, state={}, load={}/{}]",
                    e.getElevatorId(), cost,
                    BuildingConfig.floorLabel(e.getCurrentFloor()),
                    e.getDirection(), e.getState(),
                    e.getPassengerLoad(), e.getCapacity());

            // ── Select winner: lower cost wins ────────────────────────────
            if (cost < bestCost) {
                bestCost = cost;
                best = e;
            } else if (cost == bestCost) {
                // ── Tie-break: prefer lower passenger load ────────────────
                if (best == null || isBetterTieBreak(e, best)) {
                    best = e;
                }
            }
        }

        if (best != null) {
            log.debug("Winner: Elevator {} with cost={}", best.getElevatorId(), bestCost);
        } else {
            log.debug("No winner found — all elevators at capacity.");
        }

        return best;
    }

    /**
     * Determines if the candidate elevator is a better tie-break than current best.
     *
     * TIE-BREAK PRIORITY:
     * ─────────────────────
     * 1. Lower passenger load: an elevator with fewer passengers can accept more,
     *    and arrives at destination faster (fewer intermediate stops).
     * 2. Lower ID: deterministic fallback for tests and equal-load scenarios.
     *
     * @param candidate  the challenger elevator
     * @param current    the current best elevator
     * @return           true if candidate should replace current
     */
    private boolean isBetterTieBreak(Elevator candidate, Elevator current) {
        if (candidate.getPassengerLoad() != current.getPassengerLoad()) {
            return candidate.getPassengerLoad() < current.getPassengerLoad();
        }
        // Final fallback: lower elevator ID for determinism
        return candidate.getElevatorId() < current.getElevatorId();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // VALIDATION — Input Safety Gate
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Validates a hall call before processing.
     *
     * Rules:
     * ──────
     * 1. Floor must be in [MIN_FLOOR, MAX_FLOOR]
     * 2. Direction must be UP or DOWN (not IDLE)
     * 3. Floor 10 cannot press UP (nowhere to go above top floor)
     * 4. Floor -3 cannot press DOWN (nowhere to go below basement level)
     *
     * Rule 3 and 4 mirror real elevator panels which physically don't have
     * the UP button on the top floor or DOWN button on the bottom floor.
     *
     * @param floor      the requested floor
     * @param direction  the requested direction
     * @return           REJECTED if invalid, null otherwise (caller continues)
     */
    private DispatchResult validate(int floor, Direction direction) {
        if (!BuildingConfig.isValidFloor(floor)) {
            log.warn("REJECTED: floor {} out of range [{}, {}]",
                    floor, BuildingConfig.MIN_FLOOR, BuildingConfig.MAX_FLOOR);
            return DispatchResult.REJECTED;
        }

        if (direction == Direction.IDLE || direction == null) {
            log.warn("REJECTED: direction {} is not valid for hall calls", direction);
            return DispatchResult.REJECTED;
        }

        if (direction == Direction.UP && floor == BuildingConfig.MAX_FLOOR) {
            log.warn("REJECTED: Cannot press UP on top floor ({})", BuildingConfig.MAX_FLOOR);
            return DispatchResult.REJECTED;
        }

        if (direction == Direction.DOWN && floor == BuildingConfig.MIN_FLOOR) {
            log.warn("REJECTED: Cannot press DOWN on bottom floor ({})", BuildingConfig.MIN_FLOOR);
            return DispatchResult.REJECTED;
        }

        return null; // null = valid (no rejection)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // HALL CALL LIFECYCLE — Clear When Served & Retry Unassigned
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Clears the hall call indicator for a floor+direction when an elevator arrives.
     *
     * Called by SimulationEngine.arriveAtFloor() for the elevator's direction.
     *
     * WHY ONLY CLEAR THE MATCHING DIRECTION?
     * ─────────────────────────────────────────
     * If elevator is going UP and stops at floor 5:
     *   → Clear the UP hall call at floor 5 (those passengers board)
     *   → Keep the DOWN hall call at floor 5 (those passengers wait for a down-car)
     *
     * Real elevators follow this exactly: floor 5 has two separate buttons
     * (UP and DOWN). Each lights independently and clears independently.
     *
     * IDLE direction: when an IDLE elevator picks up a request, it can serve
     * either direction — clear both.
     *
     * @param floor              the floor the elevator arrived at
     * @param elevatorDirection  the direction the elevator was traveling
     */
    public void clearHallCall(int floor, Direction elevatorDirection) {
        // Always clear BOTH directions for this floor.
        //
        // WHY NOT direction-specific clear?
        // When dispatch() sends a DOWN call to the nearest elevator, addRequest(floor)
        // routes the floor into upQueue or downQueue based on POSITION, not the requested
        // direction. e.g. "floor 7 DOWN" assigned to elevator at floor 3 going UP →
        // floor 7 lands in upQueue → elevator arrives going UP → clearHallCall(7, UP)
        // would only remove "7_UP", leaving "7_DOWN" stuck in activeHallCalls forever.
        // Clearing both eliminates this mismatch entirely.
        HallRequest upRemoved   = activeHallCalls.remove(hallCallKey(floor, Direction.UP));
        HallRequest downRemoved = activeHallCalls.remove(hallCallKey(floor, Direction.DOWN));
        if (upRemoved != null) {
            long waitMs = Instant.now().toEpochMilli() - upRemoved.getTimestamp().toEpochMilli();
            log.info("✓ HallCall SERVED: floor {} ▲ UP   | wait={}ms", BuildingConfig.floorLabel(floor), waitMs);
        }
        if (downRemoved != null) {
            long waitMs = Instant.now().toEpochMilli() - downRemoved.getTimestamp().toEpochMilli();
            log.info("✓ HallCall SERVED: floor {} ▼ DOWN | wait={}ms", BuildingConfig.floorLabel(floor), waitMs);
        }
    }


    /**
     * Retries assigning all pending (capacity-blocked) hall requests.
     *
     * Called by SimulationEngine at the START of each tick, BEFORE processing
     * elevator movements. This ensures that if an elevator just dropped off
     * passengers (now has capacity), it immediately picks up waiting calls.
     *
     * ─────────────────────────────────────────────────────────────────────
     * WHY CALLED BEFORE MOVEMENT (NOT AFTER)?
     * ─────────────────────────────────────────────────────────────────────
     * If called AFTER movement, the elevator that just became available
     * won't move toward the newly assigned floor until the NEXT tick.
     * Calling BEFORE means: capacity freed → assign → elevator moves toward
     * it in the SAME tick. One tick faster response.
     * ─────────────────────────────────────────────────────────────────────
     *
     * We only retry the requests that were unassigned at the START of this
     * tick (retryCount = current queue size). New requests that arrive mid-tick
     * and fail get retried next tick. This prevents infinite loops.
     */
    public void retryPendingRequests() {
        int retryCount = unassignedQueue.size();
        if (retryCount == 0) return;

        log.debug("Retrying {} pending hall calls...", retryCount);
        int reassigned = 0;

        for (int i = 0; i < retryCount; i++) {
            HallRequest request = unassignedQueue.poll();
            if (request == null) break;

            // tryAssign will re-queue if still at capacity
            DispatchResult result = tryAssign(request);
            if (result == DispatchResult.ASSIGNED) {
                reassigned++;
            }
        }

        if (reassigned > 0) {
            log.info("Retry: {} of {} pending hall calls reassigned.", reassigned, retryCount);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // QUERY METHODS — State inspection
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Checks if a hall call is currently active for a floor+direction.
     * Used by the WebSocket state broadcast to include hall button states.
     */
    public boolean isHallCallActive(int floor, Direction direction) {
        return activeHallCalls.containsKey(hallCallKey(floor, direction));
    }

    /**
     * Returns an immutable snapshot of all active hall calls.
     * Used by ElevatorMapper to include active hall calls in the state DTO.
     */
    public Map<String, HallRequest> getActiveHallCalls() {
        return Collections.unmodifiableMap(new HashMap<>(activeHallCalls));
    }

    /** Returns the number of requests currently waiting for capacity. */
    public int getPendingQueueSize() {
        return unassignedQueue.size();
    }

    /** Returns cumulative dispatch statistics. */
    public Map<String, Long> getStats() {
        return Map.of(
            "dispatched",  totalDispatched.get(),
            "duplicates",  totalDuplicates.get(),
            "queued",      totalQueued.get(),
            "rejected",    totalRejected.get()
        );
    }

    // ═══════════════════════════════════════════════════════════════════════
    // UTILITY
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Generates the map key for a floor+direction pair.
     * Format: "{floor}_{DIRECTION}" — e.g., "5_UP", "-2_DOWN", "0_UP"
     */
    private String hallCallKey(int floor, Direction direction) {
        return floor + "_" + direction.name();
    }
}
