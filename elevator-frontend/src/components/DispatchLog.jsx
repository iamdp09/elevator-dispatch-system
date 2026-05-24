import React from 'react';

const RESULT_STYLES = {
  ASSIGNED:  { color: '#22d3ee', icon: '✓' },
  DUPLICATE: { color: '#94a3b8', icon: '◌' },
  QUEUED:    { color: '#f59e0b', icon: '⏳' },
  REJECTED:  { color: '#ef4444', icon: '✕' },
};

/**
 * DispatchLog — Live scrolling event log of all dispatch results.
 * Each entry shows: timestamp, icon, result, and message.
 */
export default function DispatchLog({ log }) {
  if (log.length === 0) {
    return (
      <div className="dispatch-log empty">
        <span className="log-empty-msg">Waiting for events…</span>
      </div>
    );
  }

  return (
    <div className="dispatch-log">
      {log.map(entry => {
        const style = RESULT_STYLES[entry.result] ?? RESULT_STYLES.REJECTED;
        const time  = new Date(entry.id).toLocaleTimeString();
        return (
          <div key={entry.id} className="log-entry">
            <span className="log-time">{time}</span>
            <span className="log-icon" style={{ color: style.color }}>{style.icon}</span>
            <span className="log-result" style={{ color: style.color }}>{entry.result}</span>
            <span className="log-msg">{entry.message}</span>
          </div>
        );
      })}
    </div>
  );
}
