package com.elevator.model;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ElevatorTest — Unit tests for the Elevator domain entity.
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * TESTING STRATEGY
 * ═══════════════════════════════════════════════════════════════════════════
 * We use JUnit 5 + AssertJ for expressive, readable assertions.
 *
 * Tests are organized with @Nested inner classes — each nested class covers
 * one behavior area:
 *   - Initialization      : correct initial state
 *   - addRequest()        : queue routing logic (core of LOOK algorithm setup)
 *   - getNextStop()       : correct next-floor selection
 *   - estimateCost()      : Nearest Car cost function correctness
 *   - EdgeCases           : boundary conditions and invalid inputs
 *
 * WHY UNIT TEST THE DOMAIN ENTITY?
 * ──────────────────────────────────
 * The Elevator class has complex logic:
 *   - addRequest() has 5 distinct code paths (IDLE, UP+ahead, UP+behind,
 *     DOWN+ahead, DOWN+behind)
 *   - estimateCost() has 4 distinct cost calculations
 *
 * If these are wrong, EVERY higher-level component built on top of them
 * will be wrong too. Fixing a bug at the unit test level is 10× easier
 * than debugging it in a live WebSocket simulation.
 *
 * REAL-WORLD RELEVANCE:
 * ─────────────────────
 * Elevator software is safety-critical. Real systems have exhaustive test
 * suites that simulate thousands of request combinations before deployment.
 * We're building those foundations here.
 * ═══════════════════════════════════════════════════════════════════════════
 */
@DisplayName("Elevator Domain Entity Tests")
class ElevatorTest {

    // Common test fixtures — reset before each test
    private Elevator elevator;

