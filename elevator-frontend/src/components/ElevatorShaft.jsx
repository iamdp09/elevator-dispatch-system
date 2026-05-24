import React from 'react';
import { FLOORS, MIN_FLOOR, MAX_FLOOR, TOTAL_FLOORS, floorLabel, floorToPercent, ELEVATOR_COLORS } from '../constants';

// ── Per-elevator car ─────────────────────────────────────────────────────────

function ElevatorCar({ elevator }) {
  const { elevatorId, currentFloor, state, direction, doorOpen } = elevator;
  const color   = ELEVATOR_COLORS[elevatorId] ?? ELEVATOR_COLORS[1];
  const topPct  = floorToPercent(currentFloor);
  const isOpen  = state === 'DOOR_OPEN' || doorOpen;
  const isMoving = state === 'MOVING';
  const isIdle   = state === 'IDLE';

  const stateClass = isOpen ? 'door-open' : isMoving ? 'moving' : 'idle';

  return (
    <div
      className={`elevator-car ${stateClass}`}
      style={{
        '--car-color':  color.primary,
        '--car-glow':   color.glow,
        top: `calc(${topPct}% + 2px)`,
      }}
      title={`E${elevatorId} | ${state} | Floor ${floorLabel(currentFloor)}`}
    >
      {/* Direction arrow */}
      <span className="car-arrow">
        {direction === 'UP' ? '▲' : direction === 'DOWN' ? '▼' : '●'}
      </span>

      {/* Elevator ID badge */}
      <span className="car-id">E{elevatorId}</span>

      {/* Door animation lines */}
      {isOpen && (
        <div className="door-lines">
          <span className="door-left" />
          <span className="door-right" />
        </div>
      )}
    </div>
  );
}

// ── Single elevator shaft column ─────────────────────────────────────────────

export default function ElevatorShaft({ elevator }) {
  const { elevatorId, upQueue = [], downQueue = [], passengerLoad, capacity } = elevator;
  const color  = ELEVATOR_COLORS[elevatorId];
  const allQ   = new Set([...upQueue, ...downQueue]);
  const loadPct = (passengerLoad / capacity) * 100;

  return (
    <div className="shaft-wrapper">
      {/* Shaft header */}
      <div className="shaft-header" style={{ '--hdr-color': color.primary }}>
        <span className="shaft-label">E{elevatorId}</span>
        {/* Capacity bar */}
        <div className="capacity-bar" title={`${passengerLoad}/${capacity} passengers`}>
          <div
            className="capacity-fill"
            style={{
              width: `${loadPct}%`,
              background: loadPct > 80 ? '#ef4444' : color.primary,
            }}
          />
        </div>
      </div>

      {/* The shaft itself */}
      <div className="shaft">
        {/* Floor grid lines */}
        {FLOORS.map(f => (
          <div key={f} className={`floor-line ${allQ.has(f) ? 'queued' : ''}`}>
            {allQ.has(f) && (
              <span className="queue-dot" style={{ background: color.primary }} />
            )}
          </div>
        ))}

        {/* The moving car */}
        <ElevatorCar elevator={elevator} />
      </div>
    </div>
  );
}
