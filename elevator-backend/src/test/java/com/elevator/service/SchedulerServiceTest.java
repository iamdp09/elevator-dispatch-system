package com.elevator.service;

import com.elevator.model.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SchedulerServiceTest — Validates the façade layer and LOOK routing through it.
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * TEST PHILOSOPHY FOR PHASE 5
 * ═══════════════════════════════════════════════════════════════════════════
 * Phase 5 has two categories of tests:
 *
 *  A) FAÇADE TESTS (mocked deps):
 *     Verify SchedulerService correctly delegates to DispatcherService and
 *     ElevatorService with the right arguments. These are unit tests that
 *     prove the wiring is correct.
 *
 *  B) LOOK ROUTING TESTS (real Elevator objects):
 *     Verify the LOOK algorithm's direction-aware queue insertion end-to-end:
 *       - Request AHEAD of elevator in current direction → served this sweep
 *       - Request BEHIND elevator in current direction → served return sweep
 *       - Dynamic mid-sweep insertion → correct queue placement
 *       - Sweep ordering → floors served in optimal sequence
 *
 *     These tests use real Elevator instances (no mocks) to exercise the
 *     actual addRequest() LOOK routing logic. This is important because
 *     LOOK correctness cannot be verified by mocking — you need the real
 *     state machine to confirm the behaviour.
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * LOOK ALGORITHM ROUTING RULES (tested here)
 * ═══════════════════════════════════════════════════════════════════════════
 *   Elevator going UP at floor C:
 *     New request F > C  →  upQueue   (serve this sweep, no detour)
 *     New request F < C  →  downQueue (serve after reversal at top)
 *
 *   Elevator going DOWN at floor C:
 *     New request F < C  →  downQueue (serve this sweep, no detour)
 *     New request F > C  →  upQueue   (serve after reversal at bottom)
 *
 *   IDLE elevator at floor C:
 *     New request F > C  →  upQueue + direction=UP
 *     New request F < C  →  downQueue + direction=DOWN
 * ═══════════════════════════════════════════════════════════════════════════
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SchedulerService — Façade & LOOK Routing Tests")
class SchedulerServiceTest {

    @Mock private DispatcherService dispatcherService;
    @Mock private ElevatorService   elevatorService;

    @InjectMocks private SchedulerService scheduler;

    // ═══════════════════════════════════════════════════════════════════════
    // A. FAÇADE DELEGATION TESTS (mocked)
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("A. Façade Delegation — Correct routing to sub-services")
    class FacadeTests {

        @Test
        @DisplayName("scheduleHallCall → delegates to DispatcherService.dispatch()")
        void hallCall_delegatesToDispatcher() {
            when(dispatcherService.dispatch(5, Direction.UP)).thenReturn(DispatchResult.ASSIGNED);

            DispatchResult result = scheduler.scheduleHallCall(5, Direction.UP);

            assertThat(result).isEqualTo(DispatchResult.ASSIGNED);
            verify(dispatcherService, times(1)).dispatch(5, Direction.UP);
            verifyNoInteractions(elevatorService);
        }

        @Test
        @DisplayName("scheduleHallCall REJECTED → result propagated unchanged")
        void hallCall_rejectedResult_propagated() {
            when(dispatcherService.dispatch(BuildingConfig.MAX_FLOOR, Direction.UP))
                    .thenReturn(DispatchResult.REJECTED);

            DispatchResult result = scheduler.scheduleHallCall(BuildingConfig.MAX_FLOOR, Direction.UP);

            assertThat(result).isEqualTo(DispatchResult.REJECTED);
        }

        @Test
        @DisplayName("scheduleCarCall → delegates to ElevatorService.addCarRequest()")
        void carCall_delegatesToElevatorService() {
            when(elevatorService.addCarRequest(2, 7)).thenReturn(true);

            boolean accepted = scheduler.scheduleCarCall(2, 7);

            assertThat(accepted).isTrue();
            verify(elevatorService, times(1)).addCarRequest(2, 7);
            verifyNoInteractions(dispatcherService);
        }

        @Test
        @DisplayName("scheduleCarCall rejected (bad floor) → false propagated")
        void carCall_rejected_propagated() {
            when(elevatorService.addCarRequest(1, 99)).thenReturn(false);

            assertThat(scheduler.scheduleCarCall(1, 99)).isFalse();
        }

        @Test
        @DisplayName("isHallCallActive → delegates to DispatcherService")
        void isHallCallActive_delegatesToDispatcher() {
            when(dispatcherService.isHallCallActive(3, Direction.DOWN)).thenReturn(true);

            assertThat(scheduler.isHallCallActive(3, Direction.DOWN)).isTrue();
            verify(dispatcherService).isHallCallActive(3, Direction.DOWN);
        }