    @BeforeEach
    void setUp() {
        // Fresh elevator at ground floor, capacity 10
        elevator = new Elevator(1, 0, 10);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 1. INITIALIZATION TESTS
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Initial State")
    class InitializationTests {

        @Test
        @DisplayName("Should start at the specified floor")
        void shouldStartAtSpecifiedFloor() {
            Elevator e = new Elevator(2, 5, 10);
            assertThat(e.getCurrentFloor()).isEqualTo(5);
        }

        @Test
        @DisplayName("Should start IDLE with no direction")
        void shouldStartIdleWithNoDirection() {
            assertThat(elevator.getState()).isEqualTo(ElevatorState.IDLE);
            assertThat(elevator.getDirection()).isEqualTo(Direction.IDLE);
        }

        @Test
        @DisplayName("Should start with empty queues and no requests")
        void shouldStartWithEmptyQueues() {
            assertThat(elevator.hasUpRequests()).isFalse();
            assertThat(elevator.hasDownRequests()).isFalse();
            assertThat(elevator.hasPendingRequests()).isFalse();
        }

        @Test
        @DisplayName("Should start with zero passenger load")
        void shouldStartWithZeroLoad() {
            assertThat(elevator.getPassengerLoad()).isZero();
            assertThat(elevator.isAtCapacity()).isFalse();
        }

        @Test
        @DisplayName("Should support basement start floors")
        void shouldSupportBasementStartFloors() {
            Elevator basementElevator = new Elevator(3, -3, 10);
            assertThat(basementElevator.getCurrentFloor()).isEqualTo(-3);
            assertThat(basementElevator.getState()).isEqualTo(ElevatorState.IDLE);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 2. addRequest() ROUTING TESTS — The heart of the LOOK algorithm
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("addRequest() — Queue Routing Logic")
    class AddRequestTests {

        // ── IDLE ELEVATOR ────────────────────────────────────────────────

        @Test
        @DisplayName("IDLE: request above current floor → sets direction UP, adds to upQueue")
        void idleElevator_requestAbove_setsDirectionUp() {
            // Elevator at floor 0, request for floor 5
            elevator.addRequest(5);

            assertThat(elevator.getDirection()).isEqualTo(Direction.UP);
            assertThat(elevator.getState()).isEqualTo(ElevatorState.MOVING);
            assertThat(elevator.getUpQueueSnapshot()).containsExactly(5);
            assertThat(elevator.getDownQueueSnapshot()).isEmpty();
        }

        @Test
        @DisplayName("IDLE: request below current floor → sets direction DOWN, adds to downQueue")
        void idleElevator_requestBelow_setsDirectionDown() {
            // Elevator at floor 5, request for floor -2
            Elevator e = new Elevator(1, 5, 10);
            e.addRequest(-2);

            assertThat(e.getDirection()).isEqualTo(Direction.DOWN);
            assertThat(e.getState()).isEqualTo(ElevatorState.MOVING);
            assertThat(e.getDownQueueSnapshot()).containsExactly(-2);
            assertThat(e.getUpQueueSnapshot()).isEmpty();
        }

        @Test
        @DisplayName("IDLE: request for current floor → opens doors immediately, does NOT move")
        void idleElevator_requestForCurrentFloor_opensDoors() {
            elevator.addRequest(0); // Elevator is already at floor 0

            assertThat(elevator.getState()).isEqualTo(ElevatorState.DOOR_OPEN);
            // Current floor request is served immediately, not queued
            assertThat(elevator.hasPendingRequests()).isFalse();
        }

        // ── MOVING UP ────────────────────────────────────────────────────

        @Test
        @DisplayName("MOVING UP: request above current floor → added to upQueue (same sweep)")
        void movingUp_requestAhead_addedToUpQueue() {
            /*
             * Scenario: Elevator at floor 2, going UP, already queued for floor 7.
             * Passenger presses floor 5 → should be inserted between current and 7.
             * LOOK: floor 5 is ahead in upward sweep → upQueue = [5, 7]
             */
            elevator.addRequest(7);   // initial destination
            elevator.addRequest(5);   // new request inserted mid-sweep

            assertThat(elevator.getUpQueueSnapshot()).containsExactlyInAnyOrder(5, 7);
            assertThat(elevator.getDownQueueSnapshot()).isEmpty();
        }

        @Test
        @DisplayName("MOVING UP: request below current floor → added to downQueue (return sweep)")
        void movingUp_requestBehind_addedToDownQueue() {
            /*
             * Scenario: Elevator at floor 0, going UP to floor 7.
             * Passenger presses floor -1 (a basement floor) → can't serve going up.
             * LOOK: floor -1 is behind us → downQueue = [-1] (serve on return)
             */
            elevator.addRequest(7);
            elevator.addRequest(-1);  // behind the UP-moving elevator

            assertThat(elevator.getUpQueueSnapshot()).containsExactly(7);
            assertThat(elevator.getDownQueueSnapshot()).containsExactly(-1);
        }

        @Test
        @DisplayName("MOVING UP: multiple requests maintain sorted upQueue order")
        void movingUp_multipleRequests_sortedUpQueue() {
            /*
             * Requests arrive out of order: 8, 3, 5, 1.
             * Since we're going UP from floor 0:
             *   → 8, 3, 5 are above → upQueue
             *   → 1 is above floor 0 (just barely) → upQueue too
             * upQueue should sort ascending: [1, 3, 5, 8]
             * getNextStop() should always return the lowest: 1
             */
            elevator.addRequest(8);
            elevator.addRequest(3);
            elevator.addRequest(5);
            elevator.addRequest(1);

            // getNextStop() returns the closest floor in current direction
            assertThat(elevator.getNextStop()).isEqualTo(1);
            assertThat(elevator.getUpQueueSnapshot()).containsExactlyInAnyOrder(1, 3, 5, 8);
        }

        // ── MOVING DOWN ──────────────────────────────────────────────────

        @Test
        @DisplayName("MOVING DOWN: request below current floor → added to downQueue (same sweep)")
        void movingDown_requestAhead_addedToDownQueue() {
            /*
             * Elevator at floor 8, going DOWN, queued for -1.
             * Passenger presses floor 3 → ahead in downward sweep.
             * LOOK: 3 < 8 → downQueue = [8, 3] (descending) → next stop = 8? No, 8 is current.
             * Actually initial request was -1, so elevator direction = DOWN.
             * New request for 3 (between 8 and -1) → downQueue gets 3 inserted.
             */
            Elevator e = new Elevator(1, 8, 10);
            e.addRequest(-1);  // sets direction DOWN
            e.addRequest(3);   // intermediate stop going down

            assertThat(e.getDownQueueSnapshot()).containsExactlyInAnyOrder(-1, 3);
            assertThat(e.getUpQueueSnapshot()).isEmpty();
            // Next stop when going down = highest floor in downQueue = 3 (closest below 8)
            assertThat(e.getNextStop()).isEqualTo(3);
        }

        @Test
        @DisplayName("MOVING DOWN: request above current floor → added to upQueue (return sweep)")
        void movingDown_requestAbove_addedToUpQueue() {
            /*
             * Elevator at floor 5, going DOWN to basement.
             * Passenger presses floor 9 (above us) → can't serve going down.
             * LOOK: 9 > 5 (current) → upQueue = [9] (serve after downward sweep completes)
             */
            Elevator e = new Elevator(1, 5, 10);
            e.addRequest(-3);  // going down
            e.addRequest(9);   // above current floor while going down

            assertThat(e.getDownQueueSnapshot()).containsExactly(-3);
            assertThat(e.getUpQueueSnapshot()).containsExactly(9);
        }

        // ── IDEMPOTENCY ──────────────────────────────────────────────────

        @Test
        @DisplayName("Duplicate request for same floor should be ignored (idempotent)")
        void duplicateRequest_shouldBeIgnored() {
            elevator.addRequest(5);
            boolean result = elevator.addRequest(5); // Same floor again

            assertThat(result).isFalse(); // Should return false (not added)
            assertThat(elevator.getUpQueueSnapshot()).containsExactly(5); // Only one entry
        }

        // ── INVALID FLOOR ────────────────────────────────────────────────

        @Test
        @DisplayName("Request for floor outside building range should be rejected")
        void invalidFloor_shouldBeRejected() {
            boolean result = elevator.addRequest(99); // No 99th floor

            assertThat(result).isFalse();
            assertThat(elevator.hasPendingRequests()).isFalse();
            assertThat(elevator.getState()).isEqualTo(ElevatorState.IDLE);
        }

        @Test
        @DisplayName("Request for floor below MIN_FLOOR should be rejected")
        void belowMinFloor_shouldBeRejected() {
            boolean result = elevator.addRequest(-10); // Below B3

            assertThat(result).isFalse();
            assertThat(elevator.hasPendingRequests()).isFalse();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 3. getNextStop() TESTS
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getNextStop() — Next Floor Selection")
    class GetNextStopTests {

        @Test
        @DisplayName("IDLE elevator with no requests → returns null")
        void idleNoRequests_returnsNull() {
            assertThat(elevator.getNextStop()).isNull();
        }

        @Test
        @DisplayName("Going UP → returns lowest floor in upQueue (closest ahead)")
        void goingUp_returnsLowestUpQueueFloor() {
            elevator.addRequest(7);
            elevator.addRequest(3);
            elevator.addRequest(10);

            // Closest floor above current (0) in ascending order = 3
            assertThat(elevator.getNextStop()).isEqualTo(3);
        }

        @Test
        @DisplayName("Going DOWN → returns highest floor in downQueue (closest below)")
        void goingDown_returnsHighestDownQueueFloor() {
            Elevator e = new Elevator(1, 8, 10);
            e.addRequest(2);
            e.addRequest(-1);
            e.addRequest(5);

            // Closest floor below 8, in descending order = 5
            assertThat(e.getNextStop()).isEqualTo(5);
        }

        @Test
        @DisplayName("After serving a floor, upQueue shrinks and next stop updates")
        void afterServingFloor_nextStopUpdates() {
            elevator.addRequest(3);
            elevator.addRequest(6);
            elevator.addRequest(9);

            // First stop = 3
            assertThat(elevator.getNextStop()).isEqualTo(3);

            // Simulate serving floor 3
            elevator.removeFloorFromQueues(3);

            // Next stop = 6
            assertThat(elevator.getNextStop()).isEqualTo(6);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 4. estimateCost() TESTS — Nearest Car dispatcher cost function
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("estimateCost() — Nearest Car Cost Function")
    class EstimateCostTests {

        @Test
        @DisplayName("IDLE elevator: cost = absolute distance to target floor")
        void idleElevator_costIsAbsoluteDistance() {
            // Elevator at floor 0, requesting floor 5 → cost = |0 - 5| = 5
            int cost = elevator.estimateCost(5, Direction.UP);
            assertThat(cost).isEqualTo(5);
        }

        @Test
        @DisplayName("IDLE elevator: cost works with basement target floors")
        void idleElevator_basementTarget_correctCost() {
            // Elevator at floor 0, target floor -3 → cost = |0 - (-3)| = 3
            int cost = elevator.estimateCost(-3, Direction.DOWN);
            assertThat(cost).isEqualTo(3);
        }

        @Test
        @DisplayName("MOVING UP toward target: direct distance + 0 direction bonus")
        void movingUp_towardTarget_matchingDirection() {
            /*
             * Elevator at floor 0, going UP (queued for floor 7).
             * Hall call: floor 4, direction UP.
             * Cost = (4 - 0) + 0 direction bonus = 4
             * Low cost because we're going right past floor 4 heading up!
             */
            elevator.addRequest(7); // elevator is now MOVING UP
            int cost = elevator.estimateCost(4, Direction.UP);
            assertThat(cost).isEqualTo(4);
        }

        @Test
        @DisplayName("MOVING UP toward target: +2 penalty for direction mismatch")
        void movingUp_towardTarget_mismatchedDirection() {
            /*
             * Elevator at floor 0, going UP.
             * Hall call: floor 4, direction DOWN (passenger wants to go down from 4).
             * We're going UP and can reach 4, but the passenger wants DOWN.
             * Cost = (4 - 0) + 2 direction mismatch penalty = 6
             */
            elevator.addRequest(7);
            int cost = elevator.estimateCost(4, Direction.DOWN);
            assertThat(cost).isEqualTo(6);
        }

        @Test
        @DisplayName("MOVING UP, target is below: must finish sweep then reverse")
        void movingUp_targetBelow_penalizedCost() {
            /*
             * Elevator at floor 3, going UP, queued for floor 9.
             * Hall call at floor 1 (below us). We can't serve it until we:
             *   a) Finish going up to floor 9
             *   b) Come back down to floor 1
             * Cost = (9 - 3) + (9 - 1) + 4 penalty = 6 + 8 + 4 = 18
             * This is intentionally expensive → dispatcher should prefer an idle elevator.
             */
            Elevator e = new Elevator(1, 3, 10);
            e.addRequest(9); // sets direction UP, current at 3

            int cost = e.estimateCost(1, Direction.DOWN);
            assertThat(cost).isEqualTo(18);
        }

        @Test
        @DisplayName("IDLE elevator always preferred over elevator moving away")
        void idleElevator_preferredOverMovingAway() {
            /*
             * This test validates the dispatcher will pick an idle elevator
             * over one that's moving away.
             *
             * Scenario:
             *   Elevator A: IDLE at floor 3. Hall call at floor 4. Cost = 1.
             *   Elevator B: at floor 3, going UP to floor 10. Hall call at floor 2.
             *               Cost = (10-3) + (10-2) + 4 = 7 + 8 + 4 = 19.
             *
             * Dispatcher should pick A (lower cost).
             */
            Elevator idleElevator = new Elevator(1, 3, 10);   // IDLE at 3
            Elevator busyElevator = new Elevator(2, 3, 10);   // MOVING UP to 10
            busyElevator.addRequest(10);

            int idleCost = idleElevator.estimateCost(4, Direction.UP);
            int busyCost  = busyElevator.estimateCost(2, Direction.DOWN);

            assertThat(idleCost).isLessThan(busyCost);
            assertThat(idleCost).isEqualTo(1);
            assertThat(busyCost).isEqualTo(19);
        }

        @ParameterizedTest(name = "IDLE at floor {0}, target floor {1} → cost {2}")
        @CsvSource({
            "0,  5, 5",
            "0, -3, 3",
            "5,  2, 3",
            "7, 10, 3",
            "0,  0, 0"
        })
        @DisplayName("IDLE cost function parameterized correctness")
        void idleCostFunction_parameterized(int startFloor, int targetFloor, int expectedCost) {
            Elevator e = new Elevator(1, startFloor, 10);
            assertThat(e.estimateCost(targetFloor, Direction.UP)).isEqualTo(expectedCost);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 5. EDGE CASE TESTS
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("Capacity check: elevator at capacity returns true")
        void atCapacity_returnsTrue() {
            elevator.setPassengerLoad(10); // capacity is 10
            assertThat(elevator.isAtCapacity()).isTrue();
        }

        @Test
        @DisplayName("Capacity check: elevator below capacity returns false")
        void belowCapacity_returnsFalse() {
            elevator.setPassengerLoad(7); // 7 of 10
            assertThat(elevator.isAtCapacity()).isFalse();
        }

        @Test
        @DisplayName("Direction switches to IDLE when all queues exhausted")
        void allQueuesExhausted_elevatorBecomesIdle() {
            elevator.addRequest(5);
            assertThat(elevator.hasPendingRequests()).isTrue();

            elevator.removeFloorFromQueues(5);
            assertThat(elevator.hasPendingRequests()).isFalse();
        }

        @Test
        @DisplayName("Floor B3 (-3) is a valid request")
        void basementFloor_validRequest() {
            Elevator e = new Elevator(1, 0, 10);
            boolean result = e.addRequest(-3);

            assertThat(result).isTrue();
            assertThat(e.getDownQueueSnapshot()).containsExactly(-3);
        }

        @Test
        @DisplayName("Floor 10 (top) is a valid request")
        void topFloor_validRequest() {
            boolean result = elevator.addRequest(10);

            assertThat(result).isTrue();
            assertThat(elevator.getUpQueueSnapshot()).containsExactly(10);
        }

        @Test
        @DisplayName("Multiple requests same direction: queue serves them in LOOK order")
        void multipleRequestsSameDirection_lookOrder() {
            /*
             * Classic LOOK scenario from floor 0 going up.
             * Requests: 7, 2, 5, 3 (unordered from different passengers).
             * LOOK should serve: 2 → 3 → 5 → 7 (ascending, NO unnecessary backtracking).
             */
            elevator.addRequest(7);
            elevator.addRequest(2);
            elevator.addRequest(5);
            elevator.addRequest(3);

            Integer next = elevator.getNextStop();
            assertThat(next).isEqualTo(2);

            elevator.removeFloorFromQueues(2);
            assertThat(elevator.getNextStop()).isEqualTo(3);

            elevator.removeFloorFromQueues(3);
            assertThat(elevator.getNextStop()).isEqualTo(5);

            elevator.removeFloorFromQueues(5);
            assertThat(elevator.getNextStop()).isEqualTo(7);
        }
    }
}
