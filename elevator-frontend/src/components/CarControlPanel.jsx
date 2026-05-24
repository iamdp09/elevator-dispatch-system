import React from 'react';
import { FLOORS, MIN_FLOOR, MAX_FLOOR, floorLabel, ELEVATOR_COLORS } from '../constants';

/**
 * CarControlPanel — The simulated button panel inside an elevator car.
 * Shows all 14 floor buttons; lit ones are already in this elevator's queue.
 */
export default function CarControlPanel({ elevator, onCarCall }) {
  const { elevatorId, upQueue = [], downQueue = [], currentFloor, state } = elevator;
  const color   = ELEVATOR_COLORS[elevatorId];
  const queued  = new Set([...upQueue, ...downQueue]);

  return (
    <div className="car-panel" style={{ '--panel-color': color.primary, '--panel-glow': color.glow }}>
      {/* Panel header */}
      <div className="car-panel-header">
        <span className="car-panel-title" style={{ color: color.primary }}>
          Elevator {elevatorId}
        </span>
        <span className={`car-state-badge ${state.toLowerCase().replace('_', '-')}`}>
          {state.replace('_', ' ')}
        </span>
      </div>

      {/* Floor button grid */}
      <div className="car-btn-grid">
        {FLOORS.map(floor => {
          const isCurrent = floor === currentFloor;
          const isQueued  = queued.has(floor);
          return (
            <button
              key={floor}
              className={`car-btn ${isCurrent ? 'current' : ''} ${isQueued ? 'queued' : ''}`}
              style={isQueued ? { borderColor: color.primary, boxShadow: `0 0 6px ${color.glow}` } : {}}
              onClick={() => onCarCall(elevatorId, floor)}
              disabled={isCurrent}
              title={`E${elevatorId} → Floor ${floorLabel(floor)}`}
              id={`car-btn-e${elevatorId}-f${floor}`}
            >
              {floorLabel(floor)}
            </button>
          );
        })}
      </div>

      {/* Queue display */}
      <div className="car-queue-display">
        <span className="queue-label">↑</span>
        <span className="queue-values" style={{ color: color.primary }}>
          {upQueue.length > 0 ? [...upQueue].sort((a,b) => a-b).map(floorLabel).join(' · ') : '—'}
        </span>
        <span className="queue-label">↓</span>
        <span className="queue-values" style={{ color: color.primary }}>
          {downQueue.length > 0 ? [...downQueue].sort((a,b) => b-a).map(floorLabel).join(' · ') : '—'}
        </span>
      </div>
    </div>
  );
}
