import React from 'react';
import { FLOORS, floorLabel, MIN_FLOOR, MAX_FLOOR } from '../constants';

/**
 * FloorPanel — Left + right side of the building for each floor.
 * Left  side: floor label (B3 / G / 1 … 10)
 * Right side: ▲ UP button + ▼ DOWN button (hall call buttons)
 *
 * Active hall calls glow in the accent colour.
 * Buttons have descriptive tooltips (title) so hovering explains the action.
 */
export default function FloorPanel({ hallCalls, onHallCall }) {
  return (
    <div className="floor-panel-column">
      {FLOORS.map(floor => {
        const label   = floorLabel(floor);
        const upKey   = `${floor}_UP`;
        const downKey = `${floor}_DOWN`;
        const upLit   = !!hallCalls[upKey];
        const downLit = !!hallCalls[downKey];
        const isGround = floor === 0;

        return (
          <div key={floor} className={`floor-row ${isGround ? 'ground-floor' : ''}`}>
            {/* Floor label */}
            <span className={`floor-label ${isGround ? 'ground-label' : ''}`}>
              {label}
            </span>

            {/* Hall call buttons */}
            <div className="hall-buttons">
              {/* UP button — hidden on top floor */}
              {floor < MAX_FLOOR ? (
                <button
                  className={`hall-btn up-btn ${upLit ? 'lit' : ''}`}
                  onClick={() => onHallCall(floor, 'UP')}
                  title={
                    upLit
                      ? `▲ Elevator on its way to floor ${label} (going UP) — button is lit`
                      : `▲ Call an elevator to floor ${label} going UP`
                  }
                  id={`hall-up-${floor}`}
                >
                  ▲
                </button>
              ) : <span className="hall-btn-placeholder" />}

              {/* DOWN button — hidden on bottom floor */}
              {floor > MIN_FLOOR ? (
                <button
                  className={`hall-btn down-btn ${downLit ? 'lit' : ''}`}
                  onClick={() => onHallCall(floor, 'DOWN')}
                  title={
                    downLit
                      ? `▼ Elevator on its way to floor ${label} (going DOWN) — button is lit`
                      : `▼ Call an elevator to floor ${label} going DOWN`
                  }
                  id={`hall-down-${floor}`}
                >
                  ▼
                </button>
              ) : <span className="hall-btn-placeholder" />}
            </div>
          </div>
        );
      })}
    </div>
  );
}
