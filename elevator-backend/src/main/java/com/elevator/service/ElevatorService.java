package com.elevator.service;

import com.elevator.model.BuildingConfig;
import com.elevator.model.Elevator;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * ElevatorService — The authoritative registry and lifecycle manager for all elevators.
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 * RESPONSIBILITIES
 * ═══════════════════════════════════════════════════════════════════════════════
 * 1. CREATE: Instantiate all elevator objects at application startup.
 * 2. STORE:  Maintain the master map of elevatorId → Elevator instance.
 * 3. PROVIDE: Give other services read access to elevator state.
 * 4. ROUTE:  Forward car requests to the correct elevator's queue.
 *
 * This service does NOT run the simulation loop or dispatch hall calls.
 * Those are the jobs of SimulationEngine and DispatcherService respectively.
 * Keeping concerns separated makes each class easier to test and reason about.
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 * WHY ConcurrentHashMap?
 * ═══════════════════════════════════════════════════════════════════════════════
 * Multiple threads access the elevator registry:
 *   - SimulationEngine reads all elevators each tick
 *   - DispatcherService reads elevators to compute costs
 *   - WebSocket handlers write new requests to specific elevators
 *
 * ConcurrentHashMap provides:
 *   - Thread-safe reads without locking the whole map
 *   - Atomic put/get operations
 *   - No ConcurrentModificationException during iteration
 *
 * The map itself is safe; individual Elevator operations are synchronized
 * inside the Elevator class itself (see Elevator.addRequest()).
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 * SPRING LIFECYCLE
 * ═══════════════════════════════════════════════════════════════════════════════
 * @PostConstruct runs AFTER dependency injection but BEFORE the app serves
 * any requests. This is the correct hook for initialization logic that needs
 * Spring beans but must run once before anything else.
 *
 * Real-world analogy: This is like the elevator control panel booting up —
 * it initializes all car controllers, runs a self-test, then opens for service.
 * ═══════════════════════════════════════════════════════════════════════════════
 */
@Slf4j
@Service
public class ElevatorService {

    /**
     * The master elevator registry: elevatorId (1/2/3) → Elevator instance.
     *
     * ConcurrentHashMap ensures thread-safe access from simulation,
     * dispatcher, and WebSocket handler threads simultaneously.
     */
    private final Map<Integer, Elevator> elevatorRegistry = new ConcurrentHashMap<>();

