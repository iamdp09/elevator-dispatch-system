package com.elevator.service;

import com.elevator.model.BuildingConfig;
import com.elevator.model.Direction;
import com.elevator.model.Elevator;
import com.elevator.model.ElevatorState;
import com.elevator.websocket.ElevatorStatePublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * SimulationEngineTest — Validates every FSM transition and LOOK algorithm branch.
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * TESTING STRATEGY
 * ═══════════════════════════════════════════════════════════════════════════
 * We use Mockito to mock the ElevatorService and ElevatorStatePublisher,
 * allowing us to:
 *   1. Inject pre-configured Elevator objects directly
 *   2. Verify that the publisher is called after each tick
 *   3. Test SimulationEngine logic in complete isolation
 *
 * DIRECT METHOD TESTING vs @Scheduled TESTING:
 * ─────────────────────────────────────────────
 * We test processElevator(), handleDoorOpenTick(), handleMovingTick(),
 * and applyLookDirectionLogic() DIRECTLY — not via the @Scheduled tick().
 *
 * Why? @Scheduled is a Spring infrastructure concern. Testing it requires
 * spinning up a Spring context, which makes tests 10× slower and harder to
 * maintain. The business logic lives in the helper methods — that's what
 * we validate. The @Scheduled wrapper just calls tick() which calls those.
 *
 * This is a best practice: "test behavior, not infrastructure."
 *
 * MOCK USAGE:
 * ────────────
 * - ElevatorService → mocked: we control what getAllElevators() returns
 * - ElevatorMapper  → mocked: we don't care about DTO conversion in these tests
 * - ElevatorStatePublisher → mocked: we verify broadcastState() is called
 * ═══════════════════════════════════════════════════════════════════════════
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SimulationEngine — FSM & LOOK Algorithm Tests")
class SimulationEngineTest {

    @Mock private ElevatorService elevatorService;
    @Mock private ElevatorMapper  elevatorMapper;
    @Mock private ElevatorStatePublisher publisher;
    @Mock private DispatcherService dispatcherService;  // Added Phase 4: SimulationEngine now depends on this

    @InjectMocks
    private SimulationEngine engine;

