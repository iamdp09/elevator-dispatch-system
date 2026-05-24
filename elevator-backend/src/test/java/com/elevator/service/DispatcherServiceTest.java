package com.elevator.service;

import com.elevator.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * DispatcherServiceTest — Validates the Nearest Car algorithm and hall call lifecycle.
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * TEST STRUCTURE
 * ═══════════════════════════════════════════════════════════════════════════
 * 1. Validation tests       — invalid inputs are rejected correctly
 * 2. Deduplication tests    — same floor+direction doesn't double-assign
 * 3. Nearest Car tests      — correct elevator selected in each scenario
 * 4. Tie-break tests        — deterministic winner when costs are equal
 * 5. Hall call lifecycle    — clearHallCall() removes correct entries
 * 6. Capacity filtering     — at-capacity elevators are skipped
 * 7. Retry queue tests      — unassigned calls are retried next tick
 * 8. Integration scenario   — full dispatch → arrive → clear flow
 * ═══════════════════════════════════════════════════════════════════════════
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DispatcherService — Nearest Car & Hall Call Lifecycle Tests")
class DispatcherServiceTest {

    @Mock private ElevatorService elevatorService;
    @InjectMocks private DispatcherService dispatcher;

    // ── Helpers ──────────────────────────────────────────────────────────

    /** Creates an elevator at the given floor with default capacity (10). */
    private Elevator elev(int id, int floor) {
        return new Elevator(id, floor, 10);
    }

    /** Creates an elevator going UP to a given floor (simulates in-motion). */
    private Elevator elevMovingUp(int id, int currentFloor, int targetFloor) {
        Elevator e = new Elevator(id, currentFloor, 10);
        e.addRequest(targetFloor);   // This sets direction=UP, state=MOVING
        return e;
    }