    // ═══════════════════════════════════════════════════════════════════════════
    // INITIALIZATION
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Initializes all elevator instances on application startup.
     *
     * WHY SPREAD ACROSS DIFFERENT FLOORS?
     * ─────────────────────────────────────
     * Placing all elevators at the same starting floor (ground) is simplistic
     * and leads to poor coverage early in simulation. Real systems do a
     * "homing" routine where cars spread to predefined floors based on
     * expected traffic patterns (e.g., one car at ground for morning rush,
     * one at mid-rise, one at top for executive floors).
     *
     * For our 14-floor building (-3 to 10):
     *   Elevator 1 → Ground floor (0)   : handles lobby and low-floor traffic
     *   Elevator 2 → Floor 4             : handles mid-rise traffic
     *   Elevator 3 → Floor 8             : handles upper-floor traffic
     *
     * This initial spread means no floor waits too long for a first response.
     */
    @PostConstruct
    public void initializeElevators() {
        log.info("═══════════════════════════════════════════");
        log.info("  Elevator Dispatch System — Initializing  ");
        log.info("  Building: Floors {} to {} ({} total)     ",
                BuildingConfig.MIN_FLOOR, BuildingConfig.MAX_FLOOR, BuildingConfig.TOTAL_FLOORS);
        log.info("  Elevators: {}                            ", BuildingConfig.TOTAL_ELEVATORS);
        log.info("═══════════════════════════════════════════");

        // Stagger starting positions for better initial coverage
        int[] startingFloors = {
            BuildingConfig.DEFAULT_START_FLOOR,   // Elevator 1 → Ground (0)
            4,                                     // Elevator 2 → 4th floor
            8                                      // Elevator 3 → 8th floor
        };

        for (int id = 1; id <= BuildingConfig.TOTAL_ELEVATORS; id++) {
            int startFloor = startingFloors[id - 1];
            Elevator elevator = new Elevator(id, startFloor, BuildingConfig.DEFAULT_CAPACITY);
            elevatorRegistry.put(id, elevator);
            log.info("  Elevator {} initialized at floor {} ({})",
                    id, startFloor, BuildingConfig.floorLabel(startFloor));
        }

        log.info("  All elevators online. System ready.      ");
        log.info("═══════════════════════════════════════════");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // READ ACCESS — Used by SimulationEngine, DispatcherService, WebSocket
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Returns all elevators as an immutable list.
     *
     * WHY UNMODIFIABLE?
     * Callers should never replace or remove elevators from the collection.
     * They can only modify individual Elevator objects via their own methods.
     * This prevents accidental structural changes to the registry.
     *
     * @return unmodifiable list of all elevator instances
     */
    public List<Elevator> getAllElevators() {
        return Collections.unmodifiableList(
            elevatorRegistry.values().stream()
                .sorted((a, b) -> Integer.compare(a.getElevatorId(), b.getElevatorId()))
                .collect(Collectors.toList())
        );
    }

    /**
     * Retrieves a specific elevator by its ID.
     *
     * Returns Optional to force callers to handle the "elevator not found" case
     * gracefully rather than getting a NullPointerException.
     *
     * @param elevatorId  elevator ID to look up (1, 2, or 3)
     * @return            Optional containing the elevator, or empty if not found
     */
    public Optional<Elevator> getElevatorById(int elevatorId) {
        return Optional.ofNullable(elevatorRegistry.get(elevatorId));
    }

    /**
     * Returns the total number of registered elevators.
     * Used in DispatcherService when iterating all candidates.
     */
    public int getElevatorCount() {
        return elevatorRegistry.size();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CAR REQUEST HANDLING — Internal elevator button presses
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Processes a car request (passenger pressing a floor button inside elevator).
     *
     * WHY HERE AND NOT IN DispatcherService?
     * ─────────────────────────────────────────
     * Car requests are NOT dispatched — they go to a SPECIFIC elevator (the one
     * the passenger is already in). There is no "which elevator should handle this?"
     * decision to make. The ElevatorService routes it directly.
     *
     * The LOOK algorithm inside Elevator.addRequest() handles the scheduling:
     *   - If floor is ahead in current direction → added to active sweep queue
     *   - If floor is behind → added to return sweep queue
     *
     * This mirrors real elevator PLC behavior where car calls bypass the group
     * controller and go directly to the car's local microcontroller.
     *
     * @param elevatorId   which elevator the button was pressed in
     * @param targetFloor  which floor button was pressed
     * @return             true if request was accepted, false otherwise
     */
    public boolean addCarRequest(int elevatorId, int targetFloor) {
        Optional<Elevator> elevatorOpt = getElevatorById(elevatorId);

        if (elevatorOpt.isEmpty()) {
            log.warn("CarRequest rejected: Elevator {} does not exist.", elevatorId);
            return false;
        }

        if (!BuildingConfig.isValidFloor(targetFloor)) {
            log.warn("CarRequest rejected: Floor {} is outside building range [{}, {}].",
                    targetFloor, BuildingConfig.MIN_FLOOR, BuildingConfig.MAX_FLOOR);
            return false;
        }

        Elevator elevator = elevatorOpt.get();
        boolean accepted = elevator.addRequest(targetFloor);

        if (accepted) {
            log.info("CarRequest: Elevator {} ← Floor {} queued. State: {}",
                    elevatorId, BuildingConfig.floorLabel(targetFloor), elevator);
        } else {
            log.debug("CarRequest: Elevator {} — Floor {} already queued or not needed.",
                    elevatorId, targetFloor);
        }

        return accepted;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HALL REQUEST ASSIGNMENT — Called by DispatcherService after it picks winner
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Assigns a hall call floor to a specific elevator's queue.
     *
     * Called by DispatcherService AFTER it runs the Nearest Car algorithm
     * and determines the optimal elevator to handle a hall request.
     *
     * The floor is added to the elevator's queue via addRequest() which
     * automatically places it in the correct sweep direction.
     *
     * @param elevatorId   the winning elevator's ID
     * @param floor        the hall call floor to add
     * @return             true if successfully queued
     */
    public boolean assignHallCallToElevator(int elevatorId, int floor) {
        return getElevatorById(elevatorId)
            .map(elevator -> {
                boolean accepted = elevator.addRequest(floor);
                if (accepted) {
                    log.info("HallCall assigned: Floor {} → Elevator {}. State: {}",
                            BuildingConfig.floorLabel(floor), elevatorId, elevator);
                }
                return accepted;
            })
            .orElse(false);
    }
}
