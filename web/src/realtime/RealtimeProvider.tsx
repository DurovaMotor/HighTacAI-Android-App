import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { parsePlatformEvent } from './events';

export type RealtimeState = 'connecting' | 'connected' | 'reconnecting' | 'offline';

interface RealtimeContextValue {
  state: RealtimeState;
  lastEventAt?: string;
  retry: () => void;
  browserOnline: boolean;
}

const RealtimeContext = createContext<RealtimeContextValue | null>(null);

const keyMap: Record<string, string[]> = {
  broker: ['broker', 'dashboard'],
  station: ['stations', 'dashboard'],
  tag: ['tags', 'dashboard'],
  binding: ['bindings', 'products', 'tags', 'dashboard'],
  command: ['commands', 'dashboard'],
  device: ['devices', 'dashboard'],
  system: ['dashboard', 'backups'],
};

function websocketUrl() {
  const configured = import.meta.env.VITE_WS_PATH || '/api/v1/ws/events';
  if (/^wss?:\/\//.test(configured)) return configured;
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
  return `${protocol}//${window.location.host}${configured.startsWith('/') ? configured : `/${configured}`}`;
}

export function RealtimeProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient();
  const [state, setState] = useState<RealtimeState>('connecting');
  const [lastEventAt, setLastEventAt] = useState<string>();
  const [generation, setGeneration] = useState(0);
  const [browserOnline, setBrowserOnline] = useState(() => navigator.onLine);
  const retry = useCallback(() => setGeneration((value) => value + 1), []);

  useEffect(() => {
    const online = () => { setBrowserOnline(true); retry(); };
    const offline = () => { setBrowserOnline(false); setState('offline'); };
    window.addEventListener('online', online);
    window.addEventListener('offline', offline);
    return () => {
      window.removeEventListener('online', online);
      window.removeEventListener('offline', offline);
    };
  }, [retry]);

  useEffect(() => {
    if (import.meta.env.VITE_MOCK_API === 'true') {
      setState('connecting');
      const timer = window.setTimeout(() => setState('connected'), 250);
      return () => window.clearTimeout(timer);
    }
    if (!navigator.onLine) {
      setState('offline');
      return;
    }

    let stopped = false;
    let socket: WebSocket | undefined;
    let timer: number | undefined;
    let attempt = 0;

    const connect = () => {
      if (stopped || !navigator.onLine) return;
      setState(attempt ? 'reconnecting' : 'connecting');
      socket = new WebSocket(websocketUrl());
      socket.onopen = () => {
        attempt = 0;
        setState('connected');
        queryClient.invalidateQueries({ type: 'active' });
      };
      socket.onmessage = (message) => {
        const event = parsePlatformEvent(String(message.data));
        if (!event) return;
        setLastEventAt(event.occurred_at);
        const domain = event.event_type.split('.')[0];
        (keyMap[domain] || ['dashboard']).forEach((key) => queryClient.invalidateQueries({ queryKey: [key] }));
      };
      socket.onerror = () => socket?.close();
      socket.onclose = () => {
        if (stopped) return;
        attempt += 1;
        setState('reconnecting');
        timer = window.setTimeout(connect, Math.min(30_000, 1000 * 2 ** Math.min(attempt, 5)));
      };
    };

    connect();
    return () => {
      stopped = true;
      if (timer) window.clearTimeout(timer);
      socket?.close();
    };
  }, [generation, queryClient]);

  const value = useMemo(() => ({ state, lastEventAt, retry, browserOnline }), [state, lastEventAt, retry, browserOnline]);
  return <RealtimeContext.Provider value={value}>{children}</RealtimeContext.Provider>;
}

export function useRealtime() {
  const value = useContext(RealtimeContext);
  if (!value) throw new Error('useRealtime must be used within RealtimeProvider');
  return value;
}