    // ── Shared elevator helper ────────────────────────────────────────────
    /** Creates a fresh elevator at the given floor for test scenarios. */
    private Elevator elevator(int floor) {
        return new Elevator(1, floor, 10);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 1. DOOR_OPEN STATE TESTS
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("handleDoorOpenTick() — Door Timer & Close Logic")
    class DoorOpenTests {

        @Test
        @DisplayName("Door open with ticks remaining → decrements counter only")
        void doorOpen_ticksRemaining_decrementsCounter() {
            Elevator e = elevator(5);
            e.addRequest(5);  // Force DOOR_OPEN on current floor
            // Manually set state
            e.setState(ElevatorState.DOOR_OPEN);
            e.setDoorOpenTicksRemaining(2);

            engine.handleDoorOpenTick(e);

            // Counter decremented from 2 → 1; state remains DOOR_OPEN
            assertThat(e.getDoorOpenTicksRemaining()).isEqualTo(1);
            assertThat(e.getState()).isEqualTo(ElevatorState.DOOR_OPEN);
        }

        @Test
        @DisplayName("Door open with 0 ticks remaining → triggers LOOK logic, doors close")
        void doorOpen_zeroTicks_closesDoorsAppliesLook() {
            Elevator e = elevator(0);
            e.addRequest(5);              // queued: upQueue=[5], direction=UP
            e.setState(ElevatorState.DOOR_OPEN);
            e.setDoorOpenTicksRemaining(0); // ready to close

            engine.handleDoorOpenTick(e);

            // LOOK should detect upQueue=[5], keep direction UP, set MOVING
            assertThat(e.getState()).isEqualTo(ElevatorState.MOVING);
            assertThat(e.getDirection()).isEqualTo(Direction.UP);
        }

        @Test
        @DisplayName("Door closes with empty queues → transitions to IDLE")
        void doorOpen_emptyQueues_transitionsToIdle() {
            Elevator e = elevator(3);
            e.setState(ElevatorState.DOOR_OPEN);
            e.setDoorOpenTicksRemaining(0);
            // No queued floors — simulate all requests served

            engine.handleDoorOpenTick(e);

            assertThat(e.getState()).isEqualTo(ElevatorState.IDLE);
            assertThat(e.getDirection()).isEqualTo(Direction.IDLE);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 2. MOVING STATE TESTS
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("handleMovingTick() — Floor-by-Floor Movement")
    class MovingTests {

        @Test
        @DisplayName("Moving UP: elevator advances one floor per tick")
        void movingUp_advancesOneFloor() {
            Elevator e = elevator(0);
            e.addRequest(5);  // direction=UP, state=MOVING

            int initialFloor = e.getCurrentFloor(); // 0
            engine.handleMovingTick(e);

            // Should have moved exactly ONE floor up
            assertThat(e.getCurrentFloor()).isEqualTo(initialFloor + 1);
        }

        @Test
        @DisplayName("Moving DOWN: elevator descends one floor per tick")
        void movingDown_descendsOneFloor() {
            Elevator e = elevator(5);
            e.addRequest(-1);  // direction=DOWN, state=MOVING

            int initialFloor = e.getCurrentFloor(); // 5
            engine.handleMovingTick(e);

            assertThat(e.getCurrentFloor()).isEqualTo(initialFloor - 1);
        }

        @Test
        @DisplayName("Moving UP: arrives at next stop → opens doors")
        void movingUp_arrivesAtStop_opensDoors() {
            /*
             * Elevator at floor 4, going UP, next stop = 5.
             * One tick: moves from 4 → 5 = arrival. Doors open.
             */
            Elevator e = elevator(4);
            e.addRequest(5);   // upQueue=[5], state=MOVING

            engine.handleMovingTick(e);

            assertThat(e.getCurrentFloor()).isEqualTo(5);
            assertThat(e.getState()).isEqualTo(ElevatorState.DOOR_OPEN);
            assertThat(e.getDoorOpenTicksRemaining()).isEqualTo(BuildingConfig.DOOR_OPEN_TICKS);
        }

        @Test
        @DisplayName("Moving DOWN: arrives at next stop → opens doors")
        void movingDown_arrivesAtStop_opensDoors() {
            Elevator e = elevator(3);
            e.addRequest(-2); // direction DOWN, downQueue=[-2]
            // Simulate: elevator is now at floor -1 (one floor above -2)
            e.setCurrentFloor(-1);

            engine.handleMovingTick(e);

            assertThat(e.getCurrentFloor()).isEqualTo(-2);
            assertThat(e.getState()).isEqualTo(ElevatorState.DOOR_OPEN);
        }

        @Test
        @DisplayName("Moving UP: mid-transit does NOT open doors (not at stop yet)")
        void movingUp_midTransit_doesNotOpenDoors() {
            /*
             * Elevator at floor 2, heading for floor 6.
             * After one tick: floor 3. Not 6 yet → no door open.
             */
            Elevator e = elevator(2);
            e.addRequest(6);  // next stop = 6, 4 floors away

            engine.handleMovingTick(e);

            assertThat(e.getCurrentFloor()).isEqualTo(3);
            assertThat(e.getState()).isEqualTo(ElevatorState.MOVING);
        }

        @Test
        @DisplayName("Moving UP: arrival removes floor from queue (floor served)")
        void movingUp_arrival_removesFloorFromQueue() {
            Elevator e = elevator(4);
            e.addRequest(5);

            engine.handleMovingTick(e); // arrives at 5

            assertThat(e.hasPendingRequests()).isFalse();
            assertThat(e.getUpQueueSnapshot()).doesNotContain(5);
        }

        @Test
        @DisplayName("Moving UP: passing intermediate floor does NOT serve it (LOOK, not SCAN)")
        void movingUp_passingThroughFloor_notServedUnlessQueued() {
            /*
             * Important LOOK property: elevator only stops at QUEUED floors.
             * Floor 3 is NOT in the queue → elevator passes through it freely.
             * This is LOOK, not SCAN (which would stop at every floor).
             */
            Elevator e = elevator(0);
            e.addRequest(5);   // Only stop: floor 5

            // Move 3 ticks: floor 0 → 1 → 2 → 3
            engine.handleMovingTick(e); // floor 1
            engine.handleMovingTick(e); // floor 2
            engine.handleMovingTick(e); // floor 3

            // Floor 3 passed through WITHOUT opening doors (not in queue!)
            assertThat(e.getCurrentFloor()).isEqualTo(3);
            assertThat(e.getState()).isEqualTo(ElevatorState.MOVING);
            assertThat(e.hasPendingRequests()).isTrue(); // floor 5 still pending
        }

        @Test
        @DisplayName("Moving with no next stop (inconsistent state) → gracefully resets to IDLE")
        void movingNoNextStop_resetsToIdle() {
            Elevator e = elevator(5);
            // Manually set MOVING without a queued stop (edge case / bug recovery)
            e.setState(ElevatorState.MOVING);
            e.setDirection(Direction.UP);
            // Queues are empty, getNextStop() = null

            engine.handleMovingTick(e);

            assertThat(e.getState()).isEqualTo(ElevatorState.IDLE);
            assertThat(e.getDirection()).isEqualTo(Direction.IDLE);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 3. LOOK ALGORITHM TESTS — The Core Intelligence
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("applyLookDirectionLogic() — Direction Reversal Algorithm")
    class LookAlgorithmTests {

        @Test
        @DisplayName("Going UP, more floors above → continues UP (no reversal)")
        void goingUp_moreFloorsAbove_continuesUp() {
            /*
             * Classic LOOK scenario: going UP, just served floor 3, floor 7 still pending.
             * Expected: continue UP to floor 7.
             */
            Elevator e = elevator(0);
            e.addRequest(3);
            e.addRequest(7);
            // Simulate arriving at floor 3 (doors just opened and closed)
            e.removeFloorFromQueues(3);
            e.setCurrentFloor(3);
            e.setState(ElevatorState.DOOR_OPEN);
            e.setDoorOpenTicksRemaining(0);

            engine.applyLookDirectionLogic(e);

            assertThat(e.getDirection()).isEqualTo(Direction.UP);
            assertThat(e.getState()).isEqualTo(ElevatorState.MOVING);
        }

        @Test
        @DisplayName("Going UP, no more floors above but floors below → REVERSES to DOWN")
        void goingUp_noMoreAbove_reversesToDown() {
            /*
             * LOOK reversal: elevator going UP just served its highest request.
             * There are floors waiting below → switch to DOWN without going to extreme.
             *
             * Example: at floor 8, served floor 8. Floors 2 and 5 still waiting.
             * Expected: reverse to DOWN, serve 5 then 2.
             * Key: does NOT go all the way to floor 10 first (that would be SCAN).
             */
            Elevator e = elevator(0);
            e.addRequest(8);  // going up
            e.addRequest(-1); // down queue (behind, will serve on return)
            // Arrive at floor 8 (highest UP stop)
            e.setCurrentFloor(8);
            e.removeFloorFromQueues(8);
            // State setup for LOOK decision
            e.setState(ElevatorState.DOOR_OPEN);
            e.setDoorOpenTicksRemaining(0);

            engine.applyLookDirectionLogic(e);

            // LOOK: upQueue empty, downQueue has -1 → REVERSE
            assertThat(e.getDirection()).isEqualTo(Direction.DOWN);
            assertThat(e.getState()).isEqualTo(ElevatorState.MOVING);
        }

        @Test
        @DisplayName("Going UP, nothing above OR below → IDLE")
        void goingUp_nothingAnyDirection_idle() {
            Elevator e = elevator(0);
            e.addRequest(5);
            e.setCurrentFloor(5);
            e.removeFloorFromQueues(5);   // last request served
            e.setState(ElevatorState.DOOR_OPEN);
            e.setDoorOpenTicksRemaining(0);

            engine.applyLookDirectionLogic(e);

            assertThat(e.getState()).isEqualTo(ElevatorState.IDLE);
            assertThat(e.getDirection()).isEqualTo(Direction.IDLE);
        }

        @Test
        @DisplayName("Going DOWN, more floors below → continues DOWN (no reversal)")
        void goingDown_moreFloorsBelow_continuesDown() {
            Elevator e = elevator(8);
            e.addRequest(3);  // going DOWN to 3
            e.addRequest(-2); // and then to -2
            // Arrive at floor 3
            e.setCurrentFloor(3);
            e.removeFloorFromQueues(3);
            e.setState(ElevatorState.DOOR_OPEN);
            e.setDoorOpenTicksRemaining(0);

            engine.applyLookDirectionLogic(e);

            assertThat(e.getDirection()).isEqualTo(Direction.DOWN);
            assertThat(e.getState()).isEqualTo(ElevatorState.MOVING);
        }

        @Test
        @DisplayName("Going DOWN, no more floors below but floors above → REVERSES to UP")
        void goingDown_noMoreBelow_reversesToUp() {
            Elevator e = elevator(8);
            e.addRequest(-1); // going DOWN to -1
            e.addRequest(10); // upQueue: serve after down sweep
            // Arrive at -1 (lowest DOWN stop)
            e.setCurrentFloor(-1);
            e.removeFloorFromQueues(-1);
            e.setState(ElevatorState.DOOR_OPEN);
            e.setDoorOpenTicksRemaining(0);

            engine.applyLookDirectionLogic(e);

            // LOOK: downQueue empty, upQueue has 10 → REVERSE to UP
            assertThat(e.getDirection()).isEqualTo(Direction.UP);
            assertThat(e.getState()).isEqualTo(ElevatorState.MOVING);
        }

        @Test
        @DisplayName("Going DOWN, nothing anywhere → IDLE")
        void goingDown_nothingAnyDirection_idle() {
            Elevator e = elevator(5);
            e.addRequest(-3); // only request
            e.setCurrentFloor(-3);
            e.removeFloorFromQueues(-3);
            e.setState(ElevatorState.DOOR_OPEN);
            e.setDoorOpenTicksRemaining(0);

            engine.applyLookDirectionLogic(e);

            assertThat(e.getState()).isEqualTo(ElevatorState.IDLE);
            assertThat(e.getDirection()).isEqualTo(Direction.IDLE);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 4. FULL SIMULATION SCENARIO TESTS — End-to-End Sequences
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Full Simulation Scenarios — Multi-Tick Sequences")
    class SimulationScenarioTests {

        /**
         * Runs N ticks of the simulation engine against a single elevator.
         * Uses the real processElevator() method (not mocked) to test
         * the actual FSM flow end-to-end.
         */
        private void runTicks(SimulationEngine eng, Elevator e, int count) {
            for (int i = 0; i < count; i++) {
                eng.processElevator(e);
            }
        }

        @Test
        @DisplayName("Scenario: Floor 0 → 3 in correct number of ticks with door cycle")
        void scenario_floor0to3_correctTiming() {
            /*
             * Elevator at floor 0, request for floor 3.
             * Door timer behaviour with DOOR_OPEN_TICKS=2:
             *   Arrival → remaining set to 2
             *   Tick 4: remaining 2→1  (doors still open)
             *   Tick 5: remaining 1→0  (doors still open)
             *   Tick 6: remaining==0 → applyLook → IDLE
             */
            Elevator e = elevator(0);
            e.addRequest(3);

            runTicks(engine, e, 3); // 3 movement ticks: 0→1→2→3
            assertThat(e.getCurrentFloor()).isEqualTo(3);
            assertThat(e.getState()).isEqualTo(ElevatorState.DOOR_OPEN);

            runTicks(engine, e, 3); // 3 door ticks: decrement twice then LOOK fires
            assertThat(e.getState()).isEqualTo(ElevatorState.IDLE);
            assertThat(e.getCurrentFloor()).isEqualTo(3);
        }

        @Test
        @DisplayName("Scenario: LOOK — serves [2, 7] going UP, no reversal needed")
        void scenario_look_servesMultipleFloors() {
            /*
             * Elevator at 0, requests: floor 2 and floor 7.
             * LOOK order: 0 → 2 [stop] → 7 [stop] → IDLE
             *
             * Door cycle = 3 ticks (remaining: set to 2, tick 2→1, tick 1→0, tick 0→LOOK)
             */
            Elevator e = elevator(0);
            e.addRequest(2);
            e.addRequest(7);

            // Phase 1: reach floor 2 (2 ticks: 0→1, 1→2)
            runTicks(engine, e, 2);
            assertThat(e.getCurrentFloor()).isEqualTo(2);
            assertThat(e.getState()).isEqualTo(ElevatorState.DOOR_OPEN);

            // Phase 2: door cycle at floor 2 (3 ticks) → LOOK → MOVING UP (floor 7 still queued)
            runTicks(engine, e, 3);
            assertThat(e.getState()).isEqualTo(ElevatorState.MOVING);
            assertThat(e.getDirection()).isEqualTo(Direction.UP);

            // Phase 3: travel from 2 to 7 (5 ticks: 2→3→4→5→6→7)
            runTicks(engine, e, 5);
            assertThat(e.getCurrentFloor()).isEqualTo(7);
            assertThat(e.getState()).isEqualTo(ElevatorState.DOOR_OPEN);

            // Phase 4: door cycle at floor 7 (3 ticks) → LOOK → IDLE
            runTicks(engine, e, 3);
            assertThat(e.getState()).isEqualTo(ElevatorState.IDLE);
            assertThat(e.getDirection()).isEqualTo(Direction.IDLE);
            assertThat(e.hasPendingRequests()).isFalse();
        }

        @Test
        @DisplayName("Scenario: LOOK reversal — UP sweep then DOWN sweep")
        void scenario_lookReversal_upThenDown() {
            /*
             * Classic LOOK reversal scenario:
             * Elevator at floor 0. Requests: UP to 5, then DOWN to -2.
             *
             * The -2 request arrives while going UP → goes to downQueue.
             * After serving floor 5 (top of UP sweep):
             *   LOOK: upQueue empty, downQueue=[-2] → REVERSE DOWN
             * Then serve -2.
             *
             * This is the defining LOOK behaviour: NO trip to floor 10 first.
             */
            Elevator e = elevator(0);
            e.addRequest(5);   // UP request → upQueue=[5]
            e.addRequest(-2);  // Added while going UP → downQueue=[-2]

            // Travel UP to 5 (5 ticks)
            runTicks(engine, e, 5);
            assertThat(e.getCurrentFloor()).isEqualTo(5);
            assertThat(e.getState()).isEqualTo(ElevatorState.DOOR_OPEN);

            // Door cycle: 3 ticks → triggers LOOK → should REVERSE DOWN
            runTicks(engine, e, 3);
            assertThat(e.getDirection()).isEqualTo(Direction.DOWN);
            assertThat(e.getState()).isEqualTo(ElevatorState.MOVING);

            // Travel DOWN from 5 to -2 (7 ticks: 5→4→3→2→1→0→-1→-2)
            runTicks(engine, e, 7);
            assertThat(e.getCurrentFloor()).isEqualTo(-2);
            assertThat(e.getState()).isEqualTo(ElevatorState.DOOR_OPEN);

            // Final door cycle (3 ticks) → IDLE
            runTicks(engine, e, 3);
            assertThat(e.getState()).isEqualTo(ElevatorState.IDLE);
        }

        @Test
        @DisplayName("Scenario: Dynamic insertion — new request added mid-travel is picked up")
        void scenario_dynamicInsertion_midTravel() {
            /*
             * This tests one of the most critical requirements:
             * "Handle requests even while elevators are already in motion."
             *
             * Elevator at 0, going UP to floor 8.
             * After 2 ticks (now at floor 2), passenger presses floor 5.
             * Floor 5 is AHEAD of direction → inserted into upQueue.
             * Elevator should stop at 5 BEFORE 8.
             */
            Elevator e = elevator(0);
            e.addRequest(8);  // initial destination

            // 2 ticks: elevator moves to floor 2
            runTicks(engine, e, 2);
            assertThat(e.getCurrentFloor()).isEqualTo(2);

            // Dynamic insertion of floor 5 while moving
            e.addRequest(5);
            assertThat(e.getNextStop()).isEqualTo(5); // 5 < 8, should be next

            // 3 more ticks: elevator reaches floor 5
            runTicks(engine, e, 3);
            assertThat(e.getCurrentFloor()).isEqualTo(5);
            assertThat(e.getState()).isEqualTo(ElevatorState.DOOR_OPEN);

            // Floor 8 should still be queued
            assertThat(e.getUpQueueSnapshot()).contains(8);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 5. TICK LOOP INTEGRATION TESTS
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("tick() — Scheduler Integration")
    class TickIntegrationTests {

        @Test
        @DisplayName("tick() calls broadcastState() exactly once per tick")
        void tick_callsBroadcastStateOnce() {
            when(elevatorService.getAllElevators()).thenReturn(List.of());
            when(elevatorMapper.toDTOList(anyList())).thenReturn(List.of());

            engine.tick();

            // Publisher must be called exactly ONCE per tick
            verify(publisher, times(1)).broadcastState(anyList());
        }

        @Test
        @DisplayName("tick() increments tick counter on each call")
        void tick_incrementsTickCounter() {
            when(elevatorService.getAllElevators()).thenReturn(List.of());
            when(elevatorMapper.toDTOList(anyList())).thenReturn(List.of());

            engine.tick();
            engine.tick();
            engine.tick();

            assertThat(engine.getTickCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("tick() processes all 3 elevators per call")
        void tick_processesAllElevators() {
            Elevator e1 = elevator(0);
            Elevator e2 = new Elevator(2, 4, 10);
            Elevator e3 = new Elevator(3, 8, 10);

            when(elevatorService.getAllElevators()).thenReturn(List.of(e1, e2, e3));
            when(elevatorMapper.toDTOList(anyList())).thenReturn(List.of());

            engine.tick();

            // All 3 elevators are IDLE with no requests → states unchanged
            assertThat(e1.getState()).isEqualTo(ElevatorState.IDLE);
            assertThat(e2.getState()).isEqualTo(ElevatorState.IDLE);
            assertThat(e3.getState()).isEqualTo(ElevatorState.IDLE);

            verify(publisher, times(1)).broadcastState(anyList());
        }
    }
}
