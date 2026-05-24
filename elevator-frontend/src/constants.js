// Building constants — must match backend BuildingConfig.java
export const MIN_FLOOR   = -3;
export const MAX_FLOOR   = 10;
export const TOTAL_FLOORS = 14;   // -3 to 10 inclusive
export const TOTAL_ELEVATORS = 3;

// Floor label: mirrors BuildingConfig.floorLabel()
export function floorLabel(floor) {
  if (floor === 0)  return 'G';
  if (floor < 0)    return `B${Math.abs(floor)}`;
  return String(floor);
}

// Visual row: floor 10 → row 0 (top), floor -3 → row 13 (bottom)
export function floorToRow(floor) {
  return MAX_FLOOR - floor;
}

// Percentage offset from top of shaft for a given floor
export function floorToPercent(floor) {
  return (floorToRow(floor) / TOTAL_FLOORS) * 100;
}

// All floors in display order (top → bottom)
export const FLOORS = Array.from(
  { length: TOTAL_FLOORS },
  (_, i) => MAX_FLOOR - i     // [10, 9, 8, ..., 0, -1, -2, -3]
);

// Elevator accent colours
export const ELEVATOR_COLORS = {
  1: { primary: '#22d3ee', glow: 'rgba(34,211,238,0.35)' },   // cyan
  2: { primary: '#a855f7', glow: 'rgba(168,85,247,0.35)' },   // purple
  3: { primary: '#f59e0b', glow: 'rgba(245,158,11,0.35)'  },  // amber
};

// Backend URLs — override with VITE_API_URL in production
// Local dev:  http://localhost:8080
// Production: set VITE_API_URL=https://your-app.up.railway.app in Vercel env vars
const _base = import.meta.env.VITE_API_URL ?? 'http://localhost:8080';
export const BACKEND_HTTP = _base;
export const BACKEND_WS   = _base + '/ws';
