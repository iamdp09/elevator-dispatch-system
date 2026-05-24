import React, { useState } from 'react';
import { useElevatorWebSocket } from './hooks/useElevatorWebSocket';
import ElevatorShaft     from './components/ElevatorShaft';
import FloorPanel        from './components/FloorPanel';
import CarControlPanel   from './components/CarControlPanel';
import DispatchLog       from './components/DispatchLog';
import { FLOORS, ELEVATOR_COLORS, floorLabel } from './constants';

// ── Collapsible help panel ────────────────────────────────────────────────────
function HelpPanel() {
  const [open, setOpen] = useState(false);
  return (
    <div className="help-panel">
      <button className="help-toggle" onClick={() => setOpen(v => !v)}>
        {open ? '✕ Close Guide' : '? How to Use'}
      </button>
      {open && (
        <div className="help-content">
          <div className="help-grid">
            <div className="help-block">
              <h4>🔼🔽 Hall Call Buttons</h4>
              <p>Press <strong>▲</strong> or <strong>▼</strong> on a floor to call an elevator,
              just like a real elevator panel in a corridor. The button <strong>glows</strong> while
              a car is on its way. It turns off the moment the elevator arrives.</p>
            </div>
            <div className="help-block">
              <h4>🏢 Elevator Shafts</h4>
              <p>The three columns are live elevator shafts. Each coloured box is a car
              moving in real time. <strong>Dots</strong> inside a shaft show queued floors.
              The car turns <strong>green</strong> when doors open.</p>
            </div>
            <div className="help-block">
              <h4>🎛️ Car Panels (bottom)</h4>
              <p>Simulate pressing a floor button <em>inside</em> an elevator.
              Click any floor button in E1/E2/E3's panel to add that floor to
              that elevator's queue directly — like a passenger already inside.</p>
            </div>
            <div className="help-block">
              <h4>📋 Dispatch Log</h4>
              <p><span style={{color:'#22d3ee'}}>✓ ASSIGNED</span> — elevator dispatched · <span style={{color:'#f59e0b'}}>⏳ QUEUED</span> — all cars busy ·
              <span style={{color:'#94a3b8'}}> ◌ DUPLICATE</span> — already handled ·
              <span style={{color:'#ef4444'}}> ✕ REJECTED</span> — invalid request</p>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

// ── State badge with explanation ──────────────────────────────────────────────
function StateBadgeExplained({ state }) {
  const labels = {
    IDLE:      { text: 'Idle',      tip: 'Waiting for a call',          cls: 'idle'      },
    MOVING:    { text: 'Moving',    tip: 'Travelling to next stop',      cls: 'moving'    },
    DOOR_OPEN: { text: 'Doors Open',tip: 'Passengers boarding/alighting',cls: 'door-open' },
  };
  const s = labels[state] ?? labels.IDLE;
  return (
    <span className={`car-state-badge ${s.cls}`} title={s.tip}>
      {s.text}
    </span>
  );
}

export default function App() {
  const {
    elevators,
    hallCalls,
    dispatchLog,
    connected,
    stats,
    sendHallCall,
    sendCarCall,
  } = useElevatorWebSocket();

  const hasData = elevators.length > 0;

  return (
    <div className="app">

      {/* ── Header ──────────────────────────────────────────────────────── */}
      <header className="app-header">
        <div className="header-left">
          <span className="app-icon">🏢</span>
          <div>
            <h1 className="app-title">Elevator Dispatch System</h1>
            <p className="app-subtitle">14 Floors · 3 Elevators · LOOK Algorithm · Nearest Car</p>
          </div>
        </div>

        <div className="header-right">
          <div className={`conn-badge ${connected ? 'online' : 'offline'}`}
               title={connected ? 'WebSocket connected — receiving live updates' : 'Connecting to backend…'}>
            <span className="conn-dot" />
            {connected ? 'Live' : 'Connecting…'}
          </div>

          {hasData && (
            <div className="stats-pills">
              <span className="stat-pill" title="Total hall/car calls successfully assigned to an elevator">
                <span className="pill-val">{stats.dispatched ?? 0}</span>
                <span className="pill-lbl">Dispatched</span>
              </span>
              <span className="stat-pill" title="Calls waiting because all elevators were at capacity">
                <span className="pill-val">{stats.queued ?? 0}</span>
                <span className="pill-lbl">Queued</span>
              </span>
              <span className="stat-pill" title="Duplicate button presses filtered out automatically">
                <span className="pill-val">{stats.duplicates ?? 0}</span>
                <span className="pill-lbl">Dupes</span>
              </span>
            </div>
          )}
        </div>
      </header>

      {/* ── Help panel ──────────────────────────────────────────────────── */}
      <HelpPanel />

      {/* ── Main content ────────────────────────────────────────────────── */}
      <main className="app-main">
        {!hasData ? (
          <div className="loading">
            <div className="spinner" />
            <p>Connecting to elevator system…</p>
            <p className="loading-hint">Make sure the backend is running on <code>localhost:8080</code></p>
          </div>
        ) : (
          <>
            {/* ── Building visualisation ──────────────────────────────── */}
            <section className="building-section">
              <div className="section-header">
                <h2 className="section-title">Building View</h2>
                <span className="section-hint">
                  Click <strong>▲ / ▼</strong> next to a floor to call an elevator there
                </span>
              </div>

              <div className="building">
                {/* Floor labels + hall call buttons */}
                <FloorPanel hallCalls={hallCalls} onHallCall={sendHallCall} />

                {/* Three elevator shafts */}
                <div className="shafts-container">
                  {elevators.map(e => (
                    <ElevatorShaft key={e.elevatorId} elevator={e} />
                  ))}
                </div>
              </div>

              {/* Legend */}
              <div className="legend">
                {elevators.map(e => {
                  const color = ELEVATOR_COLORS[e.elevatorId] ?? ELEVATOR_COLORS[1];
                  return (
                    <span key={e.elevatorId} className="legend-item"
                          title={`Elevator ${e.elevatorId}: currently ${(e.state ?? 'IDLE').replace('_',' ')} at floor ${floorLabel(e.currentFloor)}`}>
                      <span className="legend-dot" style={{ background: color.primary }} />
                      <strong>E{e.elevatorId}</strong>
                      &nbsp;—&nbsp;
                      <StateBadgeExplained state={e.state} />
                      &nbsp;@&nbsp;<code>{floorLabel(e.currentFloor)}</code>
                      {e.direction !== 'IDLE' && (
                        <span style={{ color: color.primary }}>
                          &nbsp;{e.direction === 'UP' ? '▲' : '▼'}
                        </span>
                      )}
                    </span>
                  );
                })}
                <span className="legend-item legend-key" title="Green = doors open, pulsing glow = moving">
                  🟢 Doors Open &nbsp;·&nbsp; ✨ Glow = Moving &nbsp;·&nbsp; ● Dot = Queued floor
                </span>
              </div>
            </section>

            {/* ── Car panels + log ────────────────────────────────────── */}
            <section className="controls-section">
              <div className="car-panels-col">
                <div className="section-header">
                  <h2 className="section-title">In-Elevator Controls</h2>
                  <span className="section-hint">
                    Simulate pressing a floor button <em>inside</em> each elevator
                  </span>
                </div>
                <div className="car-panels-row">
                  {elevators.map(e => (
                    <CarControlPanel
                      key={e.elevatorId}
                      elevator={e}
                      onCarCall={sendCarCall}
                    />
                  ))}
                </div>
              </div>

              {/* Dispatch event log */}
              <div className="log-section">
                <div className="section-header">
                  <h3 className="section-title">Dispatch Log</h3>
                  <span className="section-hint">Every decision the system makes, in real time</span>
                </div>
                <DispatchLog log={dispatchLog} />
              </div>
            </section>
          </>
        )}
      </main>
    </div>
  );
}
