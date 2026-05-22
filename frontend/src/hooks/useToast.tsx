import { createContext, useCallback, useContext, useMemo, useRef, useState } from 'react';
import type { PropsWithChildren } from 'react';

export type ToastVariant = 'success' | 'error' | 'info';

export type ToastItem = {
  id: number;
  message: string;
  variant: ToastVariant;
  timeoutMs?: number;
};

type ToastApi = {
  toasts: ToastItem[];
  push: (message: string, opts?: { variant?: ToastVariant; timeoutMs?: number }) => number;
  dismiss: (id: number) => void;
};

const ToastContext = createContext<ToastApi | null>(null);

export function ToastProvider({ children }: PropsWithChildren) {
  const [toasts, setToasts] = useState<ToastItem[]>([]);
  const seqRef = useRef(0);

  const dismiss = useCallback((id: number) => {
    setToasts((items) => items.filter((t) => t.id !== id));
  }, []);

  const push = useCallback<ToastApi['push']>(
    (message, opts) => {
      const id = ++seqRef.current;
      const variant = opts?.variant ?? 'info';
      const timeoutMs = opts?.timeoutMs ?? (variant === 'error' ? 6000 : 4000);
      const item: ToastItem = { id, message, variant, timeoutMs };
      setToasts((items) => [...items, item]);
      if (timeoutMs > 0) {
        window.setTimeout(() => dismiss(id), timeoutMs);
      }
      return id;
    },
    [dismiss],
  );

  const api = useMemo(() => ({ toasts, push, dismiss }), [toasts, push, dismiss]);

  return <ToastContext.Provider value={api}>{children}</ToastContext.Provider>;
}

export function useToast(): ToastApi {
  const ctx = useContext(ToastContext);
  if (!ctx) throw new Error('useToast must be used within <ToastProvider>');
  return ctx;
}
