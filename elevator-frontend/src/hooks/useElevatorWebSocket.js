import { useEffect, useState, useRef, useCallback } from 'react';
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { BACKEND_HTTP, BACKEND_WS } from '../constants';

/**
 * useElevatorWebSocket — Real-time connection + state management.
 *
 * Hall-call button lifecycle:
 *   PRESS   → optimistic: add key to hallCalls immediately (button glows)
 *   SERVING → /topic/elevator-state: elevator enters DOOR_OPEN at that floor
 *             → client-side clear (button goes dark, even before server confirms)
 *   CONFIRM → /topic/hall-calls: server authoritative set replaces local state
 *             (proper server-side clear once backend is restarted)
 */
export function useElevatorWebSocket() {
  const [elevators,   setElevators]   = useState([]);
  const [hallCalls,   setHallCalls]   = useState({});
  const [dispatchLog, setDispatchLog] = useState([]);
  const [connected,   setConnected]   = useState(false);
  const [stats,       setStats]       = useState({});
  const clientRef                     = useRef(null);

  // ── Initial state load via REST ──────────────────────────────────────────
  useEffect(() => {
    fetch(`${BACKEND_HTTP}/api/state`)
      .then(r => r.json())
      .then(data => {
        if (data.elevators) {
          // REST uses {id, floor} keys; WebSocket DTO uses {elevatorId, currentFloor}
          const normalized = data.elevators.map(e => ({
            elevatorId:    e.elevatorId    ?? e.id,
            currentFloor:  e.currentFloor  ?? e.floor,
            direction:     e.direction     ?? 'IDLE',
            state:         e.state         ?? 'IDLE',
            upQueue:       e.upQueue       ?? [],
            downQueue:     e.downQueue     ?? [],
            passengerLoad: e.passengerLoad ?? 0,
            capacity:      e.capacity      ?? 10,
            doorOpen:      e.doorOpen      ?? false,
            floorLabel:    e.floorLabel    ?? String(e.currentFloor ?? e.floor ?? 0),
          }));
          setElevators(normalized);
        }
        // Seed hall calls from initial REST state (only keys matter for the lit check)
        if (data.activeHallCalls) {
          const map = {};
          Object.keys(data.activeHallCalls).forEach(k => { map[k] = true; });
          setHallCalls(map);
        }
        if (data.dispatchStats) setStats(data.dispatchStats);
      })
      .catch(() => { /* backend not yet up — WebSocket will provide state */ });
  }, []);

  // ── WebSocket connection ─────────────────────────────────────────────────
  useEffect(() => {
    const client = new Client({
      webSocketFactory: () => new SockJS(BACKEND_WS),
      reconnectDelay: 3000,

      onConnect: () => {
        setConnected(true);

        // ── Elevator positions (every tick) ─────────────────────────────
        client.subscribe('/topic/elevator-state', (msg) => {
          const elevs = JSON.parse(msg.body);
          setElevators(elevs);

          // CLIENT-SIDE HALL CALL CLEAR:
          // When an elevator opens its doors at a floor, the hall call for
          // that floor is served. Clear the button immediately so it stops
          // glowing and can be pressed again for the next passenger.
          // This works even before the backend /topic/hall-calls confirms it.
          setHallCalls(prev => {
            let next = prev;
            elevs.forEach(e => {
              if (e.state === 'DOOR_OPEN' || e.doorOpen) {
                const upKey   = `${e.currentFloor}_UP`;
                const downKey = `${e.currentFloor}_DOWN`;
                if (next[upKey] !== undefined || next[downKey] !== undefined) {
                  next = { ...next };
                  delete next[upKey];
                  delete next[downKey];
                }
              }
            });
            return next;
          });
        });

        // ── Authoritative hall-calls set (server-side, every tick) ──────
        // Replaces local state with the exact set the server considers active.
        // Requires backend restart after our ElevatorStatePublisher change.
        client.subscribe('/topic/hall-calls', (msg) => {
          const activeKeys = JSON.parse(msg.body); // string[]
          const map = {};
          activeKeys.forEach(k => { map[k] = true; });
          setHallCalls(map);
        });

        // ── Dispatch event log ──────────────────────────────────────────
        client.subscribe('/topic/dispatch-result', (msg) => {
          const result = JSON.parse(msg.body);
          setDispatchLog(prev => [{ ...result, id: Date.now() }, ...prev].slice(0, 30));

          // If the backend rejected / duplicated — remove optimistic glow
          if (result.result === 'REJECTED') {
            setHallCalls(prev => {
              const next = { ...prev };
              delete next[`${result.floor}_${result.direction}`];
              return next;
            });
          }
        });
      },

      onDisconnect: () => setConnected(false),
      onStompError: (frame) => console.error('STOMP error', frame),
    });

    client.activate();
    clientRef.current = client;
    return () => { client.deactivate(); };
  }, []);

  // ── Outbound: Hall call ──────────────────────────────────────────────────
  const sendHallCall = useCallback((floor, direction) => {
    if (!clientRef.current?.connected) return;

    // Optimistic update: light the button immediately so the user gets
    // instant feedback. The server will confirm (ASSIGNED/DUPLICATE) and
    // the DOOR_OPEN detection / /topic/hall-calls will clear it when served.
    setHallCalls(prev => ({ ...prev, [`${floor}_${direction}`]: true }));

    clientRef.current.publish({
      destination: '/app/hall-call',
      body: JSON.stringify({ floor, direction }),
    });
  }, []);

  // ── Outbound: Car call ───────────────────────────────────────────────────
  const sendCarCall = useCallback((elevatorId, targetFloor) => {
    if (!clientRef.current?.connected) return;
    clientRef.current.publish({
      destination: '/app/car-call',
      body: JSON.stringify({ elevatorId, targetFloor }),
    });
  }, []);

  return { elevators, hallCalls, dispatchLog, connected, stats, sendHallCall, sendCarCall };
}
