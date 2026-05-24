package com.elevator.model;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Elevator — The central domain entity representing a single elevator car.
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * DESIGN PHILOSOPHY
 * ═══════════════════════════════════════════════════════════════════════════
 * This class is intentionally a "rich domain model" — it holds state AND
 * contains methods that enforce invariants (e.g., you can't add a floor
 * outside building range). This is in contrast to an "anemic domain model"
 * where entities are pure data bags and all logic lives in services.
 *
 * For an elevator system, the rich model makes sense because:
 *   - Queue management is tightly coupled to elevator identity
 *   - Direction and state transitions must be consistent with the queues
 *   - Encapsulation prevents bugs from external code corrupting the state
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * QUEUE DESIGN: WHY TWO QUEUES?
 * ═══════════════════════════════════════════════════════════════════════════
 * The LOOK algorithm requires the elevator to know which floors it should
 * serve while going UP vs while going DOWN. We use two sorted sets:
 *
 *   upQueue:   TreeSet<Integer> sorted ASCENDING
 *              → naturally gives us next floor going up = first element
 *
 *   downQueue: TreeSet<Integer> sorted DESCENDING (via reverseOrder())
 *              → naturally gives us next floor going down = first element
 *
 * When a new request arrives:
 *   - If elevator is going UP and floor > currentFloor   → upQueue
 *   - If elevator is going UP and floor < currentFloor   → downQueue
 *   - If elevator is going DOWN and floor < currentFloor → downQueue
 *   - If elevator is going DOWN and floor > currentFloor → upQueue
 *   - If elevator is IDLE                               → queue based on direction of request
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * CONCURRENCY: WHY synchronized + volatile?
 * ═══════════════════════════════════════════════════════════════════════════
 * The simulation tick thread reads/writes elevator state every second.
 * The WebSocket thread may add requests at any time (from user button presses).
 * Without synchronization, we'd have race conditions:
 *   - Half-updated state published to frontend
 *   - ConcurrentModificationException on TreeSet iteration
 *
 * We synchronize on 'this' for all queue mutations.
 * The simulation engine must also synchronize when it reads state for publishing.
 *
 * In production, you'd use a message queue (Kafka) or actor model (Akka)
 * for even stricter thread safety, but synchronized is correct here.
 * ═══════════════════════════════════════════════════════════════════════════
 */
@Slf4j
@Getter
@Setter
public class Elevator {

    // ── Identity ──────────────────────────────────────────────────────────────

    /**
     * Unique identifier for this elevator (1, 2, or 3).
     * Immutable after construction — an elevator never changes its ID.
     */
    private final int elevatorId;

    // ── Physical State ────────────────────────────────────────────────────────

    /**
     * The floor the elevator is currently on or arriving at.
     * Updated every tick by SimulationEngine (one floor per tick).
     * Range: [BuildingConfig.MIN_FLOOR, BuildingConfig.MAX_FLOOR]
     */
    private volatile int currentFloor;

    /**
     * The current travel direction.
     * volatile ensures the simulation thread always sees the latest value.
     */
    private volatile Direction direction;

    /**
     * The operational state of the elevator car.
     * Drives the FSM transitions in SimulationEngine.
     */
    private volatile ElevatorState state;

    // ── Queue Management ──────────────────────────────────────────────────────

    /**
     * Floors to be served while traveling UP.
     * TreeSet provides automatic ascending sort + O(log n) operations.
     * Access must be synchronized.
     */
    private final TreeSet<Integer> upQueue;

    /**
     * Floors to be served while traveling DOWN.
     * Reversed comparator gives descending order.
     * Access must be synchronized.
     */
    private final TreeSet<Integer> downQueue;

    /**
     * Combined set of ALL pending floor stops (union of upQueue + downQueue).
     * Used for quick membership checks: "do I already stop here?"
     * Uses ConcurrentHashMap.newKeySet() for thread-safe operations.
     */
    private final Set<Integer> currentRequests;

