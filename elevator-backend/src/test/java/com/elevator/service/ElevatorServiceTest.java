package com.elevator.service;

import com.elevator.model.BuildingConfig;
import com.elevator.model.Elevator;
import com.elevator.model.ElevatorState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ElevatorServiceTest — Unit tests for ElevatorService initialization and routing.
 *
 * WHY TEST THE SERVICE SEPARATELY FROM THE ENTITY?
 * ──────────────────────────────────────────────────
 * The service test validates:
 *   1. Correct number of elevators are created
 *   2. Elevators start at expected spread positions
 *   3. Car request routing goes to the right elevator
 *   4. Invalid inputs are rejected gracefully
 *   5. All elevators are accessible by ID
 *
 * These are separate concerns from the internal Elevator logic tested
 * in ElevatorTest. A service test tests the INTEGRATION between
 * ElevatorService and the Elevator entities it manages.
 *
 * NOTE: We manually call initializeElevators() instead of relying on
 * Spring's @PostConstruct because these are pure unit tests with no
 * application context. Faster, simpler, no Spring overhead.
 */
@DisplayName("ElevatorService Tests")
class ElevatorServiceTest {

    private ElevatorService elevatorService;

    @BeforeEach
    void setUp() {
        elevatorService = new ElevatorService();
        elevatorService.initializeElevators(); // Simulate @PostConstruct
    }

    // ── Initialization ────────────────────────────────────────────────────

    @Test
    @DisplayName("Should initialize exactly 3 elevators")
    void shouldInitializeThreeElevators() {
        List<Elevator> elevators = elevatorService.getAllElevators();
        assertThat(elevators).hasSize(BuildingConfig.TOTAL_ELEVATORS);
    }

    @Test
    @DisplayName("Elevators should be sorted by ID (1, 2, 3)")
    void elevatorsShouldBeSortedById() {
        List<Elevator> elevators = elevatorService.getAllElevators();
        assertThat(elevators)
            .extracting(Elevator::getElevatorId)
            .containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("Elevator 1 should start at ground floor (0)")
    void elevator1_startsAtGroundFloor() {
        Optional<Elevator> e = elevatorService.getElevatorById(1);
        assertThat(e).isPresent();
        assertThat(e.get().getCurrentFloor()).isEqualTo(0);
    }

    @Test
    @DisplayName("Elevator 2 should start at floor 4 (mid-rise)")
    void elevator2_startsAtMidFloor() {
        Optional<Elevator> e = elevatorService.getElevatorById(2);
        assertThat(e).isPresent();
        assertThat(e.get().getCurrentFloor()).isEqualTo(4);
    }

    @Test
    @DisplayName("Elevator 3 should start at floor 8 (upper zone)")
    void elevator3_startsAtUpperFloor() {
        Optional<Elevator> e = elevatorService.getElevatorById(3);
        assertThat(e).isPresent();
        assertThat(e.get().getCurrentFloor()).isEqualTo(8);
    }

    @Test
    @DisplayName("All elevators should start IDLE")
    void allElevators_shouldStartIdle() {
        elevatorService.getAllElevators().forEach(e ->
            assertThat(e.getState())
                .as("Elevator %d should be IDLE", e.getElevatorId())
                .isEqualTo(ElevatorState.IDLE)
        );
    }

    @Test
    @DisplayName("Non-existent elevator ID returns empty Optional")
    void nonExistentElevatorId_returnsEmpty() {
        Optional<Elevator> result = elevatorService.getElevatorById(99);
        assertThat(result).isEmpty();
    }

    // ── Car Request Routing ───────────────────────────────────────────────

    @Test
    @DisplayName("addCarRequest: valid request accepted and queued in correct elevator")
    void addCarRequest_validRequest_accepted() {
        boolean result = elevatorService.addCarRequest(1, 5);

        assertThat(result).isTrue();
        Elevator e = elevatorService.getElevatorById(1).get();
        assertThat(e.hasPendingRequests()).isTrue();
    }

    @Test
    @DisplayName("addCarRequest: invalid elevator ID rejected")
    void addCarRequest_invalidElevatorId_rejected() {
        boolean result = elevatorService.addCarRequest(99, 5);
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("addCarRequest: floor outside building range rejected")
    void addCarRequest_invalidFloor_rejected() {
        boolean result = elevatorService.addCarRequest(1, 99);
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("addCarRequest: basement floor is valid")
    void addCarRequest_basementFloor_accepted() {
        boolean result = elevatorService.addCarRequest(1, -2);
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("addCarRequest: requests go to the correct elevator, not others")
    void addCarRequest_routedToCorrectElevator() {
        elevatorService.addCarRequest(2, 7);

        // Elevator 2 should have the request
        assertThat(elevatorService.getElevatorById(2).get().hasPendingRequests()).isTrue();

        // Elevators 1 and 3 should NOT have it
        assertThat(elevatorService.getElevatorById(1).get().hasPendingRequests()).isFalse();
        assertThat(elevatorService.getElevatorById(3).get().hasPendingRequests()).isFalse();
    }

    // ── Hall Call Assignment ──────────────────────────────────────────────

    @Test
    @DisplayName("assignHallCallToElevator: valid assignment succeeds")
    void assignHallCall_validAssignment_succeeds() {
        boolean result = elevatorService.assignHallCallToElevator(1, 5);
        assertThat(result).isTrue();

        Elevator e = elevatorService.getElevatorById(1).get();
        assertThat(e.hasPendingRequests()).isTrue();
    }

    @Test
    @DisplayName("assignHallCallToElevator: invalid elevator ID returns false")
    void assignHallCall_invalidElevator_returnsFalse() {
        boolean result = elevatorService.assignHallCallToElevator(99, 5);
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("Elevator count matches BuildingConfig constant")
    void elevatorCount_matchesBuildingConfig() {
        assertThat(elevatorService.getElevatorCount())
            .isEqualTo(BuildingConfig.TOTAL_ELEVATORS);
    }
}