    /** Creates an elevator going DOWN to a given floor. */
    private Elevator elevMovingDown(int id, int currentFloor, int targetFloor) {
        Elevator e = new Elevator(id, currentFloor, 10);
        e.addRequest(targetFloor);   // This sets direction=DOWN, state=MOVING
        return e;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 1. VALIDATION TESTS
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("validate() — Input Rejection Rules")
    class ValidationTests {

        @Test
        @DisplayName("Floor out of range → REJECTED")
        void floorOutOfRange_rejected() {
            assertThat(dispatcher.dispatch(99, Direction.UP)).isEqualTo(DispatchResult.REJECTED);
            assertThat(dispatcher.dispatch(-99, Direction.DOWN)).isEqualTo(DispatchResult.REJECTED);
        }

        @Test
        @DisplayName("Direction IDLE → REJECTED")
        void directionIdle_rejected() {
            assertThat(dispatcher.dispatch(5, Direction.IDLE)).isEqualTo(DispatchResult.REJECTED);
        }

        @Test
        @DisplayName("Top floor pressing UP → REJECTED (nowhere to go)")
        void topFloor_pressUp_rejected() {
            // BuildingConfig.MAX_FLOOR = 10
            assertThat(dispatcher.dispatch(BuildingConfig.MAX_FLOOR, Direction.UP))
                    .isEqualTo(DispatchResult.REJECTED);
        }

        @Test
        @DisplayName("Bottom floor pressing DOWN → REJECTED (nowhere to go)")
        void bottomFloor_pressDown_rejected() {
            // BuildingConfig.MIN_FLOOR = -3
            assertThat(dispatcher.dispatch(BuildingConfig.MIN_FLOOR, Direction.DOWN))
                    .isEqualTo(DispatchResult.REJECTED);
        }

        @Test
        @DisplayName("Valid request → not REJECTED (passes validation)")
        void validRequest_passesValidation() {
            when(elevatorService.getAllElevators()).thenReturn(List.of(elev(1, 0)));
            when(elevatorService.assignHallCallToElevator(anyInt(), anyInt())).thenReturn(true);

            DispatchResult result = dispatcher.dispatch(5, Direction.UP);
            assertThat(result).isNotEqualTo(DispatchResult.REJECTED);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 2. DEDUPLICATION TESTS
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Deduplication — Same Floor+Direction Hall Call")
    class DeduplicationTests {

        @BeforeEach
        void setup() {
            when(elevatorService.getAllElevators()).thenReturn(List.of(elev(1, 0)));
            when(elevatorService.assignHallCallToElevator(anyInt(), anyInt())).thenReturn(true);
        }

        @Test
        @DisplayName("Second identical hall call → DUPLICATE")
        void secondCallSameFloorDirection_duplicate() {
            dispatcher.dispatch(5, Direction.UP);       // First: ASSIGNED
            DispatchResult second = dispatcher.dispatch(5, Direction.UP);

            assertThat(second).isEqualTo(DispatchResult.DUPLICATE);
        }

        @Test
        @DisplayName("Same floor, different direction → NOT a duplicate (two distinct calls)")
        void sameFloor_differentDirection_notDuplicate() {
            dispatcher.dispatch(5, Direction.UP);
            // Floor 5 DOWN is a DIFFERENT hall call
            DispatchResult result = dispatcher.dispatch(5, Direction.DOWN);

            assertThat(result).isNotEqualTo(DispatchResult.DUPLICATE);
        }

        @Test
        @DisplayName("Different floor, same direction → NOT a duplicate")
        void differentFloor_sameDirection_notDuplicate() {
            dispatcher.dispatch(5, Direction.UP);
            DispatchResult result = dispatcher.dispatch(7, Direction.UP);

            assertThat(result).isNotEqualTo(DispatchResult.DUPLICATE);
        }

        @Test
        @DisplayName("ElevatorService.assignHallCallToElevator called exactly once (no double-assign)")
        void assignCalled_exactlyOnce_forDuplicateCall() {
            dispatcher.dispatch(5, Direction.UP);   // First call
            dispatcher.dispatch(5, Direction.UP);   // Duplicate — should be blocked

            // assign should only be called once, not twice
            verify(elevatorService, times(1)).assignHallCallToElevator(anyInt(), eq(5));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 3. NEAREST CAR ALGORITHM TESTS — Core Selection Logic
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("findBestElevator() — Nearest Car Selection")
    class NearestCarTests {

        @Test
        @DisplayName("IDLE elevator: picks closest one by floor distance")
        void idleElevators_picksClosest() {
            /*
             * Three IDLE elevators at floors 0, 4, 8.
             * Hall call: floor 3 UP.
             *
             * Costs (IDLE → pure distance):
             *   Elevator 1 at floor 0: |0-3| = 3
             *   Elevator 2 at floor 4: |4-3| = 1  ← winner
             *   Elevator 3 at floor 8: |8-3| = 5
             *
             * NOTE: findBestElevator() takes the list directly — no getAllElevators() call.
             */
            Elevator e1 = elev(1, 0);
            Elevator e2 = elev(2, 4);
            Elevator e3 = elev(3, 8);

            HallRequest req = HallRequest.builder().floor(3).direction(Direction.UP)
                    .requestId(UUID.randomUUID().toString()).timestamp(Instant.now()).build();

            Elevator winner = dispatcher.findBestElevator(List.of(e1, e2, e3), req);

            // Elevator 2 is at floor 4, cost=|4-3|=1 (closest)
            assertThat(winner.getElevatorId()).isEqualTo(2);
        }

        @Test
        @DisplayName("Moving UP elevator ahead of target is preferred over closer IDLE one")
        void movingUpAhead_preferredOverIdleCloser() {
            /*
             * This tests the KEY insight of Nearest Car vs. pure distance:
             *
             * Elevator A: floor 2, moving UP, target = 8.  Hall call: floor 5 UP.
             *   Cost: floor 5 is ahead (5 >= 2) and direction matches →
             *         directCost = 5-2 = 3, directionBonus = 0 → total = 3
             *
             * Elevator B: IDLE at floor 4.  Hall call: floor 5 UP.
             *   Cost: |4-5| = 1
             *
             * By pure distance, B wins (cost 1 < 3).
             * By Nearest Car, B still wins — it's truly closer and idle.
             *
             * Let's test a case where moving wins:
             * Elevator A: floor 4, moving UP, target = 8.  Hall call: floor 5 UP.
             *   directCost = 5-4 = 1, bonus = 0 → cost = 1
             * Elevator B: IDLE at floor 0.
             *   cost = |0-5| = 5
             * Winner: A (already heading there)
             */
            Elevator a = elevMovingUp(1, 4, 8);  // at 4, going UP to 8
            Elevator b = elev(2, 0);              // IDLE at 0

            HallRequest req = HallRequest.builder().floor(5).direction(Direction.UP)
                    .requestId(UUID.randomUUID().toString()).timestamp(Instant.now()).build();

            Elevator winner = dispatcher.findBestElevator(List.of(a, b), req);
            assertThat(winner.getElevatorId()).isEqualTo(1); // Moving UP, cost=1 vs 5
        }

        @Test
        @DisplayName("Elevator moving AWAY from target has higher cost (penalty applied)")
        void movingAway_hasHigherCost() {
            /*
             * Elevator A: floor 5, moving UP to 9. Hall call: floor 3 UP.
             *   Target (3) is BELOW current floor (5) while going UP.
             *   Must finish sweep to floor 9, then come back to 3.
             *   topStop = 9. cost = (9-5) + (9-3) + 4 = 4 + 6 + 4 = 14
             *
             * Elevator B: IDLE at floor 4. Hall call: floor 3 UP.
             *   cost = |4-3| = 1
             *
             * Winner: B (cost 1 << 14)
             */
            Elevator a = elevMovingUp(1, 5, 9);  // going UP, target behind
            Elevator b = elev(2, 4);              // IDLE

            HallRequest req = HallRequest.builder().floor(3).direction(Direction.UP)
                    .requestId(UUID.randomUUID().toString()).timestamp(Instant.now()).build();

            Elevator winner = dispatcher.findBestElevator(List.of(a, b), req);
            assertThat(winner.getElevatorId()).isEqualTo(2); // IDLE is cheaper
        }

        @Test
        @DisplayName("Direction mismatch adds cost bonus (UP elevator, DOWN hall call)")
        void directionMismatch_addsCostBonus() {
            /*
             * Elevator A: floor 3, moving UP to 8. Hall call: floor 5 DOWN.
             *   Target 5 is ahead (>=3), but direction mismatches (call=DOWN, elev=UP)
             *   directCost = 5-3 = 2, directionBonus = 2 → cost = 4
             *
             * Elevator B: IDLE at floor 6. Hall call: floor 5 DOWN.
             *   cost = |6-5| = 1
             *
             * Winner: B (cost 1 < 4)
             * This shows direction mismatch bonus prevents assigning wrong car.
             */
            Elevator a = elevMovingUp(1, 3, 8);
            Elevator b = elev(2, 6);

            HallRequest req = HallRequest.builder().floor(5).direction(Direction.DOWN)
                    .requestId(UUID.randomUUID().toString()).timestamp(Instant.now()).build();

            Elevator winner = dispatcher.findBestElevator(List.of(a, b), req);
            assertThat(winner.getElevatorId()).isEqualTo(2);
        }

        @Test
        @DisplayName("Single elevator (no competition) → always wins if not at capacity")
        void singleElevator_alwaysWins() {
            Elevator e = elev(1, 0);
            HallRequest req = HallRequest.builder().floor(7).direction(Direction.UP)
                    .requestId(UUID.randomUUID().toString()).timestamp(Instant.now()).build();

            Elevator winner = dispatcher.findBestElevator(List.of(e), req);
            assertThat(winner).isEqualTo(e);
        }

        @Test
        @DisplayName("All elevators at capacity → returns null (no winner)")
        void allAtCapacity_returnsNull() {
            // Create elevators at capacity: passengerLoad == capacity
            Elevator e1 = new Elevator(1, 0, 1);  // capacity=1
            e1.setPassengerLoad(1);                 // load=1 → at capacity
            Elevator e2 = new Elevator(2, 4, 1);
            e2.setPassengerLoad(1);

            HallRequest req = HallRequest.builder().floor(5).direction(Direction.UP)
                    .requestId(UUID.randomUUID().toString()).timestamp(Instant.now()).build();

            Elevator winner = dispatcher.findBestElevator(List.of(e1, e2), req);
            assertThat(winner).isNull();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 4. TIE-BREAK TESTS
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Tie-Break Rules — Equal Cost Scenarios")
    class TieBreakTests {

        @Test
        @DisplayName("Equal cost + equal load → picks lowest elevator ID (deterministic)")
        void equalCostEqualLoad_picksLowestId() {
            /*
             * Two IDLE elevators equidistant from floor 5.
             * Elevator 1 at floor 3 (cost=2), Elevator 2 at floor 7 (cost=2).
             * Same load (both 0). Should pick Elevator 1 (lower ID).
             */
            Elevator e1 = elev(1, 3);  // cost = |3-5| = 2
            Elevator e2 = elev(2, 7);  // cost = |7-5| = 2

            HallRequest req = HallRequest.builder().floor(5).direction(Direction.UP)
                    .requestId(UUID.randomUUID().toString()).timestamp(Instant.now()).build();

            Elevator winner = dispatcher.findBestElevator(List.of(e1, e2), req);
            assertThat(winner.getElevatorId()).isEqualTo(1);
        }

        @Test
        @DisplayName("Equal cost, lower load wins over lower ID")
        void equalCost_lowerLoadWins() {
            /*
             * Elevator 1 (lower ID) has load=3. Elevator 2 has load=1.
             * Same cost → Elevator 2 wins (lighter car, room for more passengers).
             */
            Elevator e1 = elev(1, 3);
            e1.setPassengerLoad(3);   // heavier
            Elevator e2 = elev(2, 7);
            e2.setPassengerLoad(1);   // lighter

            HallRequest req = HallRequest.builder().floor(5).direction(Direction.UP)
                    .requestId(UUID.randomUUID().toString()).timestamp(Instant.now()).build();

            Elevator winner = dispatcher.findBestElevator(List.of(e1, e2), req);
            assertThat(winner.getElevatorId()).isEqualTo(2);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 5. HALL CALL LIFECYCLE TESTS
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("clearHallCall() — Hall Button Indicator Lifecycle")
    class HallCallLifecycleTests {

        @BeforeEach
        void registerACall() {
            when(elevatorService.getAllElevators()).thenReturn(List.of(elev(1, 0)));
            when(elevatorService.assignHallCallToElevator(anyInt(), anyInt())).thenReturn(true);
            dispatcher.dispatch(5, Direction.UP);   // Register a call
        }

        @Test
        @DisplayName("After dispatch, hall call is active")
        void afterDispatch_hallCallIsActive() {
            assertThat(dispatcher.isHallCallActive(5, Direction.UP)).isTrue();
        }

        @Test
        @DisplayName("clearHallCall with matching direction → call is removed")
        void clearMatchingDirection_removesCall() {
            dispatcher.clearHallCall(5, Direction.UP);
            assertThat(dispatcher.isHallCallActive(5, Direction.UP)).isFalse();
        }

        @Test
        @DisplayName("clearHallCall with WRONG direction → call remains (protects other direction)")
        void clearWrongDirection_callRemains() {
            dispatcher.clearHallCall(5, Direction.DOWN);  // Wrong direction
            // Floor 5 UP should still be active
            assertThat(dispatcher.isHallCallActive(5, Direction.UP)).isTrue();
        }

        @Test
        @DisplayName("clearHallCall with IDLE direction → clears BOTH directions")
        void clearIdleDirection_clearsBoth() {
            // Register DOWN call too
            dispatcher.dispatch(5, Direction.DOWN);  // Will try assign but might duplicate
            // Manually ensure DOWN is registered by checking after clear
            dispatcher.clearHallCall(5, Direction.IDLE);

            assertThat(dispatcher.isHallCallActive(5, Direction.UP)).isFalse();
            assertThat(dispatcher.isHallCallActive(5, Direction.DOWN)).isFalse();
        }

        @Test
        @DisplayName("After clear, same floor+direction can be re-dispatched (button resets)")
        void afterClear_canRedispatch() {
            dispatcher.clearHallCall(5, Direction.UP);

            DispatchResult second = dispatcher.dispatch(5, Direction.UP);
            assertThat(second).isNotEqualTo(DispatchResult.DUPLICATE);
            assertThat(second).isEqualTo(DispatchResult.ASSIGNED);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 6. CAPACITY FILTERING TESTS
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Capacity Filtering — Skip Full Elevators")
    class CapacityTests {

        @Test
        @DisplayName("At-capacity elevator is skipped; nearby non-full elevator wins")
        void atCapacityElevator_skipped_nearbyWins() {
            Elevator full = new Elevator(1, 3, 2);  // capacity=2, at floor 3
            full.setPassengerLoad(2);               // AT CAPACITY

            Elevator available = elev(2, 8);        // Available at floor 8

            when(elevatorService.getAllElevators()).thenReturn(List.of(full, available));
            when(elevatorService.assignHallCallToElevator(eq(2), anyInt())).thenReturn(true);

            DispatchResult result = dispatcher.dispatch(5, Direction.UP);

            assertThat(result).isEqualTo(DispatchResult.ASSIGNED);
            // Elevator 2 (not 1) should be assigned
            verify(elevatorService, times(1)).assignHallCallToElevator(eq(2), eq(5));
            verify(elevatorService, never()).assignHallCallToElevator(eq(1), anyInt());
        }

        @Test
        @DisplayName("ALL at capacity → result is QUEUED")
        void allAtCapacity_resultIsQueued() {
            Elevator full1 = new Elevator(1, 0, 1);
            full1.setPassengerLoad(1);
            Elevator full2 = new Elevator(2, 4, 1);
            full2.setPassengerLoad(1);

            when(elevatorService.getAllElevators()).thenReturn(List.of(full1, full2));

            DispatchResult result = dispatcher.dispatch(5, Direction.UP);

            assertThat(result).isEqualTo(DispatchResult.QUEUED);
            assertThat(dispatcher.getPendingQueueSize()).isEqualTo(1);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 7. RETRY QUEUE TESTS
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("retryPendingRequests() — Retry After Capacity Frees")
    class RetryQueueTests {

        @Test
        @DisplayName("Queued request is assigned on retry when elevator becomes available")
        void queuedRequest_assignedOnRetry() {
            // Step 1: Both elevators at capacity → QUEUED
            Elevator full = new Elevator(1, 0, 1);
            full.setPassengerLoad(1);
            when(elevatorService.getAllElevators()).thenReturn(List.of(full));

            dispatcher.dispatch(5, Direction.UP);  // → QUEUED
            assertThat(dispatcher.getPendingQueueSize()).isEqualTo(1);

            // Step 2: Elevator capacity frees up (passenger alights)
            full.setPassengerLoad(0);
            when(elevatorService.assignHallCallToElevator(eq(1), eq(5))).thenReturn(true);

            // Step 3: Retry is called (simulating next tick)
            dispatcher.retryPendingRequests();

            // Queue should now be empty — request was assigned
            assertThat(dispatcher.getPendingQueueSize()).isEqualTo(0);
            verify(elevatorService, times(1)).assignHallCallToElevator(eq(1), eq(5));
        }

        @Test
        @DisplayName("Retry with still-full elevators → request stays queued")
        void retryWithStillFullElevators_staysQueued() {
            Elevator full = new Elevator(1, 0, 1);
            full.setPassengerLoad(1);
            when(elevatorService.getAllElevators()).thenReturn(List.of(full));

            dispatcher.dispatch(5, Direction.UP);
            assertThat(dispatcher.getPendingQueueSize()).isEqualTo(1);

            // Retry — elevators still full
            dispatcher.retryPendingRequests();

            // Still in queue
            assertThat(dispatcher.getPendingQueueSize()).isEqualTo(1);
        }

        @Test
        @DisplayName("retryPendingRequests on empty queue → no-op (no error)")
        void retryEmptyQueue_noOp() {
            // Should not throw
            dispatcher.retryPendingRequests();
            assertThat(dispatcher.getPendingQueueSize()).isEqualTo(0);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 8. FULL INTEGRATION SCENARIO
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Integration Scenarios — Full Dispatch Lifecycle")
    class IntegrationScenarios {

        @Test
        @DisplayName("Scenario: 3 elevators, hall call at floor 6 UP → optimal elevator wins")
        void scenario_threeElevators_optimalWinner() {
            /*
             * Elevator 1: IDLE at floor 0  → cost = |0-6| = 6
             * Elevator 2: Moving UP at floor 4, next stop=9 → floor 6 is ahead
             *             cost = 6-4 = 2, direction matches UP → bonus=0 → total=2
             * Elevator 3: IDLE at floor 8  → cost = |8-6| = 2
             *
             * Elevator 2 and 3 are tied at cost=2.
             * Tie-break: same load (0) → lower ID wins → Elevator 2 wins.
             */
            Elevator e1 = elev(1, 0);
            Elevator e2 = elevMovingUp(2, 4, 9);   // going UP past floor 6
            Elevator e3 = elev(3, 8);

            when(elevatorService.getAllElevators()).thenReturn(List.of(e1, e2, e3));
            when(elevatorService.assignHallCallToElevator(eq(2), eq(6))).thenReturn(true);

            DispatchResult result = dispatcher.dispatch(6, Direction.UP);

            assertThat(result).isEqualTo(DispatchResult.ASSIGNED);
            verify(elevatorService).assignHallCallToElevator(eq(2), eq(6));
        }

        @Test
        @DisplayName("Scenario: Dispatch, arrive, re-dispatch same floor — full lifecycle")
        void scenario_fullLifecycle_dispatchArriveRedispatch() {
            Elevator e = elev(1, 0);
            when(elevatorService.getAllElevators()).thenReturn(List.of(e));
            when(elevatorService.assignHallCallToElevator(anyInt(), anyInt())).thenReturn(true);

            // 1. Hall call registered
            DispatchResult r1 = dispatcher.dispatch(5, Direction.UP);
            assertThat(r1).isEqualTo(DispatchResult.ASSIGNED);
            assertThat(dispatcher.isHallCallActive(5, Direction.UP)).isTrue();

            // 2. Same call again → DUPLICATE
            assertThat(dispatcher.dispatch(5, Direction.UP)).isEqualTo(DispatchResult.DUPLICATE);

            // 3. Elevator arrives → hall call cleared
            dispatcher.clearHallCall(5, Direction.UP);
            assertThat(dispatcher.isHallCallActive(5, Direction.UP)).isFalse();

            // 4. Passenger presses floor 5 UP again → should work (not duplicate)
            DispatchResult r2 = dispatcher.dispatch(5, Direction.UP);
            assertThat(r2).isEqualTo(DispatchResult.ASSIGNED);
        }

        @Test
        @DisplayName("Scenario: Basement hall call (-2 DOWN) dispatched to correct elevator")
        void scenario_basementHallCall() {
            /*
             * Tests that negative floor numbers work correctly.
             * Elevator at floor -1 is closest to floor -2 DOWN.
             */
            Elevator e1 = elev(1, -1);  // closest to -2
            Elevator e2 = elev(2, 5);

            when(elevatorService.getAllElevators()).thenReturn(List.of(e1, e2));
            when(elevatorService.assignHallCallToElevator(eq(1), eq(-2))).thenReturn(true);

            DispatchResult result = dispatcher.dispatch(-2, Direction.DOWN);

            assertThat(result).isEqualTo(DispatchResult.ASSIGNED);
            verify(elevatorService).assignHallCallToElevator(eq(1), eq(-2));
        }
    }
}