    // ── Load & Capacity ───────────────────────────────────────────────────────

    /** Current number of passengers in the elevator car. */
    private volatile int passengerLoad;

    /** Maximum passenger capacity. Elevators at capacity should not accept new hall calls. */
    private final int capacity;

    // ── Door State ────────────────────────────────────────────────────────────

    /**
     * Countdown timer for how many ticks the door stays open.
     * When a floor is reached: doorOpenTicksRemaining = DOOR_OPEN_TICKS
     * Each tick: doorOpenTicksRemaining--
     * When 0: doors close, state transitions to MOVING or IDLE
     */
    private volatile int doorOpenTicksRemaining;

    // ═══════════════════════════════════════════════════════════════════════════
    // CONSTRUCTOR
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Creates an elevator at a given starting floor.
     *
     * All elevators start IDLE, facing no direction, with empty queues.
     * This mirrors real-world elevator initialization: on system startup,
     * all cars return to a home floor (often ground floor) and wait.
     *
     * @param elevatorId   unique ID (1-indexed)
     * @param startFloor   initial floor position
     * @param capacity     max passenger load
     */
    public Elevator(int elevatorId, int startFloor, int capacity) {
        this.elevatorId = elevatorId;
        this.currentFloor = startFloor;
        this.capacity = capacity;
        this.direction = Direction.IDLE;
        this.state = ElevatorState.IDLE;
        this.passengerLoad = 0;
        this.doorOpenTicksRemaining = 0;

        // upQueue: ascending (natural order) — poll first() for next UP stop
        this.upQueue = new TreeSet<>();

        // downQueue: descending (reverse order) — poll first() for next DOWN stop
        this.downQueue = new TreeSet<>(Collections.reverseOrder());

        // Thread-safe set for quick "already queued?" checks
        this.currentRequests = ConcurrentHashMap.newKeySet();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // QUEUE MUTATION METHODS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Adds a target floor to the appropriate queue based on current direction.
     *
     * ALGORITHM (decides UP vs DOWN queue):
     * ────────────────────────────────────────
     * Case 1: Elevator IDLE
     *   → Determine direction from requestedFloor vs currentFloor
     *   → Set elevator direction
     *   → Add to the corresponding queue
     *
     * Case 2: Elevator moving UP
     *   → requestedFloor > currentFloor  → upQueue   (serve in current sweep)
     *   → requestedFloor < currentFloor  → downQueue (serve in return sweep)
     *   → requestedFloor == currentFloor → ignore (already here, or opening doors)
     *
     * Case 3: Elevator moving DOWN
     *   → requestedFloor < currentFloor  → downQueue (serve in current sweep)
     *   → requestedFloor > currentFloor  → upQueue   (serve in return sweep)
     *   → requestedFloor == currentFloor → ignore
     *
     * This is the heart of LOOK: requests ahead go in current-sweep queue,
     * requests behind go in return-sweep queue.
     *
     * Synchronized because the WebSocket thread calls this while the
     * simulation thread may be reading the queues.
     *
     * @param floor  destination floor to add
     * @return       true if added, false if already queued or invalid
     */
    public synchronized boolean addRequest(int floor) {
        // Validate floor is within building bounds
        if (!BuildingConfig.isValidFloor(floor)) {
            log.warn("Elevator {}: Rejected invalid floor request: {}", elevatorId, floor);
            return false;
        }

        // Already have this stop queued — idempotent, no duplicate processing
        if (currentRequests.contains(floor)) {
            log.debug("Elevator {}: Floor {} already queued, skipping.", elevatorId, floor);
            return false;
        }

        // If elevator is exactly on this floor AND doors are open → no action needed
        if (floor == currentFloor && state == ElevatorState.DOOR_OPEN) {
            log.debug("Elevator {}: Already at floor {} with doors open.", elevatorId, floor);
            return false;
        }

        currentRequests.add(floor);

        switch (direction) {
            case IDLE -> {
                // First request — determine which way to go
                if (floor > currentFloor) {
                    direction = Direction.UP;
                    upQueue.add(floor);
                } else if (floor < currentFloor) {
                    direction = Direction.DOWN;
                    downQueue.add(floor);
                } else {
                    // Requested current floor while idle → open doors immediately
                    state = ElevatorState.DOOR_OPEN;
                    doorOpenTicksRemaining = BuildingConfig.DOOR_OPEN_TICKS;
                    currentRequests.remove(floor); // Will be served immediately
                }
            }
            case UP -> {
                if (floor >= currentFloor) {
                    upQueue.add(floor);   // Ahead → serve now
                } else {
                    downQueue.add(floor); // Behind → serve on return
                }
            }
            case DOWN -> {
                if (floor <= currentFloor) {
                    downQueue.add(floor); // Ahead (going down) → serve now
                } else {
                    upQueue.add(floor);   // Behind → serve on return
                }
            }
        }

        log.info("Elevator {}: Added floor {} | upQ={} downQ={} dir={}",
                elevatorId, floor, upQueue, downQueue, direction);

        // Transition from IDLE to MOVING if elevator was waiting
        if (state == ElevatorState.IDLE && !currentRequests.isEmpty()) {
            state = ElevatorState.MOVING;
        }

        return true;
    }

    /**
     * Returns the next floor the elevator should move toward, given direction.
     *
     * For UP direction:  first element of upQueue (smallest floor above us)
     * For DOWN direction: first element of downQueue (largest floor below us)
     *
     * Returns null if no next stop exists in current direction.
     *
     * @return next target floor, or null if queue is empty in current direction
     */
    public synchronized Integer getNextStop() {
        if (direction == Direction.UP && !upQueue.isEmpty()) {
            return upQueue.first(); // TreeSet natural order → lowest value = closest up
        }
        if (direction == Direction.DOWN && !downQueue.isEmpty()) {
            return downQueue.first(); // Reverse order → highest value = closest down
        }
        return null;
    }

    /**
     * Removes a floor from both queues and currentRequests.
     * Called when the elevator arrives at a floor and opens doors.
     *
     * @param floor  the floor that has been served
     */
    public synchronized void removeFloorFromQueues(int floor) {
        upQueue.remove(floor);
        downQueue.remove(floor);
        currentRequests.remove(floor);
        log.debug("Elevator {}: Served floor {}. Remaining: {}", elevatorId, currentRequests, floor);
    }

    /**
     * Checks if there are any pending requests at all (in either queue).
     *
     * @return true if elevator has work to do
     */
    public synchronized boolean hasPendingRequests() {
        return !currentRequests.isEmpty();
    }

    /**
     * Checks if the upQueue has more floors to serve.
     */
    public synchronized boolean hasUpRequests() {
        return !upQueue.isEmpty();
    }

    /**
     * Checks if the downQueue has more floors to serve.
     */
    public synchronized boolean hasDownRequests() {
        return !downQueue.isEmpty();
    }

    /**
     * Returns an estimate of how many floors this elevator must travel
     * before it can potentially serve a given target floor.
     *
     * Used by the Nearest Car dispatcher as the primary cost metric.
     *
     * ALGORITHM:
     * ──────────────────────────────────────────────────────────────
     * Case 1: Elevator is IDLE
     *   cost = |currentFloor - targetFloor|
     *
     * Case 2: Elevator moving TOWARD target (same direction, target is ahead)
     *   cost = |currentFloor - targetFloor|   ← it will naturally pass by
     *
     * Case 3: Elevator moving AWAY or in opposite direction
     *   cost = |currentFloor - endOfCurrentSweep| + |endOfCurrentSweep - targetFloor|
     *   (must finish current run before being available)
     *
     * @param targetFloor  floor we want to reach
     * @param callDirection direction the hall call is for (for direction matching bonus)
     * @return             estimated travel cost (lower = better)
     */
    public synchronized int estimateCost(int targetFloor, Direction callDirection) {
        if (state == ElevatorState.IDLE || direction == Direction.IDLE) {
            // Idle elevators just pay distance cost
            return Math.abs(currentFloor - targetFloor);
        }

        if (direction == Direction.UP) {
            if (targetFloor >= currentFloor) {
                // Target is ahead going up — elevator will naturally stop here
                int directCost = targetFloor - currentFloor;
                // Bonus: matching direction reduces cost (right elevator for the job)
                int directionBonus = (callDirection == Direction.UP) ? 0 : 2;
                return directCost + directionBonus;
            } else {
                // Target is below current floor while going up
                // Must finish upward sweep first, then come back down
                int topStop = upQueue.isEmpty() ? currentFloor : upQueue.last();
                return (topStop - currentFloor) + (topStop - targetFloor) + 4; // penalty
            }
        } else { // Direction.DOWN
            if (targetFloor <= currentFloor) {
                // Target is ahead going down
                int directCost = currentFloor - targetFloor;
                int directionBonus = (callDirection == Direction.DOWN) ? 0 : 2;
                return directCost + directionBonus;
            } else {
                // Target is above current floor while going down
                // Must finish downward sweep first, then come back up
                int bottomStop = downQueue.isEmpty() ? currentFloor : downQueue.last();
                return (currentFloor - bottomStop) + (targetFloor - bottomStop) + 4; // penalty
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SAFE SNAPSHOT METHODS — Used by ElevatorMapper for DTO construction
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Returns a defensive copy of the upQueue as a plain HashSet.
     *
     * WHY NOT RETURN THE TREESET DIRECTLY?
     * ───────────────────────────────────────
     * 1. SAFETY: The caller would hold a reference to our internal TreeSet.
     *    If the simulation thread modifies it while the caller iterates, we
     *    get ConcurrentModificationException.
     *
     * 2. SERIALIZATION: Jackson doesn't know how to serialize a TreeSet that
     *    was constructed with Collections.reverseOrder(). It can serialize a
     *    plain HashSet or ArrayList without any issues.
     *
     * The copy is taken inside a synchronized block to ensure the snapshot
     * captures a consistent state (no partial updates mid-tick).
     *
     * @return a fresh HashSet with the current upQueue contents
     */
    public synchronized java.util.HashSet<Integer> getUpQueueSnapshot() {
        return new java.util.HashSet<>(upQueue);
    }

    /**
     * Returns a defensive copy of the downQueue as a plain HashSet.
     * Same rationale as getUpQueueSnapshot().
     *
     * @return a fresh HashSet with the current downQueue contents
     */
    public synchronized java.util.HashSet<Integer> getDownQueueSnapshot() {
        return new java.util.HashSet<>(downQueue);
    }

    /**
     * Returns a defensive copy of ALL pending requests (union of both queues).
     * Used by DispatcherService to check if a floor is already being served.
     *
     * @return immutable set of all pending floor stops
     */
    public java.util.Set<Integer> getCurrentRequestsSnapshot() {
        // ConcurrentHashMap.newKeySet() is already thread-safe for reads
        return java.util.Collections.unmodifiableSet(currentRequests);
    }

    /**
     * Returns true if the elevator is at passenger capacity.
     * At-capacity elevators should not accept new hall call assignments
     * (but they should still serve their already-queued car requests).
     */
    public boolean isAtCapacity() {
        return passengerLoad >= capacity;
    }

    /**
     * Human-readable summary for logs and debugging.
     */
    @Override
    public String toString() {
        return String.format(
            "Elevator{id=%d, floor=%s, dir=%s, state=%s, upQ=%s, downQ=%s, load=%d/%d}",
            elevatorId,
            BuildingConfig.floorLabel(currentFloor),
            direction,
            state,
            upQueue,
            downQueue,
            passengerLoad,
            capacity
        );
    }
}
