import React from 'react';
import { FLOORS, floorLabel, floorToPercent, ELEVATOR_COLORS } from '../constants';

// ── Per-elevator car ─────────────────────────────────────────────────────────

function ElevatorCar({ elevator }) {
  const { elevatorId, currentFloor, state, direction } = elevator;
  const color    = ELEVATOR_COLORS[elevatorId] ?? ELEVATOR_COLORS[1];
  const topPct   = floorToPercent(currentFloor);
  const isOpen   = state === 'DOOR_OPEN';
  const isMoving = state === 'MOVING';

  const stateClass = isOpen ? 'door-open' : isMoving ? 'moving' : 'idle';

  return (
    <div
      className={`elevator-car ${stateClass}`}
      style={{
        '--car-color': color.primary,
        '--car-glow':  color.glow,
        top: `calc(${topPct}% + 2px)`,
      }}
      title={`E${elevatorId} | ${state} | Floor ${floorLabel(currentFloor)}`}
    >
      <span className="car-arrow">
        {direction === 'UP' ? '▲' : direction === 'DOWN' ? '▼' : '●'}
      </span>
      <span className="car-id">E{elevatorId}</span>
      {isOpen && (
        <div className="door-lines">
          <span className="door-left" />
          <span className="door-right" />
        </div>
      )}
    </div>
  );
}

// ── Single elevator shaft column (NO header — header is in App.jsx) ──────────

export default function ElevatorShaft({ elevator }) {
  const { elevatorId, upQueue = [], downQueue = [] } = elevator;
  const color = ELEVATOR_COLORS[elevatorId] ?? ELEVATOR_COLORS[1];
  const allQ  = new Set([...upQueue, ...downQueue]);

  return (
    <div className="shaft-wrapper">
      <div className="shaft">
        {/* Floor grid lines — one per floor, top→bottom */}
        {FLOORS.map(f => (
          <div key={f} className={`floor-line ${allQ.has(f) ? 'queued' : ''}`}>
            {allQ.has(f) && (
              <span className="queue-dot" style={{ background: color.primary }} />
            )}
          </div>
        ))}

        {/* The moving elevator car */}
        <ElevatorCar elevator={elevator} />
      </div>
    </div>
  );
}