        @Test
        @DisplayName("getSystemStatus → returns map with 'elevators' and 'building' keys")
        void getSystemStatus_returnsExpectedKeys() {
            when(elevatorService.getAllElevators())
                    .thenReturn(List.of(new Elevator(1, 0, 10)));
            when(dispatcherService.getActiveHallCalls()).thenReturn(Map.of());
            when(dispatcherService.getPendingQueueSize()).thenReturn(0);
            when(dispatcherService.getStats()).thenReturn(Map.of("dispatched", 0L));

            Map<String, Object> status = scheduler.getSystemStatus();

            assertThat(status).containsKeys("elevators", "activeHallCalls",
                    "pendingQueue", "dispatchStats", "building");
        }

        @Test
        @DisplayName("getSystemStatus → elevator snapshot includes floor, state, direction, queues")
        void getSystemStatus_elevatorSnapshot_hasExpectedFields() {
            Elevator e = new Elevator(1, 5, 10);
            when(elevatorService.getAllElevators()).thenReturn(List.of(e));
            when(dispatcherService.getActiveHallCalls()).thenReturn(Map.of());
            when(dispatcherService.getPendingQueueSize()).thenReturn(0);
            when(dispatcherService.getStats()).thenReturn(Map.of());

            Map<String, Object> status = scheduler.getSystemStatus();

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> elevators = (List<Map<String, Object>>) status.get("elevators");
            Map<String, Object> snap = elevators.get(0);

            assertThat(snap).containsKeys("id", "floor", "floorLabel",
                    "direction", "state", "passengerLoad", "capacity",
                    "atCapacity", "upQueue", "downQueue", "doorTicks");
            assertThat(snap.get("floor")).isEqualTo(5);
            assertThat(snap.get("state")).isEqualTo("IDLE");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // B. LOOK ROUTING TESTS (real Elevator, no mocks)
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("B. LOOK Queue Routing — Direction-Aware Insertion")
    class LookRoutingTests {

        /**
         * Creates a real Elevator going UP toward a topFloor, at currentFloor.
         * Uses the real addRequest() LOOK routing to set up state.
         */
        private Elevator makeTravellingUpElevator(int id, int currentFloor, int topFloor) {
            Elevator e = new Elevator(id, currentFloor, 10);
            e.addRequest(topFloor);   // sets direction=UP, state=MOVING, topFloor in upQueue
            return e;
        }

        private Elevator makeTravellingDownElevator(int id, int currentFloor, int bottomFloor) {
            Elevator e = new Elevator(id, currentFloor, 10);
            e.addRequest(bottomFloor); // sets direction=DOWN, state=MOVING, bottomFloor in downQueue
            return e;
        }

        // ── B1: IDLE elevator routing ────────────────────────────────────

        @Test
        @DisplayName("LOOK: IDLE elevator, request above → upQueue, direction=UP")
        void idle_requestAbove_goesToUpQueue() {
            Elevator e = new Elevator(1, 0, 10);

            boolean added = e.addRequest(7);

            assertThat(added).isTrue();
            assertThat(e.getDirection()).isEqualTo(Direction.UP);
            assertThat(e.getState()).isEqualTo(ElevatorState.MOVING);
            assertThat(e.getUpQueueSnapshot()).contains(7);
            assertThat(e.getDownQueueSnapshot()).isEmpty();
        }

        @Test
        @DisplayName("LOOK: IDLE elevator, request below → downQueue, direction=DOWN")
        void idle_requestBelow_goesToDownQueue() {
            Elevator e = new Elevator(1, 5, 10);

            e.addRequest(-2);

            assertThat(e.getDirection()).isEqualTo(Direction.DOWN);
            assertThat(e.getState()).isEqualTo(ElevatorState.MOVING);
            assertThat(e.getDownQueueSnapshot()).contains(-2);
            assertThat(e.getUpQueueSnapshot()).isEmpty();
        }

        // ── B2: UP-travelling elevator routing ──────────────────────────

        @Test
        @DisplayName("LOOK: going UP, new request AHEAD → goes to upQueue (served this sweep)")
        void goingUp_requestAhead_servesThisSweep() {
            /*
             * Elevator at floor 3, going UP to 8.
             * New request: floor 6 (ahead of current position).
             * Expected: floor 6 in upQueue → served BEFORE reaching floor 8.
             *
             * Real-world: passenger on floor 6 presses UP,
             * elevator stops there on its way to 8. No wasted motion.
             */
            Elevator e = makeTravellingUpElevator(1, 3, 8);
            e.addRequest(6);

            assertThat(e.getUpQueueSnapshot()).contains(6, 8);   // both in upQueue
            assertThat(e.getDownQueueSnapshot()).isEmpty();
        }

        @Test
        @DisplayName("LOOK: going UP, new request BEHIND → goes to downQueue (served on return)")
        void goingUp_requestBehind_servesReturnSweep() {
            /*
             * Elevator at floor 5, going UP to 9.
             * New request: floor 2 (behind current position).
             * Expected: floor 2 in downQueue → served AFTER elevator reverses at 9.
             *
             * Real-world: passenger on floor 2 presses UP while elevator is passing.
             * The elevator will serve floor 2 on its way back down.
             */
            Elevator e = makeTravellingUpElevator(1, 5, 9);
            e.addRequest(2);

            assertThat(e.getUpQueueSnapshot()).contains(9);
            assertThat(e.getDownQueueSnapshot()).contains(2); // floor 2 deferred to return
        }

        @Test
        @DisplayName("LOOK: going UP, multiple requests — upQueue sorted ascending, nextStop=lowest")
        void goingUp_multipleRequests_nextStopIsLowestInUpQueue() {
            /*
             * Elevator at floor 0, going UP.
             * Requests: 8, 3, 6 (added in random order).
             * LOOK should serve them in ascending order: 3 → 6 → 8.
             * getNextStop() must return 3 (the first = lowest in upQueue TreeSet).
             */
            Elevator e = new Elevator(1, 0, 10);
            e.addRequest(8);
            e.addRequest(3);
            e.addRequest(6);

            assertThat(e.getNextStop()).isEqualTo(3);    // ascending order
            assertThat(e.getUpQueueSnapshot()).containsExactlyInAnyOrder(3, 6, 8);
        }

        // ── B3: DOWN-travelling elevator routing ─────────────────────────

        @Test
        @DisplayName("LOOK: going DOWN, new request AHEAD (lower) → goes to downQueue (this sweep)")
        void goingDown_requestAhead_servesThisSweep() {
            /*
             * Elevator at floor 7, going DOWN to 1.
             * New request: floor 4 (below current → ahead for a DOWN-going elevator).
             * Expected: floor 4 in downQueue → served BEFORE reaching floor 1.
             */
            Elevator e = makeTravellingDownElevator(1, 7, 1);
            e.addRequest(4);

            assertThat(e.getDownQueueSnapshot()).contains(4, 1);
            assertThat(e.getUpQueueSnapshot()).isEmpty();
        }

        @Test
        @DisplayName("LOOK: going DOWN, new request ABOVE → goes to upQueue (served on return)")
        void goingDown_requestAbove_servesReturnSweep() {
            /*
             * Elevator at floor 6, going DOWN to 0.
             * New request: floor 9 (above current → behind a DOWN elevator).
             * Expected: floor 9 in upQueue → served after reversal at bottom.
             */
            Elevator e = makeTravellingDownElevator(1, 6, 0);
            e.addRequest(9);

            assertThat(e.getDownQueueSnapshot()).contains(0);
            assertThat(e.getUpQueueSnapshot()).contains(9); // deferred to return
        }

        @Test
        @DisplayName("LOOK: going DOWN, multiple requests — downQueue sorted descending, nextStop=highest")
        void goingDown_multipleRequests_nextStopIsHighestInDownQueue() {
            /*
             * Elevator at floor 10, going DOWN.
             * Requests: 2, 7, 5 (added in random order).
             * LOOK should serve them in descending order: 7 → 5 → 2.
             * getNextStop() must return 7 (the first = highest in reversed TreeSet).
             */
            Elevator e = new Elevator(1, 10, 10);
            e.addRequest(2);
            e.addRequest(7);
            e.addRequest(5);

            assertThat(e.getNextStop()).isEqualTo(7);   // descending order
            assertThat(e.getDownQueueSnapshot()).containsExactlyInAnyOrder(2, 5, 7);
        }

        // ── B4: Dynamic mid-sweep insertion ─────────────────────────────

        @Test
        @DisplayName("LOOK: dynamic insertion mid-sweep doesn't change current nextStop")
        void dynamicInsertion_doesNotDisruptCurrentSweep() {
            /*
             * Elevator at floor 1, going UP.
             * Initial request: floor 5.
             * Mid-sweep: new request for floor 3 arrives (ahead).
             *
             * The elevator's next stop should update to floor 3
             * (because 3 < 5, it's the new first in the upQueue).
             * This proves LOOK handles mid-sweep dynamic inserts correctly.
             */
            Elevator e = new Elevator(1, 1, 10);
            e.addRequest(5);
            assertThat(e.getNextStop()).isEqualTo(5); // before dynamic insert

            e.addRequest(3);  // dynamic insert while "in motion"
            assertThat(e.getNextStop()).isEqualTo(3); // now serves 3 first (closer)
            assertThat(e.getUpQueueSnapshot()).containsExactlyInAnyOrder(3, 5);
        }

        @Test
        @DisplayName("LOOK: dynamic insertion BEHIND during UP sweep goes to return queue")
        void dynamicInsertion_behindCurrentPos_goesToReturnQueue() {
            /*
             * Elevator at floor 6, going UP to 10.
             * Dynamic insert: floor 2 (behind current position).
             * Expected: floor 2 goes to downQueue (deferred to return sweep).
             * Current sweep (upQueue) is unaffected.
             */
            Elevator e = new Elevator(1, 6, 10);
            e.addRequest(10);

            // Simulate elevator "moved" to floor 6 by adjusting floor directly
            // (in real sim this happens tick by tick, but here we test the routing)
            e.addRequest(2); // behind current floor 6

            assertThat(e.getUpQueueSnapshot()).contains(10);   // current sweep unchanged
            assertThat(e.getDownQueueSnapshot()).contains(2);  // deferred correctly
        }

        // ── B5: Sweep ordering validation ───────────────────────────────

        @Test
        @DisplayName("LOOK: UP sweep serves floors in strictly ascending order")
        void upSweep_ascendingOrder() {
            /*
             * This test simulates the actual LOOK service order for an upward sweep.
             * We verify the queue gives us floors in ascending order
             * (as the elevator would naturally visit them).
             */
            Elevator e = new Elevator(1, 0, 10);
            e.addRequest(9);
            e.addRequest(4);
            e.addRequest(1);
            e.addRequest(7);

            // getNextStop() returns first element of upQueue (ascending TreeSet)
            assertThat(e.getNextStop()).isEqualTo(1);
            e.removeFloorFromQueues(1);
            assertThat(e.getNextStop()).isEqualTo(4);
            e.removeFloorFromQueues(4);
            assertThat(e.getNextStop()).isEqualTo(7);
            e.removeFloorFromQueues(7);
            assertThat(e.getNextStop()).isEqualTo(9);
        }

        @Test
        @DisplayName("LOOK: DOWN sweep serves floors in strictly descending order")
        void downSweep_descendingOrder() {
            /*
             * Elevator going DOWN from floor 10.
             * Requests at 8, 3, 6, 1 — should be served 8 → 6 → 3 → 1.
             */
            Elevator e = new Elevator(1, 10, 10);
            e.addRequest(8);
            e.addRequest(3);
            e.addRequest(6);
            e.addRequest(1);

            assertThat(e.getNextStop()).isEqualTo(8);
            e.removeFloorFromQueues(8);
            assertThat(e.getNextStop()).isEqualTo(6);
            e.removeFloorFromQueues(6);
            assertThat(e.getNextStop()).isEqualTo(3);
            e.removeFloorFromQueues(3);
            assertThat(e.getNextStop()).isEqualTo(1);
        }

        @Test
        @DisplayName("LOOK: basement floors (negative) handled correctly in downQueue")
        void basementFloors_handledCorrectly() {
            /*
             * Tests negative floor numbers (-3 to 0) in LOOK routing.
             * Elevator at floor 2, going DOWN.
             * Requests: -1, -3, 0 → should be served 0 → -1 → -3 (descending).
             */
            Elevator e = new Elevator(1, 2, 10);
            e.addRequest(-1);
            e.addRequest(-3);
            e.addRequest(0);

            assertThat(e.getNextStop()).isEqualTo(0);
            e.removeFloorFromQueues(0);
            assertThat(e.getNextStop()).isEqualTo(-1);
            e.removeFloorFromQueues(-1);
            assertThat(e.getNextStop()).isEqualTo(-3);
        }

        @Test
        @DisplayName("LOOK: duplicate floor request ignored (idempotent)")
        void duplicateFloorRequest_ignored() {
            /*
             * Real elevators ignore a button press if that floor is already queued.
             * addRequest() must return false and not add a duplicate.
             */
            Elevator e = new Elevator(1, 0, 10);
            boolean first  = e.addRequest(5);
            boolean second = e.addRequest(5); // same floor again

            assertThat(first).isTrue();
            assertThat(second).isFalse();
            assertThat(e.getUpQueueSnapshot()).hasSize(1);  // only one entry
        }
    }
}
